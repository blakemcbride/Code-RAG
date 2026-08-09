package scripts

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.kissweb.database.Connection
import org.kissweb.database.Record
import org.kissweb.json.JSONObject
import org.kissweb.rag.ProjectRegistry
import org.kissweb.restServer.MainServlet

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Extracts symbol definitions with Universal Ctags into {@code rag_def}.
 *
 * <h2>Why this exists alongside the embeddings</h2>
 * Semantic search answers "what code is about X". It cannot answer "what calls
 * this", "what implements this interface", or "what breaks if I change this
 * signature" — those are exact structural relations, and similarity is the
 * wrong instrument for them. They are also what an agent editing code needs
 * most often. This is the structural half of the index.
 *
 * <h2>Why not reuse rag_chunk.symbol</h2>
 * The chunker finds symbols with per-language regexes, which miss common
 * declaration shapes. {@code public List<Edge> findJoinPath(...)} is not
 * matched, so that chunk is attributed to the previous method and the
 * definition is unfindable. ctags has real parsers for ~40 languages.
 *
 * Cheap enough to run on every sweep that touched files: one ctags process per
 * root, a few seconds even on a 12k-file tree.
 */
class RAGDefs {

    private static final Logger logger = LogManager.getLogger(RAGDefs.class)

    /**
     * Extensions ctags has no parser for, mapped onto a close relative.
     *
     * Universal Ctags ships no Groovy parser, which would silently leave every
     * Groovy file without definitions — a large hole here, since Kiss backend
     * services and Ownsona are Groovy. Groovy's declaration syntax is close
     * enough to Java that the Java parser finds classes and methods correctly.
     */
    private static final List<String> LANGMAPS = ["Java:+.groovy"]

    /** Directories never worth parsing; mirrors the indexer's base excludes. */
    private static final List<String> EXCLUDES = [
            ".git", "node_modules", "target", "build", "work", "dist", "out",
            "tomcat", "coverage", ".idea", ".vscode", "jsdoc"
    ]

    /** JSON-RPC entry point. params: { project } */
    static JSONObject runJson(Connection db, JSONObject params) {
        return run(db, params.getString("project", null))
    }

    static JSONObject run(Connection db, String project) {
        if (!ProjectRegistry.isValidName(project))
            throw new RuntimeException("Invalid project name: ${project}")
        ProjectRegistry.Project proj = ProjectRegistry.get(project)
        if (proj == null)
            throw new RuntimeException("Unknown project '${project}'")
        if (!ctagsAvailable()) {
            JSONObject out = new JSONObject()
            out.put("project", project)
            out.put("error", "ctags not found on PATH — install universal-ctags")
            return out
        }

        long t0 = System.currentTimeMillis()
        int totalDefs = 0, rootsDone = 0
        List<String> notes = []

        for (String rootPath : proj.roots) {
            File root = new File(rootPath)
            if (!root.isDirectory()) {
                notes.add("${root.name}: missing")
                continue
            }
            // (repo, relative path) -> file_id, so ctags output can be joined to
            // the files we actually indexed. Anything ctags finds that is not in
            // rag_file (excluded, binary, too large) is simply dropped.
            Map<String, Long> fileIds = [:]
            for (Record r : db.fetchAll(
                    "SELECT file_id, path FROM ${project}.rag_file WHERE repo = ?".toString(), root.name))
                fileIds[r.getString("path")] = r.getLong("file_id")
            if (fileIds.isEmpty()) {
                notes.add("${root.name}: no indexed files")
                continue
            }

            try {
                int n = ingestRoot(db, project, root, fileIds)
                totalDefs += n
                rootsDone++
                notes.add("${root.name}: ${n}")
            } catch (Exception e) {
                notes.add("${root.name}: FAILED ${e.message}")
                logger.warn("RAGDefs[${project}/${root.name}] failed", e)
                try { db.rollback() } catch (Exception ignored) { }
            }
        }
        db.commit()

        JSONObject out = new JSONObject()
        out.put("project", project)
        out.put("definitions", totalDefs)
        out.put("roots", rootsDone)
        out.put("elapsedSec", (System.currentTimeMillis() - t0) / 1000L)
        out.put("detail", notes.join("; "))
        logger.info("RAGDefs[${project}]: ${totalDefs} definitions across ${rootsDone} root(s)")
        return out
    }

    /**
     * Re-extract definitions for a handful of specific files.
     *
     * Called by reindex_path: without it an agent could add a function, refresh
     * the chunk index, and still not find the new symbol via find_symbol —
     * the two halves of the index would disagree.
     *
     * params: { project, files: [ {fileId, repo, absPath} ] } is inconvenient
     * across the Groovy boundary, so the caller passes parallel arrays.
     */
    static JSONObject ingestFilesJson(Connection db, JSONObject params) {
        String project = params.getString("project", null)
        org.kissweb.json.JSONArray ids = params.getJSONArray("fileIds")
        org.kissweb.json.JSONArray abs = params.getJSONArray("absPaths")
        List<Long> fileIds = []
        List<String> absPaths = []
        for (int i = 0; i < ids.length(); i++) {
            fileIds.add(ids.getLong(i))
            absPaths.add(abs.getString(i))
        }
        JSONObject out = new JSONObject()
        out.put("definitions", ingestFiles(db, project, fileIds, absPaths))
        return out
    }

