package services

import org.kissweb.rag.ProjectRegistry
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.kissweb.database.Connection
import org.kissweb.database.Record
import org.kissweb.json.JSONArray
import org.kissweb.json.JSONObject
import org.kissweb.restServer.GroovyService
import org.kissweb.restServer.MainServlet
import org.kissweb.restServer.ProcessServlet

/**
 * JSON-RPC service for the local multi-project RAG system.
 *
 *   listProjects()              — names and per-project file/chunk counts
 *   status({project?})          — counts/meta/last sweep; if project omitted, returns one row per project
 *   reindex({project, full?})   — kick off async sweep for one project; returns started=false if a sweep is already running
 *
 * The concurrency guard lives in each project's <project>.rag_meta row
 * (key='reindex_running'), so the gate survives Groovy hot-reload and is
 * naturally per-project: project A and project B can be reindexed in
 * parallel; two reindexes of the same project cannot.
 */
class RAGAdmin {

    private static final Logger logger = LogManager.getLogger(RAGAdmin.class)

    void listProjects(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        JSONArray out = new JSONArray()
        for (ProjectRegistry.Project p : ProjectRegistry.load()) {
            JSONObject row = new JSONObject()
            row.put("name", p.name)
            JSONArray roots = new JSONArray()
            for (String r : p.roots)
                roots.put(r)
            row.put("roots", roots)
            Record fc = db.fetchOne("SELECT count(*) AS n FROM ${p.name}.rag_file".toString())
            Record cc = db.fetchOne("SELECT count(*) AS n FROM ${p.name}.rag_chunk".toString())
            row.put("files", fc.getLong("n"))
            row.put("chunks", cc.getLong("n"))
            out.put(row)
        }
        outjson.put("projects", out)
    }

    void status(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String project = injson.getString("project", null)
        if (project == null || project.isEmpty()) {
            // No project specified — return a list of per-project summaries.
            JSONArray rows = new JSONArray()
            for (ProjectRegistry.Project p : ProjectRegistry.load())
                rows.put(projectStatus(db, p.name))
            outjson.put("projects", rows)
            return
        }
        if (!ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            outjson.put("error", "Unknown project: " + project)
            return
        }
        JSONObject s = projectStatus(db, project)
        for (String k : s.keySet())
            outjson.put(k, s.get(k))
    }

    void reindex(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String project = injson.getString("project", null)
        if (project == null || project.isEmpty()) {
            outjson.put("started", false)
            outjson.put("message", "project is required")
            return
        }
        if (!ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            outjson.put("started", false)
            outjson.put("message", "Unknown project: " + project)
            return
        }
        boolean full = injson.getBoolean("full", Boolean.FALSE)

        if (!tryAcquireLock(db, project)) {
            logger.info("RAGAdmin.reindex[${project}] rejected — a sweep is already running")
            outjson.put("started", false)
            outjson.put("project", project)
            outjson.put("message", "A reindex is already in progress for '${project}'; poll services/RAGAdmin.status")
            return
        }
        logger.info("RAGAdmin.reindex[${project}] queued (full=${full}); spawning background worker")

        Thread t = new Thread({ ->
            Connection bgDb = null
            try {
                String dbHost = nz(MainServlet.getEnvironment("DatabaseHost"), "localhost")
                int    dbPort = Integer.parseInt(nz(MainServlet.getEnvironment("DatabasePort"), "5432"))
                String dbName = (String) MainServlet.getEnvironment("DatabaseName")
                String dbUser = nz(MainServlet.getEnvironment("DatabaseUser"), "")
                String dbPw   = nz(MainServlet.getEnvironment("DatabasePassword"), "")
                bgDb = new Connection(Connection.ConnectionType.PostgreSQL, dbHost, dbPort, dbName, dbUser, dbPw)

                JSONObject stats = (JSONObject) GroovyService.run(
                        "scripts", "RAGIndexer",
                        full ? "runFullRebuildJson" : "runSweepJson", null, bgDb, project)
                bgDb.commit()
                logger.info("RAG background reindex[${project}] finished: " + stats.toString())
            } catch (Throwable e) {
                logger.error("RAG background reindex[${project}] failed", e)
                try { bgDb?.rollback() } catch (Exception ignored) {}
            } finally {
                try { releaseLock(bgDb, project) } catch (Exception ignored) {}
                try { bgDb?.close() } catch (Exception ignored) {}
            }
        } as Runnable, "RAGReindex-" + project)
        t.setDaemon(true)
        t.start()

        outjson.put("started", true)
        outjson.put("project", project)
        outjson.put("message", "Reindex started in background; poll services/RAGAdmin.status with project=${project}")
    }

    // ---- helpers ----

    private static JSONObject projectStatus(Connection db, String project) {
        Record fileCount  = db.fetchOne("SELECT count(*) AS n FROM ${project}.rag_file".toString())
        Record chunkCount = db.fetchOne("SELECT count(*) AS n FROM ${project}.rag_chunk".toString())
        Record lastSweep  = db.fetchOne("SELECT max(indexed_at) AS t FROM ${project}.rag_file".toString())

        JSONArray repos = new JSONArray()
        for (Record r : db.fetchAll(
                ("SELECT repo, count(*) AS files, sum(size_bytes) AS bytes " +
                 "FROM ${project}.rag_file GROUP BY repo ORDER BY repo").toString())) {
            JSONObject row = new JSONObject()
            row.put("repo",  r.getString("repo"))
            row.put("files", r.getLong("files"))
            row.put("bytes", r.getLong("bytes"))
            repos.put(row)
        }

        JSONObject meta = new JSONObject()
        JSONObject lastSweepStats = null
        boolean indexing = false
        for (Record r : db.fetchAll(
                "SELECT key, value FROM ${project}.rag_meta ORDER BY key".toString())) {
            String key = r.getString("key")
            String val = r.getString("value")
            if (key == "reindex_running") {
                indexing = "true".equalsIgnoreCase(val)
                continue
            }
            if (key == "last_sweep") {
                try { lastSweepStats = new JSONObject(val) }
                catch (Exception ignored) { meta.put(key, val) }
                continue
            }
            meta.put(key, val)
        }

        JSONObject out = new JSONObject()
        out.put("project", project)
        out.put("fileCount",  fileCount.getLong("n"))
        out.put("chunkCount", chunkCount.getLong("n"))
        out.put("lastIndexedAt", lastSweep.getDateTime("t")?.toString())
        out.put("indexing", indexing)
        if (lastSweepStats != null)
            out.put("lastSweep", lastSweepStats)
        out.put("repos", repos)
        out.put("meta",  meta)
        return out
    }

    /** Atomic compare-and-set on this project's lock row. */
    private static boolean tryAcquireLock(Connection db, String project) {
        List<Record> rows = db.fetchAll(
                ("UPDATE ${project}.rag_meta SET value = 'true' " +
                 "WHERE key = 'reindex_running' AND value = 'false' " +
                 "RETURNING key").toString())
        db.commit()
        return !rows.isEmpty()
    }

    private static void releaseLock(Connection db, String project) {
        if (db == null)
            return
        db.execute(
                "UPDATE ${project}.rag_meta SET value = 'false' WHERE key = 'reindex_running'".toString())
        db.commit()
    }

    private static String nz(String s, String dflt) {
        return (s != null && !s.isEmpty()) ? s : dflt
    }
}
