package scripts

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.kissweb.database.Connection
import org.kissweb.database.Record
import org.kissweb.json.JSONObject
import org.kissweb.rag.ProjectRegistry
import org.kissweb.rag.RAGSearch
import org.kissweb.restServer.MainServlet

import java.nio.charset.StandardCharsets

/**
 * Generates a one-paragraph natural-language summary of every indexed file and
 * embeds it as a second, independent retrieval signal.
 *
 * <h2>Why this exists</h2>
 * Queries are written in the vocabulary of intent; code is written in the
 * vocabulary of implementation. Someone asks "where do we stop two sweeps
 * running at once" and the code says {@code tryAcquireLock}. Embedding the
 * source can only ever partially bridge that gap, because the words simply are
 * not there. A summary puts them there: it is prose about the code, so a prose
 * query matches it directly.
 *
 * <h2>Cost</h2>
 * Roughly a second per file — by far the most expensive operation in the
 * system, and it saturates the GPU while running. It is therefore a separate
 * pass ({@code ./bld summarize}) rather than part of the sweep, and it is
 * incremental: {@code summary_sha} records the file's sha256 at the time the
 * summary was written, so re-running only touches genuinely changed files.
 * A failure or interruption loses only the file in flight.
 */
class RAGSummarizer {

    private static final Logger logger = LogManager.getLogger(RAGSummarizer.class)

    /** Max source characters sent to the model. Beyond this, summaries stop improving. */
    private static final int MAX_SOURCE_CHARS = 6000
    /** Commit (and report progress) every this many files. */
    private static final int PROGRESS_EVERY = 25

    /** JSON-RPC entry point. params: { project, regenerate?, maxFiles?, symbols? } */
    static JSONObject runJson(Connection db, JSONObject params) {
        if (params.getBoolean("symbols", Boolean.FALSE))
            return runSymbols(db, params.getString("project", null),
                              params.getInt("maxFiles", 0))
        return run(db, params.getString("project", null),
                   params.getBoolean("regenerate", Boolean.FALSE),
                   params.getInt("maxFiles", 0))
    }

    /**
     * Summarize individual symbols inside large files.
     *
     * Only files above {@code RAGSymbolSummaryMinChunks} are eligible: a small
     * file is already well described by its single file-level summary, and
     * summarizing every symbol everywhere would cost many hours for no gain.
     * Incremental on the file's sha256, same as file summaries.
     */
    static JSONObject runSymbols(Connection db, String project, int maxFiles) {
        if (!ProjectRegistry.isValidName(project))
            throw new RuntimeException("Invalid project name: ${project}")
        ProjectRegistry.Project proj = ProjectRegistry.get(project)
        if (proj == null)
            throw new RuntimeException("Unknown project '${project}'")
        String model = env("RAGSummaryModel", "qwen2.5-coder:latest")
        int minChunks = Integer.parseInt(env("RAGSymbolSummaryMinChunks", "30"))
        String limit = maxFiles > 0 ? " LIMIT ${maxFiles}" : ""

        // Distinct symbols of large files whose summary is missing or stale.
        List<Record> todo = db.fetchAll(
                ("""
                 WITH big AS (
                     SELECT file_id FROM ${project}.rag_chunk
                     GROUP BY file_id HAVING count(*) > ${minChunks}
                 )
                 SELECT DISTINCT c.file_id, c.sym_start_line, c.sym_end_line,
                        min(c.symbol) AS symbol, f.repo, f.path, f.sha256
                   FROM ${project}.rag_chunk c
                   JOIN big USING (file_id)
                   JOIN ${project}.rag_file f USING (file_id)
                   LEFT JOIN ${project}.rag_symbol s
                          ON s.file_id = c.file_id AND s.sym_start_line = c.sym_start_line
                  WHERE c.sym_start_line > 0
                    AND (s.summary_sha IS NULL OR s.summary_sha <> f.sha256)
                  GROUP BY c.file_id, c.sym_start_line, c.sym_end_line, f.repo, f.path, f.sha256
                  ORDER BY c.file_id, c.sym_start_line${limit}
                 """).toString())

        Map<String, File> roots = [:]
        for (String rootPath : proj.roots) {
            File rf = new File(rootPath)
            roots[rf.name] = rf
        }

        long t0 = System.currentTimeMillis()
        int done = 0, skipped = 0, failed = 0
        for (Record r : todo) {
            File root = roots[r.getString("repo")]
            if (root == null) {
                skipped++
                continue
            }
            File src = new File(root, r.getString("path"))
            if (!src.isFile()) {
                skipped++
                continue
            }
            try {
                String body = sliceFile(src, r.getInt("sym_start_line"), r.getInt("sym_end_line"))
                if (body == null || body.trim().isEmpty()) {
                    skipped++
                    continue
                }
                String sym = r.getString("symbol") ?: "(anonymous)"
                String summary = generateSymbol(model, r.getString("path"), sym, body)
                if (summary == null || summary.trim().isEmpty()) {
                    failed++
                    continue
                }
                float[] v = RAGSearch.embedQuery(summary)
                db.execute(
                        ("INSERT INTO ${project}.rag_symbol " +
                         "(file_id, symbol, sym_start_line, sym_end_line, summary, summary_model, summary_sha, embedding) " +
                         "VALUES (?,?,?,?,?,?,?,?::vector) " +
                         "ON CONFLICT (file_id, sym_start_line) DO UPDATE SET " +
                         "  symbol = EXCLUDED.symbol, sym_end_line = EXCLUDED.sym_end_line, " +
                         "  summary = EXCLUDED.summary, summary_model = EXCLUDED.summary_model, " +
                         "  summary_sha = EXCLUDED.summary_sha, embedding = EXCLUDED.embedding").toString(),
                        r.getLong("file_id"), sym, r.getInt("sym_start_line"), r.getInt("sym_end_line"),
                        summary, model, r.getString("sha256"), RAGSearch.vectorToLiteral(v))
                done++
                if (done % PROGRESS_EVERY == 0) {
                    db.commit()
                    reportProgress(db, project, done, todo.size(), t0)
                }
            } catch (Exception e) {
                failed++
                logger.warn("RAGSummarizer[${project}] symbol ${r.getString('path')}: ${e.message}")
                try { db.rollback() } catch (Exception ignored) { }
            }
        }
        db.commit()
        clearProgress(db, project)

        JSONObject out = new JSONObject()
        out.put("project", project)
        out.put("mode", "symbols")
        out.put("candidates", todo.size())
        out.put("summarized", done)
        out.put("skipped", skipped)
        out.put("failed", failed)
        out.put("elapsedSec", (System.currentTimeMillis() - t0) / 1000L)
        logger.info("RAGSummarizer[${project}] symbols done: ${done}/${todo.size()}")
        return out
    }

