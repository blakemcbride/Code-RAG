package scripts

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.kissweb.database.Connection
import org.kissweb.database.Record
import org.kissweb.json.JSONObject
import org.kissweb.rag.ProjectRegistry
import org.kissweb.rag.RAGSearch
import org.kissweb.rag.RAGTokenizer
import org.kissweb.restServer.MainServlet

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Indexes VCS commit history so search can answer "why was this done".
 *
 * Commit messages carry the one thing no amount of code search can recover:
 * intent. The tree says what the code does now; only the history says why it
 * changed, what was tried before, and what a change was meant to fix.
 *
 * <h2>Why there is a VCS abstraction</h2>
 * The configured roots are not all git. Stack360 — the largest project here —
 * is Subversion, so a git-only implementation would cover the small
 * repositories and miss the codebase where commit rationale matters most.
 * Both systems yield the same shape (id, author, date, message, changed
 * paths), so the difference is confined to {@link #readGit} / {@link #readSvn}.
 *
 * Incremental by construction: git resumes from the newest indexed sha,
 * Subversion from the newest indexed revision number.
 */
class RAGHistory {

    private static final Logger logger = LogManager.getLogger(RAGHistory.class)

    /**
     * Cap on characters embedded per commit. A single commit can touch hundreds
     * of files and the resulting text overruns the embedding model's context
     * window ("input length exceeds the context length").
     */
    private static final int MAX_EMBED_CHARS = 2000

    /**
     * Max changed paths included in a commit's embedded text. Subversion paths
     * are long and repetitive ("/Project/trunk/src/java/com/..."), so they
     * tokenize far denser than prose — a character cap alone still overran the
     * context window on large commits.
     */
    private static final int MAX_EMBED_PATHS = 20

    /** ASCII record / unit separators — safe against newlines in commit messages. */
    private static final String RS = "\u001e"
    private static final String US = "\u001f"

    /** JSON-RPC entry point. params: { project, maxCommits? } */
    static JSONObject runJson(Connection db, JSONObject params) {
        return run(db, params.getString("project", null), params.getInt("maxCommits", 0))
    }

    /**
     * Index new commits for every root of a project.
     *
     * @param maxCommits cap per root on a first import (0 = use RAGHistoryMaxCommits)
     */
    static JSONObject run(Connection db, String project, int maxCommits) {
        if (!ProjectRegistry.isValidName(project))
            throw new RuntimeException("Invalid project name: ${project}")
        ProjectRegistry.Project proj = ProjectRegistry.get(project)
        if (proj == null)
            throw new RuntimeException("Unknown project '${project}'")

        int cap = maxCommits > 0 ? maxCommits : Integer.parseInt(env("RAGHistoryMaxCommits", "5000"))

        int totalNew = 0, rootsDone = 0, rootsSkipped = 0
        List<String> notes = []
        for (String rootPath : proj.roots) {
            File root = new File(rootPath)
            String repo = root.name
            String vcs = detectVcs(root)
            if (vcs == null) {
                rootsSkipped++
                notes.add("${repo}: no git or svn working copy")
                continue
            }
            try {
                String since = newestRev(db, project, repo)
                List<Map> commits = (vcs == 'git') ? readGit(root, since, cap) : readSvn(root, since, cap)
                int n = store(db, project, repo, vcs, commits)
                totalNew += n
                rootsDone++
                notes.add("${repo} (${vcs}): ${n} new")
                logger.info("RAGHistory[${project}/${repo}]: ${vcs}, ${n} new commits")
            } catch (Exception e) {
                rootsSkipped++
                notes.add("${repo} (${vcs}): FAILED ${e.message}")
                logger.warn("RAGHistory[${project}/${repo}] failed", e)
                try { db.rollback() } catch (Exception ignored) { }
            }
        }
        db.commit()

        JSONObject out = new JSONObject()
        out.put("project", project)
        out.put("newCommits", totalNew)
        out.put("rootsIndexed", rootsDone)
        out.put("rootsSkipped", rootsSkipped)
        out.put("detail", notes.join("; "))
        return out
    }

    /** git if .git exists, svn if .svn exists, else null (not under VCS). */
    private static String detectVcs(File root) {
        if (new File(root, ".git").exists())
            return "git"
        if (new File(root, ".svn").exists())
            return "svn"
        return null
    }

    /** Newest revision already stored for this repo, or null when empty. */
    private static String newestRev(Connection db, String project, String repo) {
        // No explicit LIMIT: Kiss's fetchOne appends its own, and "LIMIT 1 LIMIT 1"
        // is a syntax error.
        Record r = db.fetchOne(
                ("SELECT rev FROM ${project}.rag_commit WHERE repo = ? " +
                 "ORDER BY committed_at DESC NULLS LAST, commit_id DESC").toString(), repo)
        return r == null ? null : r.getString("rev")
    }

    // ----- git ----------------------------------------------------------------

    private static List<Map> readGit(File root, String sinceSha, int cap) {
        // Records are prefixed with RS and fields separated by US so that
        // newlines inside a commit body cannot corrupt the parse.
        List<String> cmd = ["git", "log",
                            "--pretty=format:${RS}%H${US}%an${US}%aI${US}%s${US}%b${US}".toString(),
                            "--name-only", "-n", String.valueOf(cap)]
        if (sinceSha != null && !sinceSha.isEmpty() && sinceSha ==~ /[0-9a-f]{7,40}/)
            cmd.add("${sinceSha}..HEAD".toString())
        String out = exec(root, cmd, 120)
        List<Map> commits = []
        for (String rec : out.split(RS)) {
            if (rec.trim().isEmpty())
                continue
            String[] f = rec.split(US, -1)
            if (f.length < 6)
                continue
            commits.add([rev  : f[0].trim(),
                         author: f[1],
                         date  : f[2],
                         subject: f[3],
                         body  : f[4],
                         files : f[5].trim()])
        }
        return commits
    }

    // ----- svn ----------------------------------------------------------------

    private static List<Map> readSvn(File root, String sinceRev, int cap) {
        // svn log talks to the repository, so it can be slow or fail when the
        // server is unreachable; exec() bounds it and the caller treats a
        // failure as "skip this root", never as a fatal error.
        long start = 1L
        if (sinceRev != null && sinceRev.isInteger())
            start = Long.parseLong(sinceRev) + 1L
        List<String> cmd = ["svn", "log", "-v", "--xml",
                            "-l", String.valueOf(cap),
                            "-r", "${start}:HEAD".toString()]
        String xml = exec(root, cmd, 180)
        if (xml == null || !xml.contains("<log"))
            return []
        // Parsed with the JDK's DOM parser rather than Groovy's XmlSlurper:
        // only the core groovy jar is on the classpath here, not groovy-xml.
        List<Map> commits = []
        def dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        dbf.setNamespaceAware(false)
        def doc = dbf.newDocumentBuilder().parse(
                new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
        def entries = doc.getElementsByTagName("logentry")
        for (int i = 0; i < entries.getLength(); i++) {
            def e = entries.item(i)
            String msg = childText(e, "msg") ?: ""
            int nl = msg.indexOf('\n')
            List<String> paths = []
            def pathNodes = ((org.w3c.dom.Element) e).getElementsByTagName("path")
            for (int j = 0; j < pathNodes.getLength(); j++)
                paths.add(pathNodes.item(j).getTextContent())
            commits.add([rev    : ((org.w3c.dom.Element) e).getAttribute("revision"),
                         author : childText(e, "author"),
                         date   : childText(e, "date"),
                         subject: nl < 0 ? msg : msg.substring(0, nl),
                         body   : nl < 0 ? "" : msg.substring(nl + 1),
                         files  : paths.join("\n")])
        }
        return commits
    }

    /** First direct-or-nested child element's text, or null. */
    private static String childText(org.w3c.dom.Node parent, String tag) {
        def nl = ((org.w3c.dom.Element) parent).getElementsByTagName(tag)
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent()
    }

    // ----- storage ------------------------------------------------------------

    private static int store(Connection db, String project,
                             String repo, String vcs, List<Map> commits) {
        if (commits.isEmpty())
            return 0
        // Embed the whole commit — subject, body and touched paths. The paths
        // matter: "which change touched the login screen" is a path question
        // asked in prose.
        // Embedding goes through the precompiled RAGSearch rather than the
        // indexer's batch path: backend Groovy files load in isolation, so one
        // cannot reference another's classes.
        List<float[]> vectors = new ArrayList<>(commits.size())
        for (Map c : commits)
            vectors.add(embedCommit(repo, c))

        int n = 0
        for (int i = 0; i < commits.size(); i++) {
            Map c = commits[i]
            if (!c.rev || vectors[i] == null)
                continue
            String lex = RAGTokenizer.tokenize(
                    "${c.subject} ${c.body ?: ''} ${c.files ?: ''} ${c.author ?: ''}".toString())
            java.sql.Timestamp ts = parseDate(c.date)
            db.execute(
                    ("INSERT INTO ${project}.rag_commit " +
                     "(repo, vcs, rev, author, committed_at, subject, body, files_changed, lexemes, embedding) " +
                     "VALUES (?,?,?,?,?,?,?,?,?,?::vector) " +
                     "ON CONFLICT (repo, rev) DO NOTHING").toString(),
                    repo, vcs, c.rev, c.author, ts,
                    (c.subject ?: "(no message)"), c.body, c.files, lex,
                    RAGSearch.vectorToLiteral(vectors[i]))
            n++
        }
        db.commit()
        return n
    }

    /**
     * Embed one commit, shrinking the text and retrying if the model rejects it.
     *
     * Subversion paths tokenize far denser than prose, so no fixed character cap
     * is reliably safe — a commit touching many deeply-nested paths can exceed
     * the context window at a length that is fine for ordinary text. Halving on
     * rejection mirrors what the chunk indexer does and removes the guesswork.
     * Returns null if even a minimal text fails, so one pathological commit
     * cannot abort an entire repository's import.
     */
    private static float[] embedCommit(String repo, Map c) {
        StringBuilder sb = new StringBuilder()
        sb.append("commit in ").append(repo).append('\n')
        sb.append(c.subject).append('\n')
        if (c.body)
            sb.append(c.body).append('\n')
        if (c.files) {
            String[] paths = ((String) c.files).split("\n")
            sb.append("files: ")
            for (int pi = 0; pi < Math.min(paths.length, MAX_EMBED_PATHS); pi++)
                sb.append(paths[pi]).append(' ')
        }
        String t = sb.toString()
        if (t.length() > MAX_EMBED_CHARS)
            t = t.substring(0, MAX_EMBED_CHARS)
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return RAGSearch.embedQuery(t)
            } catch (Exception e) {
                if (t.length() <= 120) {
                    logger.warn("RAGHistory: giving up embedding ${repo} rev ${c.rev}: ${e.message}")
                    return null
                }
                t = t.substring(0, t.length().intdiv(2))
            }
        }
        return null
    }

    private static java.sql.Timestamp parseDate(String s) {
        if (s == null || s.isEmpty())
            return null
        try {
            return new java.sql.Timestamp(
                    java.time.Instant.parse(s.replace(' ', 'T')).toEpochMilli())
        } catch (Exception ignored) {
            try {
                return new java.sql.Timestamp(
                        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli())
            } catch (Exception ignored2) {
                return null
            }
        }
    }

    /** Run a command in a directory with a hard timeout; returns stdout. */
    private static String exec(File dir, List<String> cmd, int timeoutSec) {
        ProcessBuilder pb = new ProcessBuilder(cmd)
        pb.directory(dir)
        pb.redirectErrorStream(false)
        Process p = pb.start()
        StringBuilder sb = new StringBuilder()
        Thread reader = new Thread({ ->
            try {
                p.getInputStream().withReader(StandardCharsets.UTF_8.name()) { r ->
                    char[] buf = new char[8192]
                    int len
                    while ((len = r.read(buf)) > 0)
                        sb.append(buf, 0, len)
                }
            } catch (Exception ignored) { }
        } as Runnable)
        reader.setDaemon(true)
        reader.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw new RuntimeException("timed out after ${timeoutSec}s: ${cmd.take(2).join(' ')}")
        }
        reader.join(5000)
        if (p.exitValue() != 0) {
            String err = ""
            try { err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) }
            catch (Exception ignored) { }
            throw new RuntimeException("${cmd.take(2).join(' ')} exited ${p.exitValue()}: ${err.take(200)}")
        }
        return sb.toString()
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