    static int ingestFiles(Connection db, String project, List<Long> fileIds,
                           List<String> absPaths) {
        if (!ctagsAvailable() || fileIds.isEmpty())
            return 0
        int n = 0
        for (int i = 0; i < fileIds.size(); i++) {
            Long fid = fileIds[i]
            File f = new File(absPaths[i])
            if (!f.isFile())
                continue
            db.execute("DELETE FROM ${project}.rag_def WHERE file_id = ?".toString(), fid)
            List<String> cmd = ["ctags", "--output-format=json", "--fields=+nKzS",
                                "--quiet=yes"]
            for (String m : LANGMAPS)
                cmd.add("--langmap=" + m)
            cmd.addAll(["-f", "-", f.absolutePath])
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start()
            try {
                p.getInputStream().withReader(StandardCharsets.UTF_8.name()) { r ->
                    String line
                    while ((line = r.readLine()) != null) {
                        if (line.isEmpty() || line.charAt(0) != ('{' as char))
                            continue
                        JSONObject t
                        try { t = new JSONObject(line) } catch (Exception ignored) { continue }
                        if (t.getString("_type", "") != "tag")
                            continue
                        String name = t.getString("name", null)
                        int lineNo = t.getInt("line", 0)
                        if (name == null || name.isEmpty() || lineNo <= 0)
                            continue
                        db.execute(
                                ("INSERT INTO ${project}.rag_def " +
                                 "(file_id, name, name_lower, kind, scope, signature, line) " +
                                 "VALUES (?,?,?,?,?,?,?) ON CONFLICT (file_id, name, line) DO NOTHING").toString(),
                                fid, name, name.toLowerCase(),
                                t.getString("kind", null), t.getString("scope", null),
                                t.getString("signature", null), lineNo)
                        n++
                    }
                }
                p.waitFor(60, TimeUnit.SECONDS)
            } finally {
                try { p.destroy() } catch (Exception ignored) { }
            }
        }
        db.commit()
        return n
    }

    private static int ingestRoot(Connection db, String project, File root, Map<String, Long> fileIds) {
        List<String> cmd = ["ctags", "--output-format=json", "--fields=+nKzS",
                            "-R", "--quiet=yes", "-f", "-"]
        for (String m : LANGMAPS)
            cmd.add("--langmap=" + m)
        for (String x : EXCLUDES)
            cmd.add("--exclude=" + x)
        cmd.add(".")

        // Replace this root's definitions wholesale: simpler and safer than
        // diffing, and ctags over one root is seconds.
        db.execute(("DELETE FROM ${project}.rag_def d USING ${project}.rag_file f " +
                    "WHERE d.file_id = f.file_id AND f.repo = ?").toString(), root.name)

        int n = 0
        Process p = new ProcessBuilder(cmd).directory(root).redirectErrorStream(false).start()
        try {
            p.getInputStream().withReader(StandardCharsets.UTF_8.name()) { r ->
                String line
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) != ('{' as char))
                        continue
                    JSONObject t
                    try {
                        t = new JSONObject(line)
                    } catch (Exception ignored) {
                        continue
                    }
                    if (t.getString("_type", "") != "tag")
                        continue
                    String rel = t.getString("path", null)
                    if (rel == null)
                        continue
                    if (rel.startsWith("./"))
                        rel = rel.substring(2)
                    Long fid = fileIds[rel]
                    if (fid == null)
                        continue
                    String name = t.getString("name", null)
                    int lineNo = t.getInt("line", 0)
                    if (name == null || name.isEmpty() || lineNo <= 0)
                        continue
                    db.execute(
                            ("INSERT INTO ${project}.rag_def " +
                             "(file_id, name, name_lower, kind, scope, signature, line) " +
                             "VALUES (?,?,?,?,?,?,?) ON CONFLICT (file_id, name, line) DO NOTHING").toString(),
                            fid, name, name.toLowerCase(),
                            t.getString("kind", null), t.getString("scope", null),
                            t.getString("signature", null), lineNo)
                    n++
                    if (n % 2000 == 0)
                        db.commit()
                }
            }
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                throw new RuntimeException("ctags timed out")
            }
        } finally {
            try { p.destroy() } catch (Exception ignored) { }
        }
        db.commit()
        return n
    }

    private static boolean ctagsAvailable() {
        try {
            Process p = new ProcessBuilder(["ctags", "--version"]).start()
            p.waitFor(10, TimeUnit.SECONDS)
            return p.exitValue() == 0
        } catch (Exception ignored) {
            return false
        }
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