    /** Lines [from,to] of a file, 1-based inclusive, capped. */
    private static String sliceFile(File f, int from, int to) {
        List<String> lines = f.readLines()
        if (from < 1)
            from = 1
        if (to > lines.size())
            to = lines.size()
        if (from > to)
            return null
        String s = lines.subList(from - 1, to).join("\n")
        return s.length() > MAX_SOURCE_CHARS ? s.substring(0, MAX_SOURCE_CHARS) : s
    }

    private static String generateSymbol(String model, String relPath, String symbol, String body) {
        String prompt = """You are documenting one function or class for code search.

File: ${relPath}
Symbol: ${symbol}

Write TWO OR THREE sentences describing what this does, in the plain language a
developer would use when ASKING about it rather than when writing it. Say what
it is for and when it would be used. Prefer ordinary domain words over
identifiers copied from the code. Output only the sentences.

--- CODE ---
${body}
"""
        return callOllama(model, prompt, 160)
    }

    /**
     * @param maxFiles cap on files processed this run (0 = no cap). The cron
     *                 top-up passes a small cap so a periodic refresh cannot
     *                 turn into a multi-hour job holding the project lock.
     */
    static JSONObject run(Connection db, String project, boolean regenerate, int maxFiles) {
        if (!ProjectRegistry.isValidName(project))
            throw new RuntimeException("Invalid project name: ${project}")
        ProjectRegistry.Project proj = ProjectRegistry.get(project)
        if (proj == null)
            throw new RuntimeException("Unknown project '${project}'")
        String model = env("RAGSummaryModel", "qwen2.5-coder:latest")
        long maxBytes = Long.parseLong(env("RAGSummaryMaxFileBytes", "400000"))

        // repo basename -> absolute root, to turn stored relative paths back
        // into readable files. Read from ProjectRegistry (precompiled) rather
        // than the indexer's Config: backend Groovy files load in isolation and
        // cannot reference one another's classes.
        Map<String, File> roots = [:]
        for (String rootPath : proj.roots) {
            File rf = new File(rootPath)
            roots[rf.name] = rf
        }

        String where = regenerate ? "" : " AND (summary_sha IS NULL OR summary_sha <> sha256)"
        String limit = maxFiles > 0 ? " LIMIT ${maxFiles}" : ""
        // Newest-changed first: when capped, the files most likely to be asked
        // about are the ones that just changed.
        List<Record> todo = db.fetchAll(
                ("SELECT file_id, repo, path, sha256, size_bytes FROM ${project}.rag_file " +
                 "WHERE size_bytes <= ${maxBytes} ${where} " +
                 "ORDER BY indexed_at DESC, file_id${limit}").toString())

        long t0 = System.currentTimeMillis()
        int done = 0, skipped = 0, failed = 0
        for (Record f : todo) {
            String repo = f.getString("repo")
            String rel = f.getString("path")
            File root = roots[repo]
            if (root == null) {
                skipped++
                continue
            }
            File src = new File(root, rel)
            if (!src.isFile()) {
                skipped++
                continue
            }
            try {
                String content = readCapped(src)
                if (content.trim().isEmpty()) {
                    skipped++
                    continue
                }
                String summary = generate(model, repo, rel, content)
                if (summary == null || summary.trim().isEmpty()) {
                    failed++
                    continue
                }
                // Embed the summary with the SAME model as the code chunks —
                // both vectors must live in one space to be fused later.
                float[] v = RAGSearch.embedQuery(summary)
                db.execute(
                        ("UPDATE ${project}.rag_file SET summary = ?, summary_model = ?, " +
                         "summary_sha = ?, summary_embedding = ?::vector WHERE file_id = ?").toString(),
                        summary, model, f.getString("sha256"),
                        RAGSearch.vectorToLiteral(v), f.getLong("file_id"))
                done++
                if (done % PROGRESS_EVERY == 0) {
                    db.commit()
                    reportProgress(db, project, done, todo.size(), t0)
                }
            } catch (Exception e) {
                failed++
                logger.warn("RAGSummarizer[${project}] ${repo}/${rel}: ${e.message}")
                try { db.rollback() } catch (Exception ignored) { }
            }
        }
        db.commit()
        clearProgress(db, project)

        long secs = (System.currentTimeMillis() - t0) / 1000L
        JSONObject out = new JSONObject()
        out.put("project", project)
        out.put("candidates", todo.size())
        out.put("summarized", done)
        out.put("skipped", skipped)
        out.put("failed", failed)
        out.put("elapsedSec", secs)
        out.put("model", model)
        logger.info("RAGSummarizer[${project}] done: ${done}/${todo.size()} in ${secs}s " +
                "(skipped=${skipped} failed=${failed})")
        return out
    }

