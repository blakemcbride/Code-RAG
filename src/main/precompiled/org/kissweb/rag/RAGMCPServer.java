package org.kissweb.rag;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.MCPServerBase;
import org.kissweb.json.JSONArray;
import org.kissweb.json.JSONObject;
import org.kissweb.restServer.MainServlet;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

/**
 * MCP server for one project's local RAG index.
 * <br><br>
 * Exposes semantic-search and metadata tools to MCP clients (Claude Code, etc.).
 * <ul>
 *   <li>search_code   — top-K nearest chunks for a natural-language query</li>
 *   <li>get_chunk     — full text of one chunk by id</li>
 *   <li>list_repos    — indexed repos with file/byte counts</li>
 *   <li>index_status  — overall file/chunk counts, last sweep, embedding meta</li>
 * </ul>
 * Reindexing is intentionally not exposed here — use the services/RAGAdmin.reindex
 * JSON-RPC endpoint (which holds the single source-of-truth concurrency guard) or
 * let the 10-minute cron handle it.
 * <br><br>
 * Authentication: requires the X-RAG-Token header to match RAGMCPSharedSecret from
 * application.ini. Bind Tomcat to localhost (it is) and that is enough for single-user use.
 * <br><br>
 * As a precompiled class, edits require <code>./bld -v build</code> and a server restart;
 * hot reload does not apply.
 */
@WebServlet(urlPatterns="/rag-mcp/*")
public class RAGMCPServer extends MCPServerBase {

    private static final Logger logger = LogManager.getLogger(RAGMCPServer.class);

    /**
     * Default per-hit body budget. Chunks are capped at ~1500 chars by the
     * indexer, so at the default k this is a few KB total — far cheaper than
     * the extra Read/get_chunk round trip a truncated snippet forces. Callers
     * that are scanning broadly can lower it; 0 means unlimited.
     */
    private static final int DEFAULT_MAX_CHARS = 2000;

    /**
     * Reserved URL segment selecting every configured project at once:
     * {@code /rag-mcp/_all}. Not a legal project name (leading underscore),
     * so it cannot be shadowed by one.
     */
    static final String ALL_PROJECTS = "_all";

    /** True when this request came in on the cross-project endpoint. */
    private static boolean isAllProjects() {
        return ALL_PROJECTS.equals(CURRENT_PROJECT.get());
    }

    /**
     * The project name parsed from the URL path (/rag-mcp/<project>).
     * MCPServerBase's doPost is final, so we extract it during authenticate()
     * and stash here for callTool() to read. ThreadLocal because Tomcat may
     * serve concurrent requests on the same servlet instance.
     */
    private static final ThreadLocal<String> CURRENT_PROJECT = new ThreadLocal<>();

    /**
     * Path style requested by the client via the X-Path-Style header.
     * Currently the only recognized value is "windows", which causes
     * absolute_path / root strings in responses to be rewritten from
     * WSL2-style Linux paths to native Windows paths so a Windows
     * client (Claude Code or Codex running outside WSL2) can Read them.
     * Null = no translation (default). See Windows.md.
     */
    private static final ThreadLocal<String> CURRENT_PATH_STYLE = new ThreadLocal<>();

    @Override
    protected String getServerName() {
        return "kiss-rag-mcp";
    }

    @Override
    protected String getServerVersion() {
        return "2.0.0";
    }

