/*
 * Author: Blake McBride
 * Date: 2/16/20
 *
 * I've found that I sometimes spend more time messing with build programs (such 
 * as Maven, Gradle, and others) than the underlying application I am trying to 
 * build.  They all do the normal things very, very easily.  But when you try to
 * go off their beaten path it gets real difficult real fast.  Being sick and
 * tired of this, and having easily built a shell script to build what I want, I
 * needed a more portable solution.  The files in this directory are that solution.
 *
 * It should be noted, however, that unlike a shell script, this build system 
 * does not execute commands that are already done.  In other words, only the 
 * minimum steps necessary to rebuild a system are actually executed.  So, this 
 * build system runs as fast as the others.
 *
 * There are two classes as follows:
 *
 *     BuildUtils -  the generic utilities needed to build
 *     Tasks      -  the application-specific build procedures (or tasks)
 *
 *    Non-private instance methods with no parameters are considered tasks.
 */

import org.kissweb.BuildUtils;

import static org.kissweb.BuildUtils.*;

/**
 * This class contains the tasks that are executed by the build system.
 * <br><br>
 * The build system finds the names of the tasks through reflection.
 * It also does camelCase conversion.  So a task named abcDef may be evoked
 * as abc-def.
 * <br><br>
 * Each task must be declared as a public static method with no parameters.
 */
public class Tasks {

    // Things that change semi-often
    final static String groovyVer = "4.0.28";
    final static String postgresqlVer = "42.7.11";
    final static String tomcatVer = "11.0.12";
    final static String LIBS = "libs";  // compile time location
    final static ForeignDependencies foreignLibs = buildForeignDependencies();
    final static LocalDependencies localLibs = buildLocalDependencies();
    final static String tomcatTarFile = "apache-tomcat-" + tomcatVer + ".tar.gz";
    final static String BUILDDIR = "work";
    final static String explodedDir = BUILDDIR + "/" + "exploded";
    final static String postgresqlJar = "postgresql-" + postgresqlVer + ".jar";
    final static String groovyJar = "groovy-" + groovyVer + ".jar";
    /**
     * Network ports the embedded Tomcat binds to. All three default to
     * Kiss's traditional values; each can be overridden on the bld
     * command line so multiple Kiss instances can run side by side
     * without colliding.
     * <ul>
     *   <li><code>-dp PORT</code> / <code>--debug-port=PORT</code>     — JDWP debug (default 17900)</li>
     *   <li><code>-hp PORT</code> / <code>--http-port=PORT</code>      — Tomcat HTTP (default 17080)</li>
     *   <li><code>-sp PORT</code> / <code>--shutdown-port=PORT</code>  — Tomcat shutdown signal (default 17005)</li>
     * </ul>
     * The HTTP and shutdown ports are written into
     * <code>tomcat/conf/server.xml</code> on every {@link #setupTomcat()};
     * the debug port into <code>tomcat/bin/debug</code>. All three are
     * re-applied on every <code>bld</code> invocation, so you can pick
     * different values per run with no manual cleanup.
     */
    static String debugPort    = "17900";
    static String httpPort     = "17080";
    static String shutdownPort = "17005";

    /**
     * Main entry point for the build system.  It tells the build system what arguments were passed in
     * and what class contains all the tasks.
     *
     * @param args the arguments to the program
     * @throws Exception if exception is thrown
     * @throws InstantiationException if the class cannot be instantiated
     */
    public static void main(String[] args) throws Exception {
        args = consumePortOptions(args);
        args = consumeScanArg(args);
        BuildUtils.build(args, Tasks.class, LIBS);
    }

    /** Argument captured by {@code scan <project|all>} on the command line. */
    static String scanTarget = null;