    private static String readCapped(File f) {
        byte[] b = f.bytes
        String s = new String(b, StandardCharsets.UTF_8)
        return s.length() > MAX_SOURCE_CHARS ? s.substring(0, MAX_SOURCE_CHARS) : s
    }

    /**
     * Ask the model for prose about the file. The prompt deliberately steers
     * away from restating identifiers — a summary that just lists method names
     * adds no vocabulary the code did not already contain, and so adds no
     * retrieval value.
     */
    private static String generate(String model, String repo, String relPath, String content) {
        String prompt = """You are documenting a codebase for search.

File: ${repo}/${relPath}

Write ONE paragraph (3-5 sentences) describing what this file does, in plain
language a developer would use when ASKING about it rather than when writing
it. Say what problem it solves, what it is responsible for, and what it works
with. Prefer ordinary domain words over identifiers copied from the code. Do
not list method signatures. Do not use bullet points. Output only the
paragraph.

--- FILE CONTENT ---
${content}
"""
        return callOllama(model, prompt, 300)
    }

    /** One non-streaming Ollama generation. Returns the trimmed response text. */
    private static String callOllama(String model, String prompt, int numPredict) {
        JSONObject body = new JSONObject()
        body.put("model", model)
        body.put("prompt", prompt)
        body.put("stream", false)
        JSONObject opts = new JSONObject()
        opts.put("temperature", 0.1d)
        opts.put("num_predict", numPredict)
        body.put("options", opts)

        String base = env("OllamaURL", "http://127.0.0.1:11434")
        if (base.endsWith("/"))
            base = base.substring(0, base.length() - 1)
        int timeout = Integer.parseInt(env("RAGSummaryTimeoutMs", "120000"))

        HttpURLConnection con = (HttpURLConnection) new URL(base + "/api/generate").openConnection()
        con.setRequestMethod("POST")
        con.setDoOutput(true)
        con.setConnectTimeout(5000)
        con.setReadTimeout(timeout)
        con.setRequestProperty("Content-Type", "application/json")
        con.getOutputStream().withWriter(StandardCharsets.UTF_8.name()) { w -> w.write(body.toString()) }
        if (con.getResponseCode() != 200)
            throw new RuntimeException("Ollama /api/generate returned ${con.getResponseCode()}")
        String resp = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        return new JSONObject(resp).getString("response", "").trim()
    }

    private static void reportProgress(Connection db, String project, int done, int total, long t0) {
        long secs = (System.currentTimeMillis() - t0) / 1000L
        double rate = secs > 0 ? (done / (double) secs) : 0.0d
        long etaSec = rate > 0 ? (long) ((total - done) / rate) : 0L
        JSONObject p = new JSONObject()
        p.put("done", done)
        p.put("total", total)
        p.put("elapsedSec", secs)
        p.put("etaSec", etaSec)
        db.execute(("INSERT INTO ${project}.rag_meta(key, value) VALUES('summarize_progress', ?) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value").toString(), p.toString())
        db.commit()
    }

    private static void clearProgress(Connection db, String project) {
        db.execute("DELETE FROM ${project}.rag_meta WHERE key = 'summarize_progress'".toString())
        db.commit()
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
