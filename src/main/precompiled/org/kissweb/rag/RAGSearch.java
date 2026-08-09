package org.kissweb.rag;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The retrieval engine for one project's RAG index.
 * <br><br>
 * This class holds the whole read path — query embedding, the pgvector
 * similarity search, and the resolution of stored relative paths back to
 * absolute ones. It deliberately knows nothing about MCP, HTTP, or any
 * particular client.
 * <br><br>
 * Two callers share it, and it exists so that they cannot drift apart:
 * <ul>
 *   <li>{@link RAGMCPServer} — wraps the result in an MCP envelope and
 *       applies client-specific path translation.</li>
 *   <li>{@code scripts/RAGEval.groovy} — scores the same results against a
 *       golden query set. An eval that exercised a different code path than
 *       the live tool would measure nothing useful.</li>
 * </ul>
 * As a precompiled class, edits require <code>./bld -v build</code> and a
 * server restart; hot reload does not apply.
 */
public final class RAGSearch {

    private static final Logger logger = LogManager.getLogger(RAGSearch.class);

    /** Hard cap on k. */
    public static final int MAX_K = 50;
    /** Default k when the caller does not specify one. */
    public static final int DEFAULT_K = 8;

    private RAGSearch() {
        // utility class
    }

    // ====================================================================================
    // Value types
    // ====================================================================================

    /** One search request. Only {@link #query} is required. */
    public static final class SearchRequest {
        /** Natural-language query text. Required. */
        public String query;
        /** Number of hits to return. Clamped to [1, MAX_K]. */
        public int k = DEFAULT_K;
        /** Optional repo filter (root basename). */
        public String repo;
        /** Optional language filter. */
        public String language;
        /** Optional path prefix, relative to the repo root. */
        public String pathPrefix;
        /**
         * "symbol" (default) — a hit that is one fragment of a split symbol is
         * returned as the whole enclosing symbol. "none" — return the raw chunk.
         */
        public String expand = "symbol";
        /**
         * Per-hit body budget in characters, and therefore the ceiling on how
         * much an expansion may add. One budget rather than two, so expanding a
         * hit to its enclosing symbol cannot be undone by a smaller truncation
         * applied afterwards. 0 = fall back to RAGMaxExpandChars.
         */
        public int maxChars;
        /**
         * "file" — collapse chunk hits into a ranked list of files (agents
         * navigate by file, and one file's several matching chunks are one
         * answer, not several). "chunk" — return individual chunks.
         */
        public String granularity = "chunk";
        /**
         * Attach this many chunks either side of each hit from the same file.
         * A hit that is a self-contained symbol usually needs none (expansion
         * already handled that); this is for fixed-window chunks, where the
         * boundary is arbitrary and the useful context often sits just outside
         * it. 0 disables.
         */
        public int includeNeighbors;

        public SearchRequest() {
        }

        public SearchRequest(String query, int k) {
            this.query = query;
            this.k = k;
        }
    }

    /** One retrieved chunk. */
    public static final class Hit {
        public long chunkId;
        public long fileId;
        /** Owning project; set only by cross-project search. */
        public String project;
        public String repo;
        /** Path relative to the repo root — the form stored in rag_file. */
        public String path;
        public int startLine;
        public int endLine;
        public String symbol;
        public String language;
        /** Similarity in [0,1]; higher is closer. Unset (0) for getChunk results. */
        public double score;
        /** Estimated token count. Populated by getChunk; 0 from search. */
        public int tokenEst;
        /** Line range of the enclosing symbol; 0 when unknown. */
        public int symStartLine;
        public int symEndLine;
        /** True when {@link #content} was widened to the whole enclosing symbol. */
        public boolean expanded;
        /** Chunk body — or the whole enclosing symbol when {@link #expanded}. */
        public String content;
        /** File mtime recorded when this was indexed, epoch millis. */
        public long indexedMtime;
        /** The file has changed on disk since indexing — content/lines may be wrong. */
        public boolean stale;
        /** The file no longer exists on disk. */
        public boolean missing;
    }

    /**
     * One file's worth of matches, produced when granularity is "file".
     * <br><br>
     * Scored by the sum of its best few chunk scores rather than by its single
     * best chunk: a file that matches the query in three places is a stronger
     * answer than one that matches in a single place just as well, and
     * best-chunk-only scoring cannot express that.
     */
    public static final class FileHit {
        public long fileId;
        public String repo;
        public String path;
        public String language;
        public double score;
        /** How many chunks of this file were in the candidate pool. */
        public int matchCount;
        /** Distinct symbols that matched, best-first. */
        public final List<String> symbols = new ArrayList<>();
        /** The single best-matching chunk, expanded if it was a fragment. */
        public String bestContent;
        public int bestStartLine;
        public int bestEndLine;
        public boolean bestExpanded;
        /** File mtime recorded when this was indexed, epoch millis. */
        public long indexedMtime;
        /** The file has changed on disk since indexing — content/lines may be wrong. */
        public boolean stale;
        /** The file no longer exists on disk. */
        public boolean missing;
    }

    /** How many of a file's chunk scores contribute to its aggregate score. */
    private static final int FILE_SCORE_DEPTH = 3;

    /** One commit from the VCS history index. */
    public static final class CommitHit {
        public String project;
        public String repo;
        public String vcs;
        public String rev;
        public String author;
        public String committedAt;
        public String subject;
        public String body;
        public String filesChanged;
        public double score;
    }

    /** One edge of the import graph. */
    public static final class DepHit {
        public String repo;
        public String path;
        public String target;
        public int line;
        /** "exact" when the import target maps onto the file's own path; else "name". */
        public String confidence;
    }