    /**
     * Pull the positional argument immediately after the {@code scan}
     * task name out of the argument list. Stores it in {@link #scanTarget}
     * so the {@link #scan()} method can read it after BuildUtils dispatch.
     * If {@code scan} is present without a following arg, leaves
     * {@code scanTarget} null and lets {@link #scan()} print usage.
     */
    private static String[] consumeScanArg(String[] args) {
        java.util.List<String> out = new java.util.ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            if ("scan".equals(args[i])) {
                out.add("scan");
                if (i + 1 < args.length) {
                    scanTarget = args[i + 1];
                    i++;
                }
            } else {
                out.add(args[i]);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * Pull port-override options out of <code>args</code> if present, set
     * the corresponding static fields, and return the remaining arguments
     * for normal task dispatch. Recognized flags (any position):
     * <pre>
     *   -dp PORT, --debug-port=PORT      JDWP   (default 17900)
     *   -hp PORT, --http-port=PORT       HTTP   (default 17080)
     *   -sp PORT, --shutdown-port=PORT   Tomcat shutdown signal (default 17005)
     * </pre>
     * Non-numeric or out-of-range values are reported on stderr; the
     * default is kept in that case.
     */
    private static String[] consumePortOptions(String[] args) {
        java.util.List<String> out = new java.util.ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String[] pair = matchPortOption(a);
            if (pair != null && pair[1] != null) {
                setPort(pair[0], pair[1]);
            } else if (pair != null) {
                if (i + 1 < args.length) {
                    setPort(pair[0], args[i + 1]);
                    i++;
                } else {
                    System.err.println("missing value for " + a + "; ignoring");
                }
            } else {
                out.add(a);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * Match one arg against the port-option flags. Returns a 2-element
     * array <code>{which, value}</code> on match, or <code>null</code>
     * otherwise. <code>which</code> is one of "debug", "http", "shutdown",
     * "frontend". <code>value</code> is the part after "=" for long-form
     * options that include one, or <code>null</code> when the value is
     * the next argument.
     */
    private static String[] matchPortOption(String a) {
        if (a.equals("-dp") || a.equals("--debug-port"))     return new String[]{"debug",    null};
        if (a.equals("-hp") || a.equals("--http-port"))      return new String[]{"http",     null};
        if (a.equals("-sp") || a.equals("--shutdown-port"))  return new String[]{"shutdown", null};
        if (a.startsWith("--debug-port="))    return new String[]{"debug",    a.substring("--debug-port=".length())};
        if (a.startsWith("--http-port="))     return new String[]{"http",     a.substring("--http-port=".length())};
        if (a.startsWith("--shutdown-port=")) return new String[]{"shutdown", a.substring("--shutdown-port=".length())};
        return null;
    }

    private static void setPort(String which, String value) {
        int p;
        try {
            p = Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            System.err.println(which + " port '" + value + "' is not a number; using default");
            return;
        }
        if (p <= 0 || p >= 65536) {
            System.err.println(which + " port '" + value + "' is out of range; using default");
            return;
        }
        String s = Integer.toString(p);
        switch (which) {
            case "debug":    debugPort    = s; break;
            case "http":     httpPort     = s; break;
            case "shutdown": shutdownPort = s; break;
        }
    }

    /**
     * Display a list of valid tasks.  It is called by the build system
     * when the user selects the 'list-tasks' task.
     * <br><br>
     * The build system expects this method to be named listTasks.
     *
     * @see BuildUtils#build
     */
    public static void listTasks() {
        println("");
        println("start                    build and run backend in the background");
        println("stop                     stop the background backend");
        println("status                   report whether the system is running and its config");
        println("scan <project|all>       trigger an incremental rescan of one project or all");
        println("build                    build the entire system but don't run it");
        println("war                      create deployable war file");

        println("");
        println("clean                    remove all compiled files");
        println("realclean                + remove downloaded jar files and tomcat");
        println("ideclean                 + IDE files");
        println("");

        println("jar                      build Kiss.jar");
        println("javadoc                  build javadoc files");
        println("");

        println("libs                     download foreign jar files");
        println("setup-tomcat             set up tomcat");
        println("unit-tests               build the system for unit testing (KissUnitTest.jar)");
        println("");
        println("Options (any position):");
        println("  -dp PORT, --debug-port=PORT       JDWP debug port (default 17900)");
        println("  -hp PORT, --http-port=PORT        Tomcat HTTP port (default 17080)");
        println("  -sp PORT, --shutdown-port=PORT    Tomcat shutdown port (default 17005)");
        println("");
    }

    /**
     * Build the whole system
     * <br><br>
     * 1. download needed jar files<br>
     * 2. build the system into a deployable war file<br>
     * 3. set up a local tomcat server<br>
     * 4. deploy the war file to the local tomcat<br>
     * 5. build JavaDocs
     */
    public static void build() {
        war();
        setupTomcat();
        deployWar();
        javadoc();
    }

    /**
     * Download needed foreign libraries
     */
    public static void libs() {
        downloadAll(foreignLibs);
    }

    /**
     * Create Kiss.jar.  This is a JAR file that can be used in other apps as a
     * utility library.
     */
    private static void jar(boolean unitTest) {
        libs();
        buildJava("src/main/core", explodedDir + "/WEB-INF/classes", localLibs, foreignLibs, null);
        if (unitTest)
            buildJava("src/test/core", explodedDir + "/WEB-INF/classes", localLibs, foreignLibs, explodedDir + "/WEB-INF/classes");
        rm(explodedDir + "/WEB-INF/lib/jakarta.servlet-api-4.0.1.jar");
        createJar(explodedDir + "/WEB-INF/classes", BUILDDIR + "/Kiss.jar");
        //println("Kiss.jar has been created in the " + BUILDDIR + " directory");
    }

    /**
     * Build Kiss.jar<br><br>
     * This is a JAR file that can be used in other apps as a utility library.
     */
    public static void jar() {
        jar(false);
    }

    /**
     * Build the system for unit testing. (KissUnitTest.jar)
     */
    public static void unitTests() {
        final String name = "KissUnitTest";
        final String workDir = BUILDDIR + "/" + name;
        final String jarName = workDir + ".jar";
        jar(true);
        rmTree(workDir);
        rm(jarName);
        unJar(workDir, BUILDDIR + "/Kiss.jar");
        unJar(workDir, "libs/" + postgresqlJar);

        // jUnit stuff
        unJar(workDir, "libs/junit-jupiter-engine-5.11.0.jar");
        unJar(workDir, "libs/junit-jupiter-api-5.11.0.jar");
        unJar(workDir, "libs/junit-jupiter-params-5.11.0.jar");
        unJar(workDir, "libs/junit-platform-console-1.11.0.jar");
        unJar(workDir, "libs/junit-platform-console-standalone-1.11.0.jar");

        unJar(workDir, "libs/" + groovyJar);
        rm(workDir + "/META-INF/MANIFEST.MF");
        writeToFile(workDir + "/META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMain-Class: org.junit.platform.console.ConsoleLauncher\nClass-Path: KissUnitTest.jar\n");
        createJar(workDir, jarName);
        rmTree(workDir);
    }

    /**
     * Build the system into explodedDir
     */
    public static void buildSystem() {
        libs();
        copyTree("src/main/frontend", explodedDir);
        writeToFile(explodedDir + "/META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
        copyTree("src/main/backend", explodedDir + "/WEB-INF/backend");
        copyTree(LIBS, explodedDir + "/WEB-INF/lib");
        buildJava("src/main/core", explodedDir + "/WEB-INF/classes", localLibs, foreignLibs, null);
        buildJava("src/test/core", explodedDir + "/WEB-INF/test-classes", localLibs, foreignLibs, explodedDir + "/WEB-INF/classes");
        buildJava("src/main/precompiled", explodedDir + "/WEB-INF/classes", localLibs, foreignLibs, explodedDir + "/WEB-INF/classes");
        rm(explodedDir + "/WEB-INF/lib/jakarta.servlet-api-4.0.1.jar");
        copyRegex("src/main/core/org/kissweb/lisp", explodedDir + "/WEB-INF/classes/org/kissweb/lisp", ".*\\.lisp", null, false);
        copy("src/main/core/log4j2.xml", explodedDir + "/WEB-INF/classes");
        copyForce("src/main/core/WEB-INF/web-unsafe.xml", explodedDir + "/WEB-INF/web.xml");
    }

    /**
     * Build the system and create the deployable WAR file.
     */
    public static void war() {
        buildSystem();
        copyForce("src/main/core/WEB-INF/web-secure.xml", explodedDir + "/WEB-INF/web.xml");
        createJar(explodedDir, BUILDDIR + "/Kiss.war");
        copyForce("src/main/core/WEB-INF/web-unsafe.xml", explodedDir + "/WEB-INF/web.xml");
        //println("Kiss.war has been created in the " + BUILDDIR + " directory");
    }

    private static void deployWar() {
        copy(BUILDDIR + "/Kiss.war", "tomcat/webapps/ROOT.war");
    }

    /**
     * Unpack and install tomcat
     */
    public static void setupTomcat() {
        if (!exists("tomcat/bin/startup.sh")) {
            download(tomcatTarFile, ".", "https://archive.apache.org/dist/tomcat/tomcat-11/v" + tomcatVer + "/bin/apache-tomcat-" + tomcatVer + ".tar.gz");
            gunzip(tomcatTarFile, "tomcat", 1);
            rmTree("tomcat/webapps/ROOT");
            //run("tar xf apache-tomcat-9.0.31.tar.gz --one-top-level=tomcat --strip-components=1");
        }
        // Always re-stamp server.xml with the current httpPort / shutdownPort
        // so the options take effect on each bld invocation.
        rewriteServerXmlPorts();
        if (isWindows) {
            System.err.println("Setting up tomcat.  Please wait...");
            rm("tomcat\\conf\\tomcat-users.xml");
            // The following is needed by NetBeans
            writeToFile("tomcat\\conf\\tomcat-users.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<tomcat-users xmlns=\"http://tomcat.apache.org/xml\"\n" +
                    "              xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "              xsi:schemaLocation=\"http://tomcat.apache.org/xml tomcat-users.xsd\"\n" +
                    "              version=\"1.0\">\n" +
                    "  <user username=\"admin\" password=\"admin\" roles=\"tomcat,manager-script\" />\n" +
                    "</tomcat-users>\n");
            // writeToFile is a no-op when the target exists, so explicitly
            // remove the helper scripts first.  Without this, changes to
            // debugPort (KISS_DEBUG_PORT) or to the current working directory
            // would not propagate into the regenerated scripts.
            rm("tomcat\\bin\\debug.cmd");
            rm("tomcat\\bin\\stopdebug.cmd");
            writeToFile("tomcat\\bin\\debug.cmd", "@echo off\n" +
                    "cd " + getcwd() + "\\tomcat\\bin\n" +
                    "set JAVA_HOME=" + getJavaPathOnWindows() + "\n" +
                    "set CATALINA_HOME=" + getTomcatPath() + "\n" +
                    "set JPDA_ADDRESS=" + debugPort + "\n" +
                    "set JPDA_TRANSPORT=dt_socket\n" +
                    "catalina.bat jpda start\n");
            writeToFile("tomcat\\bin\\stopdebug.cmd", "@echo off\n" +
                    "cd " + getcwd() + "\\tomcat\\bin\n" +
                    "set JAVA_HOME=" + getJavaPathOnWindows() + "\n" +
                    "set CATALINA_HOME=" + getTomcatPath() + "\n" +
                    "shutdown.bat\n");
        } else {
            rm("tomcat/conf/tomcat-users.xml");
            // The following is needed by NetBeans
            writeToFile("tomcat/conf/tomcat-users.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<tomcat-users xmlns=\"http://tomcat.apache.org/xml\"\n" +
                    "              xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "              xsi:schemaLocation=\"http://tomcat.apache.org/xml tomcat-users.xsd\"\n" +
                    "              version=\"1.0\">\n" +
                    "  <user username=\"admin\" password=\"admin\" roles=\"tomcat,manager-script\" />\n" +
                    "</tomcat-users>\n");
            // writeToFile is a no-op when the target exists, so explicitly
            // remove the debug helper first.  Without this, changes to
            // debugPort (KISS_DEBUG_PORT) or to the current working directory
            // would not propagate into the regenerated script.
            rm("tomcat/bin/debug");
            writeToFile("tomcat/bin/debug", "#\n" +
                    "cd " + getcwd() + "/tomcat/bin\n" +
                    "export JPDA_ADDRESS=" + debugPort + "\n" +
                    "export JPDA_TRANSPORT=dt_socket\n" +
                    "./catalina.sh jpda start\n");
            makeExecutable("tomcat/bin/debug");
        }
        /* The SQLite jar file doesn't correctly support this.
        if (isSunOS) {
            writeToFile("tomcat/bin/setenv.sh","export JAVA_OPTS=\"-Dorg.sqlite.lib.path=/usr/lib/amd64 -Dorg.sqlite.lib.name=libsqlite3.so\"\n");
        }
         */
    }

    /**
     * Edit <code>tomcat/conf/server.xml</code> in place so the
     * <code>&lt;Server&gt;</code> shutdown port and the HTTP/1.1
     * <code>&lt;Connector&gt;</code> port match the currently configured
     * values. Idempotent — repeated calls converge on the configured
     * ports, regardless of what was in the file previously.
     */
    private static void rewriteServerXmlPorts() {
        java.nio.file.Path p = java.nio.file.Paths.get("tomcat/conf/server.xml");
        if (!java.nio.file.Files.exists(p))
            return;   // tomcat not yet installed; setupTomcat runs again later
        try {
            String content = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            String updated = content
                    .replaceFirst("<Server port=\"\\d+\"",
                                  "<Server port=\"" + shutdownPort + "\"")
                    .replaceFirst("<Connector port=\"\\d+\" protocol=\"HTTP/1\\.1\"",
                                  "<Connector port=\"" + httpPort + "\" protocol=\"HTTP/1.1\"");
            if (!updated.equals(content))
                java.nio.file.Files.write(p, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            System.err.println("warning: could not rewrite server.xml ports: " + e.getMessage());
        }
    }

    /**
     * Build and run the back-end asynchronously
     * <br><br>
     * 1. download needed jar files<br>
     * 2. build the system into a deployable war file<br>
     * 3. set up a local tomcat server<br>
     * 4. deploy the war file to the local tomcat<br>
     * 5. run the local tomcat backend<br>
     */
    public static void start() {
        buildSystem();
        setupTomcat();
        copyTree(BUILDDIR + "/exploded", "tomcat/webapps/ROOT");
        if (isWindows)
            runWait(true, "tomcat\\bin\\debug.cmd");
        else
            runWait(true, "tomcat/bin/debug");
        println("***** SERVER IS RUNNING *****");
        println("Server log can be viewed at " + cwd() + "/tomcat/logs/catalina.out or via the view-log command");
        println("The app can also be debugged at port " + debugPort);
        println("To stop the backend, type 'bld stop'");
    }

    /**
     * Stop the backend development server
     */
    public static void stop() {
        println("shutting down tomcat");
        if (isWindows)
            runWait(true, "tomcat\\bin\\stopdebug.cmd");
        else
            runWait(true, "tomcat/bin/shutdown.sh");
    }

    /**
     * Trigger an incremental rescan of one project (or all projects) by
     * calling the running server's {@code RAGAdmin.reindex} JSON-RPC
     * endpoint, then poll the status endpoint until the sweep is done,
     * printing per-project progress (file and chunk counts) as it goes.
     *
     * <p>The server still owns the actual indexing — bld is a separate
     * JVM and cannot invoke the Kiss-resident indexer directly. This
     * method's job is to drive the work and surface progress to the
     * person at the terminal.</p>
     *
     * <p>For multiple targets the projects are scanned sequentially
     * (they would otherwise contend on the shared Ollama GPU anyway).
     * Each project's row shows files / chunks indexed so far and total
     * elapsed time; the final line says DONE with the completion
     * timing.</p>
     */
    public static void scan() {
        if (scanTarget == null || scanTarget.isEmpty()) {
            System.err.println("Usage: ./bld scan <project|all>");
            return;
        }
        String httpP = extractFromFile("tomcat/conf/server.xml",
                "<Connector port=\"(\\d+)\" protocol=\"HTTP/1\\.1\"", httpPort);
        int port = parseIntOr(httpP, 0);
        if (!portListening("127.0.0.1", port)) {
            System.err.println("Server is not running on port " + httpP + ". Start it with './bld start' first.");
            return;
        }

        java.util.List<String> known = readProjectNames("src/main/backend/rag-projects.json");
        java.util.List<String> targets;
        if ("all".equalsIgnoreCase(scanTarget)) {
            if (known.isEmpty()) {
                System.err.println("No projects found in src/main/backend/rag-projects.json");
                return;
            }
            targets = known;
        } else {
            if (!known.isEmpty() && !known.contains(scanTarget))
                System.err.println("warning: '" + scanTarget + "' is not in rag-projects.json (known: " + known + ")");
            targets = java.util.Collections.singletonList(scanTarget);
        }

        String baseUrl = "http://127.0.0.1:" + httpP + "/rest";
        for (String p : targets) {
            scanOne(baseUrl, p);
        }
        println("");
    }

    /** Reindex one project synchronously, printing progress as files/chunks accumulate. */
    private static void scanOne(String baseUrl, String project) {
        println("");
        println("Scanning " + project + "...");

        String reindexBody = "{\"_method\":\"reindex\",\"_class\":\"services/RAGAdmin\",\"project\":\"" + project + "\"}";
        String resp = httpPostJson(baseUrl, reindexBody);
        if (resp.startsWith("error:")) {
            println("  " + resp);
            return;
        }
        if (!(resp.contains("\"started\":true") || resp.contains("\"started\": true"))) {
            String msg = extractJsonString(resp, "message");
            println("  rejected: " + (msg != null ? msg : (resp.length() > 160 ? resp.substring(0, 160) + "…" : resp)));
            return;
        }

        long startMs = System.currentTimeMillis();
        String statusBody = "{\"_method\":\"status\",\"_class\":\"services/RAGAdmin\",\"project\":\"" + project + "\"}";
        int lastFiles = -1, lastChunks = -1;
        long lastPrintMs = 0L;
        while (true) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            String sresp = httpPostJson(baseUrl, statusBody);
            if (sresp.startsWith("error:")) {
                println("  status poll failed: " + sresp);
                return;
            }
            // RAGAdmin.status returns camelCase ("fileCount", "chunkCount").
            // The MCP index_status tool returns snake_case; this client talks
            // to the JSON-RPC service, so camelCase is what we want here.
            boolean indexing = sresp.contains("\"indexing\":true") || sresp.contains("\"indexing\": true");
            int files  = extractJsonInt(sresp, "fileCount");
            int chunks = extractJsonInt(sresp, "chunkCount");
            long now = System.currentTimeMillis();
            long elapsedSec = (now - startMs) / 1000L;
            boolean changed = (files != lastFiles) || (chunks != lastChunks);
            boolean heartbeat = (now - lastPrintMs) > 10_000L;
            if (!indexing) {
                println(String.format("  [%-8s]  DONE: files=%-7d chunks=%d", humanDuration(elapsedSec), files, chunks));
                return;
            }
            if (changed || heartbeat || lastPrintMs == 0L) {
                println(String.format("  [%-8s]  files=%-7d chunks=%d", humanDuration(elapsedSec), files, chunks));
                lastFiles = files;
                lastChunks = chunks;
                lastPrintMs = now;
            }
        }
    }

    /** Extract an integer field value out of a JSON-RPC response by key name. Returns 0 if absent. */
    private static int extractJsonInt(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Extract a string field value out of a JSON-RPC response by key name. Returns null if absent. */
    private static String extractJsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Issue a JSON POST and return the response body (or "error: ..." on failure). */
    private static String httpPostJson(String url, String body) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(15000);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            byte[] data = is == null ? new byte[0] : is.readAllBytes();
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Report whether the embedded Tomcat is running and summarize the
     * live deployment: ports actually configured in
     * <code>tomcat/conf/server.xml</code> and <code>tomcat/bin/debug</code>,
     * database connection details from <code>application.ini</code>, and
     * the project list from <code>rag-projects.json</code>. Reads from the
     * running config files (not the in-process defaults), so the values
     * reflect what's actually deployed rather than what the next
     * invocation would use.
     */
    public static void status() {
        String httpP = extractFromFile("tomcat/conf/server.xml",
                "<Connector port=\"(\\d+)\" protocol=\"HTTP/1\\.1\"", httpPort);
        String shutP = extractFromFile("tomcat/conf/server.xml",
                "<Server port=\"(\\d+)\"", shutdownPort);
        String dbgP  = extractFromFile("tomcat/bin/debug",
                "JPDA_ADDRESS=(\\d+)", debugPort);

        // Identify *this* application's JVM by its absolute catalina.base path,
        // not by any JVM that happens to be running Catalina. This avoids
        // confusing two Kiss installations (e.g. one for development and one
        // for a derived app) when both are running.
        java.util.Optional<ProcessHandle> jvm = findKissJvm();
        boolean up = jvm.isPresent();

        println("");
        println("  Status:            " + (up ? "RUNNING" : "not running"));
        if (up) {
            ProcessHandle h = jvm.get();
            println("  PID:               " + h.pid());
            h.info().startInstant().ifPresent(start -> {
                long sec = java.time.Duration.between(start, java.time.Instant.now()).getSeconds();
                println("  Uptime:            " + humanDuration(sec));
            });
        } else {
            int httpInt = parseIntOr(httpP, 0);
            if (httpInt > 0 && portListening("127.0.0.1", httpInt))
                println("  Note: TCP port " + httpP + " is bound, but not by this installation.");
        }
        println("");
        println("  HTTP port:         " + httpP + (up ? "  (listening)" : ""));
        println("  Shutdown port:     " + shutP);
        println("  Debug port (JDWP): " + dbgP);

        java.util.Map<String, String> cfg = readIni("src/main/backend/application.ini");
        if (!cfg.isEmpty()) {
            println("");
            println("  Database:");
            printlnIfSet(cfg, "DatabaseType",   "    type:             ");
            printlnIfSet(cfg, "DatabaseHost",   "    host:             ");
            printlnIfSet(cfg, "DatabasePort",   "    port:             ");
            printlnIfSet(cfg, "DatabaseName",   "    name:             ");
            printlnIfSet(cfg, "DatabaseUser",   "    user:             ");
            println("");
            println("  Ollama:");
            printlnIfSet(cfg, "OllamaURL",      "    url:              ");
            printlnIfSet(cfg, "EmbeddingModel", "    embedding model:  ");
        }

        // Per-project block with roots + each client's MCP entry wiring.
        //   ✓ wired   — project exists AND a matching MCP entry exists
        //   ⚠ no MCP  — project exists but no MCP entry targets it
        //   (orphan)  — MCP entry targets this server but the project name
        //               isn't in rag-projects.json
        java.util.LinkedHashMap<String, java.util.List<String>> projects =
                readProjects("src/main/backend/rag-projects.json");
        java.io.File claudeCfg = new java.io.File(System.getProperty("user.home"), ".claude.json");
        java.io.File codexCfg  = new java.io.File(System.getProperty("user.home"), ".codex/config.toml");
        java.util.List<ClientEntry> claudeAll = readClaudeCodeEntries(claudeCfg);
        java.util.List<ClientEntry> codexAll  = readCodexEntries(codexCfg);

        // Partition each client's entries:
        //   matching  = current-port AND urlProject is in rag-projects.json
        //               (drawn under the project's block, with cwd check)
        //   stranded  = current-port but project not configured, OR any
        //               entry whose port differs from this server's HTTP port,
        //               OR an entry with no project segment in the URL
        //               (shown in the "stale / orphan" section)
        java.util.Map<String, java.util.List<ClientEntry>> claudeMatched =
                bucketByProject(claudeAll, httpP, projects.keySet());
        java.util.Map<String, java.util.List<ClientEntry>> codexMatched =
                bucketByProject(codexAll, httpP, projects.keySet());
        java.util.List<ClientEntry> claudeStranded = strandedEntries(claudeAll, httpP, projects.keySet());
        java.util.List<ClientEntry> codexStranded  = strandedEntries(codexAll, httpP, projects.keySet());

        println("");
        if (claudeCfg.exists())
            println("  Claude Code config: " + claudeCfg.getAbsolutePath());
        if (codexCfg.exists())
            println("  Codex config:       " + codexCfg.getAbsolutePath());
        if (!claudeCfg.exists() && !codexCfg.exists())
            println("  Client configs:     neither ~/.claude.json nor ~/.codex/config.toml found");
        if (claudeCfg.exists()) {
            println("");
            println("  Note: each Claude Code MCP entry is keyed by the directory it was");
            println("  registered from. The entry is only visible to Claude Code sessions");
            println("  launched from that exact directory. The 'registered under …' lines");
            println("  below state that directory for every entry — compare against the");
            println("  directory you actually start 'claude' in.");
        }

        if (!projects.isEmpty()) {
            println("");
            println("  Projects (rag-projects.json):");
            for (java.util.Map.Entry<String, java.util.List<String>> p : projects.entrySet()) {
                String name = p.getKey();
                java.util.List<String> roots = p.getValue();
                println("");
                println("    " + name);
                if (roots.isEmpty()) {
                    println("      roots:        (none configured)");
                } else if (roots.size() == 1) {
                    println("      roots:        " + roots.get(0));
                } else {
                    println("      roots:        " + roots.get(0));
                    for (int i = 1; i < roots.size(); i++)
                        println("                    " + roots.get(i));
                }
                if (claudeCfg.exists())
                    printWiringLines("Claude Code", claudeMatched.get(name), true);
                if (codexCfg.exists())
                    printWiringLines("Codex", codexMatched.get(name), false);
            }
        }

        if (!claudeStranded.isEmpty() || !codexStranded.isEmpty()) {
            println("");
            println("  Stale / orphan MCP entries (URL is rag-mcp but does not match a current project on this server):");
            for (ClientEntry e : claudeStranded)
                printStrandedLines("Claude Code", e, httpP, true);
            for (ClientEntry e : codexStranded)
                printStrandedLines("Codex", e, httpP, false);
        }
        println("");
    }

    /**
     * Print indented lines for each MCP entry under a project. For Claude
     * Code entries the registration directory is always shown so a stale
     * registration (one keyed to the wrong cwd) is visible without having
     * to read claude.json by hand.
     */
    private static void printWiringLines(String clientLabel,
                                         java.util.List<ClientEntry> entries,
                                         boolean cwdScoped) {
        if (entries == null || entries.isEmpty()) {
            println(String.format("      %-13s (no MCP entry)", clientLabel + ":"));
            return;
        }
        for (ClientEntry e : entries) {
            println(String.format("      %-13s '%s'", clientLabel + ":", e.name));
            println("                    " + visibilityNote(e, cwdScoped));
        }
    }

    /** Multi-line block in the stranded section: entry + URL + visibility + reason. */
    private static void printStrandedLines(String clientLabel, ClientEntry e,
                                           String currentPort, boolean cwdScoped) {
        println(String.format("    %-12s '%s' → %s", clientLabel + ":", e.name, e.urlString()));
        println("                 " + visibilityNote(e, cwdScoped));
        StringBuilder why = new StringBuilder();
        if (!currentPort.equals(e.urlPort)) why.append("stale port ").append(e.urlPort).append("; ");
        if (e.urlProject.isEmpty()) why.append("no project segment in URL; ");
        else if (currentPort.equals(e.urlPort)) why.append("project '").append(e.urlProject)
                                                      .append("' not in rag-projects.json; ");
        if (why.length() == 0) why.append("orphan");
        println("                 ⚠ " + why.toString().replaceFirst("; $", ""));
    }

    /** "registered under …" / "user scope" / "global" — never judges, just states. */
    private static String visibilityNote(ClientEntry e, boolean cwdScoped) {
        if (!cwdScoped)
            return "(global — visible to any Codex session)";
        if (e.registeredUnder == null)
            return "(user scope — visible from any directory)";
        return "registered under " + e.registeredUnder
                + "   (only visible when 'claude' is launched from there)";
    }

    /** Partition: entries on the current port whose urlProject is in {@code knownProjects}. */
    private static java.util.Map<String, java.util.List<ClientEntry>> bucketByProject(
            java.util.List<ClientEntry> entries, String currentPort,
            java.util.Set<String> knownProjects) {
        java.util.LinkedHashMap<String, java.util.List<ClientEntry>> out = new java.util.LinkedHashMap<>();
        for (ClientEntry e : entries) {
            if (!currentPort.equals(e.urlPort)) continue;
            if (e.urlProject.isEmpty()) continue;
            if (!knownProjects.contains(e.urlProject)) continue;
            out.computeIfAbsent(e.urlProject, k -> new java.util.ArrayList<>()).add(e);
        }
        return out;
    }

    /** Anything not in the bucketByProject result: stale port, wrong project, or no project at all. */
    private static java.util.List<ClientEntry> strandedEntries(
            java.util.List<ClientEntry> entries, String currentPort,
            java.util.Set<String> knownProjects) {
        java.util.List<ClientEntry> out = new java.util.ArrayList<>();
        for (ClientEntry e : entries) {
            boolean good = currentPort.equals(e.urlPort)
                    && !e.urlProject.isEmpty()
                    && knownProjects.contains(e.urlProject);
            if (!good) out.add(e);
        }
        return out;
    }

    /**
     * Parse rag-projects.json into a name-ordered map of project name → roots[].
     * Best-effort regex parser (Tasks.java intentionally has no JSON dep) —
     * tolerates excludeGlobs and other fields, expects the JSON shape that
     * the .example template uses.
     */
    private static java.util.LinkedHashMap<String, java.util.List<String>> readProjects(String file) {
        java.util.LinkedHashMap<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        java.nio.file.Path p = java.nio.file.Paths.get(file);
        if (!java.nio.file.Files.exists(p)) return out;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher nameMatcher =
                    java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            java.util.regex.Pattern rootsPat =
                    java.util.regex.Pattern.compile("\"roots\"\\s*:\\s*\\[([^\\]]*)\\]");
            java.util.regex.Pattern stringPat =
                    java.util.regex.Pattern.compile("\"([^\"]+)\"");
            // Collect name+offset for each project, then slice between them
            // to find that project's roots array.
            java.util.List<int[]> spans = new java.util.ArrayList<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            while (nameMatcher.find()) {
                spans.add(new int[]{nameMatcher.start(), nameMatcher.end()});
                names.add(nameMatcher.group(1));
            }
            for (int i = 0; i < names.size(); i++) {
                int sliceStart = spans.get(i)[1];
                int sliceEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : content.length();
                String slice = content.substring(sliceStart, sliceEnd);
                java.util.regex.Matcher rm = rootsPat.matcher(slice);
                java.util.List<String> roots = new java.util.ArrayList<>();
                if (rm.find()) {
                    java.util.regex.Matcher sm = stringPat.matcher(rm.group(1));
                    while (sm.find()) roots.add(sm.group(1));
                }
                out.put(names.get(i), roots);
            }
        } catch (java.io.IOException ignored) {}
        return out;
    }

    /**
     * A single MCP entry found in a client's config.
     * <ul>
     *   <li>{@link #name} — the key in claude.json or the TOML section name in Codex.</li>
     *   <li>{@link #registeredUnder} — the absolute working-directory path the
     *       entry is registered under (claude.json default <code>--scope local</code>).
     *       {@code null} for entries not cwd-keyed (Claude Code user-scope, or Codex).</li>
     *   <li>{@link #urlPort} — the port in the entry's URL. If this differs
     *       from the server's current HTTP port the entry is stale.</li>
     *   <li>{@link #urlProject} — the project segment from the URL path
     *       (<code>…/rag-mcp/&lt;project&gt;</code>). May be empty if the URL has no
     *       project segment.</li>
     * </ul>
     */
    static class ClientEntry {
        final String name;
        final String registeredUnder;
        final String urlPort;
        final String urlProject;
        ClientEntry(String name, String registeredUnder, String urlPort, String urlProject) {
            this.name = name;
            this.registeredUnder = registeredUnder;
            this.urlPort = urlPort;
            this.urlProject = urlProject == null ? "" : urlProject;
        }
        String urlString() {
            return "http://127.0.0.1:" + urlPort + "/rag-mcp"
                    + (urlProject.isEmpty() ? "" : "/" + urlProject);
        }
    }

    /**
     * Scan a Claude Code config file for MCP server entries whose URL targets
     * any port at <code>http://127.0.0.1:&lt;port&gt;/rag-mcp[/&lt;project&gt;]</code>.
     * Returns the full list — caller filters by current-port-and-known-project
     * vs. stale/orphan. Each entry records its registered-under cwd key
     * (null = user-scope) plus port and url-path-project.
     */
    private static java.util.List<ClientEntry> readClaudeCodeEntries(java.io.File ccCfg) {
        java.util.List<ClientEntry> out = new java.util.ArrayList<>();
        if (!ccCfg.exists()) return out;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(ccCfg.toPath()),
                                        java.nio.charset.StandardCharsets.UTF_8);
            // Match any rag-mcp URL regardless of port, with optional project segment.
            java.util.regex.Pattern urlPat = java.util.regex.Pattern.compile(
                    "http://127\\.0\\.0\\.1:(\\d+)/rag-mcp(?:/([a-zA-Z0-9_.-]+))?");
            java.util.regex.Pattern namePat =
                    java.util.regex.Pattern.compile("\"([a-zA-Z0-9_.-]+)\"\\s*:\\s*\\{");
            java.util.regex.Pattern pathKeyPat =
                    java.util.regex.Pattern.compile("\"(/[^\"]+)\"\\s*:\\s*\\{");
            java.util.regex.Matcher um = urlPat.matcher(content);
            while (um.find()) {
                String port = um.group(1);
                String project = um.group(2); // may be null
                int hit = um.start();
                String beforeUrl = content.substring(0, hit);
                // Most recent `"<name>": {` is the entry name.
                java.util.regex.Matcher m = namePat.matcher(beforeUrl);
                String lastName = null;
                while (m.find()) lastName = m.group(1);
                if (lastName == null) continue;
                // Most recent `"mcpServers"` before that defines scope.
                int mcpServersPos = beforeUrl.lastIndexOf("\"mcpServers\"");
                String regUnder = null;
                if (mcpServersPos > 0) {
                    String beforeMcp = beforeUrl.substring(0, mcpServersPos);
                    java.util.regex.Matcher pm = pathKeyPat.matcher(beforeMcp);
                    while (pm.find()) regUnder = pm.group(1);
                }
                out.add(new ClientEntry(lastName, regUnder, port, project));
            }
        } catch (java.io.IOException ignored) {}
        return out;
    }

    /**
     * Scan a Codex CLI <code>config.toml</code> for {@code [mcp_servers.&lt;name&gt;]}
     * sections whose {@code url = "…"} targets any port at
     * <code>http://127.0.0.1:&lt;port&gt;/rag-mcp[/&lt;project&gt;]</code>. Returns a flat list
     * (caller filters); the {@code registeredUnder} field is always null
     * because Codex's config is not cwd-keyed.
     */
    private static java.util.List<ClientEntry> readCodexEntries(java.io.File toml) {
        java.util.List<ClientEntry> out = new java.util.ArrayList<>();
        if (!toml.exists()) return out;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(toml.toPath()),
                                        java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Pattern headerPat =
                    java.util.regex.Pattern.compile("(?m)^\\s*\\[([^\\]]+)\\]");
            java.util.regex.Pattern urlPat =
                    java.util.regex.Pattern.compile("(?m)^\\s*url\\s*=\\s*\"([^\"]+)\"");
            java.util.regex.Pattern ragMcpPat = java.util.regex.Pattern.compile(
                    "http://127\\.0\\.0\\.1:(\\d+)/rag-mcp(?:/([a-zA-Z0-9_.-]+))?");
            java.util.regex.Matcher m = headerPat.matcher(content);
            String prevSection = null;
            int prevEnd = 0;
            while (m.find()) {
                if (prevSection != null)
                    captureCodexSection(prevSection, content.substring(prevEnd, m.start()),
                                        urlPat, ragMcpPat, out);
                prevSection = m.group(1).trim();
                prevEnd = m.end();
            }
            if (prevSection != null)
                captureCodexSection(prevSection, content.substring(prevEnd),
                                    urlPat, ragMcpPat, out);
        } catch (java.io.IOException ignored) {}
        return out;
    }

    private static void captureCodexSection(String section, String body,
                                            java.util.regex.Pattern urlPat,
                                            java.util.regex.Pattern ragMcpPat,
                                            java.util.List<ClientEntry> out) {
        if (!section.startsWith("mcp_servers."))
            return;
        String entryName = section.substring("mcp_servers.".length());
        java.util.regex.Matcher u = urlPat.matcher(body);
        if (!u.find())
            return;
        String url = u.group(1);
        java.util.regex.Matcher rm = ragMcpPat.matcher(url);
        if (!rm.find())
            return;
        out.add(new ClientEntry(entryName, null, rm.group(1), rm.group(2)));
    }

    /** Extract group 1 of the first match of {@code regex} in {@code file}, or {@code fallback}. */
    private static String extractFromFile(String file, String regex, String fallback) {
        java.nio.file.Path p = java.nio.file.Paths.get(file);
        if (!java.nio.file.Files.exists(p)) return fallback;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(content);
            return m.find() ? m.group(1) : fallback;
        } catch (java.io.IOException e) {
            return fallback;
        }
    }

    /** Open a TCP socket to host:port with a short timeout; true if reachable. */
    private static boolean portListening(String host, int port) {
        if (port <= 0) return false;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 250);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /**
     * Find the JVM belonging to <em>this</em> Kiss installation by matching
     * its <code>-Dcatalina.base=&lt;abs-path-to-our-tomcat&gt;</code>
     * argument. Multiple Kiss instances on the same host therefore stay
     * disambiguated.
     */
    private static java.util.Optional<ProcessHandle> findKissJvm() {
        String ourCatalinaBase;
        try {
            ourCatalinaBase = new java.io.File("tomcat").getCanonicalPath();
        } catch (java.io.IOException e) {
            ourCatalinaBase = new java.io.File("tomcat").getAbsolutePath();
        }
        String needle = "-Dcatalina.base=" + ourCatalinaBase;
        return ProcessHandle.allProcesses()
                .filter(ph -> ph.info().commandLine()
                        .map(c -> c.contains(needle))
                        .orElse(false))
                .findFirst();
    }

    /** Format a duration in seconds as e.g. "2d 3h", "5h 12m", "45s". */
    private static String humanDuration(long seconds) {
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    /** Parse a simple key=value ini file (no sections). Lines starting with # or ; are comments. */
    private static java.util.Map<String, String> readIni(String file) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        java.nio.file.Path p = java.nio.file.Paths.get(file);
        if (!java.nio.file.Files.exists(p)) return out;
        try {
            for (String line : java.nio.file.Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) continue;
                int eq = t.indexOf('=');
                if (eq < 0) continue;
                out.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        } catch (java.io.IOException ignored) {}
        return out;
    }

    private static void printlnIfSet(java.util.Map<String, String> cfg, String key, String prefix) {
        String v = cfg.get(key);
        if (v != null && !v.isEmpty())
            println(prefix + v);
    }

    /** Extract every {@code "name": "..."} value from a JSON file. */
    private static java.util.List<String> readProjectNames(String file) {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.nio.file.Path p = java.nio.file.Paths.get(file);
        if (!java.nio.file.Files.exists(p)) return names;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            while (m.find()) names.add(m.group(1));
        } catch (java.io.IOException ignored) {}
        return names;
    }

