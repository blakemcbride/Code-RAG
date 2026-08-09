package CronTasks

import org.kissweb.rag.ProjectRegistry
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.kissweb.database.Connection
import org.kissweb.database.Record
import org.kissweb.json.JSONObject
import org.kissweb.restServer.GroovyService

/**
 * Periodic incremental sweep across all projects.
 *
 * Sequential by design — multiple parallel sweeps would only fight Ollama
 * for the GPU. A failure in one project does not block the others. The
 * per-project lock prevents collision with a manual reindex triggered via
 * services/RAGAdmin.
 */
class RAGSweep {

    private static final Logger logger = LogManager.getLogger(RAGSweep.class)

    static void start(Object obj) {
        Connection db = (Connection) obj
        List<String> projects
        try {
            projects = ProjectRegistry.listNames()
        } catch (Exception e) {
            logger.error("RAGSweep: cannot read rag-projects.json", e)
            return
        }
        for (String project : projects) {
            try {
                if (!tryAcquireLock(db, project)) {
                    logger.info("RAGSweep[${project}] skipped — another sweep is running")
                    continue
                }
                try {
                    JSONObject stats = (JSONObject) GroovyService.run(
                            "scripts", "RAGIndexer", "runSweepJson", null, db, project)
                    logger.info("RAGSweep[${project}] finished: " + (stats != null ? stats.toString() : "(null)"))
                    refreshDefs(db, project, stats)
                    topUpSummaries(db, project)
                } finally {
                    releaseLock(db, project)
                }
            } catch (Exception ex) {
                logger.error("RAGSweep[${project}] failed", ex)
                try { db.rollback() } catch (Exception ignored) {}
                try { releaseLock(db, project) } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Bring a bounded number of stale or missing per-file summaries up to date.
     *
     * Without this the summary retrieval leg silently rots: an ordinary sweep
     * re-chunks a changed file but leaves its summary describing the old
     * content, and a brand-new file gets no summary at all. The newest code —
     * the code most likely to be asked about — would progressively lose the
     * strongest retrieval signal, with nothing anywhere reporting a problem.
     *
     * Two safety properties:
     *  - Capped per run ({@code RAGSummaryBatchPerSweep}), because this holds
     *    the project lock and generation costs about a second per file.
     *  - Only tops up projects that ALREADY have summaries. Summarizing a
     *    project for the first time is a multi-hour GPU job and must stay an
     *    explicit decision ({@code ./bld summarize}), never something a cron
     *    silently starts.
     */
    private static void topUpSummaries(Connection db, String project) {
        try {
            if (!"true".equalsIgnoreCase(env("RAGSummarizeOnSweep", "true")))
                return
            int batch = Integer.parseInt(env("RAGSummaryBatchPerSweep", "50"))
            if (batch <= 0)
                return
            Record adopted = db.fetchOne(
                    "SELECT count(*) AS n FROM ${project}.rag_file WHERE summary IS NOT NULL".toString())
            if (adopted == null || adopted.getLong("n") == 0L)
                return
            Record stale = db.fetchOne(
                    ("SELECT count(*) AS n FROM ${project}.rag_file " +
                     "WHERE summary_sha IS NULL OR summary_sha <> sha256").toString())
            long n = (stale == null) ? 0L : stale.getLong("n")
            if (n == 0L)
                return
            logger.info("RAGSweep[${project}]: ${n} summaries stale/missing, refreshing up to ${batch}")
            JSONObject params = new JSONObject()
            params.put("project", project)
            params.put("regenerate", false)
            params.put("maxFiles", batch)
            JSONObject res = (JSONObject) GroovyService.run(
                    "scripts", "RAGSummarizer", "runJson", null, db, params)
            logger.info("RAGSweep[${project}] summary top-up: " + (res != null ? res.toString() : "(null)"))
        } catch (Exception e) {
            // Never let summary refresh break the sweep — indexing matters more.
            logger.warn("RAGSweep[${project}] summary top-up failed: ${e.message}")
            try { db.rollback() } catch (Exception ignored) { }
        }
    }

    /**
     * Re-extract ctags definitions when the sweep actually changed something.
     *
     * Skipped on a no-op sweep (the common case) because ctags re-parses the
     * whole root; running it only when files moved keeps a 10-minute cadence
     * cheap while never letting find_symbol drift behind the code.
     */
    private static void refreshDefs(Connection db, String project, JSONObject stats) {
        try {
            if (!"true".equalsIgnoreCase(env("RAGDefsOnSweep", "true")))
                return
            if (stats == null)
                return
            int changed = stats.getInt("filesIndexed", 0) + stats.getInt("filesDeleted", 0)
            if (changed == 0)
                return
            JSONObject params = new JSONObject()
            params.put("project", project)
            JSONObject res = (JSONObject) GroovyService.run(
                    "scripts", "RAGDefs", "runJson", null, db, params)
            logger.info("RAGSweep[${project}] defs refresh: " + (res != null ? res.toString() : "(null)"))
        } catch (Exception e) {
            // Definitions are a bonus index; never let them break the sweep.
            logger.warn("RAGSweep[${project}] defs refresh failed: ${e.message}")
            try { db.rollback() } catch (Exception ignored) { }
        }
    }

    private static String env(String key, String dflt) {
        try {
            String v = org.kissweb.restServer.MainServlet.getEnvironment(key)
            return (v != null && !v.isEmpty()) ? v : dflt
        } catch (Exception ignored) {
            return dflt
        }
    }

    private static boolean tryAcquireLock(Connection db, String project) {
        List<Record> rows = db.fetchAll(
                ("UPDATE ${project}.rag_meta SET value = 'true' " +
                 "WHERE key = 'reindex_running' AND value = 'false' " +
                 "RETURNING key").toString())
        db.commit()
        return !rows.isEmpty()
    }

    private static void releaseLock(Connection db, String project) {
        db.execute(
                "UPDATE ${project}.rag_meta SET value = 'false' WHERE key = 'reindex_running'".toString())
        db.commit()
    }
}
