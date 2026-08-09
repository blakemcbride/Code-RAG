package scripts

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.kissweb.database.Connection
import org.kissweb.database.Record
import org.kissweb.json.JSONArray
import org.kissweb.json.JSONObject
import org.kissweb.rag.RAGSearch
import org.kissweb.restServer.MainServlet

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Retrieval-quality eval harness.
 *
 * Runs a golden query set through {@link RAGSearch#search} — the exact code
 * path the live MCP tool uses — and scores the results against hand-written
 * expectations. Driven by {@code ./bld eval <project>}.
 *
 * <h2>Scoring model</h2>
 *
 * Expectations are file-level ("this query should surface this file"), but
 * retrieval is chunk-level. So every run:
 * <ol>
 *   <li>retrieves {@link #CHUNK_K} chunks (fixed, not the caller's k, so that
 *       numbers stay comparable across phases that change the default k),</li>
 *   <li>collapses them to a <b>file ranking</b> by de-duplicating on
 *       (repo, path) and keeping first appearance,</li>
 *   <li>scores against that file ranking.</li>
 * </ol>
 * Chunk-level hit@10 is also reported, but the file ranking is the honest
 * measure of "did the tool put the right file in front of the agent".
 *
 * <h2>Metrics</h2>
 * <ul>
 *   <li><b>hit@N</b> — fraction of queries with at least one expected file in
 *       the top N. The headline number.</li>
 *   <li><b>recall@10</b> — mean fraction of a query's expected files found in
 *       the top 10. Differs from hit@10 only for multi-file expectations.</li>
 *   <li><b>mrr@10</b> — mean reciprocal rank of the first expected file.</li>
 *   <li><b>ndcg@10</b> — rank-discounted coverage, binary relevance.</li>
 *   <li><b>latency</b> — p50/p95 wall time, with embedding split out because
 *       it dominates and is not affected by retrieval changes.</li>
 * </ul>
 * Everything is broken down by tag and by split so a change that helps
 * conceptual queries while wrecking symbol lookups is visible rather than
 * averaged away.
 */
class RAGEval {

    private static final Logger logger = LogManager.getLogger(RAGEval.class)

    /**
     * Chunks retrieved per query. Deliberately larger than the MCP tool's
     * default k and deliberately constant: every phase must be scored on the
     * same retrieval depth or the comparison is meaningless.
     */
    static final int CHUNK_K = 30

    /** Cutoffs reported for hit@N. */
    private static final List<Integer> CUTOFFS = [1, 5, 10]

    /**
     * Cross-file entry point, invoked via GroovyService.run.
     *
     * Takes a single JSONObject of parameters rather than a positional list:
     * Kiss resolves the target method by the runtime classes of the trailing
     * varargs, so every added parameter is another chance for the binding to
     * shift and fail at run time with an opaque NoSuchMethodException. One
     * object argument keeps the signature stable as options accumulate.
     *
     * params: { project, queryFile, granularity? }
     */
    static JSONObject runJson(Connection db, JSONObject params) {
        return run(db,
                params.getString("project", null),
                params.getString("queryFile", null),
                params.getString("granularity", "chunk"))
    }

    /**
     * Execute the eval.
     *
     * @param db         Kiss connection (its underlying JDBC connection is used)
     * @param project    project name / schema
     * @param queryFile  absolute path to the JSONL golden query set
     */
    static JSONObject run(Connection db, String project, String queryFile, String granularity) {
        boolean fileMode = "file".equalsIgnoreCase(granularity)
        List<JSONObject> queries = loadQueries(queryFile)
        if (queries.isEmpty())
            throw new RuntimeException("No queries loaded from ${queryFile}")

        java.sql.Connection sql = db.getSQLConnection()
        List<JSONObject> perQuery = []

        for (JSONObject q : queries) {
            String text = q.getString("q")
            Set<String> expected = new LinkedHashSet<>()
            JSONArray exp = q.getJSONArray("expect")
            for (int i = 0; i < exp.length(); i++) {
                JSONObject e = exp.getJSONObject(i)
                expected.add(key(e.getString("repo"), e.getString("path")))
            }

            RAGSearch.SearchRequest req = new RAGSearch.SearchRequest()
            req.query = text
            req.k = CHUNK_K
            req.granularity = fileMode ? "file" : "chunk"
            // Expansion changes only the returned body, never the ranking, and
            // costs an extra query per search. Off here so timings reflect
            // retrieval alone.
            req.expand = "none"

            long t0 = System.currentTimeMillis()
            RAGSearch.SearchResult res = RAGSearch.search(sql, project, req)
            long totalMs = System.currentTimeMillis() - t0

            List<String> fileRank = []
            boolean chunkHit10 = false
            if (fileMode) {
                // Already a file ranking, scored by aggregate rather than by
                // first appearance.
                for (RAGSearch.FileHit fh : res.fileHits)
                    fileRank.add(key(fh.repo, fh.path))
                int n = Math.min(10, fileRank.size())
                for (int i = 0; i < n; i++)
                    if (expected.contains(fileRank[i])) {
                        chunkHit10 = true
                        break
                    }
            } else {
                // Collapse chunk hits to a file ranking, first appearance wins.
                Set<String> seen = new HashSet<>()
                for (RAGSearch.Hit h : res.hits) {
                    String kk = key(h.repo, h.path)
                    if (seen.add(kk))
                        fileRank.add(kk)
                }
                // Chunk-level: did any of the first 10 chunks land in an expected file?
                int nChunks = Math.min(10, res.hits.size())
                for (int i = 0; i < nChunks; i++) {
                    if (expected.contains(key(res.hits[i].repo, res.hits[i].path))) {
                        chunkHit10 = true
                        break
                    }
                }
            }

            JSONObject row = new JSONObject()
            row.put("q", text)
            row.put("tags", q.has("tags") ? q.getJSONArray("tags") : new JSONArray())
            row.put("split", q.getString("split", "tune"))
            row.put("expectedCount", expected.size())
            row.put("firstRelevantRank", firstRelevantRank(fileRank, expected))
            for (Integer c : CUTOFFS)
                row.put("hit@" + c, hitAt(fileRank, expected, c) ? 1 : 0)
            row.put("recall@10", recallAt(fileRank, expected, 10))
            row.put("mrr@10", reciprocalRank(fileRank, expected, 10))
            row.put("ndcg@10", ndcgAt(fileRank, expected, 10))
            row.put("chunkHit@10", chunkHit10 ? 1 : 0)
            row.put("totalMs", totalMs)
            row.put("embedMs", res.embedMillis)
            row.put("queryMs", res.queryMillis)
            // Top files, for eyeballing what the retriever actually thought.
            JSONArray top = new JSONArray()
            for (int i = 0; i < Math.min(5, fileRank.size()); i++)
                top.put(fileRank[i])
            row.put("topFiles", top)
            perQuery.add(row)
        }

        JSONObject out = new JSONObject()
        out.put("project", project)
        out.put("queryFile", queryFile)
        out.put("queryCount", perQuery.size())
        out.put("chunkK", CHUNK_K)
        out.put("granularity", fileMode ? "file" : "chunk")
        out.put("config", configSnapshot(db, project))
        out.put("overall", aggregate(perQuery))

        // Per-split and per-tag breakdowns.
        JSONObject bySplit = new JSONObject()
        for (String s : distinct(perQuery, { it.getString("split", "tune") }))
            bySplit.put(s, aggregate(perQuery.findAll { it.getString("split", "tune") == s }))
        out.put("bySplit", bySplit)

        JSONObject byTag = new JSONObject()
        Set<String> tags = new TreeSet<>()
        for (JSONObject r : perQuery) {
            JSONArray ta = r.getJSONArray("tags")
            for (int i = 0; i < ta.length(); i++)
                tags.add(ta.getString(i))
        }
        for (String t : tags) {
            List<JSONObject> subset = perQuery.findAll { r ->
                JSONArray ta = r.getJSONArray("tags")
                for (int i = 0; i < ta.length(); i++)
                    if (ta.getString(i) == t)
                        return true
                return false
            }
            byTag.put(t, aggregate(subset))
        }
        out.put("byTag", byTag)

        JSONArray pq = new JSONArray()
        for (JSONObject r : perQuery)
            pq.put(r)
        out.put("perQuery", pq)
        return out
    }

    // ----- metrics -------------------------------------------------------------

    private static String key(String repo, String path) {
        return repo + " " + path
    }

    /** 1-based rank of the first expected file, or 0 if absent entirely. */
    private static int firstRelevantRank(List<String> rank, Set<String> expected) {
        for (int i = 0; i < rank.size(); i++)
            if (expected.contains(rank[i]))
                return i + 1
        return 0
    }

    private static boolean hitAt(List<String> rank, Set<String> expected, int n) {
        int r = firstRelevantRank(rank, expected)
        return r > 0 && r <= n
    }

    private static double recallAt(List<String> rank, Set<String> expected, int n) {
        if (expected.isEmpty())
            return 0.0d
        int found = 0
        int lim = Math.min(n, rank.size())
        for (int i = 0; i < lim; i++)
            if (expected.contains(rank[i]))
                found++
        return (double) found / (double) expected.size()
    }

    private static double reciprocalRank(List<String> rank, Set<String> expected, int n) {
        int r = firstRelevantRank(rank, expected)
        return (r > 0 && r <= n) ? 1.0d / r : 0.0d
    }

    private static double ndcgAt(List<String> rank, Set<String> expected, int n) {
        double dcg = 0.0d
        int lim = Math.min(n, rank.size())
        for (int i = 0; i < lim; i++)
            if (expected.contains(rank[i]))
                dcg += 1.0d / (Math.log((double)(i + 2)) / Math.log(2.0d))
        double idcg = 0.0d
        int ideal = Math.min(n, expected.size())
        for (int i = 0; i < ideal; i++)
            idcg += 1.0d / (Math.log((double)(i + 2)) / Math.log(2.0d))
        return idcg == 0.0d ? 0.0d : dcg / idcg
    }

    private static JSONObject aggregate(List<JSONObject> rows) {
        JSONObject o = new JSONObject()
        o.put("n", rows.size())
        if (rows.isEmpty())
            return o
        for (Integer c : CUTOFFS)
            o.put("hit@" + c, round3(mean(rows, "hit@" + c)))
        o.put("recall@10", round3(mean(rows, "recall@10")))
        o.put("mrr@10", round3(mean(rows, "mrr@10")))
        o.put("ndcg@10", round3(mean(rows, "ndcg@10")))
        o.put("chunkHit@10", round3(mean(rows, "chunkHit@10")))
        // Count of queries where the expected file never appeared at all.
        int misses = rows.count { it.getInt("firstRelevantRank", 0) == 0 }
        o.put("totalMisses", misses)
        List<Long> totals = rows.collect { (long) it.getInt("totalMs", 0) }
        List<Long> embeds = rows.collect { (long) it.getInt("embedMs", 0) }
        List<Long> qms    = rows.collect { (long) it.getInt("queryMs", 0) }
        o.put("p50TotalMs", percentile(totals, 50))
        o.put("p95TotalMs", percentile(totals, 95))
        o.put("p50EmbedMs", percentile(embeds, 50))
        o.put("p50QueryMs", percentile(qms, 50))
        return o
    }

    private static double mean(List<JSONObject> rows, String field) {
        double s = 0.0d
        for (JSONObject r : rows)
            s += r.getDouble(field, 0.0d)
        return rows.isEmpty() ? 0.0d : s / rows.size()
    }

    private static long percentile(List<Long> vals, int pct) {
        if (vals.isEmpty())
            return 0L
        List<Long> sorted = new ArrayList<>(vals)
        Collections.sort(sorted)
        int idx = (int) Math.ceil(pct / 100.0d * sorted.size()) - 1
        if (idx < 0)
            idx = 0
        if (idx >= sorted.size())
            idx = sorted.size() - 1
        return sorted[idx]
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0d) / 1000.0d
    }

    private static List<String> distinct(List<JSONObject> rows, Closure<String> f) {
        Set<String> s = new TreeSet<>()
        for (JSONObject r : rows)
            s.add(f(r))
        return new ArrayList<>(s)
    }

    // ----- inputs --------------------------------------------------------------

    private static List<JSONObject> loadQueries(String path) {
        java.nio.file.Path p = Paths.get(path)
        if (!Files.exists(p))
            throw new RuntimeException("Query file not found: ${path}")
        List<JSONObject> out = []
        int lineNo = 0
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            lineNo++
            String t = line.trim()
            if (t.isEmpty() || t.startsWith("#"))
                continue
            JSONObject o
            try {
                o = new JSONObject(t)
            } catch (Exception e) {
                throw new RuntimeException("Malformed JSON at ${path}:${lineNo}: ${e.message}")
            }
            if (!o.has("q") || !o.has("expect"))
                throw new RuntimeException("Missing 'q' or 'expect' at ${path}:${lineNo}")
            out.add(o)
        }
        return out
    }

    /**
     * Snapshot the settings that affect retrieval, so a results file is
     * self-describing months later.
     */
    private static JSONObject configSnapshot(Connection db, String project) {
        JSONObject c = new JSONObject()
        c.put("embeddingModel", env("EmbeddingModel", "nomic-embed-text:v1.5"))
        c.put("embeddingDim", env("EmbeddingDim", "768"))
        c.put("hnswEfSearch", env("HNSWEfSearch", "400"))
        try {
            Record f = db.fetchOne("SELECT count(*) AS n FROM ${project}.rag_file".toString())
            Record ch = db.fetchOne("SELECT count(*) AS n FROM ${project}.rag_chunk".toString())
            c.put("fileCount", f.getLong("n"))
            c.put("chunkCount", ch.getLong("n"))
        } catch (Exception ignored) { /* counts are informational */ }
        try {
            Record sv = db.fetchOne(
                    "SELECT value FROM ${project}.rag_meta WHERE key = 'schema_version'".toString())
            if (sv != null)
                c.put("schemaVersion", sv.getString("value"))
        } catch (Exception ignored) { /* informational */ }
        return c
    }

    private static String env(String key, String dflt) {
        try {
            String v = MainServlet.getEnvironment(key)
            return (v != null && !v.isEmpty()) ? v : dflt
        } catch (Exception ignored) {
            return dflt
        }
    }
}