    /**
     * build the javdoc files
     */
    public static void javadoc() {
        libs();
        buildJavadoc("src/main/core", LIBS, BUILDDIR + "/javadoc", "JavaDocOverview.html");
    }

    /**
     * Remove:<br>
     * -- all files that were built<br><br>
     * Do not remove:<br>
     * -- the downloaded jar files, tomcat<br>
     * -- the IDE files
     */
    public static void clean() {
        rmTree(BUILDDIR);
        rmTree("build.work");  // used in the past
        rm("manual/Kiss.log");
        rm("manual/Kiss.aux");
        rm("manual/Kiss.toc");
    }

    /**
     * Remove:<br>
     * -- all files that were built<br>
     * -- the downloaded jar files, tomcat<br><br>
     * Do not remove:<br>
     * -- the IDE files
     */
    public static void realclean() {
        clean();
        rmRegex("src/main/frontend/lib", "jquery.*");
        delete(foreignLibs);
        rmTree("tomcat");
        rmRegex(".", "apache-tomcat-.*");
        rm("manual/Kiss.pdf");

        // remove old stuff
        rm("libs/json.jar");
        rmRegex(LIBS, "dynamic-loader-.*\\.jar");
        rmRegex(LIBS, "groovy-.*\\.jar");
        rmRegex(LIBS, "postgresql-.*\\.jar");
        rmRegex(LIBS, "sqlite-jdbc-.*\\.jar");

        /* libraries that don't have their version number in the file name
           must be removed from cache.
           Now we must include them with Kiss because they are no longer available through a CDN.
         */
        //removeFromCache("ag-grid-community.noStyle.min.js");
        //removeFromCache("ag-grid.min.css");
        //removeFromCache("ag-theme-balham.min.css");
    }

