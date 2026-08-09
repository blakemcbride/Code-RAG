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

    /**
     * Run the retrieval-quality eval for one project against a golden query
     * set and return the scored report.
     *
     * Input:  { "project": "<name>", "queryFile": "<absolute path to .jsonl>" }
     * Output: the full report from scripts/RAGEval (overall / bySplit / byTag /
     *         perQuery), or { "error": "..." }.
     *
     * Synchronous — the caller (./bld eval) waits. A 50-query set costs one
     * embedding round trip each, so expect single-digit seconds.
     */
    void eval(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String project = injson.getString("project", null)
        String queryFile = injson.getString("queryFile", null)
        if (project == null || project.isEmpty()) {
            outjson.put("error", "project is required")
            return
        }
        if (!ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            outjson.put("error", "Unknown project: " + project)
            return
        }
        if (queryFile == null || queryFile.isEmpty()) {
            outjson.put("error", "queryFile is required")
            return
        }
        JSONObject params = new JSONObject()
        params.put("project", project)
        params.put("queryFile", queryFile)
        params.put("granularity", injson.getString("granularity", "chunk"))
        JSONObject res
        try {
            res = (JSONObject) GroovyService.run("scripts", "RAGEval", "runJson", null, db, params)
        } catch (Exception e) {
            logger.error("RAGAdmin.eval[${project}] failed", e)
            outjson.put("error", e.message ?: e.toString())
            return
        }
        for (String k : res.keySet())
            outjson.put(k, res.get(k))
    }

    /**
     * Report how the tool is actually being used.
     *
     * Retrieval scores say the tool CAN find things; this says whether anything
     * is asking it to. That distinction matters most right after the routing
     * rules were added — if searches per day stays at zero, no amount of
     * ranking work is reaching Claude Code.
     *
     * Input:  { "project": "<name>", "days": <int, default 14> }
     */
    void usage(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String project = injson.getString("project", null)
        if (project == null || !ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            outjson.put("error", "Unknown or missing project: " + project)
            return
        }
        int days = injson.getInt("days", 14)
        try {
            Record tot = db.fetchOne(
                    ("SELECT count(*) FILTER (WHERE kind='search') AS searches, " +
                     "       count(*) FILTER (WHERE kind='fetch')  AS fetches, " +
                     "       count(*) FILTER (WHERE kind='search' AND coalesce(paths,'')='') AS empties, " +
                     "       coalesce(round(avg(latency_ms) FILTER (WHERE kind='search')),0) AS avg_ms " +
                     "  FROM ${project}.rag_query_log").toString())
            outjson.put("project", project)
            outjson.put("searches", tot.getLong("searches"))
            outjson.put("fetches", tot.getLong("fetches"))
            outjson.put("zeroResultSearches", tot.getLong("empties"))
            outjson.put("avgLatencyMs", tot.getLong("avg_ms"))

            JSONArray daily = new JSONArray()
            for (Record r : db.fetchAll(
                    ("SELECT to_char(at::date,'YYYY-MM-DD') AS d, " +
                     "       count(*) FILTER (WHERE kind='search') AS s, " +
                     "       count(*) FILTER (WHERE kind='fetch') AS f " +
                     "  FROM ${project}.rag_query_log " +
                     " WHERE at > now() - interval '${days} days' " +
                     " GROUP BY 1 ORDER BY 1 DESC").toString())) {
                JSONObject o = new JSONObject()
                o.put("date", r.getString("d"))
                o.put("searches", r.getLong("s"))
                o.put("fetches", r.getLong("f"))
                daily.put(o)
            }
            outjson.put("daily", daily)

            JSONArray top = new JSONArray()
            for (Record r : db.fetchAll(
                    ("SELECT query, count(*) AS n FROM ${project}.rag_query_log " +
                     " WHERE kind='search' AND query IS NOT NULL " +
                     " GROUP BY query ORDER BY n DESC, query LIMIT 10").toString())) {
                JSONObject o = new JSONObject()
                o.put("query", r.getString("query"))
                o.put("count", r.getLong("n"))
                top.put(o)
            }
            outjson.put("topQueries", top)
        } catch (Exception e) {
            logger.error("RAGAdmin.usage[${project}] failed", e)
            outjson.put("error", e.message ?: e.toString())
        }
    }

    /**
     * Backfill the import graph for files indexed before it existed.
     *
     * Input:  { "project": "<name>" }
     */
    void deps(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String project = injson.getString("project", null)
        if (project == null || !ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            outjson.put("error", "Unknown or missing project: " + project)
            return
        }
        JSONObject params = new JSONObject()
        params.put("project", project)
        try {
            JSONObject res = (JSONObject) GroovyService.run(
                    "scripts", "RAGIndexer", "backfillDepsJson", null, db, params)
            for (String k : res.keySet())
                outjson.put(k, res.get(k))
        } catch (Exception e) {
            logger.error("RAGAdmin.deps[${project}] failed", e)
            outjson.put("error", e.message ?: e.toString())
        }
    }

    /**
     * Extract symbol definitions with ctags into rag_def.
     *
     * Input:  { "project": "<name>" }
     */
    void defs(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String project = injson.getString("project", null)
        if (project == null || !ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            outjson.put("error", "Unknown or missing project: " + project)
            return
        }
        JSONObject params = new JSONObject()
        params.put("project", project)
        try {
            JSONObject res = (JSONObject) GroovyService.run("scripts", "RAGDefs", "runJson", null, db, params)
            for (String k : res.keySet())
                outjson.put(k, res.get(k))
        } catch (Exception e) {
            logger.error("RAGAdmin.defs[${project}] failed", e)
            outjson.put("error", e.message ?: e.toString())
        }
    }

    /**
     * Re-index specific files immediately (synchronous, small).
     *
     * Input:  { "project": "<name>", "paths": ["...", ...] }
     */
    void reindexPath(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String project = injson.getString("project", null)
        if (project == null || !ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            outjson.put("error", "Unknown or missing project: " + project)
            return
        }
        if (!injson.has("paths")) {
            outjson.put("error", "paths is required")
            return
        }
        JSONObject params = new JSONObject()
        params.put("project", project)
        params.put("paths", injson.getJSONArray("paths"))
        try {
            JSONObject res = (JSONObject) GroovyService.run(
                    "scripts", "RAGIndexer", "reindexPathsJson", null, db, params)
            for (String k : res.keySet())
                outjson.put(k, res.get(k))
        } catch (Exception e) {
            logger.error("RAGAdmin.reindexPath[${project}] failed", e)
            outjson.put("error", e.message ?: e.toString())
        }
    }

    /**
     * Index VCS commit history for one project (git and Subversion roots).
     * Synchronous; incremental after the first run.
     *
     * Input:  { "project": "<name>", "maxCommits": <int, optional> }
     */
    void history(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String project = injson.getString("project", null)
        if (project == null || !ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            outjson.put("started", false)
            outjson.put("message", "Unknown or missing project: " + project)
            return
        }
        // Async, like reindex and summarize. A first import can be tens of
        // thousands of commits across several roots; run synchronously it
        // outlives the HTTP request and Tomcat recycles the response out from
        // under it ("response object has been recycled").
        if (!tryAcquireLock(db, project)) {
            outjson.put("started", false)
            outjson.put("message", "A sweep, summarize or history run is already active for '${project}'")
            return
        }
        int maxCommits = injson.getInt("maxCommits", 0)
        logger.info("RAGAdmin.history[${project}] queued")
        Thread t = new Thread({ ->
            Connection bgDb = null
            try {
                bgDb = backgroundConnection()
                JSONObject params = new JSONObject()
                params.put("project", project)
                params.put("maxCommits", maxCommits)
                JSONObject res = (JSONObject) GroovyService.run(
                        "scripts", "RAGHistory", "runJson", null, bgDb, params)
                bgDb.commit()
                logger.info("RAGAdmin.history[${project}] finished: " + res.toString())
            } catch (Throwable e) {
                logger.error("RAGAdmin.history[${project}] failed", e)
                try { bgDb?.rollback() } catch (Exception ignored) {}
            } finally {
                try { releaseLock(bgDb, project) } catch (Exception ignored) {}
                try { bgDb?.close() } catch (Exception ignored) {}
            }
        } as Runnable, "RAGHistory-" + project)
        t.setDaemon(true)
        t.start()
        outjson.put("started", true)
        outjson.put("project", project)
        outjson.put("message", "History indexing started; poll services/RAGAdmin.status")
    }

    /** A dedicated (non-pooled) connection for a long-running background job. */
    private static Connection backgroundConnection() {
        String dbHost = nz(MainServlet.getEnvironment("DatabaseHost"), "localhost")
        int    dbPort = Integer.parseInt(nz(MainServlet.getEnvironment("DatabasePort"), "5432"))
        String dbName = (String) MainServlet.getEnvironment("DatabaseName")
        String dbUser = nz(MainServlet.getEnvironment("DatabaseUser"), "")
        String dbPw   = nz(MainServlet.getEnvironment("DatabasePassword"), "")
        return new Connection(Connection.ConnectionType.PostgreSQL, dbHost, dbPort, dbName, dbUser, dbPw)
    }

    /**
     * Generate per-file LLM summaries for one project. Long-running (roughly a
     * second per file), so it runs on a background thread behind the same
     * per-project lock as indexing and is polled via status().
     *
     * Input:  { "project": "<name>", "regenerate": <bool, optional> }
     */
    void summarize(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        String project = injson.getString("project", null)
        if (project == null || !ProjectRegistry.isValidName(project) || ProjectRegistry.get(project) == null) {
            outjson.put("started", false)
            outjson.put("message", "Unknown or missing project: " + project)
            return
        }
        boolean regenerate = injson.getBoolean("regenerate", Boolean.FALSE)
        boolean symbols = injson.getBoolean("symbols", Boolean.FALSE)
        if (!tryAcquireLock(db, project)) {
            outjson.put("started", false)
            outjson.put("message", "A sweep or summarize is already running for '${project}'")
            return
        }
        logger.info("RAGAdmin.summarize[${project}] queued (regenerate=${regenerate})")
        Thread t = new Thread({ ->
            Connection bgDb = null
            try {
                String dbHost = nz(MainServlet.getEnvironment("DatabaseHost"), "localhost")
                int    dbPort = Integer.parseInt(nz(MainServlet.getEnvironment("DatabasePort"), "5432"))
                String dbName = (String) MainServlet.getEnvironment("DatabaseName")
                String dbUser = nz(MainServlet.getEnvironment("DatabaseUser"), "")
                String dbPw   = nz(MainServlet.getEnvironment("DatabasePassword"), "")
                bgDb = new Connection(Connection.ConnectionType.PostgreSQL, dbHost, dbPort, dbName, dbUser, dbPw)
                JSONObject params = new JSONObject()
                params.put("project", project)
                params.put("regenerate", regenerate)
                params.put("symbols", symbols)
                JSONObject stats = (JSONObject) GroovyService.run(
                        "scripts", "RAGSummarizer", "runJson", null, bgDb, params)
                bgDb.commit()
                logger.info("RAGAdmin.summarize[${project}] finished: " + stats.toString())
            } catch (Throwable e) {
                logger.error("RAGAdmin.summarize[${project}] failed", e)
                try { bgDb?.rollback() } catch (Exception ignored) {}
            } finally {
                try { releaseLock(bgDb, project) } catch (Exception ignored) {}
                try { bgDb?.close() } catch (Exception ignored) {}
            }
        } as Runnable, "RAGSummarize-" + project)
        t.setDaemon(true)
        t.start()
        outjson.put("started", true)
        outjson.put("project", project)
        outjson.put("message", "Summarization started; poll services/RAGAdmin.status")
    }

    /**
     * Reconcile DB state with rag-projects.json:
     *   • drop schemas for projects no longer in the file (CASCADE)
     *   • create schemas + tables for new projects
     *   • delete rag_file rows whose `repo` (root basename) is no longer
     *     a configured root for that project
     *   • detect newly-configured roots on existing projects so the caller
     *     can include those projects in its scan target list (the sweep
     *     itself handles the actual indexing — no DB change here)
     *
     * Schemas are identified as "Code-RAG project schemas" by the presence
     * of a rag_meta table (the bootstrap marker). Other schemas are never
     * touched.
     *
     * Per-project lock (rag_meta.reindex_running) is acquired before any
     * destructive mutation; a project currently being indexed lands in
     * `blocked` and is skipped without harm.
     *
     * Two modes:
     *   dryRun=true   — report the plan without making changes (default)
     *   dryRun=false  — execute the plan
     *
     * Response (both modes):
     *   {
     *     "executed":         <bool>,
     *     "requiresConfirm":  <bool>,            // true iff plan has drops or root deletes
     *     "createsNames":     ["new1",...],      // newly-bootstrapped projects
     *     "rootAddsProjects": ["proj1",...],     // projects that gained roots
     *     "humanPlan":        "  Drop:\n    ..."
     *   }
     * The humanPlan string is empty when there is nothing to do.
     */
    void reconcile(JSONObject injson, JSONObject outjson, Connection db, ProcessServlet servlet) {
        boolean dryRun = injson.getBoolean("dryRun", Boolean.TRUE)

        List<ProjectRegistry.Project> configured
        try {
            configured = ProjectRegistry.load()
        } catch (Exception e) {
            outjson.put("error", "rag-projects.json: " + e.getMessage())
            return
        }
        Map<String, ProjectRegistry.Project> byName = [:]
        for (ProjectRegistry.Project p : configured)
            byName[p.name] = p

        // Existing project schemas: any schema with a rag_meta table.
        Set<String> existingProjects = new TreeSet<>()
        for (Record r : db.fetchAll(
                "SELECT table_schema FROM information_schema.tables " +
                "WHERE table_name = 'rag_meta' AND table_type = 'BASE TABLE'")) {
            String s = r.getString("table_schema")
            if (ProjectRegistry.isValidName(s))
                existingProjects.add(s)
        }

        // Plan:
        //   drops      = existing - configured
        //   creates    = configured - existing
        //   rootDels   = for each surviving project, repos in DB not in config
        //   rootAdds   = for each surviving project, configured roots whose
        //                basename is not in DB
        List<String> drops = new ArrayList<>()
        for (String s : existingProjects)
            if (!byName.containsKey(s))
                drops.add(s)

        List<String> creates = new ArrayList<>()
        for (ProjectRegistry.Project p : configured)
            if (!existingProjects.contains(p.name))
                creates.add(p.name)

        // rootDels: project -> list of repo basenames to delete
        // rootAdds: project -> list of root paths (full path, for display)
        LinkedHashMap<String, List<String>> rootDels = new LinkedHashMap<>()
        LinkedHashMap<String, Long> rootDelCounts = new LinkedHashMap<>()
        LinkedHashMap<String, List<String>> rootAdds = new LinkedHashMap<>()
        for (String s : existingProjects) {
            if (!byName.containsKey(s))
                continue
            // configuredRepos: basename -> full path (last one wins on dup basename)
            LinkedHashMap<String, String> configuredRepos = new LinkedHashMap<>()
            for (String rootPath : byName[s].roots)
                configuredRepos[new File(rootPath).name] = rootPath
            Set<String> dbRepos = new HashSet<>()
            for (Record r : db.fetchAll(
                    "SELECT repo, count(*) AS n FROM ${s}.rag_file GROUP BY repo ORDER BY repo".toString())) {
                String repo = r.getString("repo")
                dbRepos.add(repo)
                if (!configuredRepos.containsKey(repo)) {
                    rootDels.computeIfAbsent(s, { k -> new ArrayList<String>() }).add(repo)
                    rootDelCounts["${s}/${repo}".toString()] = r.getLong("n")
                }
            }
            for (Map.Entry<String, String> e : configuredRepos.entrySet())
                if (!dbRepos.contains(e.key))
                    rootAdds.computeIfAbsent(s, { k -> new ArrayList<String>() }).add(e.value)
        }

        // File counts for drop targets (display only).
        LinkedHashMap<String, Long> dropCounts = new LinkedHashMap<>()
        for (String s : drops) {
            Record r = db.fetchOne("SELECT count(*) AS n FROM ${s}.rag_file".toString())
            dropCounts[s] = (r != null) ? r.getLong("n") : 0L
        }

        boolean anyChanges = !drops.isEmpty() || !creates.isEmpty()
                          || !rootDels.isEmpty() || !rootAdds.isEmpty()
        // Only DROP SCHEMA needs a confirmation prompt. Root-deletes are not
        // prompted because the regular cron sweep already deletes-on-disappearance
        // — declining here only buys one cron tick before the data is gone anyway.
        // The cron never drops schemas, so drops remain the one path where the
        // prompt actually protects data from a JSON typo.
        boolean requiresConfirm = !drops.isEmpty()

        // ---- DRY RUN ----
        if (dryRun || !anyChanges) {
            outjson.put("executed", false)
            outjson.put("requiresConfirm", requiresConfirm)
            JSONArray ca = new JSONArray()
            for (String s : creates)
                ca.put(s)
            outjson.put("createsNames", ca)
            JSONArray ra = new JSONArray()
            for (String s : rootAdds.keySet())
                ra.put(s)
            outjson.put("rootAddsProjects", ra)
            outjson.put("humanPlan",
                    formatPlan(drops, dropCounts, creates, byName, rootDels, rootDelCounts, rootAdds))
            return
        }

        // ---- EXECUTE ----
        List<String> blocked = new ArrayList<>()
        List<String> dropped = new ArrayList<>()
        List<String> created = new ArrayList<>()
        LinkedHashMap<String, List<String>> deletedRoots = new LinkedHashMap<>()

        // 1. Drops. Acquire the lock first so a sweep can't start mid-drop.
        for (String s : drops) {
            if (!tryAcquireLock(db, s)) {
                blocked.add(s + " (drop)")
                logger.warn("reconcile: cannot drop '${s}' — sweep in progress")
                continue
            }
            try {
                db.execute("DROP SCHEMA ${s} CASCADE".toString())
                db.commit()
                dropped.add(s)
                logger.info("reconcile: dropped schema '${s}'")
            } catch (Exception e) {
                logger.error("reconcile: drop '${s}' failed", e)
                try { db.rollback() } catch (Exception ignored) {}
                try { releaseLock(db, s) } catch (Exception ignored) {}
                blocked.add(s + " (drop failed: " + e.message + ")")
            }
        }

        // 2. Creates. No lock needed — schema does not exist yet.
        for (String s : creates) {
            try {
                GroovyService.run("scripts", "ProjectBootstrap", "ensureOne", null, db, s)
                created.add(s)
                logger.info("reconcile: created schema '${s}'")
            } catch (Exception e) {
                logger.error("reconcile: create '${s}' failed", e)
                try { db.rollback() } catch (Exception ignored) {}
                blocked.add(s + " (create failed: " + e.message + ")")
            }
        }

        // 3. Root deletions for surviving projects. Acquire the per-project
        //    lock so we don't race with a running sweep.
        for (Map.Entry<String, List<String>> e : rootDels.entrySet()) {
            String project = e.key
            List<String> repos = e.value
            if (!tryAcquireLock(db, project)) {
                blocked.add(project + " (root-delete)")
                logger.warn("reconcile: cannot delete roots from '${project}' — sweep in progress")
                continue
            }
            try {
                List<String> doneRepos = new ArrayList<>()
                for (String repo : repos) {
                    db.execute(
                            "DELETE FROM ${project}.rag_file WHERE repo = ?".toString(),
                            repo)
                    doneRepos.add(repo)
                    logger.info("reconcile: deleted root '${repo}' from project '${project}'")
                }
                db.commit()
                deletedRoots[project] = doneRepos
            } catch (Exception ex) {
                logger.error("reconcile: root-delete on '${project}' failed", ex)
                try { db.rollback() } catch (Exception ignored) {}
            } finally {
                try { releaseLock(db, project) } catch (Exception ignored) {}
            }
        }

        outjson.put("executed", true)
        outjson.put("requiresConfirm", requiresConfirm)
        JSONArray ca = new JSONArray()
        for (String s : created)
            ca.put(s)
        outjson.put("createsNames", ca)
        JSONArray ra = new JSONArray()
        for (String s : rootAdds.keySet())
            ra.put(s)
        outjson.put("rootAddsProjects", ra)
        outjson.put("humanPlan",
                formatExecutionSummary(dropped, created, deletedRoots, rootAdds, blocked))
    }

    /** Multi-line plan string for the user to read before confirming. */
    private static String formatPlan(List<String> drops,
                                     Map<String, Long> dropCounts,
                                     List<String> creates,
                                     Map<String, ProjectRegistry.Project> byName,
                                     Map<String, List<String>> rootDels,
                                     Map<String, Long> rootDelCounts,
                                     Map<String, List<String>> rootAdds) {
        if (drops.isEmpty() && creates.isEmpty() && rootDels.isEmpty() && rootAdds.isEmpty())
            return ""
        StringBuilder b = new StringBuilder()
        if (!drops.isEmpty()) {
            b.append("  Drop schemas (CASCADE — destroys all indexed data):\n")
            for (String s : drops)
                b.append("    - ").append(s).append("  (").append(dropCounts.get(s) ?: 0L).append(" indexed files)\n")
        }
        if (!creates.isEmpty()) {
            b.append("  Create schemas (will be scanned after reconciliation):\n")
            for (String s : creates) {
                b.append("    - ").append(s).append("\n")
                ProjectRegistry.Project p = byName.get(s)
                if (p != null)
                    for (String root : p.roots)
                        b.append("        root: ").append(root).append("\n")
            }
        }
        if (!rootDels.isEmpty()) {
            b.append("  Delete indexed files under roots no longer configured:\n")
            for (Map.Entry<String, List<String>> e : rootDels.entrySet())
                for (String repo : e.value) {
                    long n = rootDelCounts.get(e.key + "/" + repo) ?: 0L
                    b.append("    - ").append(e.key).append(" / ").append(repo)
                            .append("  (").append(n).append(" files)\n")
                }
        }
        if (!rootAdds.isEmpty()) {
            b.append("  New roots to scan on existing projects:\n")
            for (Map.Entry<String, List<String>> e : rootAdds.entrySet())
                for (String path : e.value)
                    b.append("    - ").append(e.key).append(" : ").append(path).append("\n")
        }
        return b.toString()
    }

    /** Multi-line summary of what was actually done (returned after execute). */
    private static String formatExecutionSummary(List<String> dropped,
                                                 List<String> created,
                                                 Map<String, List<String>> deletedRoots,
                                                 Map<String, List<String>> rootAdds,
                                                 List<String> blocked) {
        StringBuilder b = new StringBuilder()
        if (!dropped.isEmpty())
            b.append("  Dropped: ").append(dropped).append("\n")
        if (!created.isEmpty())
            b.append("  Created: ").append(created).append("\n")
        if (!deletedRoots.isEmpty()) {
            b.append("  Root deletions:\n")
            for (Map.Entry<String, List<String>> e : deletedRoots.entrySet())
                b.append("    ").append(e.key).append(": ").append(e.value).append("\n")
        }
        if (rootAdds != null && !rootAdds.isEmpty()) {
            b.append("  New roots to index:\n")
            for (Map.Entry<String, List<String>> e : rootAdds.entrySet())
                for (String path : e.value)
                    b.append("    ").append(e.key).append(" : ").append(path).append("\n")
        }
        if (blocked != null && !blocked.isEmpty()) {
            b.append("  Skipped (sweep in progress or error):\n")
            for (String s : blocked)
                b.append("    - ").append(s).append("\n")
        }
        return b.toString()
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
