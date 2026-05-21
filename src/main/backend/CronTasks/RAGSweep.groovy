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