    @Override
    protected boolean authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 1) Shared-secret check first — fail fast on unauthenticated callers
        // before doing any project lookups.
        final String expected = (String) MainServlet.getEnvironment("RAGMCPSharedSecret");
        if (expected == null || expected.isEmpty()) {
            response.sendError(503, "RAGMCPSharedSecret not configured in application.ini");
            return false;
        }
        final String header = request.getHeader("X-RAG-Token");
        if (!expected.equals(header)) {
            response.sendError(401, "Bad or missing X-RAG-Token");
            return false;
        }
        // 2) Extract project from URL path.  /rag-mcp/<project> -> "<project>".
        // Trailing slashes and extra segments are ignored after the first.
        final String pathInfo = request.getPathInfo();
        String project = null;
        if (pathInfo != null && pathInfo.length() > 1) {
            String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
            int slash = trimmed.indexOf('/');
            project = slash < 0 ? trimmed : trimmed.substring(0, slash);
        }
        if (project == null || project.isEmpty()) {
            response.sendError(404, "Missing project in URL (expected /rag-mcp/<project>)");
            return false;
        }
        // ALL_PROJECTS is checked before the registry lookup. It starts with an
        // underscore, which ProjectRegistry's [a-z][a-z0-9_]* rule forbids, so
        // it can never collide with a real project name.
        if (!ALL_PROJECTS.equals(project)
                && (!ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null)) {
            response.sendError(404, "Unknown project: " + project);
            return false;
        }
        CURRENT_PROJECT.set(project);
        // Record path-style preference for this request. Set unconditionally
        // (even to null) so a previous request's value can't leak through
        // Tomcat's pooled thread.
        String style = request.getHeader("X-Path-Style");
        CURRENT_PATH_STYLE.set(style == null ? null : style.toLowerCase());
        return true;
    }

    /** Read the project parsed for this request from authenticate(). */
    private static String currentProject() {
        String p = CURRENT_PROJECT.get();
        if (p == null)
            throw new IllegalStateException("CURRENT_PROJECT not set — authenticate() did not run?");
        return p;
    }

    @Override
    protected JSONArray listTools() {
        final JSONArray tools = new JSONArray();

        // ---- search_code ----
        final JSONObject search = new JSONObject();
        search.put("name", "search_code");
        search.put("description",
                "Find code by concept rather than by literal text. Preferred for \"where do " +
                "we handle X\", \"what code does Y\", or \"find anything related to Z\" questions " +
                "on this project — it does similarity search over the indexed tree and surfaces " +
                "related code that grep misses because the matching files do not contain the " +
                "keyword. Returns top-K chunks (file path, line range, symbol, score, snippet). " +
                "Prefer this over Grep/Glob whenever you do not already know an exact token to " +
                "search for. By default it returns ranked FILES — each with the symbols that matched " +
                "and the best excerpt already expanded to the whole enclosing function — so one call " +
                "usually answers the question; Read the absolute_path + line range only when you need " +
                "more surrounding context. It also covers every repository configured for this " +
                "project at once, which a directory-scoped Grep cannot do. " +
                (isAllProjects()
                        ? "THIS ENDPOINT SEARCHES EVERY CONFIGURED PROJECT AT ONCE — reach for it when "
                          + "the answer may live in a different repository than the one you are working "
                          + "in, which no directory-scoped tool can find. Every hit is tagged with its "
                          + "'project'; pass that back to get_chunk, because chunk ids are per-project."
                        : "Project scope is fixed by the MCP endpoint — all results are from this "
                          + "project only."));
        search.put("inputSchema", buildSchema(
                "search_code input",
                new String[]{"query"},
                new String[]{"query", "k", "repo", "language", "path_prefix", "max_chars", "expand",
                             "granularity", "include_neighbors"},
                new String[]{"string", "number", "string", "string", "string", "number", "string",
                             "string", "number"},
                new String[]{
                        "Natural-language search query",
                        "Number of hits to return (default 8, max 50)",
                        "Optional repo filter (one of the directory roots configured for this project)",
                        "Optional language filter: java, groovy, javascript, markdown, sql, html, ...",
                        "Optional path prefix (relative to the repo root) to limit the search",
                        "Max characters of body text per hit (default 1200, 0 = unlimited)",
                        "'symbol' (default) returns the whole enclosing function/class when a hit is "
                                + "only a fragment of one; 'none' returns the raw chunk",
                        "'file' (default) returns ranked FILES, each with its matched symbols and best "
                                + "excerpt; 'chunk' returns individual chunks",
                        "Chunks of surrounding context to attach either side of a hit that has no "
                                + "symbol structure (default 1, 0 = none)"
                }));
        tools.put(search);

        // ---- search_history ----
        final JSONObject hist = new JSONObject();
        hist.put("name", "search_history");
        hist.put("description",
                "Search version-control history (git and Subversion) by concept. Use for questions " +
                "about INTENT rather than content — \"why was X done this way\", \"when did Y change " +
                "and what for\", \"what else was touched alongside Z\". Commit messages record " +
                "rationale that exists nowhere in the code, so no amount of reading the tree or " +
                "grepping it can answer these. Returns commits with revision, author, date, message " +
                "and the files each one touched.");
        hist.put("inputSchema", buildSchema(
                "search_history input",
                new String[]{"query"},
                new String[]{"query", "k", "repo"},
                new String[]{"string", "number", "string"},
                new String[]{
                        "Natural-language query about why or when something changed",
                        "Number of commits to return (default 8, max 50)",
                        "Optional repo filter (one of the directory roots configured for this project)"
                }));
        tools.put(hist);

        // ---- find_symbol ----
        final JSONObject fs = new JSONObject();
        fs.put("name", "find_symbol");
        fs.put("description",
                "Locate exactly where a symbol is DEFINED and everywhere it is MENTIONED. Use this " +
                "for structural questions that similarity search cannot answer: \"what calls this\", " +
                "\"where is this defined\", \"what would break if I change this signature\", \"what " +
                "implements this\". Prefer it over search_code whenever you already know the exact " +
                "identifier — search_code finds code ABOUT a concept, this finds a specific symbol " +
                "and its call sites. References are classified as 'caller', 'test' or 'doc' and " +
                "returned in that order, with likely invocations (call_site) ahead of bare mentions — " +
                "so the 'test' entries answer \"what covers this\" directly. Definitions come from a " +
                "real language parser (ctags); references are textual, so overloads and same-named " +
                "methods on different classes are not disambiguated.");
        fs.put("inputSchema", buildSchema(
                "find_symbol input",
                new String[]{"symbol"},
                new String[]{"symbol", "limit"},
                new String[]{"string", "number"},
                new String[]{
                        "Exact identifier, e.g. findJoinPath or BPerson",
                        "Max definitions and max references to return (default 20, max 200)"
                }));
        tools.put(fs);

        // ---- find_dependents ----
        final JSONObject fd = new JSONObject();
        fd.put("name", "find_dependents");
        fd.put("description",
                "Impact analysis: which files IMPORT a given file (\"what breaks if I change this\"), " +
                "or what that file imports itself. Ask this BEFORE editing anything whose callers you " +
                "do not already know — neither similarity search nor a symbol lookup can tell you what " +
                "is standing on top of a file. Each result is labelled with confidence: 'exact' means " +
                "the import target provably maps onto this file's path; 'name' means only the trailing " +
                "class/module name agrees, so it could be a different file of the same name.");
        fd.put("inputSchema", buildSchema(
                "find_dependents input",
                new String[]{"path"},
                new String[]{"path", "direction", "limit"},
                new String[]{"string", "string", "number"},
                new String[]{
                        "File to analyse (repo-relative path, or just enough of the tail to identify it)",
                        "'dependents' (default) = who imports this; 'dependencies' = what this imports",
                        "Max results (default 50, max 200)"
                }));
        tools.put(fd);

        // ---- reindex_path ----
        final JSONObject rip = new JSONObject();
        rip.put("name", "reindex_path");
        rip.put("description",
                "Immediately re-index specific files you have just created or modified, so " +
                "search_code can find them in this session. The background sweep only runs every " +
                "few minutes, so without this your own new code is invisible to search for a while " +
                "— exactly when you are most likely to look for it. Cheap: only the named files are " +
                "re-read and re-embedded. Accepts absolute paths or paths relative to a configured " +
                "root; anything outside the indexed roots is reported as skipped.");
        rip.put("inputSchema", buildSchema(
                "reindex_path input",
                new String[]{"paths"},
                new String[]{"paths"},
                new String[]{"array"},
                new String[]{"Files to re-index (absolute, or relative to an indexed root)"}));
        tools.put(rip);

        // ---- get_chunk ----
        final JSONObject get = new JSONObject();
        get.put("name", "get_chunk");
        get.put("description",
                "Retrieve the full text and metadata of one chunk by its chunk_id. " +
                "Use when a search_code snippet is not enough but you do not want to read the whole file.");
        get.put("inputSchema", buildSchema(
                "get_chunk input",
                new String[]{"chunk_id"},
                new String[]{"chunk_id"},
                new String[]{"number"},
                new String[]{"chunk_id returned by a previous search_code hit"}));
        tools.put(get);

        // ---- list_repos ----
        final JSONObject list = new JSONObject();
        list.put("name", "list_repos");
        list.put("description",
                "List the indexed repositories with file and byte counts, so you can pick a repo " +
                "filter for search_code.");
        list.put("inputSchema", buildSchema(
                "list_repos input",
                new String[]{},
                new String[]{}, new String[]{}, new String[]{}));
        tools.put(list);

        // ---- index_status ----
        final JSONObject status = new JSONObject();
        status.put("name", "index_status");
        status.put("description",
                "Report total file and chunk counts, the timestamp of the most recent sweep, and " +
                "the embedding model + dimension. Use to confirm the index is populated and recent.");
        status.put("inputSchema", buildSchema(
                "index_status input",
                new String[]{},
                new String[]{}, new String[]{}, new String[]{}));
        tools.put(status);

        return tools;
    }

    @Override
    protected JSONObject callTool(String name, JSONObject args) throws Exception {
        switch (name) {
            case "search_code":   return doSearch(args);
            case "search_history": return doSearchHistory(args);
            case "reindex_path":  return doReindexPath(args);
            case "find_symbol":   return doFindSymbol(args);
            case "find_dependents": return doFindDependents(args);
            case "get_chunk":     return doGetChunk(args);
            case "list_repos":    return doListRepos();
            case "index_status":  return doIndexStatus();
            default:              return toolError("Unknown tool: " + name);
        }
    }

    // ====================================================================================
    // Tools
    // ====================================================================================

    private JSONObject doSearch(JSONObject args) throws Exception {
        final RAGSearch.SearchRequest req = new RAGSearch.SearchRequest();
        req.query = args.getString("query", null);
        if (req.query == null || req.query.isEmpty())
            return toolError("query is required");
        req.k = args.getInt("k", RAGSearch.DEFAULT_K);
        req.repo = args.getString("repo", null);
        req.language = args.getString("language", null);
        req.pathPrefix = args.getString("path_prefix", null);
        req.expand = args.getString("expand", "symbol");
        int maxChars = args.getInt("max_chars", DEFAULT_MAX_CHARS);
        if (maxChars < 0)
            maxChars = DEFAULT_MAX_CHARS;
        req.maxChars = maxChars;

        req.granularity = args.getString("granularity", "file");
        req.includeNeighbors = args.getInt("include_neighbors", 1);

        // Cross-project endpoint: fan out over every configured project.
        // File-level aggregation is per-project only, so results come back as
        // chunks tagged with their project.
        if (isAllProjects()) {
            req.granularity = "chunk";
            final RAGSearch.SearchResult all;
            try (Connection conn = RAGSearch.openConnection()) {
                all = RAGSearch.searchAll(conn, ProjectRegistry.listNames(), req);
            }
            final JSONArray ahits = new JSONArray();
            for (RAGSearch.Hit h : all.hits) {
                final JSONObject hit = new JSONObject();
                hit.put("project", h.project);
                hit.put("chunk_id", h.chunkId);
                hit.put("repo", h.repo);
                hit.put("path", h.path);
                hit.put("absolute_path", absolutePath(h.project, h.repo, h.path));
                hit.put("start_line", h.startLine);
                hit.put("end_line", h.endLine);
                if (h.symbol != null && !h.symbol.isEmpty())
                    hit.put("symbol", h.symbol);
                hit.put("score", h.score);
                if (h.expanded)
                    hit.put("expanded_to_symbol", true);
                putBody(hit, h.content, maxChars);
                ahits.put(hit);
            }
            final JSONObject env = new JSONObject();
            env.put("project", ALL_PROJECTS);
            env.put("projects_searched", ProjectRegistry.listNames().size());
            env.put("query", req.query);
            env.put("granularity", "chunk");
            env.put("count", ahits.length());
            env.put("hits", ahits);
            return toolResult(env.toString(2));
        }

        final String proj = currentProject();
        final RAGSearch.SearchResult result;
        try (Connection conn = RAGSearch.openConnection()) {
            result = RAGSearch.search(conn, proj, req);
        }

        final JSONArray hits = new JSONArray();
        if ("file".equalsIgnoreCase(req.granularity)) {
            for (RAGSearch.FileHit fh : result.fileHits) {
                final JSONObject hit = new JSONObject();
                hit.put("repo", fh.repo);
                hit.put("path", fh.path);
                hit.put("absolute_path", absolutePath(proj, fh.repo, fh.path));
                hit.put("score", fh.score);
                hit.put("match_count", fh.matchCount);
                if (!fh.symbols.isEmpty()) {
                    final JSONArray syms = new JSONArray();
                    for (String s : fh.symbols)
                        syms.put(s);
                    hit.put("symbols", syms);
                }
                hit.put("start_line", fh.bestStartLine);
                hit.put("end_line", fh.bestEndLine);
                if (fh.bestExpanded)
                    hit.put("expanded_to_symbol", true);
                putBody(hit, fh.bestContent, maxChars);
                hits.put(hit);
            }
        } else {
            for (RAGSearch.Hit h : result.hits) {
                final JSONObject hit = new JSONObject();
                hit.put("chunk_id", h.chunkId);
                hit.put("repo", h.repo);
                hit.put("path", h.path);
                hit.put("absolute_path", absolutePath(proj, h.repo, h.path));
                hit.put("start_line", h.startLine);
                hit.put("end_line", h.endLine);
                if (h.symbol != null && !h.symbol.isEmpty())
                    hit.put("symbol", h.symbol);
                hit.put("score", h.score);
                if (h.expanded)
                    hit.put("expanded_to_symbol", true);
                putBody(hit, h.content, maxChars);
                hits.put(hit);
            }
        }
        final JSONObject envelope = new JSONObject();
        envelope.put("project", proj);
        envelope.put("query", req.query);
        envelope.put("granularity", req.granularity);
        envelope.put("count", hits.length());
        envelope.put("hits", hits);
        logSearch(proj, "search_code", req.query, hits, result.embedMillis + result.queryMillis);
        return toolResult(envelope.toString(2));
    }

    /** Record a search and the paths it returned. */
    private static void logSearch(String project, String tool, String query,
                                  JSONArray hits, long latencyMs) {
        final StringBuilder paths = new StringBuilder();
        for (int i = 0; i < hits.length(); i++) {
            final JSONObject h = hits.getJSONObject(i);
            if (paths.length() > 0)
                paths.append('\n');
            paths.append(h.getString("repo", "")).append('/').append(h.getString("path", ""));
        }
        RAGSearch.logUsage(project, "search", tool, query, paths.toString(), latencyMs);
    }

    /** Attach a body to a hit, applying the per-hit character budget. */
    private static void putBody(JSONObject hit, String content, int maxChars) {
        final String body = content == null ? "" : content;
        if (maxChars > 0 && body.length() > maxChars) {
            hit.put("content", body.substring(0, maxChars));
            hit.put("truncated", true);
        } else {
            hit.put("content", body);
        }
    }

    private JSONObject doSearchHistory(JSONObject args) throws Exception {
        final String query = args.getString("query", null);
        if (query == null || query.isEmpty())
            return toolError("query is required");
        final int k = args.getInt("k", RAGSearch.DEFAULT_K);
        final String repo = args.getString("repo", null);

        final JSONArray out = new JSONArray();
        int searched = 0;
        try (Connection conn = RAGSearch.openConnection()) {
            for (String proj : targetProjects()) {
                searched++;
                for (RAGSearch.CommitHit c : RAGSearch.searchHistory(conn, proj, query, k, repo)) {
                    final JSONObject row = new JSONObject();
                    if (isAllProjects())
                        row.put("project", proj);
                    row.put("repo", c.repo);
                    row.put("vcs", c.vcs);
                    row.put("rev", c.rev);
                    row.put("author", c.author);
                    row.put("committed_at", c.committedAt);
                    row.put("subject", c.subject);
                    if (c.body != null && !c.body.trim().isEmpty())
                        row.put("body", c.body);
                    if (c.filesChanged != null && !c.filesChanged.trim().isEmpty())
                        row.put("files_changed", c.filesChanged);
                    row.put("score", c.score);
                    out.put(row);
                }
            }
        }
        final JSONObject envelope = new JSONObject();
        envelope.put("query", query);
        envelope.put("projects_searched", searched);
        envelope.put("count", out.length());
        envelope.put("commits", out);
        return toolResult(envelope.toString(2));
    }

    private JSONObject doFindDependents(JSONObject args) throws Exception {
        final String path = args.getString("path", null);
        if (path == null || path.trim().isEmpty())
            return toolError("path is required");
        final boolean dependents = !"dependencies".equalsIgnoreCase(args.getString("direction", "dependents"));
        int limit = args.getInt("limit", 50);
        if (limit < 1)
            limit = 1;
        if (limit > 200)
            limit = 200;

        final JSONArray rows = new JSONArray();
        int exact = 0;
        try (Connection conn = RAGSearch.openConnection()) {
            for (String proj : targetProjects()) {
                for (RAGSearch.DepHit d : RAGSearch.findDeps(conn, proj, path.trim(), dependents, limit)) {
                    final JSONObject o = new JSONObject();
                    if (isAllProjects())
                        o.put("project", proj);
                    o.put("repo", d.repo);
                    o.put("path", d.path);
                    o.put("absolute_path", absolutePath(proj, d.repo, d.path));
                    o.put("target", d.target);
                    o.put("line", d.line);
                    o.put("confidence", d.confidence);
                    if ("exact".equals(d.confidence))
                        exact++;
                    rows.put(o);
                }
            }
        }
        final JSONObject env = new JSONObject();
        env.put("path", path);
        env.put("direction", dependents ? "dependents" : "dependencies");
        env.put("count", rows.length());
        env.put("exact_matches", exact);
        env.put("results", rows);
        if (rows.length() == 0)
            env.put("note", "No import edges. Languages without tracked imports "
                    + "(markdown, sql, config) have none; otherwise run './bld deps <project>'.");
        return toolResult(env.toString(2));
    }

    private JSONObject doFindSymbol(JSONObject args) throws Exception {
        final String symbol = args.getString("symbol", null);
        if (symbol == null || symbol.trim().isEmpty())
            return toolError("symbol is required");
        int limit = args.getInt("limit", 20);
        if (limit < 1)
            limit = 1;
        if (limit > 200)
            limit = 200;

        final JSONArray defs = new JSONArray();
        final JSONArray refs = new JSONArray();
        int searched = 0;
        try (Connection conn = RAGSearch.openConnection()) {
            for (String proj : targetProjects()) {
                searched++;
                for (RAGSearch.DefHit d : RAGSearch.findDefinitions(conn, proj, symbol, limit)) {
                    final JSONObject o = new JSONObject();
                    if (isAllProjects())
                        o.put("project", proj);
                    o.put("repo", d.repo);
                    o.put("path", d.path);
                    o.put("absolute_path", absolutePath(proj, d.repo, d.path));
                    o.put("name", d.name);
                    if (d.kind != null)
                        o.put("kind", d.kind);
                    if (d.scope != null)
                        o.put("scope", d.scope);
                    if (d.signature != null)
                        o.put("signature", d.signature);
                    o.put("line", d.line);
                    defs.put(o);
                }
                for (RAGSearch.RefHit r : RAGSearch.findReferences(conn, proj, symbol, limit)) {
                    final JSONObject o = new JSONObject();
                    if (isAllProjects())
                        o.put("project", proj);
                    o.put("repo", r.repo);
                    o.put("path", r.path);
                    o.put("absolute_path", absolutePath(proj, r.repo, r.path));
                    if (r.symbol != null && !r.symbol.isEmpty())
                        o.put("in_symbol", r.symbol);
                    o.put("start_line", r.startLine);
                    o.put("end_line", r.endLine);
                    o.put("kind", r.kind);
                    o.put("call_site", r.callSite);
                    refs.put(o);
                }
            }
        }
        final JSONObject env = new JSONObject();
        env.put("symbol", symbol);
        env.put("projects_searched", searched);
        env.put("definition_count", defs.length());
        env.put("reference_count", refs.length());
        env.put("definitions", defs);
        env.put("references", refs);
        if (defs.length() == 0)
            env.put("note", "No definition indexed. Run './bld defs <project>' if this is unexpected.");
        return toolResult(env.toString(2));
    }

    /**
     * Re-index named files now.
     *
     * Calls the indexer directly rather than looping back through this server's
     * own /rest endpoint: a self-HTTP call would have to guess its own port
     * (which -hp can change) and pay a pointless network hop. Backend Groovy is
     * reached the same way KissInit reaches it, via GroovyService.
     */
    private JSONObject doReindexPath(JSONObject args) throws Exception {
        if (isAllProjects())
            return toolError("reindex_path is per-project; call it on /rag-mcp/<project>, not _all");
        final JSONArray paths = args.getJSONArray("paths");
        if (paths == null || paths.length() == 0)
            return toolError("paths is required");

        final JSONObject params = new JSONObject();
        params.put("project", currentProject());
        params.put("paths", paths);

        org.kissweb.database.Connection db = null;
        try {
            db = kissConnection();
            final JSONObject res = (JSONObject) org.kissweb.restServer.GroovyService.run(
                    "scripts", "RAGIndexer", "reindexPathsJson", null, db, params);
            db.commit();
            return toolResult(res.toString(2));
        } catch (Exception e) {
            if (db != null) {
                try { db.rollback(); } catch (Exception ignored) { /* best effort */ }
            }
            logger.error("reindex_path failed", e);
            return toolError("reindex failed: " + e.getMessage());
        } finally {
            if (db != null) {
                try { db.close(); } catch (Exception ignored) { /* best effort */ }
            }
        }
    }

    /** A Kiss Connection, which the Groovy indexer requires (not a raw JDBC one). */
    private static org.kissweb.database.Connection kissConnection() throws Exception {
        final String host = str("DatabaseHost", "localhost");
        final int port = Integer.parseInt(str("DatabasePort", "5432"));
        final String name = str("DatabaseName", "");
        final String user = str("DatabaseUser", "");
        final String pw = str("DatabasePassword", "");
        return new org.kissweb.database.Connection(
                org.kissweb.database.Connection.ConnectionType.PostgreSQL, host, port, name, user, pw);
    }

    private static String str(String key, String dflt) {
        final Object o = MainServlet.getEnvironment(key);
        return (o == null || o.toString().isEmpty()) ? dflt : o.toString();
    }

    private JSONObject doGetChunk(JSONObject args) throws Exception {
        final Long boxed = args.getLong("chunk_id", null);
        final long chunkId = boxed == null ? 0L : boxed.longValue();
        if (chunkId <= 0)
            return toolError("chunk_id is required");
        String proj = currentProject();
        if (isAllProjects()) {
            // chunk_id is only unique within a project's schema, so the caller
            // must say which one — search_all tags every hit with its project.
            proj = args.getString("project", null);
            if (proj == null || proj.isEmpty())
                return toolError("project is required on the _all endpoint "
                        + "(chunk ids are per-project; use the 'project' field from a search hit)");
            if (!ProjectRegistry.isValidName(proj) || ProjectRegistry.get(proj) == null)
                return toolError("Unknown project: " + proj);
        }
        final RAGSearch.Hit h;
        try (Connection conn = RAGSearch.openConnection()) {
            h = RAGSearch.getChunk(conn, proj, chunkId);
        }
        if (h == null)
            return toolError("chunk_id not found: " + chunkId);
        final JSONObject r = new JSONObject();
        r.put("chunk_id", h.chunkId);
        r.put("repo", h.repo);
        r.put("path", h.path);
        r.put("absolute_path", absolutePath(proj, h.repo, h.path));
        r.put("start_line", h.startLine);
        r.put("end_line", h.endLine);
        if (h.symbol != null && !h.symbol.isEmpty())
            r.put("symbol", h.symbol);
        r.put("language", h.language);
        r.put("token_est", h.tokenEst);
        r.put("content", h.content);
        // A fetch means the agent chose this result out of a search's output —
        // the nearest thing to a human relevance judgement we can observe.
        RAGSearch.logUsage(proj, "fetch", "get_chunk", null, h.repo + "/" + h.path, 0L);
        return toolResult(r.toString(2));
    }

    private JSONObject doListRepos() throws Exception {
        final JSONArray out = new JSONArray();
        try (Connection conn = RAGSearch.openConnection()) {
            for (String proj : targetProjects())
                appendRepos(conn, proj, out);
        }
        return toolResult(out.toString(2));
    }

    /** Projects this request addresses: all of them on _all, otherwise just the one. */
    private static java.util.List<String> targetProjects() {
        if (isAllProjects())
            return ProjectRegistry.listNames();
        return java.util.Collections.singletonList(currentProject());
    }

    private static void appendRepos(Connection conn, String proj, JSONArray out) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT repo, count(*) AS files, sum(size_bytes) AS bytes " +
                     "  FROM " + proj + ".rag_file GROUP BY repo ORDER BY repo");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final JSONObject row = new JSONObject();
                final String repo = rs.getString("repo");
                row.put("project", proj);
                row.put("repo", repo);
                row.put("files", rs.getLong("files"));
                row.put("bytes", rs.getLong("bytes"));
                row.put("root", absolutePath(proj, repo, ""));
                out.put(row);
            }
        }
    }

    private JSONObject doIndexStatus() throws Exception {
        if (isAllProjects()) {
            final JSONArray rows = new JSONArray();
            for (String p : ProjectRegistry.listNames())
                rows.put(projectStatus(p));
            final JSONObject envelope = new JSONObject();
            envelope.put("project", ALL_PROJECTS);
            envelope.put("projects", rows);
            return toolResult(envelope.toString(2));
        }
        return toolResult(projectStatus(currentProject()).toString(2));
    }

    private JSONObject projectStatus(String proj) throws Exception {
        final JSONObject result = new JSONObject();
        result.put("project", proj);
        try (Connection conn = RAGSearch.openConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT (SELECT count(*) FROM " + proj + ".rag_file)        AS files, " +
                     "       (SELECT count(*) FROM " + proj + ".rag_chunk)       AS chunks, " +
                     "       (SELECT max(indexed_at) FROM " + proj + ".rag_file) AS last_indexed");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result.put("file_count",  rs.getLong("files"));
                    result.put("chunk_count", rs.getLong("chunks"));
                    final Timestamp ts = rs.getTimestamp("last_indexed");
                    result.put("last_indexed_at", ts == null ? null : ts.toString());
                }
            }
            // Pull every meta row, but route a few well-known keys to their own
            // top-level fields and keep the rest in the generic "meta" bag.
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT key, value FROM " + proj + ".rag_meta ORDER BY key");
                 ResultSet rs = ps.executeQuery()) {
                final JSONObject meta = new JSONObject();
                while (rs.next()) {
                    final String key = rs.getString("key");
                    final String val = rs.getString("value");
                    if ("reindex_running".equals(key)) {
                        result.put("indexing", "true".equalsIgnoreCase(val));
                    } else if ("rebuild_required".equals(key)) {
                        // Promoted out of the generic bag: while this is set the
                        // index is stale or partial and results are not
                        // trustworthy. That must be visible, not buried.
                        result.put("rebuild_required", val);
                    } else if ("schema_version".equals(key) || "embedding_model".equals(key)) {
                        result.put(key, val);
                    } else if ("last_sweep".equals(key)) {
                        try { result.put("last_sweep", new JSONObject(val)); }
                        catch (Exception e) { meta.put(key, val); }
                    } else {
                        meta.put(key, val);
                    }
                }
                result.put("meta", meta);
            }
        }
        return result;
    }

    // ====================================================================================
    // Internals
    // ====================================================================================

    /**
     * Resolve a (project, repo, relative-path) tuple to an absolute filesystem
     * path via {@link RAGSearch#absolutePath}, then pass it through
     * {@link #translateForCurrentClient(String)} so requests with an
     * {@code X-Path-Style: windows} header get back Windows-native paths
     * instead of the server's Linux/WSL2 paths.
     */
    private static String absolutePath(String project, String repo, String relPath) {
        return translateForCurrentClient(RAGSearch.absolutePath(project, repo, relPath));
    }

    /**
     * Translate a Linux/WSL2 absolute path to its Windows equivalent if
     * the current request set {@code X-Path-Style: windows}. Otherwise
     * return the path unchanged.
     *
     * <p>Two mappings, in order:</p>
     * <ul>
     *   <li>{@code /mnt/<drive>/...} → {@code <DRIVE>:\...} — for code that
     *       physically lives on the Windows filesystem and is mounted into
     *       WSL2 via DrvFs.</li>
     *   <li>any other absolute path → {@code \\wsl$\<distro>\...} — for
     *       code that lives natively on the WSL2 ext4. The distro name
     *       comes from {@code WindowsWslDistro} in application.ini
     *       (default {@code Ubuntu}).</li>
     * </ul>
     * Relative paths and null are returned as-is.
     */
    static String translateForCurrentClient(String path) {
        if (path == null || path.isEmpty())
            return path;
        if (!"windows".equals(CURRENT_PATH_STYLE.get()))
            return path;
        return toWindowsPath(path, wslDistro());
    }

    /** Pure translation (no ThreadLocal access) — package-private for unit testing. */
    static String toWindowsPath(String linuxPath, String distro) {
        if (linuxPath == null || linuxPath.isEmpty() || linuxPath.charAt(0) != '/')
            return linuxPath;
        // /mnt/<letter>/... or /mnt/<letter> — DrvFs mount.
        if (linuxPath.length() >= 6
                && linuxPath.charAt(1) == 'm'
                && linuxPath.charAt(2) == 'n'
                && linuxPath.charAt(3) == 't'
                && linuxPath.charAt(4) == '/'
                && Character.isLetter(linuxPath.charAt(5))
                && (linuxPath.length() == 6 || linuxPath.charAt(6) == '/')) {
            char drive = Character.toUpperCase(linuxPath.charAt(5));
            String rest = linuxPath.length() > 6 ? linuxPath.substring(7) : "";
            return drive + ":\\" + rest.replace('/', '\\');
        }
        // Anything else under /: it lives on the WSL2 ext4 — reach via UNC.
        return "\\\\wsl$\\" + distro + linuxPath.replace('/', '\\');
    }

    /** Lookup the WSL2 distro name from application.ini, defaulting to Ubuntu. */
    private static String wslDistro() {
        Object o = MainServlet.getEnvironment("WindowsWslDistro");
        if (o == null)
            return "Ubuntu";
        String s = o.toString().trim();
        return s.isEmpty() ? "Ubuntu" : s;
    }
}
