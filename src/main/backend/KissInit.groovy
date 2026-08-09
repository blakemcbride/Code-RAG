import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.config.Configurator
import org.kissweb.json.JSONObject
import org.kissweb.database.Connection
import org.kissweb.restServer.GroovyService
import org.kissweb.restServer.MainServlet
import org.kissweb.restServer.UserCache
import org.kissweb.restServer.UserData
import java.util.function.Consumer

class KissInit {

    private static final Logger logger = LogManager.getLogger(KissInit.class)

    /**
     * Configure the system.
     */
    static void init() {

        MainServlet.readIniFile "application.ini", "main"

        // Example of how to specify a method that is allowed without authentication
    //    MainServlet.allowWithoutAuthentication("services.MyGroovyService", "addNumbers")

        // Phase 1 smoke test: RAG admin endpoints are open while we wire up the
        // indexer. Tighten this once Phase 2 (MCP server) is in place.
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "status")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "reindex")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "listProjects")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "reconcile")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "eval")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "history")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "summarize")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "reindexPath")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "defs")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "deps")
        MainServlet.allowWithoutAuthentication("services.RAGAdmin", "usage")

        // The Kiss default root logger is at ERROR — bump our RAG namespaces
        // to INFO so sweep-start/done lines, queued requests, and other
        // progress signals are visible in catalina.out.
        Configurator.setLevel(LogManager.getLogger("services.RAGAdmin"), Level.INFO)
        Configurator.setLevel(LogManager.getLogger("scripts.RAGIndexer"), Level.INFO)
        Configurator.setLevel(LogManager.getLogger("scripts.RAGEval"), Level.INFO)
        Configurator.setLevel(LogManager.getLogger("CronTasks.RAGSweep"), Level.INFO)
        Configurator.setLevel(LogManager.getLogger(KissInit.class), Level.INFO)

        // Set up a global logout handler that runs whenever any user logs out
        // This can be used for cleanup tasks like logging, closing resources, etc.
        UserCache.setLogoutHandler({ UserData ud ->
            // Example: Log the logout event
            println "User ${ud.getUsername()} (ID: ${ud.getUserId()}) is logging out"

            // Add any custom cleanup code here
            // Examples:
            // - Close user-specific resources
            // - Update database logout timestamp
            // - Send notifications
            // - Clean up temporary files
        } as Consumer<UserData>)

    }

    /**
     * Code to run once the database is open but before the app is running.
     */
    static void init2(Connection db) {
        // Multi-project bootstrap:
        //   1. CREATE SCHEMA + tables for every project listed in rag-projects.json
        //   2. Reset reindex_running='false' per project (crash recovery)
        //   3. Report any project whose rag_file is empty — that's "never scanned"
        // Backend Groovy files load in isolation, so we go through
        // GroovyService.run rather than importing scripts.ProjectBootstrap.
        List<String> needsScan = (List<String>) GroovyService.run(
                "scripts", "ProjectBootstrap", "ensureAll", null, db)

        if (needsScan != null && !needsScan.isEmpty()) {
            logger.info("Auto-scan at startup for never-scanned project(s): " + needsScan)
            startAutoScanThread(needsScan)
        }
    }

    /**
     * Spawn a daemon thread that iterates over the given (never-scanned)
     * projects and runs a sweep on each. Uses its own JDBC connection,
     * not a pool one — same pattern as RAGAdmin.reindex's background
     * worker — to avoid pool churn on long-held connections.
     */
    private static void startAutoScanThread(List<String> projects) {
        Thread t = new Thread({ ->
            Connection bgDb = null
            try {
                String dbHost = nz(MainServlet.getEnvironment("DatabaseHost"), "localhost")
                int    dbPort = Integer.parseInt(nz(MainServlet.getEnvironment("DatabasePort"), "5432"))
                String dbName = (String) MainServlet.getEnvironment("DatabaseName")
                String dbUser = nz(MainServlet.getEnvironment("DatabaseUser"), "")
                String dbPw   = nz(MainServlet.getEnvironment("DatabasePassword"), "")
                bgDb = new Connection(Connection.ConnectionType.PostgreSQL,
                                      dbHost, dbPort, dbName, dbUser, dbPw)
                for (String project : projects) {
                    logger.info("auto-scan starting: " + project)
                    try {
                        JSONObject stats = (JSONObject) GroovyService.run(
                                "scripts", "RAGIndexer", "runSweepJson", null, bgDb, project)
                        logger.info("auto-scan finished[" + project + "]: "
                                    + (stats != null ? stats.toString() : "(null)"))
                    } catch (Throwable e) {
                        logger.error("auto-scan failed for " + project, e)
                    }
                }
            } catch (Throwable e) {
                logger.error("auto-scan worker crashed", e)
            } finally {
                if (bgDb != null) {
                    try { bgDb.close() } catch (Throwable ignored) { }
                }
            }
        } as Runnable)
        t.setDaemon(true)
        t.setName("rag-auto-scan-startup")
        t.start()
    }

    private static String nz(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s
    }
}
