package org.kissweb.rag;

import com.mchange.v2.c3p0.ComboPooledDataSource;
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
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

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

    /** Preview length for snippets returned by search_code. */
    private static final int SNIPPET_LEN = 300;
    /** Hard cap on k for search_code. */
    private static final int MAX_K = 50;
    /** Default k if the client does not specify one. */
    private static final int DEFAULT_K = 8;

    /**
     * (project, repo) -> absolute root path on disk; built lazily from
     * rag-projects.json. The repo name is the last segment of the configured
     * root path — same convention as the indexer.
     */
    private static volatile Map<String, Map<String, String>> repoRootCache;

    /**
     * The project name parsed from the URL path (/rag-mcp/<project>).
     * MCPServerBase's doPost is final, so we extract it during authenticate()
     * and stash here for callTool() to read. ThreadLocal because Tomcat may
     * serve concurrent requests on the same servlet instance.
     */
    private static final ThreadLocal<String> CURRENT_PROJECT = new ThreadLocal<>();

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
        if (!ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            response.sendError(404, "Unknown project: " + project);
            return false;
        }
        CURRENT_PROJECT.set(project);
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
                "Semantic search over this project's local code index. " +
                "Returns the top-K chunks most similar to the natural-language query " +
                "(file path, line range, symbol, score, snippet). Use when grep is too narrow " +
                "or when you do not know the exact symbol name. After picking a hit, follow up " +
                "with the Read tool on the returned absolute_path + line range to see the full code, " +
                "or call get_chunk for just that chunk's content. The project scope is determined " +
                "by the MCP endpoint URL — every result is from this project's index only.");
        search.put("inputSchema", buildSchema(
                "search_code input",
                new String[]{"query"},
                new String[]{"query", "k", "repo", "language", "path_prefix"},
                new String[]{"string", "number", "string", "string", "string"},
                new String[]{
                        "Natural-language search query",
                        "Number of hits to return (default 8, max 50)",
                        "Optional repo filter (one of the directory roots configured for this project)",
                        "Optional language filter: java, groovy, javascript, markdown, sql, html, ...",
                        "Optional path prefix (relative to the repo root) to limit the search"
                }));
        tools.put(search);

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
        final String query = args.getString("query", null);
        if (query == null || query.isEmpty())
            return toolError("query is required");

        int k = args.getInt("k", DEFAULT_K);
        if (k < 1) k = 1;
        if (k > MAX_K) k = MAX_K;

        final String repo = args.getString("repo", null);
        final String language = args.getString("language", null);
        final String pathPrefix = args.getString("path_prefix", null);

        final float[] qvec = embedQuery(query);
        final String vecLit = vectorToLiteral(qvec);
        final String proj = currentProject();

        final StringBuilder sql = new StringBuilder(
                "SELECT c.chunk_id, c.file_id, f.repo, f.path, " +
                "       c.start_line, c.end_line, c.symbol, " +
                "       1 - (c.embedding <=> ?::vector) AS sim, " +
                "       c.content " +
                "  FROM " + proj + ".rag_chunk c JOIN " + proj + ".rag_file f USING (file_id) " +
                " WHERE TRUE");
        if (repo != null && !repo.isEmpty())              sql.append(" AND f.repo = ?");
        if (language != null && !language.isEmpty())      sql.append(" AND f.language = ?");
        if (pathPrefix != null && !pathPrefix.isEmpty())  sql.append(" AND f.path LIKE ?");
        sql.append(" ORDER BY c.embedding <=> ?::vector LIMIT ?");

        final JSONArray hits = new JSONArray();
        try (Connection conn = openConnection()) {
            // The HNSW default ef_search=40 returns approximate nearest
            // neighbors that visibly miss true top hits at ~100k chunks.
            // Set per-session before the query (c3p0 may recycle this
            // connection later, but a fresh SET each request is cheap).
            applyEfSearch(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, vecLit);
            if (repo != null && !repo.isEmpty())             ps.setString(idx++, repo);
            if (language != null && !language.isEmpty())     ps.setString(idx++, language);
            if (pathPrefix != null && !pathPrefix.isEmpty()) ps.setString(idx++, pathPrefix + "%");
            ps.setString(idx++, vecLit);
            ps.setInt(idx, k);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final JSONObject hit = new JSONObject();
                    final String hRepo = rs.getString("repo");
                    final String hPath = rs.getString("path");
                    hit.put("chunk_id", rs.getLong("chunk_id"));
                    hit.put("repo", hRepo);
                    hit.put("path", hPath);
                    hit.put("absolute_path", absolutePath(proj, hRepo, hPath));
                    hit.put("start_line", rs.getInt("start_line"));
                    hit.put("end_line", rs.getInt("end_line"));
                    final String sym = rs.getString("symbol");
                    if (sym != null && !sym.isEmpty())
                        hit.put("symbol", sym);
                    hit.put("score", Math.round(rs.getDouble("sim") * 1000.0) / 1000.0);
                    final String content = rs.getString("content");
                    hit.put("snippet", content == null ? "" :
                            (content.length() > SNIPPET_LEN ? content.substring(0, SNIPPET_LEN) + "..." : content));
                    hits.put(hit);
                }
            }
            }  // close PreparedStatement try-with-resources
        }
        final JSONObject envelope = new JSONObject();
        envelope.put("project", proj);
        envelope.put("query", query);
        envelope.put("count", hits.length());
        envelope.put("hits", hits);
        return toolResult(envelope.toString(2));
    }

    /** Run {@code SET hnsw.ef_search = N} on this connection. */
    private static void applyEfSearch(Connection conn) throws Exception {
        int ef = 400;
        try {
            final String s = (String) MainServlet.getEnvironment("HNSWEfSearch");
            if (s != null && !s.isEmpty())
                ef = Integer.parseInt(s);
        } catch (Exception ignored) { /* fall back to default */ }
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("SET hnsw.ef_search = " + ef);
        }
    }

    private JSONObject doGetChunk(JSONObject args) throws Exception {
        final Long boxed = args.getLong("chunk_id", null);
        final long chunkId = boxed == null ? 0L : boxed.longValue();
        if (chunkId <= 0)
            return toolError("chunk_id is required");
        final String proj = currentProject();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT c.chunk_id, f.repo, f.path, c.start_line, c.end_line, " +
                     "       c.symbol, c.content, c.token_est, f.language " +
                     "  FROM " + proj + ".rag_chunk c JOIN " + proj + ".rag_file f USING (file_id) " +
                     " WHERE c.chunk_id = ?")) {
            ps.setLong(1, chunkId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return toolError("chunk_id not found: " + chunkId);
                final JSONObject r = new JSONObject();
                final String repo = rs.getString("repo");
                final String path = rs.getString("path");
                r.put("chunk_id", rs.getLong("chunk_id"));
                r.put("repo", repo);
                r.put("path", path);
                r.put("absolute_path", absolutePath(proj, repo, path));
                r.put("start_line", rs.getInt("start_line"));
                r.put("end_line", rs.getInt("end_line"));
                final String sym = rs.getString("symbol");
                if (sym != null && !sym.isEmpty())
                    r.put("symbol", sym);
                r.put("language", rs.getString("language"));
                r.put("token_est", rs.getInt("token_est"));
                r.put("content", rs.getString("content"));
                return toolResult(r.toString(2));
            }
        }
    }

    private JSONObject doListRepos() throws Exception {
        final String proj = currentProject();
        final JSONArray out = new JSONArray();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT repo, count(*) AS files, sum(size_bytes) AS bytes " +
                     "  FROM " + proj + ".rag_file GROUP BY repo ORDER BY repo");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final JSONObject row = new JSONObject();
                final String repo = rs.getString("repo");
                row.put("repo", repo);
                row.put("files", rs.getLong("files"));
                row.put("bytes", rs.getLong("bytes"));
                row.put("root", absolutePath(proj, repo, ""));
                out.put(row);
            }
        }
        return toolResult(out.toString(2));
    }

    private JSONObject doIndexStatus() throws Exception {
        final String proj = currentProject();
        final JSONObject result = new JSONObject();
        result.put("project", proj);
        try (Connection conn = openConnection()) {
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
        return toolResult(result.toString(2));
    }

    // ====================================================================================
    // Internals
    // ====================================================================================

    /** Embed a single query string via Ollama /api/embeddings. */
    private static float[] embedQuery(String text) throws IOException {
        String base = (String) MainServlet.getEnvironment("OllamaURL");
        if (base == null || base.isEmpty())
            base = "http://127.0.0.1:11434";
        if (base.endsWith("/"))
            base = base.substring(0, base.length() - 1);
        String model = (String) MainServlet.getEnvironment("EmbeddingModel");
        if (model == null || model.isEmpty())
            model = "nomic-embed-text:v1.5";

        final JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", text);

        final URL url = new URL(base + "/api/embeddings");
        final HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(5000);
        con.setReadTimeout(30000);
        con.setRequestProperty("Content-Type", "application/json");
        try (OutputStreamWriter w = new OutputStreamWriter(con.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(body.toString());
        }
        if (con.getResponseCode() != 200) {
            String err = "";
            try { err = new String(con.getErrorStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (Exception ignored) { /* nothing useful to report */ }
            throw new IOException("Ollama /api/embeddings returned " + con.getResponseCode() + ": " + err);
        }
        final String resp = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final JSONObject j = new JSONObject(resp);
        final JSONArray e = j.getJSONArray("embedding");
        final float[] v = new float[e.length()];
        for (int i = 0; i < v.length; i++)
            v[i] = e.getDouble(i).floatValue();
        return v;
    }

    private static String vectorToLiteral(float[] v) {
        final StringBuilder sb = new StringBuilder(v.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0)
                sb.append(',');
            sb.append(Float.toString(v[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Open a pooled JDBC connection. Kiss exposes c3p0 via a package-private accessor; reach it once via reflection. */
    private static Connection openConnection() throws Exception {
        final Method m = MainServlet.class.getDeclaredMethod("getCpds");
        m.setAccessible(true);
        final ComboPooledDataSource cpds = (ComboPooledDataSource) m.invoke(null);
        return cpds.getConnection();
    }

    /**
     * Resolve a (project, repo, relative-path) tuple to an absolute filesystem
     * path by consulting rag-projects.json. Cached lazily.
     */
    private static String absolutePath(String project, String repo, String relPath) {
        Map<String, Map<String, String>> cache = repoRootCache;
        if (cache == null) {
            cache = new HashMap<>();
            for (ProjectRegistry.Project p : ProjectRegistry.load()) {
                Map<String, String> inner = new HashMap<>();
                for (String absRoot : p.roots) {
                    final int slash = absRoot.lastIndexOf('/');
                    final String name = slash >= 0 ? absRoot.substring(slash + 1) : absRoot;
                    inner.put(name, absRoot);
                }
                cache.put(p.name, inner);
            }
            repoRootCache = cache;
        }
        Map<String, String> inner = cache.get(project);
        final String root = inner == null ? null : inner.get(repo);
        if (root == null)
            return relPath;
        if (relPath == null || relPath.isEmpty())
            return root;
        return root.endsWith("/") ? root + relPath : root + "/" + relPath;
    }
}
