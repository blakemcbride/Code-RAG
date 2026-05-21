import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
import org.kissweb.database.Connection
import org.kissweb.restServer.GroovyService
import org.kissweb.restServer.MainServlet
import org.kissweb.restServer.UserCache
import org.kissweb.restServer.UserData
import java.util.function.Consumer

class KissInit {

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

        // The Kiss default root logger is at ERROR — bump our RAG namespaces
        // to INFO so sweep-start/done lines, queued requests, and other
        // progress signals are visible in catalina.out.
        Configurator.setLevel(LogManager.getLogger("services.RAGAdmin"), Level.INFO)
        Configurator.setLevel(LogManager.getLogger("scripts.RAGIndexer"), Level.INFO)
        Configurator.setLevel(LogManager.getLogger("CronTasks.RAGSweep"), Level.INFO)

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
        // Backend Groovy files load in isolation, so we go through
        // GroovyService.run rather than importing scripts.ProjectBootstrap.
        GroovyService.run("scripts", "ProjectBootstrap", "ensureAll", null, db)
    }
}