    /**
     * Files that import the given file ("what breaks if I change this"), or the
     * files it imports itself.
     * <br><br>
     * Matching is by the last segment of the import target — the class or
     * module name — because a real resolver would need per-language module
     * paths, build configuration and a classpath. To keep that honest, each
     * result is labelled: {@code exact} when the dotted or slashed target
     * actually maps onto the file's path (so {@code org.kissweb.database.Connection}
     * matching {@code .../org/kissweb/database/Connection.java} is certain),
     * {@code name} when only the trailing name agrees and the hit could be a
     * different class with the same name.
     *
     * @param dependents true for "who imports this", false for "what this imports"
     */
    public static List<DepHit> findDeps(Connection conn, String project, String filePath,
                                        boolean dependents, int limit) throws Exception {
        if (project == null || !ProjectRegistry.isValidName(project))
            throw new IllegalArgumentException("Invalid project name: " + project);
        final List<DepHit> out = new ArrayList<>();
        if (filePath == null || filePath.isEmpty())
            return out;

        if (!dependents) {
            // What this file imports: a straight lookup on its own rows.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT f.repo, f.path, d.target, d.line " +
                    "  FROM " + project + ".rag_dep d JOIN " + project + ".rag_file f USING (file_id) " +
                    " WHERE f.path = ? OR f.path LIKE ? ORDER BY d.line LIMIT ?")) {
                ps.setString(1, filePath);
                ps.setString(2, "%/" + filePath);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final DepHit d = new DepHit();
                        d.repo = rs.getString("repo");
                        d.path = rs.getString("path");
                        d.target = rs.getString("target");
                        d.line = rs.getInt("line");
                        d.confidence = "exact";
                        out.add(d);
                    }
                }
            }
            return out;
        }

        // Who imports this file: match on the basename, then label by whether
        // the full target maps back onto this path.
        final String base = basenameNoExt(filePath);
        if (base.isEmpty())
            return out;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT f.repo, f.path, d.target, d.line " +
                "  FROM " + project + ".rag_dep d JOIN " + project + ".rag_file f USING (file_id) " +
                " WHERE d.target_tail = ? ORDER BY f.repo, f.path LIMIT ?")) {
            ps.setString(1, base.toLowerCase());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final DepHit d = new DepHit();
                    d.repo = rs.getString("repo");
                    d.path = rs.getString("path");
                    d.target = rs.getString("target");
                    d.line = rs.getInt("line");
                    d.confidence = targetMapsToPath(d.target, filePath) ? "exact" : "name";
                    out.add(d);
                }
            }
        }
        // Certain matches first — a same-named class from another package is a
        // plausible answer, but never a better one.
        out.sort((a, b) -> {
            final int c = Boolean.compare(!"exact".equals(a.confidence), !"exact".equals(b.confidence));
            return c != 0 ? c : a.path.compareTo(b.path);
        });
        return out;
    }

    /** "a/b/Connection.java" -> "Connection" */
    private static String basenameNoExt(String path) {
        String b = path;
        final int slash = b.lastIndexOf('/');
        if (slash >= 0)
            b = b.substring(slash + 1);
        final int dot = b.lastIndexOf('.');
        return dot > 0 ? b.substring(0, dot) : b;
    }

    /** True when an import target's package/module path is a suffix of the file path. */
    private static boolean targetMapsToPath(String target, String filePath) {
        if (target == null)
            return false;
        String t = target;
        if (t.endsWith(".*"))
            t = t.substring(0, t.length() - 2);
        // a.b.C -> a/b/C ; A\B\C -> A/B/C ; ./x/y -> x/y
        String asPath = t.replace('.', '/').replace('\\', '/');
        while (asPath.startsWith("./") || asPath.startsWith("/"))
            asPath = asPath.substring(asPath.charAt(0) == '.' ? 2 : 1);
        if (asPath.isEmpty())
            return false;
        final String noExt = filePath.contains(".")
                ? filePath.substring(0, filePath.lastIndexOf('.')) : filePath;
        return noExt.endsWith("/" + asPath) || noExt.equals(asPath);
    }

    /** One symbol definition from the ctags index. */
    public static final class DefHit {
        public String repo;
        public String path;
        public String name;
        public String kind;
        public String scope;
        public String signature;
        public int line;
    }

    /** One place a symbol is mentioned. */
    public static final class RefHit {
        public String repo;
        public String path;
        public String symbol;
        public int startLine;
        public int endLine;
        /** "caller" | "test" | "doc" — what kind of mention this is. */
        public String kind;
        /** True when the text looks like an actual invocation, not a bare mention. */
        public boolean callSite;
    }

    /**
     * Look up where a symbol is DEFINED and where it is MENTIONED.
     * <br><br>
     * The structural counterpart to {@code search_code}: similarity search
     * answers "what code is about X", this answers "where exactly is X, and
     * what touches it" — the questions that come up when changing code rather
     * than exploring it.
     * <br><br>
     * Definitions come from ctags, which parses the language properly.
     * References come from the identifier-aware lexeme index built for hybrid
     * search, so no extra indexing was needed to support this.
     * <br><br>
     * Honest about what it is: a TEXTUAL reference index, not a type-resolved
     * call graph. It does not disambiguate overloads, resolve types, or know
     * that two same-named methods on different classes are different. For an
     * agent asking "what calls verifyPassword" that is normally enough; for
     * "which overload does this call site bind to" it is not.
     */
    public static List<DefHit> findDefinitions(Connection conn, String project,
                                               String symbol, int limit) throws Exception {
        if (project == null || !ProjectRegistry.isValidName(project))
            throw new IllegalArgumentException("Invalid project name: " + project);
        final List<DefHit> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT f.repo, f.path, d.name, d.kind, d.scope, d.signature, d.line " +
                "  FROM " + project + ".rag_def d JOIN " + project + ".rag_file f USING (file_id) " +
                " WHERE d.name_lower = ? ORDER BY f.repo, f.path, d.line LIMIT ?")) {
            ps.setString(1, symbol.toLowerCase());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final DefHit d = new DefHit();
                    d.repo = rs.getString("repo");
                    d.path = rs.getString("path");
                    d.name = rs.getString("name");
                    d.kind = rs.getString("kind");
                    d.scope = rs.getString("scope");
                    d.signature = rs.getString("signature");
                    d.line = rs.getInt("line");
                    out.add(d);
                }
            }
        }
        return out;
    }

    /**
     * Where a symbol is used, classified and ranked so the answer is usable.
     * <br><br>
     * A raw lexeme match is a poor answer to "what calls this": measured on
     * {@code findJoinPath}, 11 matches were 8 tests, 1 documentation file and
     * the definition itself — one real caller buried under ten. So this drops
     * the defining chunk, separates callers from tests from prose, and marks
     * mentions that look like actual invocations.
     * <br><br>
     * The test classification is useful in its own right: "what covers this
     * symbol" is asked as often as "what calls it" before a change.
     */
    public static List<RefHit> findReferences(Connection conn, String project,
                                              String symbol, int limit) throws Exception {
        if (project == null || !ProjectRegistry.isValidName(project))
            throw new IllegalArgumentException("Invalid project name: " + project);
        final String tok = RAGTokenizer.tokenize(symbol).trim();
        if (tok.isEmpty())
            return new ArrayList<>();
        // Match the whole identifier, not its camelCase parts: searching for
        // "findJoinPath" should not return every chunk containing "path".
        final String whole = tok.split("\\s+")[0];

        final List<RefHit> out = new ArrayList<>();
        final String sql =
                "SELECT f.repo, f.path, f.language, c.symbol, c.start_line, c.end_line, " +
                "       (c.content ~* ('(^|[^A-Za-z0-9_])' || ? || '\\s*\\(')) AS is_call, " +
                "       EXISTS (SELECT 1 FROM " + project + ".rag_def d " +
                "                WHERE d.file_id = c.file_id AND d.name_lower = ? " +
                "                  AND d.line BETWEEN c.start_line AND c.end_line) AS is_def " +
                "  FROM " + project + ".rag_chunk c JOIN " + project + ".rag_file f USING (file_id) " +
                " WHERE c.lexemes_tsv @@ to_tsquery('simple', ?) " +
                " ORDER BY f.repo, f.path, c.start_line LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, java.util.regex.Pattern.quote(symbol).replace("\\Q", "").replace("\\E", ""));
            ps.setString(2, symbol.toLowerCase());
            ps.setString(3, whole);
            // Over-fetch: the definition chunk and low-value mentions are
            // dropped below, and we still want a full page of real callers.
            ps.setInt(4, Math.min(limit * 4, 400));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getBoolean("is_def"))
                        continue;   // the definition is not a reference to itself
                    final RefHit r = new RefHit();
                    r.repo = rs.getString("repo");
                    r.path = rs.getString("path");
                    r.symbol = rs.getString("symbol");
                    r.startLine = rs.getInt("start_line");
                    r.endLine = rs.getInt("end_line");
                    r.callSite = rs.getBoolean("is_call");
                    r.kind = classifyRef(rs.getString("language"), r.path);
                    out.add(r);
                }
            }
        }
        // Callers first, then tests, then prose; genuine call sites ahead of
        // bare mentions within each group.
        out.sort((a, b) -> {
            final int ka = refRank(a.kind), kb = refRank(b.kind);
            if (ka != kb)
                return Integer.compare(ka, kb);
            if (a.callSite != b.callSite)
                return a.callSite ? -1 : 1;
            return a.path.compareTo(b.path);
        });
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static int refRank(String kind) {
        if ("caller".equals(kind))
            return 0;
        if ("test".equals(kind))
            return 1;
        return 2;
    }

    private static String classifyRef(String language, String path) {
        if (language != null) {
            switch (language) {
                case "markdown": case "text": case "latex": case "html":
                case "rst": case "asciidoc":
                    return "doc";
                default:
                    break;
            }
        }
        final String p = path == null ? "" : path.toLowerCase();
        if (p.contains("/test/") || p.contains("/tests/") || p.startsWith("test/")
                || p.endsWith("test.java") || p.endsWith("tests.java")
                || p.endsWith("_test.go") || p.endsWith("test.groovy")
                || p.contains(".test.") || p.contains(".spec."))
            return "test";
        return "caller";
    }

    /**
     * Search commit history — the same hybrid dense+lexical retrieval, over
     * {@code rag_commit} instead of {@code rag_chunk}.
     * <br><br>
     * Answers the class of question code search structurally cannot: not "what
     * does this do" but "why is it like this", "when did this change", "what
     * else moved with it".
     */
    public static List<CommitHit> searchHistory(Connection conn, String project,
                                                String query, int k, String repo) throws Exception {
        if (project == null || !ProjectRegistry.isValidName(project))
            throw new IllegalArgumentException("Invalid project name: " + project);
        if (query == null || query.isEmpty())
            throw new IllegalArgumentException("query is required");
        if (k < 1)
            k = 1;
        if (k > MAX_K)
            k = MAX_K;

        final double wDense = envDouble("RAGHybridWeightDense", 1.0);
        final double wLex = envDouble("RAGHybridWeightLexical", 0.25);
        final double rrfK = envDouble("RAGRRFConstant", 60.0);
        int overfetch = envInt("RAGOverfetch", 50);
        if (overfetch < k)
            overfetch = k;

        final String vecLit = vectorToLiteral(embedQuery(query));
        applyEfSearch(conn);

        final String cols = "commit_id, repo, vcs, rev, author, committed_at, subject, body, files_changed ";
        final boolean hasRepo = repo != null && !repo.isEmpty();

        final Map<Long, CommitHit> byId = new LinkedHashMap<>();
        final Map<Long, Double> fused = new HashMap<>();

        final StringBuilder dsql = new StringBuilder(
                "SELECT " + cols + " FROM " + project + ".rag_commit WHERE TRUE");
        if (hasRepo)
            dsql.append(" AND repo = ?");
        dsql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        try (PreparedStatement ps = conn.prepareStatement(dsql.toString())) {
            int i = 1;
            if (hasRepo)
                ps.setString(i++, repo);
            ps.setString(i++, vecLit);
            ps.setInt(i, overfetch);
            accumulateCommits(ps, byId, fused, wDense, rrfK);
        }

        final String tsq = RAGTokenizer.toTsQueryOr(query, MAX_TSQUERY_TERMS);
        if (wLex > 0.0 && !tsq.isEmpty()) {
            final StringBuilder lsql = new StringBuilder(
                    "SELECT " + cols + " FROM " + project + ".rag_commit, to_tsquery('simple', ?) AS q " +
                    " WHERE lexemes_tsv @@ q");
            if (hasRepo)
                lsql.append(" AND repo = ?");
            lsql.append(" ORDER BY ts_rank_cd(lexemes_tsv, q) DESC, commit_id LIMIT ?");
            try (PreparedStatement ps = conn.prepareStatement(lsql.toString())) {
                int i = 1;
                ps.setString(i++, tsq);
                if (hasRepo)
                    ps.setString(i++, repo);
                ps.setInt(i, overfetch);
                accumulateCommits(ps, byId, fused, wLex, rrfK);
            }
        }

        final List<Map.Entry<Long, Double>> ranked = new ArrayList<>(fused.entrySet());
        ranked.sort((a, b) -> {
            final int c = Double.compare(b.getValue(), a.getValue());
            return c != 0 ? c : Long.compare(a.getKey(), b.getKey());
        });
        final List<CommitHit> out = new ArrayList<>(Math.min(k, ranked.size()));
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            final CommitHit c = byId.get(ranked.get(i).getKey());
            c.score = Math.round(ranked.get(i).getValue() * 100000.0) / 100000.0;
            out.add(c);
        }
        return out;
    }

    private static void accumulateCommits(PreparedStatement ps, Map<Long, CommitHit> byId,
                                          Map<Long, Double> fused, double weight,
                                          double rrfK) throws Exception {
        int rank = 0;
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final long id = rs.getLong("commit_id");
                CommitHit c = byId.get(id);
                if (c == null) {
                    c = new CommitHit();
                    c.repo = rs.getString("repo");
                    c.vcs = rs.getString("vcs");
                    c.rev = rs.getString("rev");
                    c.author = rs.getString("author");
                    final Timestamp ts = rs.getTimestamp("committed_at");
                    c.committedAt = ts == null ? null : ts.toString();
                    c.subject = rs.getString("subject");
                    c.body = rs.getString("body");
                    c.filesChanged = rs.getString("files_changed");
                    byId.put(id, c);
                }
                rank++;
                fused.merge(id, weight / (rrfK + rank), Double::sum);
            }
        }
    }

    /** Result of one search, including timing broken out for the eval harness. */
    public static final class SearchResult {
        public String project;
        public String query;
        public final List<Hit> hits = new ArrayList<>();
        /** Populated instead of {@link #hits} when granularity is "file". */
        public final List<FileHit> fileHits = new ArrayList<>();
        /** Milliseconds spent in the Ollama embedding call. */
        public long embedMillis;
        /** Milliseconds spent in the database query. */
        public long queryMillis;
    }

    // ====================================================================================
    // Search
    // ====================================================================================

    /**
     * Run one hybrid search against a project's index.
     * <br><br>
     * Two independent retrievals run over the same filtered corpus — dense
     * (pgvector cosine over the embedding) and lexical (GIN over the
     * identifier-aware tsvector) — each fetching {@code overfetch} candidates.
     * The two lists are then fused with Reciprocal Rank Fusion.
     * <br><br>
     * RRF is used rather than a weighted sum of the raw scores because cosine
     * similarity and {@code ts_rank_cd} live on incomparable scales with
     * corpus-dependent distributions; normalizing them against each other is
     * guesswork. RRF throws the magnitudes away and fuses on rank alone:
     * <pre>
     *   score(d) = Σ over retrievers  weight / (K + rank(d))
     * </pre>
     * A document ranked well by either retriever surfaces; one ranked well by
     * both wins. Setting {@code RAGHybridWeightLexical} to 0 restores pure
     * dense retrieval, which is how the two are A/B'd.
     *
     * @param conn    open JDBC connection (caller owns it)
     * @param project project name; must be a valid registry name
     * @param req     the request
     * @return hits ordered best-first
     */
    public static SearchResult search(Connection conn, String project, SearchRequest req) throws Exception {
        if (project == null || !ProjectRegistry.isValidName(project))
            throw new IllegalArgumentException("Invalid project name: " + project);
        if (req == null || req.query == null || req.query.isEmpty())
            throw new IllegalArgumentException("query is required");

        int k = req.k;
        if (k < 1)
            k = 1;
        if (k > MAX_K)
            k = MAX_K;

        final double wDense = envDouble("RAGHybridWeightDense", 1.0);
        final double wLex = envDouble("RAGHybridWeightLexical", 1.0);
        final double rrfK = envDouble("RAGRRFConstant", 60.0);
        int overfetch = envInt("RAGOverfetch", 50);
        if (overfetch < k)
            overfetch = k;

        final SearchResult out = new SearchResult();
        out.project = project;
        out.query = req.query;

        final long t0 = System.currentTimeMillis();
        // Expansion feeds the DENSE leg only. The lexical leg keeps the original
        // query: extra generated prose would dilute an identifier-shaped search,
        // which is the case the lexical leg exists to serve.
        final String vecLit = wDense > 0.0
                ? vectorToLiteral(embedQuery(expandQuery(req.query))) : null;
        final long t1 = System.currentTimeMillis();
        out.embedMillis = t1 - t0;

        applyEfSearch(conn);

        // Rerank the fused pool, then truncate to k. Reranking before
        // truncation is the whole point of overfetching.
        List<Hit> reranked = rankedCandidates(conn, project, req, vecLit,
                wDense, wLex, rrfK, overfetch);
        // Optional LLM pass on top of the heuristic order (no-op unless
        // RAGRerankModel is configured).
        reranked = llmRerank(req.query, reranked, k);
        final int perFile = envInt("RAGMaxPerFile", 2);
        final int expandCeiling = req.maxChars > 0 ? req.maxChars : envInt("RAGMaxExpandChars", 6000);
        final boolean doExpand = !"none".equalsIgnoreCase(req.expand);

        if ("file".equalsIgnoreCase(req.granularity)) {
            final Map<Long, Hit> reps = new HashMap<>();
            // Aggregate wider than k, so the summary leg can promote a file the
            // chunk retrievers ranked just outside the cut.
            final List<FileHit> files = aggregateByFile(reranked, Math.max(k, overfetch), reps);
            fuseSummaryLeg(conn, project, req, vecLit, files, reps, rrfK, overfetch);
            fuseSymbolSummaryLeg(conn, project, req, vecLit, files, rrfK, overfetch);
            while (files.size() > k)
                files.remove(files.size() - 1);
            // Only each file's representative chunk needs widening. Files
            // contributed solely by the summary leg have no retrieved chunk —
            // their bestContent is already the summary, so skip them.
            final List<Hit> repList = new ArrayList<>(files.size());
            for (FileHit fh : files) {
                final Hit rep = reps.get(fh.fileId);
                if (rep != null)
                    repList.add(rep);
            }
            if (doExpand)
                expandToSymbols(conn, project, repList, expandCeiling);
            if (req.includeNeighbors > 0)
                attachNeighbors(conn, project, repList, req.includeNeighbors, expandCeiling);
            for (FileHit fh : files) {
                final Hit rep = reps.get(fh.fileId);
                if (rep == null)
                    continue;
                fh.bestContent = rep.content;
                fh.bestStartLine = rep.startLine;
                fh.bestEndLine = rep.endLine;
                fh.bestExpanded = rep.expanded;
            }
            markFileStaleness(project, files);
            out.fileHits.addAll(files);
        } else {
            final List<Hit> selected = applyDiversity(dedupeHits(reranked), k, perFile);
            if (doExpand)
                expandToSymbols(conn, project, selected, expandCeiling);
            if (req.includeNeighbors > 0)
                attachNeighbors(conn, project, selected, req.includeNeighbors, expandCeiling);
            markStaleness(project, selected);
            out.hits.addAll(selected);
        }
        out.queryMillis = System.currentTimeMillis() - t1;
        return out;
    }

    // ====================================================================================
    // Reranking
    // ====================================================================================

    /**
     * Score-adjust the fused candidate pool using signals the retrievers cannot
     * see, and return it ordered best-first.
     * <br><br>
     * All heuristic and sub-millisecond — no model call. The retrievers rank by
     * semantic and lexical similarity of the chunk body; what they miss is that
     * a query naming a symbol almost always wants the chunk that <i>defines</i>
     * that symbol, and that boilerplate chunks (import blocks, license headers)
     * are never the answer however well they match.
     */
    private static List<Hit> rerank(String query, Map<Long, Hit> byId, Map<Long, Double> fused) {
        final Set<String> qTerms = RAGTokenizer.tokens(query);
        final double symBoost = envDouble("RAGBoostSymbol", 0.60);
        final double pathBoost = envDouble("RAGBoostPath", 0.25);
        final double boilerPenalty = envDouble("RAGPenaltyBoilerplate", 0.50);

        final List<Hit> all = new ArrayList<>(byId.size());
        final Map<Long, Double> adjusted = new HashMap<>();
        for (Map.Entry<Long, Hit> e : byId.entrySet()) {
            final Hit h = e.getValue();
            // Base RRF score. Multiplicative adjustments keep everything on the
            // same scale regardless of how many retrievers contributed.
            double s = fused.getOrDefault(e.getKey(), 0.0);
            double mult = 1.0;

            // The chunk's declared symbol overlaps the query: likely the very
            // definition being asked for.
            //
            // Scaled by HOW MUCH of the symbol the query accounts for, not by
            // mere any-token overlap. "SchemaGraph findJoinPath" covers all of
            // findJoinPath and earns the full boost; a conceptual query that
            // happens to contain "user" covers only a quarter of getUserData
            // and earns a quarter. Unscaled, the boost fired on every loosely
            // related symbol and measurably pushed correct hits off the top.
            if (h.symbol != null && !h.symbol.isEmpty())
                mult += symBoost * overlapFraction(RAGTokenizer.tokens(h.symbol), qTerms);
            // Same treatment for the file name. The directory part is excluded:
            // "src", "main", "org" match everything and mean nothing.
            if (h.path != null) {
                final int slash = h.path.lastIndexOf('/');
                final String base = slash >= 0 ? h.path.substring(slash + 1) : h.path;
                mult += pathBoost * overlapFraction(RAGTokenizer.tokens(stripExtension(base)), qTerms);
            }
            // Boilerplate: a chunk that is mostly imports or a license comment
            // can match well lexically but is never a useful answer.
            if (looksLikeBoilerplate(h.content))
                mult -= boilerPenalty;

            if (mult < 0.05)
                mult = 0.05;
            adjusted.put(e.getKey(), s * mult);
            all.add(h);
        }
        all.sort((a, b) -> {
            final int c = Double.compare(adjusted.getOrDefault(b.chunkId, 0.0),
                                         adjusted.getOrDefault(a.chunkId, 0.0));
            return c != 0 ? c : Long.compare(a.chunkId, b.chunkId);
        });
        for (Hit h : all)
            h.score = Math.round(adjusted.getOrDefault(h.chunkId, 0.0) * 100000.0) / 100000.0;
        return all;
    }

    /**
     * Optional second-stage rerank by a local LLM.
     * <br><br>
     * Justified only by a gap between "the answer is in the candidate pool" and
     * "the answer is at the top" — which the eval does show (hit@1 well below
     * hit@10). Disabled unless {@code RAGRerankModel} is set, because it trades
     * the thing the caller feels most: an agent blocks on this call, and a
     * generation step turns a ~30 ms search into a multi-second one.
     * <br><br>
     * One batched generation ranks all candidates from their headers (path,
     * symbol, opening lines) rather than one call per candidate. Any failure —
     * timeout, malformed output, unparseable ids — falls back silently to the
     * heuristic order; a reranker must never be able to break search.
     *
     * @return reordered candidates, or the input unchanged on any failure
     */
    private static List<Hit> llmRerank(String query, List<Hit> candidates, int keep) {
        final String model = envString("RAGRerankModel", "");
        if (model.isEmpty() || candidates.size() <= 1)
            return candidates;
        final int timeoutMs = envInt("RAGRerankTimeoutMs", 2000);
        final int poolSize = Math.min(candidates.size(), envInt("RAGRerankPool", 30));

        try {
            final StringBuilder p = new StringBuilder();
            p.append("You are ranking code search results.\nQuery: ").append(query).append("\n\n");
            for (int i = 0; i < poolSize; i++) {
                final Hit h = candidates.get(i);
                p.append('[').append(i).append("] ").append(h.path);
                if (h.symbol != null && !h.symbol.isEmpty())
                    p.append("  symbol=").append(h.symbol);
                p.append('\n');
                p.append(firstLines(h.content, 2)).append("\n\n");
            }
            p.append("Return ONLY a JSON array of the ").append(Math.min(keep, poolSize))
             .append(" most relevant ids, best first. Example: [3,0,7]");

            final JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("prompt", p.toString());
            body.put("stream", false);
            final JSONObject opts = new JSONObject();
            opts.put("temperature", 0);
            body.put("options", opts);

            String base = envString("OllamaURL", "http://127.0.0.1:11434");
            if (base.endsWith("/"))
                base = base.substring(0, base.length() - 1);
            final HttpURLConnection con = (HttpURLConnection) new URL(base + "/api/generate").openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(1000);
            con.setReadTimeout(timeoutMs);
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStreamWriter w = new OutputStreamWriter(con.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write(body.toString());
            }
            if (con.getResponseCode() != 200)
                return candidates;
            final String resp = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String text = new JSONObject(resp).getString("response", "");

            final int lb = text.indexOf('['), rb = text.indexOf(']', lb + 1);
            if (lb < 0 || rb < 0)
                return candidates;
            final List<Hit> ordered = new ArrayList<>();
            final boolean[] used = new boolean[candidates.size()];
            for (String tok : text.substring(lb + 1, rb).split(",")) {
                final String t = tok.trim();
                if (t.isEmpty())
                    continue;
                final int id;
                try {
                    id = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (id < 0 || id >= candidates.size() || used[id])
                    continue;
                used[id] = true;
                ordered.add(candidates.get(id));
            }
            if (ordered.isEmpty())
                return candidates;
            // Anything the model omitted keeps its heuristic order behind the
            // ranked head, so nothing is ever lost outright.
            for (int i = 0; i < candidates.size(); i++)
                if (!used[i])
                    ordered.add(candidates.get(i));
            return ordered;
        } catch (Exception e) {
            return candidates;
        }
    }

    private static String firstLines(String s, int n) {
        if (s == null)
            return "";
        final String[] parts = s.split("\n", n + 1);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, parts.length); i++) {
            if (i > 0)
                sb.append('\n');
            final String line = parts[i];
            sb.append(line.length() > 160 ? line.substring(0, 160) : line);
        }
        return sb.toString();
    }

    private static String envString(String key, String dflt) {
        try {
            final String s = (String) MainServlet.getEnvironment(key);
            if (s != null && !s.isEmpty())
                return s;
        } catch (Exception ignored) { /* fall through */ }
        return dflt;
    }

    /**
     * Widen hits with their immediately adjacent chunks from the same file.
     * <br><br>
     * Applies only to chunks with no symbol structure — a fixed-window chunk's
     * boundary falls wherever the window happened to end, so the lines that
     * explain it are often just outside. Symbol chunks are skipped: they are
     * already a complete unit, and {@link #expandToSymbols} handles fragments.
     */
    private static void attachNeighbors(Connection conn, String project, List<Hit> hits,
                                        int radius, int maxChars) throws Exception {
        final List<Hit> needy = new ArrayList<>();
        for (Hit h : hits)
            if (h.symStartLine <= 0 && !h.expanded)
                needy.add(h);
        if (needy.isEmpty())
            return;

        final StringBuilder in = new StringBuilder();
        for (int i = 0; i < needy.size(); i++)
            in.append(i > 0 ? ",?" : "?");
        final String sql =
                "SELECT file_id, chunk_no, start_line, end_line, content " +
                "  FROM " + project + ".rag_chunk " +
                " WHERE file_id IN (" + in + ") ORDER BY file_id, chunk_no";

        // file_id -> (chunk_no -> row)
        final Map<Long, Map<Integer, String[]>> byFile = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Hit h : needy)
                ps.setLong(idx++, h.fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byFile.computeIfAbsent(rs.getLong("file_id"), x -> new HashMap<>())
                          .put(rs.getInt("chunk_no"), new String[]{
                                  rs.getString("content"),
                                  Integer.toString(rs.getInt("start_line")),
                                  Integer.toString(rs.getInt("end_line"))});
                }
            }
        }
        // Locate each hit's own chunk_no by matching its line range, then glue
        // the neighbours on.
        for (Hit h : needy) {
            final Map<Integer, String[]> chunks = byFile.get(h.fileId);
            if (chunks == null)
                continue;
            int self = -1;
            for (Map.Entry<Integer, String[]> e : chunks.entrySet()) {
                if (Integer.parseInt(e.getValue()[1]) == h.startLine
                        && Integer.parseInt(e.getValue()[2]) == h.endLine) {
                    self = e.getKey();
                    break;
                }
            }
            if (self < 0)
                continue;
            final StringBuilder sb = new StringBuilder();
            int lo = h.startLine, hi = h.endLine;
            for (int n = self - radius; n <= self + radius; n++) {
                final String[] row = chunks.get(n);
                if (row == null)
                    continue;
                if (sb.length() > 0)
                    sb.append('\n');
                sb.append(row[0]);
                lo = Math.min(lo, Integer.parseInt(row[1]));
                hi = Math.max(hi, Integer.parseInt(row[2]));
                if (sb.length() >= maxChars)
                    break;
            }
            if (sb.length() == 0)
                continue;
            h.content = sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
            h.startLine = lo;
            h.endLine = hi;
        }
    }

    /**
     * Fuse the LLM-summary retrieval leg into an existing file ranking.
     * <br><br>
     * Summaries are prose <i>about</i> a file, so a prose query matches them
     * directly where it could only partially match the code. They are also
     * inherently file-level, which is why this fuses at file granularity rather
     * than joining the chunk-level RRF: there is no meaningful "chunk" of a
     * summary to rank.
     * <br><br>
     * A file already in the ranking has the summary contribution added to its
     * score; a file the chunk retrievers missed entirely is appended, which is
     * the whole point — it is found by what it <i>does</i>, not by the words it
     * happens to contain. No-op until {@code ./bld summarize} has been run.
     */
    private static void fuseSummaryLeg(Connection conn, String project, SearchRequest req,
                                       String vecLit, List<FileHit> files,
                                       Map<Long, Hit> reps, double rrfK, int limit) throws Exception {
        final double wSum = envDouble("RAGSummaryWeight", 1.0);
        if (wSum <= 0.0 || vecLit == null)
            return;

        final StringBuilder sql = new StringBuilder(
                "SELECT f.file_id, f.repo, f.path, f.language, f.mtime, f.summary " +
                "  FROM " + project + ".rag_file f " +
                " WHERE f.summary_embedding IS NOT NULL");
        if (req.repo != null && !req.repo.isEmpty())
            sql.append(" AND f.repo = ?");
        if (req.language != null && !req.language.isEmpty())
            sql.append(" AND f.language = ?");
        if (req.pathPrefix != null && !req.pathPrefix.isEmpty())
            sql.append(" AND f.path LIKE ?");
        sql.append(" ORDER BY f.summary_embedding <=> ?::vector LIMIT ?");

        final Map<Long, FileHit> byId = new HashMap<>();
        for (FileHit fh : files)
            byId.put(fh.fileId, fh);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = bindFilters(ps, req, 1);
            ps.setString(idx++, vecLit);
            ps.setInt(idx, limit);
            int rank = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rank++;
                    final long fileId = rs.getLong("file_id");
                    final double contrib = wSum / (rrfK + rank);
                    FileHit fh = byId.get(fileId);
                    if (fh == null) {
                        fh = new FileHit();
                        fh.fileId = fileId;
                        fh.repo = rs.getString("repo");
                        fh.path = rs.getString("path");
                        fh.language = rs.getString("language");
                        final Timestamp smt = rs.getTimestamp("mtime");
                        fh.indexedMtime = smt == null ? 0L : smt.getTime();
                        fh.matchCount = 0;
                        // No chunk was retrieved for this file, so the summary
                        // itself is the best excerpt we can show.
                        fh.bestContent = rs.getString("summary");
                        byId.put(fileId, fh);
                        files.add(fh);
                    }
                    fh.score = Math.round((fh.score + contrib) * 100000.0) / 100000.0;
                }
            }
        }
        files.sort((a, b) -> {
            final int c = Double.compare(b.score, a.score);
            return c != 0 ? c : Long.compare(a.fileId, b.fileId);
        });
    }

    /**
     * Fuse the per-symbol summary leg into the file ranking.
     * <br><br>
     * The file-level summary is one paragraph regardless of file size, so on a
     * very large class it cannot describe any individual method — precisely
     * where prose queries need the most help. This leg carries a summary per
     * symbol for those files, and when it is the reason a file surfaced, the
     * returned excerpt points at the matching symbol rather than at whatever
     * chunk happened to rank.
     * <br><br>
     * No-op until {@code ./bld summarize <project> --symbols} has been run.
     */
    private static void fuseSymbolSummaryLeg(Connection conn, String project, SearchRequest req,
                                             String vecLit, List<FileHit> files,
                                             double rrfK, int limit) throws Exception {
        final double w = envDouble("RAGSymbolSummaryWeight", 1.0);
        if (w <= 0.0 || vecLit == null)
            return;

        final StringBuilder sql = new StringBuilder(
                "SELECT s.file_id, s.symbol, s.sym_start_line, s.sym_end_line, s.summary, " +
                "       f.repo, f.path, f.language, f.mtime " +
                "  FROM " + project + ".rag_symbol s JOIN " + project + ".rag_file f USING (file_id) " +
                " WHERE TRUE");
        appendFilters(sql, req);
        sql.append(" ORDER BY s.embedding <=> ?::vector LIMIT ?");

        final Map<Long, FileHit> byId = new HashMap<>();
        for (FileHit fh : files)
            byId.put(fh.fileId, fh);
        // Only a file's BEST symbol may contribute. Summing over every matching
        // symbol let a large file with many mediocre matches outscore a file
        // with one excellent one — which is backwards, and measurably halved
        // hit@1 (0.62 -> 0.24) before this guard.
        final Set<Long> scored = new java.util.HashSet<>();

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = bindFilters(ps, req, 1);
            ps.setString(idx++, vecLit);
            ps.setInt(idx, limit);
            int rank = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rank++;
                    final long fileId = rs.getLong("file_id");
                    final double contrib = w / (rrfK + rank);
                    FileHit fh = byId.get(fileId);
                    final boolean isNew = (fh == null);
                    if (isNew) {
                        fh = new FileHit();
                        fh.fileId = fileId;
                        fh.repo = rs.getString("repo");
                        fh.path = rs.getString("path");
                        fh.language = rs.getString("language");
                        final Timestamp ymt = rs.getTimestamp("mtime");
                        fh.indexedMtime = ymt == null ? 0L : ymt.getTime();
                        byId.put(fileId, fh);
                        files.add(fh);
                    }
                    final String sym = rs.getString("symbol");
                    if (sym != null && !sym.isEmpty() && !fh.symbols.contains(sym))
                        fh.symbols.add(sym);
                    // Point the excerpt at the matching symbol when this leg is
                    // what surfaced the file — otherwise the agent gets a
                    // ranked file with an unrelated excerpt from it.
                    if (isNew) {
                        fh.bestStartLine = rs.getInt("sym_start_line");
                        fh.bestEndLine = rs.getInt("sym_end_line");
                        fh.bestContent = rs.getString("summary");
                    }
                    if (scored.add(fileId))
                        fh.score = Math.round((fh.score + contrib) * 100000.0) / 100000.0;
                }
            }
        }
        files.sort((a, b) -> {
            final int c = Double.compare(b.score, a.score);
            return c != 0 ? c : Long.compare(a.fileId, b.fileId);
        });
    }

    /**
     * Collapse a ranked chunk list into a ranked file list.
     *
     * @param ranked  chunks, best-first
     * @param k       how many files to return
     * @param repsOut receives each file's best chunk, keyed by file id
     */
    private static List<FileHit> aggregateByFile(List<Hit> ranked, int k, Map<Long, Hit> repsOut) {
        final Map<Long, FileHit> byFile = new LinkedHashMap<>();
        final Map<Long, List<Double>> scores = new HashMap<>();
        for (Hit h : ranked) {
            FileHit fh = byFile.get(h.fileId);
            if (fh == null) {
                fh = new FileHit();
                fh.fileId = h.fileId;
                fh.repo = h.repo;
                fh.path = h.path;
                fh.language = h.language;
                fh.indexedMtime = h.indexedMtime;
                byFile.put(h.fileId, fh);
                // `ranked` is best-first, so the first chunk seen for a file is
                // its best one.
                repsOut.put(h.fileId, h);
            }
            fh.matchCount++;
            if (h.symbol != null && !h.symbol.isEmpty() && !fh.symbols.contains(h.symbol))
                fh.symbols.add(h.symbol);
            scores.computeIfAbsent(h.fileId, x -> new ArrayList<>()).add(h.score);
        }
        for (FileHit fh : byFile.values()) {
            final List<Double> s = scores.get(fh.fileId);
            s.sort((a, b) -> Double.compare(b, a));
            double total = 0.0;
            for (int i = 0; i < Math.min(FILE_SCORE_DEPTH, s.size()); i++)
                total += s.get(i);
            fh.score = Math.round(total * 100000.0) / 100000.0;
        }
        final List<FileHit> out = new ArrayList<>(byFile.values());
        out.sort((a, b) -> {
            final int c = Double.compare(b.score, a.score);
            return c != 0 ? c : Long.compare(a.fileId, b.fileId);
        });
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    /**
     * Fraction of {@code candidate}'s tokens that appear in {@code queryTerms},
     * in [0,1]. Measures how completely the query accounts for the candidate
     * identifier, so a full name match scores 1.0 and an incidental single-word
     * overlap scores low.
     */
    private static double overlapFraction(Set<String> candidate, Set<String> queryTerms) {
        if (candidate.isEmpty())
            return 0.0;
        int hits = 0;
        for (String t : candidate)
            if (queryTerms.contains(t))
                hits++;
        return (double) hits / (double) candidate.size();
    }

    /** Drop a trailing file extension so "Connection.java" tokenizes without "java". */
    private static String stripExtension(String name) {
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Cap how many chunks any one file may contribute to the result set.
     * <br><br>
     * Without this a single large, on-topic file can occupy every slot, which
     * is the worst case for a cross-file question ("what is involved in the
     * OAuth flow"). Overflow chunks are not discarded — they are appended after
     * the diverse selection, so a genuinely single-file answer still fills k.
     */
    private static List<Hit> applyDiversity(List<Hit> ranked, int k, int maxPerFile) {
        if (maxPerFile <= 0)
            return ranked.subList(0, Math.min(k, ranked.size()));
        final List<Hit> picked = new ArrayList<>(k);
        final List<Hit> overflow = new ArrayList<>();
        final Map<Long, Integer> perFile = new HashMap<>();
        for (Hit h : ranked) {
            if (picked.size() >= k)
                break;
            final int n = perFile.getOrDefault(h.fileId, 0);
            if (n < maxPerFile) {
                picked.add(h);
                perFile.put(h.fileId, n + 1);
            } else {
                overflow.add(h);
            }
        }
        for (Hit h : overflow) {
            if (picked.size() >= k)
                break;
            picked.add(h);
        }
        return picked;
    }

    /**
     * Heuristic: is this chunk almost entirely an import/include block?
     * <br><br>
     * Deliberately narrow. An earlier version also counted comment lines
     * ({@code *}, {@code //}, {@code /*}) as boilerplate, which measurably hurt
     * retrieval: doc comments are the richest natural-language description of
     * what code does, and they are the reason a query phrased in domain
     * vocabulary finds the right chunk at all. Penalizing them attacked exactly
     * the queries this system exists to serve. Comments are now left alone;
     * only declaration preamble is demoted.
     */
    private static boolean looksLikeBoilerplate(String content) {
        if (content == null || content.isEmpty())
            return false;
        final String[] lines = content.split("\n", 40);
        int considered = 0;
        int boiler = 0;
        for (String raw : lines) {
            final String t = raw.trim();
            if (t.isEmpty())
                continue;
            considered++;
            if (considered > 30)
                break;
            if (t.startsWith("import ") || t.startsWith("#include ") || t.startsWith("using ")
                    || t.startsWith("package ") || t.startsWith("from ")
                    || t.startsWith("require(") || t.startsWith("#import "))
                boiler++;
        }
        return considered >= 3 && boiler * 100 / considered >= 80;
    }

    /**
     * Flag results whose file has changed on disk since it was indexed.
     * <br><br>
     * The index is only as current as the last sweep, which runs every few
     * minutes. Between a file being edited and the next sweep, a search returns
     * the OLD content and the OLD line numbers with no indication that anything
     * is wrong — so an agent can read stale code and act on it confidently.
     * That is a correctness problem, not a ranking one, which is why the answer
     * is to tell the truth about it rather than to hide or demote the result:
     * a stale hit is still almost certainly the right FILE.
     * <br><br>
     * Comparison is against the mtime recorded at index time, not against
     * indexed_at — the question is "has the file moved on since we read it",
     * and only the file's own timestamp answers that. One stat() per returned
     * hit; at k=8 that is not measurable.
     */
    private static void markStaleness(String project, List<Hit> hits) {
        for (Hit h : hits) {
            final long disk = diskMtime(project, h.repo, h.path);
            if (disk == -1L)
                h.missing = true;
            else if (h.indexedMtime > 0L && disk != h.indexedMtime)
                h.stale = true;
        }
    }

    private static void markFileStaleness(String project, List<FileHit> files) {
        for (FileHit f : files) {
            final long disk = diskMtime(project, f.repo, f.path);
            if (disk == -1L)
                f.missing = true;
            else if (f.indexedMtime > 0L && disk != f.indexedMtime)
                f.stale = true;
        }
    }

    /** Current mtime of a result's file, or -1 when it no longer exists. */
    private static long diskMtime(String project, String repo, String path) {
        try {
            final java.io.File f = new java.io.File(absolutePath(project, repo, path));
            if (!f.isFile())
                return -1L;
            return f.lastModified();
        } catch (Exception e) {
            return 0L;   // unknown: do not claim staleness we cannot prove
        }
    }

    /** Cap on tsquery terms — bounds worst-case cost on a rambling query. */
    private static final int MAX_TSQUERY_TERMS = 40;

    /**
     * Drop duplicate hits for the same physical file seen through more than one
     * project.
     * <br><br>
     * Vendoring makes this common rather than exotic: the Kiss framework is both
     * its own project and a subdirectory of Stack360, so 331 files are indexed
     * twice and a cross-project search would spend two of its result slots
     * showing the same file. Identity is (repo, path, line range) — repo is the
     * root's basename, so the same vendored tree collides by construction, which
     * is exactly what we want. The best-scoring copy wins because the list is
     * already sorted.
     */
    private static List<Hit> dedupeHits(List<Hit> ranked) {
        final Set<String> seen = new java.util.HashSet<>();
        final List<Hit> out = new ArrayList<>(ranked.size());
        for (Hit h : ranked) {
            // Key on the range that will actually be RETURNED, not the raw chunk
            // range. Two different fragments of one large method are distinct
            // chunks, but expansion widens both to the same enclosing symbol, so
            // keying on the chunk range lets visibly identical results through.
            final String range = h.symStartLine > 0
                    ? h.symStartLine + "-" + h.symEndLine
                    : h.startLine + "-" + h.endLine;
            final String key = h.repo + "\u001f" + h.path + "\u001f" + range;
            if (seen.add(key))
                out.add(h);
        }
        return out;
    }

    /**
     * Run both retrievers against one project, fuse with RRF, and apply the
     * heuristic rerank. Factored out so cross-project search can reuse the
     * identical per-project pipeline with a query vector embedded only once.
     */
    private static List<Hit> rankedCandidates(Connection conn, String project, SearchRequest req,
                                              String vecLit, double wDense, double wLex,
                                              double rrfK, int overfetch) throws Exception {
        final Map<Long, Hit> byId = new LinkedHashMap<>();
        final Map<Long, Double> fused = new HashMap<>();
        if (wDense > 0.0 && vecLit != null)
            accumulate(runDense(conn, project, req, vecLit, overfetch), byId, fused, wDense, rrfK);
        if (wLex > 0.0) {
            final String tsq = RAGTokenizer.toTsQueryOr(req.query, MAX_TSQUERY_TERMS);
            if (!tsq.isEmpty())
                accumulate(runLexical(conn, project, req, tsq, overfetch), byId, fused, wLex, rrfK);
        }
        return rerank(req.query, byId, fused);
    }

    /**
     * Search every configured project at once.
     * <br><br>
     * This is the capability a directory-scoped Grep cannot provide at any
     * effort: an agent launched in one repository still finds the answer living
     * in a sibling repository. The query is embedded once and reused across all
     * schemas — embedding dominates the cost, so N projects is far cheaper than
     * N searches.
     * <br><br>
     * Caveat on ordering: per-project scores are RRF ranks, so a project's
     * top hit scores about the same regardless of corpus size. Merging by score
     * therefore interleaves projects rather than strictly ordering by relevance.
     * That is usually what you want here ("which repo has this?"), but it is not
     * the same ranking a single-project search would produce.
     */
    public static SearchResult searchAll(Connection conn, List<String> projects,
                                         SearchRequest req) throws Exception {
        if (req == null || req.query == null || req.query.isEmpty())
            throw new IllegalArgumentException("query is required");
        int k = req.k;
        if (k < 1)
            k = 1;
        if (k > MAX_K)
            k = MAX_K;

        final double wDense = envDouble("RAGHybridWeightDense", 1.0);
        final double wLex = envDouble("RAGHybridWeightLexical", 0.25);
        final double rrfK = envDouble("RAGRRFConstant", 60.0);
        int overfetch = envInt("RAGOverfetch", 50);
        if (overfetch < k)
            overfetch = k;

        final SearchResult out = new SearchResult();
        out.project = "_all";
        out.query = req.query;

        final long t0 = System.currentTimeMillis();
        final String vecLit = wDense > 0.0 ? vectorToLiteral(embedQuery(req.query)) : null;
        final long t1 = System.currentTimeMillis();
        out.embedMillis = t1 - t0;

        applyEfSearch(conn);

        final List<Hit> merged = new ArrayList<>();
        for (String p : projects) {
            if (!ProjectRegistry.isValidName(p))
                continue;
            try {
                final List<Hit> hits = rankedCandidates(conn, p, req, vecLit, wDense, wLex, rrfK, overfetch);
                for (Hit h : hits)
                    h.project = p;
                merged.addAll(hits);
            } catch (Exception e) {
                // One unbootstrapped or broken project must not take down a
                // search across all the others.
                logger.warn("searchAll: skipping project '" + p + "': " + e.getMessage());
            }
        }
        merged.sort((a, b) -> {
            final int c = Double.compare(b.score, a.score);
            return c != 0 ? c : Long.compare(a.chunkId, b.chunkId);
        });

        final List<Hit> deduped = dedupeHits(merged);
        final List<Hit> selected = applyDiversity(deduped, k, envInt("RAGMaxPerFile", 2));
        if (!"none".equalsIgnoreCase(req.expand)) {
            final int ceiling = req.maxChars > 0 ? req.maxChars : envInt("RAGMaxExpandChars", 6000);
            // Expansion is a per-schema query, so group the selection by project.
            final Map<String, List<Hit>> byProject = new LinkedHashMap<>();
            for (Hit h : selected)
                byProject.computeIfAbsent(h.project, x -> new ArrayList<>()).add(h);
            for (Map.Entry<String, List<Hit>> e : byProject.entrySet())
                expandToSymbols(conn, e.getKey(), e.getValue(), ceiling);
        }
        for (Hit h : selected)
            markStaleness(h.project, java.util.Collections.singletonList(h));
        out.hits.addAll(selected);
        out.queryMillis = System.currentTimeMillis() - t1;
        return out;
    }

    /** Fold one retriever's ranked list into the running RRF totals. */
    private static void accumulate(List<Hit> list, Map<Long, Hit> byId,
                                   Map<Long, Double> fused, double weight, double rrfK) {
        for (int i = 0; i < list.size(); i++) {
            final Hit h = list.get(i);
            byId.putIfAbsent(h.chunkId, h);
            final double contrib = weight / (rrfK + (i + 1));
            fused.merge(h.chunkId, contrib, Double::sum);
        }
    }

    /** Append the shared repo/language/path filters to a WHERE clause. */
    private static void appendFilters(StringBuilder sql, SearchRequest req) {
        if (req.repo != null && !req.repo.isEmpty())
            sql.append(" AND f.repo = ?");
        if (req.language != null && !req.language.isEmpty())
            sql.append(" AND f.language = ?");
        if (req.pathPrefix != null && !req.pathPrefix.isEmpty())
            sql.append(" AND f.path LIKE ?");
    }

    /** Bind the filters appended by {@link #appendFilters}; returns the next index. */
    private static int bindFilters(PreparedStatement ps, SearchRequest req, int idx) throws Exception {
        if (req.repo != null && !req.repo.isEmpty())
            ps.setString(idx++, req.repo);
        if (req.language != null && !req.language.isEmpty())
            ps.setString(idx++, req.language);
        if (req.pathPrefix != null && !req.pathPrefix.isEmpty())
            ps.setString(idx++, req.pathPrefix + "%");
        return idx;
    }

    private static final String HIT_COLUMNS =
            "c.chunk_id, c.file_id, f.repo, f.path, f.language, f.mtime, " +
            "c.start_line, c.end_line, c.symbol, c.content, " +
            "c.sym_start_line, c.sym_end_line ";

    private static Hit readHit(ResultSet rs) throws Exception {
        final Hit h = new Hit();
        h.chunkId = rs.getLong("chunk_id");
        h.fileId = rs.getLong("file_id");
        h.repo = rs.getString("repo");
        h.path = rs.getString("path");
        h.language = rs.getString("language");
        h.startLine = rs.getInt("start_line");
        h.endLine = rs.getInt("end_line");
        h.symbol = rs.getString("symbol");
        h.content = rs.getString("content");
        h.symStartLine = rs.getInt("sym_start_line");
        h.symEndLine = rs.getInt("sym_end_line");
        final Timestamp mt = rs.getTimestamp("mtime");
        h.indexedMtime = mt == null ? 0L : mt.getTime();
        return h;
    }

    /**
     * Widen fragment hits to the whole enclosing symbol.
     * <br><br>
     * A method too large for one chunk is stored as several fragments. Landing
     * on fragment 2 of 3 gives the agent the middle of a method with no
     * signature and no return — technically a match, practically useless. Every
     * fragment records its parent symbol's line range, so the siblings can be
     * stitched back together.
     * <br><br>
     * Reconstruction reads the sibling chunks from the database rather than the
     * file on disk: the stored chunks are what the recorded line numbers
     * actually describe, whereas the file may have changed since the last
     * sweep. One batched query covers all hits.
     */
    private static void expandToSymbols(Connection conn, String project,
                                        List<Hit> hits, int maxChars) throws Exception {
        final List<Hit> needy = new ArrayList<>();
        for (Hit h : hits) {
            // Only fragments need widening: a chunk that already spans its
            // whole symbol is complete as-is.
            if (h.symStartLine > 0 && h.symEndLine > 0
                    && (h.startLine > h.symStartLine || h.endLine < h.symEndLine))
                needy.add(h);
        }
        if (needy.isEmpty())
            return;

        final StringBuilder in = new StringBuilder();
        for (int i = 0; i < needy.size(); i++)
            in.append(i > 0 ? ",?" : "?");
        final String sql =
                "SELECT file_id, sym_start_line, chunk_no, content " +
                "  FROM " + project + ".rag_chunk " +
                " WHERE file_id IN (" + in + ") AND sym_start_line > 0 " +
                " ORDER BY file_id, sym_start_line, chunk_no";

        // (file_id, sym_start_line) -> concatenated fragments, in chunk order.
        final Map<String, StringBuilder> assembled = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Hit h : needy)
                ps.setLong(idx++, h.fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String key = rs.getLong("file_id") + ":" + rs.getInt("sym_start_line");
                    final StringBuilder sb = assembled.computeIfAbsent(key, k -> new StringBuilder());
                    if (sb.length() > maxChars)
                        continue;
                    if (sb.length() > 0)
                        sb.append('\n');
                    sb.append(rs.getString("content"));
                }
            }
        }
        for (Hit h : needy) {
            final StringBuilder sb = assembled.get(h.fileId + ":" + h.symStartLine);
            if (sb == null || sb.length() == 0)
                continue;
            String text = sb.toString();
            if (text.length() > maxChars)
                text = text.substring(0, maxChars);
            h.content = text;
            h.startLine = h.symStartLine;
            h.endLine = h.symEndLine;
            h.expanded = true;
        }
    }

    /** Dense leg: approximate nearest neighbors by cosine distance. */
    private static List<Hit> runDense(Connection conn, String project, SearchRequest req,
                                      String vecLit, int limit) throws Exception {
        final StringBuilder sql = new StringBuilder(
                "SELECT " + HIT_COLUMNS +
                "  FROM " + project + ".rag_chunk c JOIN " + project + ".rag_file f USING (file_id) " +
                " WHERE TRUE");
        appendFilters(sql, req);
        sql.append(" ORDER BY c.embedding <=> ?::vector LIMIT ?");

        final List<Hit> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = bindFilters(ps, req, 1);
            ps.setString(idx++, vecLit);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(readHit(rs));
            }
        }
        return out;
    }

    /** Lexical leg: GIN-indexed tsvector match ranked by ts_rank_cd. */
    private static List<Hit> runLexical(Connection conn, String project, SearchRequest req,
                                        String tsQueryExpr, int limit) throws Exception {
        final StringBuilder sql = new StringBuilder(
                "SELECT " + HIT_COLUMNS +
                "  FROM " + project + ".rag_chunk c JOIN " + project + ".rag_file f USING (file_id), " +
                "       to_tsquery('simple', ?) AS q " +
                " WHERE c.lexemes_tsv @@ q");
        appendFilters(sql, req);
        sql.append(" ORDER BY ts_rank_cd(c.lexemes_tsv, q) DESC, c.chunk_id LIMIT ?");

        final List<Hit> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, tsQueryExpr);
            idx = bindFilters(ps, req, idx);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(readHit(rs));
            }
        }
        return out;
    }

    private static double envDouble(String key, double dflt) {
        try {
            final String s = (String) MainServlet.getEnvironment(key);
            if (s != null && !s.isEmpty())
                return Double.parseDouble(s);
        } catch (Exception ignored) { /* fall through */ }
        return dflt;
    }

    private static int envInt(String key, int dflt) {
        try {
            final String s = (String) MainServlet.getEnvironment(key);
            if (s != null && !s.isEmpty())
                return Integer.parseInt(s);
        } catch (Exception ignored) { /* fall through */ }
        return dflt;
    }

    /**
     * Fetch one chunk by id. Returns null when the id does not exist.
     * Shared with the MCP {@code get_chunk} tool.
     */
    public static Hit getChunk(Connection conn, String project, long chunkId) throws Exception {
        if (project == null || !ProjectRegistry.isValidName(project))
            throw new IllegalArgumentException("Invalid project name: " + project);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.chunk_id, c.file_id, f.repo, f.path, c.start_line, c.end_line, " +
                "       c.symbol, c.content, c.token_est, f.language " +
                "  FROM " + project + ".rag_chunk c JOIN " + project + ".rag_file f USING (file_id) " +
                " WHERE c.chunk_id = ?")) {
            ps.setLong(1, chunkId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return null;
                final Hit h = new Hit();
                h.chunkId = rs.getLong("chunk_id");
                h.fileId = rs.getLong("file_id");
                h.repo = rs.getString("repo");
                h.path = rs.getString("path");
                h.language = rs.getString("language");
                h.startLine = rs.getInt("start_line");
                h.endLine = rs.getInt("end_line");
                h.symbol = rs.getString("symbol");
                h.content = rs.getString("content");
                h.tokenEst = rs.getInt("token_est");
                return h;
            }
        }
    }

    /** Run {@code SET hnsw.ef_search = N} on this connection. */
    public static void applyEfSearch(Connection conn) throws Exception {
        int ef = 400;
        try {
            final String s = (String) MainServlet.getEnvironment("HNSWEfSearch");
            if (s != null && !s.isEmpty())
                ef = Integer.parseInt(s);
        } catch (Exception ignored) { /* fall back to default */ }
        try (Statement st = conn.createStatement()) {
            st.execute("SET hnsw.ef_search = " + ef);
        }
    }

    // ====================================================================================
    // Embedding
    // ====================================================================================

    /**
     * Optionally rewrite a query into a hypothetical answer before embedding
     * ("HyDE").
     * <br><br>
     * The dominant failure mode in this system was never the similarity math —
     * it was that the words a person searches with are absent from the thing
     * being embedded. The two biggest wins so far (context headers, file
     * summaries) both worked by adding the missing vocabulary to the DOCUMENT.
     * This attacks the same gap from the other end: expand the short query into
     * a paragraph of the kind of text that would answer it, so the query vector
     * lands nearer the documents.
     * <br><br>
     * Off unless {@code RAGQueryExpansionModel} is set, because it puts a
     * generation on the critical path of a call the agent blocks on. Any
     * failure returns the original query unchanged.
     */
    private static String expandQuery(String query) {
        final String model = envString("RAGQueryExpansionModel", "");
        if (model.isEmpty())
            return query;
        try {
            final String prompt =
                    "Write two sentences of the source-code documentation that would answer this "
                    + "question about a codebase. Use the plain technical vocabulary such "
                    + "documentation would use. Do not answer the question, do not mention that you "
                    + "are speculating, output only the two sentences.\n\nQuestion: " + query;
            final JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);
            final JSONObject opts = new JSONObject();
            opts.put("temperature", 0.0);
            opts.put("num_predict", 90);
            body.put("options", opts);

            String base = envString("OllamaURL", "http://127.0.0.1:11434");
            if (base.endsWith("/"))
                base = base.substring(0, base.length() - 1);
            final HttpURLConnection con = (HttpURLConnection) new URL(base + "/api/generate").openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(1000);
            con.setReadTimeout(envInt("RAGQueryExpansionTimeoutMs", 4000));
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStreamWriter w = new OutputStreamWriter(con.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write(body.toString());
            }
            if (con.getResponseCode() != 200)
                return query;
            final String resp = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String text = new JSONObject(resp).getString("response", "").trim();
            if (text.isEmpty())
                return query;
            // Keep the original query in the embedded text: the expansion is
            // extra signal, not a replacement, and a bad expansion should not
            // be able to steer the search away from what was actually asked.
            return query + "\n" + text;
        } catch (Exception e) {
            return query;
        }
    }

    /** Embed a single query string via Ollama /api/embeddings. */
    public static float[] embedQuery(String text) throws IOException {
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

    /** Render a float vector as a pgvector literal. */
    public static String vectorToLiteral(float[] v) {
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

    // ====================================================================================
    // Connections and path resolution
    // ====================================================================================

    /**
     * Open a pooled JDBC connection. Kiss exposes c3p0 via a package-private
     * accessor; reach it once via reflection.
     */
    public static Connection openConnection() throws Exception {
        final Method m = MainServlet.class.getDeclaredMethod("getCpds");
        m.setAccessible(true);
        final ComboPooledDataSource cpds = (ComboPooledDataSource) m.invoke(null);
        return cpds.getConnection();
    }

    /**
     * (project, repo) -> absolute root path on disk; built lazily from
     * rag-projects.json. The repo name is the last segment of the configured
     * root path — same convention as the indexer.
     */
    private static volatile Map<String, Map<String, String>> repoRootCache;

    /**
     * Resolve a (project, repo, relative-path) tuple to an absolute filesystem
     * path by consulting rag-projects.json. Returns a Linux path; any
     * client-specific translation is the caller's job.
     */
    public static String absolutePath(String project, String repo, String relPath) {
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
        final Map<String, String> inner = cache.get(project);
        final String root = inner == null ? null : inner.get(repo);
        if (root == null)
            return relPath;
        if (relPath == null || relPath.isEmpty())
            return root;
        return root.endsWith("/") ? root + relPath : root + "/" + relPath;
    }

    /**
     * Record a search or a follow-up fetch, for mining a real eval set later.
     * <br><br>
     * Every golden query set in this repo was hand-written, which makes it a
     * guess at what actually gets asked — the one caveat no amount of tuning
     * can remove. This captures the real thing: what was searched for, what came
     * back, and (via {@code kind='fetch'}) which result was then opened, which
     * is as close to a relevance judgement as usage can give.
     * <br><br>
     * Best-effort and non-fatal: a logging failure must never break a search.
     * Disabled with {@code RAGLogQueries=false}.
     */
    public static void logUsage(String project, String kind, String tool,
                                String query, String paths, long latencyMs) {
        if (project == null || !ProjectRegistry.isValidName(project))
            return;
        if (!"true".equalsIgnoreCase(envString("RAGLogQueries", "true")))
            return;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + project + ".rag_query_log (kind, tool, query, paths, latency_ms) " +
                     "VALUES (?,?,?,?,?)")) {
            ps.setString(1, kind);
            ps.setString(2, tool);
            ps.setString(3, query);
            ps.setString(4, paths);
            ps.setInt(5, (int) latencyMs);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.debug("usage logging failed for '" + project + "': " + e.getMessage());
        }
    }

    /** Drop the cached repo-root map (call after rag-projects.json changes). */
    public static void clearRepoRootCache() {
        repoRootCache = null;
    }
}