    /**
     * Remove:<br>
     * -- all files that were built<br>
     * -- the downloaded jar files, tomcat<br>
     * -- the IDE files
     */
    public static void ideclean() {
        realclean();

        rmTree(".project");
        rmTree(".settings");
        rmTree(".vscode");

        // IntelliJ
        rmTree(".idea");
        rmTree("out");
        rmRegex(".", ".*\\.iml");
        rmRegex("src", ".*\\.iml");

        // NetBeans
        rmTree("dist");
        rmTree("nbproject");
        rmTree("build");
        rm("nbbuild.xml");
    }

    /**
     * Specify the jars used by the system but not included in the distribution.
     * These are the jars that are to be downloaded by the build system.
     *
     * @return
     */
    private static ForeignDependencies buildForeignDependencies() {
        final ForeignDependencies dep = new ForeignDependencies();
        dep.add(LIBS, "https://repo1.maven.org/maven2/com/mchange/c3p0/0.12.0/c3p0-0.12.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/apache/groovy/groovy/" + groovyVer + "/" + groovyJar);
        dep.add(LIBS, "https://repo1.maven.org/maven2/jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.25.4/log4j-core-2.25.4.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.25.4/log4j-api-2.25.4.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/com/mchange/mchange-commons-java/0.4.0/mchange-commons-java-0.4.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/com/microsoft/sqlserver/mssql-jdbc/12.4.2.jre8/mssql-jdbc-12.4.2.jre8.jar");
        // Oracle has removed these files from their public repository
        //dep.add(LIBS, "https://repo1.maven.org/maven2/mysql/mysql-connector-java/9.2.0/mysql-connector-java-9.2.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/com/oracle/ojdbc/ojdbc10/19.3.0.0/ojdbc10-19.3.0.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/postgresql/postgresql/" + postgresqlVer + "/" + postgresqlJar);
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.30/slf4j-api-1.7.30.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/1.7.30/slf4j-simple-1.7.30.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.47.1.0/sqlite-jdbc-3.47.1.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/3.0.5/pdfbox-3.0.5.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/apache/pdfbox/fontbox/3.0.5/fontbox-3.0.5.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox-io/3.0.5/pdfbox-io-3.0.5.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/com/drewnoakes/metadata-extractor/2.19.0/metadata-extractor-2.19.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/com/adobe/xmp/xmpcore/6.1.11/xmpcore-6.1.11.jar");
        // ag-grid appears to no longer be available through a CDN.  Therefore, I am simply including it with the Kiss distribution
        //dep.add("src/main/frontend/lib", "https://cdnjs.cloudflare.com/ajax/libs/ag-grid/25.1.0/ag-grid-community.noStyle.min.js");
        //dep.add("src/main/frontend/lib", "https://cdnjs.cloudflare.com/ajax/libs/ag-grid/25.1.0/styles/ag-grid.min.css");
        //dep.add("src/main/frontend/lib", "https://cdnjs.cloudflare.com/ajax/libs/ag-grid/25.1.0/styles/ag-theme-balham.min.css");

        // jUnit
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter/5.11.0/junit-jupiter-5.11.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter-params/5.11.0/junit-jupiter-params-5.11.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter-api/5.11.0/junit-jupiter-api-5.11.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter-engine/5.11.0/junit-jupiter-engine-5.11.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console/1.11.0/junit-platform-console-1.11.0.jar");
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.11.0/junit-platform-console-standalone-1.11.0.jar");
        return dep;
    }

    /**
     * This specifies the jar files used by the system that are included in the distribution.
     * (All are open-source but exist in other projects.)
     *
     * @return
     */
    private static LocalDependencies buildLocalDependencies() {
        final LocalDependencies dep = new LocalDependencies();
        dep.add(LIBS, "abcl.jar");
        return dep;
    }

}
