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
import org.kissweb.json.JSONArray;
import org.kissweb.json.JSONException;
import org.kissweb.json.JSONObject;

import static org.kissweb.BuildUtils.*;
import static org.kissweb.KissBuildUtils.*;

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
        args = consumeYesFlag(args);
        args = consumeCommandArgs(args);
        BuildUtils.build(args, Tasks.class, LIBS);
    }

    /** Argument captured by {@code scan <project|all>} on the command line. */
    static String scanTarget = null;

    /** Set by {@code scan ... --full} — TRUNCATE and rebuild rather than sweep incrementally. */
    static boolean scanFull = false;

    /**
     * Positional args captured after a known multi-arg command name
     * (everything that follows the command on the CLI). Tasks like
     * {@link #newProject()} read this directly. Empty by default.
     */
    static String[] commandArgs = new String[0];

    /** Commands whose trailing CLI positionals are slurped into {@link #commandArgs}. */
    private static final java.util.Set<String> COMMANDS_WITH_ARGS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "scan", "new-project", "remove-project", "add-root", "remove-root", "eval",
                    "history", "summarize", "defs", "deps", "usage"));

    /** Set by {@code -y} / {@code --yes} — suppress the confirmation prompt before destructive reconcile actions. */
    static boolean assumeYes = false;

    /** Strip {@code -y} / {@code --yes} from argv and set {@link #assumeYes}. */
    private static String[] consumeYesFlag(String[] args) {
        java.util.List<String> out = new java.util.ArrayList<>(args.length);
        for (String a : args) {
            if ("-y".equals(a) || "--yes".equals(a))
                assumeYes = true;
            else
                out.add(a);
        }
        return out.toArray(new String[0]);
    }

    /**
     * Pull positional args following any of {@link #COMMANDS_WITH_ARGS} out
     * of the argument list and stash them in {@link #commandArgs} so the
     * task method can read them after BuildUtils dispatch.
     *
     * <p>BuildUtils dispatches tasks by the first non-option name and
     * ignores extras, so we strip them from argv but keep the command
     * name itself. For backward compatibility {@code scan}'s first
     * positional is also mirrored into the legacy {@link #scanTarget}
     * field.</p>
     */
    private static String[] consumeCommandArgs(String[] args) {
        java.util.List<String> out = new java.util.ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (COMMANDS_WITH_ARGS.contains(a)) {
                out.add(a);
                java.util.List<String> captured = new java.util.ArrayList<>();
                while (i + 1 < args.length)
                    captured.add(args[++i]);
                commandArgs = captured.toArray(new String[0]);
                if ("scan".equals(a) && commandArgs.length >= 1)
                    scanTarget = commandArgs[0];
            } else {
                out.add(a);
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
    /**
     * Print the user-facing operational commands. Shared between
     * {@link #listTasks()} (which appends the build/dev commands)
     * and {@link #commands()} (which doesn't, so {@code code-rag}
     * with no args shows only what's relevant to users).
     */
    private static void printOperationalCommands() {
        println("start                                     build and run backend in the background");
        println("stop                                      stop the background backend");
        println("status                                    report whether the system is running and its config");
        println("scan <project|all> [--full]               reconcile rag-projects.json then rescan");
        println("                                          --full TRUNCATEs and rebuilds from scratch");
        println("new-project <name> [--project-dir <dir>] <root> [<root>...]");
        println("                                          add a project + bootstrap + register MCP entries");
        println("                                          --project-dir <dir> drops .mcp.json + a CLAUDE.local.md");
        println("                                          routing snippet at <dir> so a Claude Code session");
        println("                                          launched from there (an umbrella directory above");
        println("                                          the roots) sees the MCP tool");
        println("remove-project <name>                     drop a project + deregister MCP entries");
        println("add-root <name> <root> [<root>...]        add roots to an existing project");
        println("remove-root <name> <root> [<root>...]     remove roots from an existing project");
        println("history <project|all>                     index git/svn commit history (search_history)");
        println("defs <project|all>                        extract ctags symbol definitions (find_symbol)");
        println("deps <project|all>                        backfill the import graph (find_dependents)");
        println("usage <project|all>                       is the tool actually being called? (from rag_query_log)");
        println("summarize <project|all> [--regenerate]    generate per-file LLM summaries (slow, GPU-bound)");
        println("summarize <project|all> --symbols       per-symbol summaries for large files (slower)");
        println("eval <project> [--baseline]               score retrieval against eval/<project>-queries.jsonl");
        println("                                          --baseline records the run as the comparison point;");
        println("                                          otherwise the run is diffed against eval/baseline.json");
    }

    /**
     * User-facing command list. This is what {@code code-rag} (no args)
     * prints — operational commands only, no build/dev/cleanup noise.
     * For the full developer view, use {@code bld list-tasks}.
     */
    public static void commands() {
        println("");
        printOperationalCommands();
        println("");
        println("Project names must match [a-z][a-z0-9_]* (dashes are auto-rewritten to underscores).");
        println("Run 'bld list-tasks' (from inside CODE_RAG_HOME) for build/dev tasks.");
        println("");
    }

    public static void listTasks() {
        println("");
        printOperationalCommands();
        println("build                                     build the entire system but don't run it");
        println("war                                       create deployable war file");

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
        println("  -y, --yes                         skip the scan reconcile confirmation prompt");
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
        if (!ollamaPreflightCheck())
            return;
        buildSystem();
        setupTomcat();
        copyTree(BUILDDIR + "/exploded", "tomcat/webapps/ROOT");
        if (isWindows)
            runWait(true, "tomcat\\bin\\debug.cmd");
        else
            runWait(true, "tomcat/bin/debug");
        reassertMcpEntries();
        println("***** SERVER IS RUNNING *****");
        println("Server log can be viewed at " + cwd() + "/tomcat/logs/catalina.out or via the view-log command");
        println("The app can also be debugged at port " + debugPort);
        println("To stop the backend, type 'bld stop'");
    }

    /**
     * On every {@code bld start}, walk rag-projects.json and re-assert
     * each project's MCP entries with installed clients. Two things this
     * accomplishes:
     * <ol>
     *   <li><b>Migration from legacy user-scope registration.</b> Older
     *       releases wrote Claude Code entries at {@code --scope user},
     *       so every Claude Code session everywhere saw every project's
     *       tools. {@link #registerClaudeEntry} sweeps any user/local
     *       scope entry for the project name before adding the new
     *       project-scope {@code .mcp.json} in each root, which migrates
     *       existing installs in place.</li>
     *   <li><b>Self-repair after manual edits.</b> If the user has
     *       deleted a {@code .mcp.json} by hand or moved a project root,
     *       restarting the server re-creates the registration.</li>
     * </ol>
     * Idempotent — if everything is already in the right shape, nothing
     * visible happens. Silently skipped when neither {@code claude} nor
     * {@code codex} is installed.
     */
    private static void reassertMcpEntries() {
        boolean hasClaude = isOnPath("claude");
        boolean hasCodex  = isOnPath("codex")
                || new java.io.File(codexConfigPath()).exists();
        if (!hasClaude && !hasCodex)
            return;
        JSONObject cfg = loadProjectsJson();
        if (cfg == null)
            return;
        JSONArray projects = cfg.getJSONArray("projects");
        for (int i = 0; i < projects.length(); i++) {
            JSONObject p = projects.getJSONObject(i);
            if (!p.has("name"))
                continue;
            String name = p.getString("name");
            if (name == null || name.isEmpty())
                continue;
            JSONArray roots = p.has("roots") ? p.getJSONArray("roots") : new JSONArray();
            String projectDir = p.has("project_dir") ? p.getString("project_dir", null) : null;
            registerMcpEntries(name, roots, projectDir);
        }
        reassertAllProjectsEntry(hasClaude, hasCodex);
        writeRoutingRules();
    }

    /**
     * Drop a routing rule into every indexed root so Claude Code knows when to
     * reach for this tool at all.
     *
     * Without it the whole system is invisible in practice: the agent sees
     * search_code in its tool list, has no instruction about when it beats
     * Grep, and Grep is the reflex. Every retrieval-quality number is
     * conditional on the tool actually being called.
     *
     * Written to CLAUDE.local.md rather than CLAUDE.md deliberately:
     *  - CLAUDE.md is often committed and shared; this is a fact about THIS
     *    machine's local index, not about the repository.
     *  - Some repositories (Kiss) explicitly forbid editing their CLAUDE.md.
     *  - It keeps a tracked file from being modified in every indexed repo.
     */
    private static void writeRoutingRules() {
        if (!"true".equalsIgnoreCase(readIniValue("RAGWriteRoutingRules", "true")))
            return;
        JSONObject cfg = loadProjectsJson();
        if (cfg == null)
            return;
        JSONArray projects = cfg.getJSONArray("projects");
        java.util.Set<String> done = new java.util.HashSet<>();
        for (int i = 0; i < projects.length(); i++) {
            JSONObject p = projects.getJSONObject(i);
            JSONArray roots = p.has("roots") ? p.getJSONArray("roots") : new JSONArray();
            for (int j = 0; j < roots.length(); j++) {
                String root = roots.getString(j);
                if (root == null || root.isEmpty() || !done.add(root))
                    continue;
                java.io.File dir = new java.io.File(root);
                if (!dir.isDirectory())
                    continue;
                writeManagedBlock(new java.io.File(dir, "CLAUDE.local.md"));
            }
        }
    }

    /** MCP entry name for the cross-project endpoint. */
    private static final String ALL_MCP_NAME = "code_rag_all";

    /**
     * Re-assert the cross-project ({@code /rag-mcp/_all}) registration.
     *
     * Registered at USER scope for Claude Code rather than per-root: a tool
     * whose entire purpose is finding code in a repository you are NOT in is
     * useless if it is only visible from inside those repositories.
     *
     * Re-asserting on every start matters because the entry embeds the shared
     * secret. Rotate RAGMCPSharedSecret without this and both clients keep
     * sending the old token, failing 401 with no obvious cause.
     */
    private static void reassertAllProjectsEntry(boolean hasClaude, boolean hasCodex) {
        String url = MCP_BASE_URL + "/" + "_all";
        String secret = readSharedSecret();
        if (secret == null || secret.isEmpty())
            return;
        if (hasClaude) {
            java.io.File cwd = new java.io.File(".");
            // User scope is global, so the working directory is irrelevant —
            // any directory will do for the CLI invocation.
            runSilentInDir(cwd, "claude", "mcp", "remove", ALL_MCP_NAME, "-s", "user");
            String[] captured = new String[1];
            int code = runCapturedInDir(cwd, captured, "claude", "mcp", "add",
                    "--transport", "http", "-s", "user", ALL_MCP_NAME, url,
                    "--header", "X-RAG-Token: " + secret);
            if (code != 0)
                System.err.println("warning: could not register '" + ALL_MCP_NAME + "' with Claude Code.");
        }
        if (hasCodex)
            registerCodexEntry(ALL_MCP_NAME, url, secret);
    }

    /**
     * Confirm Ollama is reachable AND the configured embedding model is
     * installed, before bld start spends time building and launching
     * Tomcat. Reads OllamaURL and EmbeddingModel from application.ini
     * (with the same defaults the server uses). On any failure prints a
     * specific, actionable error to stderr and returns false so the
     * caller can abort.
     */
    private static boolean ollamaPreflightCheck() {
        java.util.Map<String, String> cfg = readIni("src/main/backend/application.ini");
        String url   = cfg.getOrDefault("OllamaURL", "http://127.0.0.1:11434");
        String model = cfg.getOrDefault("EmbeddingModel", "nomic-embed-text:v1.5");
        while (url.endsWith("/"))
            url = url.substring(0, url.length() - 1);

        String tagsUrl = url + "/api/tags";
        String resp;
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(tagsUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code != 200) {
                System.err.println("Ollama at " + url + " responded with HTTP " + code + " for " + tagsUrl + ".");
                System.err.println("Verify Ollama is running and accepting requests on that URL.");
                return false;
            }
            java.io.InputStream is = conn.getInputStream();
            byte[] data = is == null ? new byte[0] : is.readAllBytes();
            resp = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            System.err.println("Cannot reach Ollama at " + url + ": " + e.getMessage());
            System.err.println("Start Ollama (e.g. 'ollama serve' or via your service manager), then retry './bld start'.");
            return false;
        }

        // /api/tags returns {"models":[{"name":"<m>","model":"<m>",...},...]}.
        // The quoted model identifier is the safest substring check — it
        // appears as a JSON string value in both the "name" and "model" fields.
        if (!resp.contains("\"" + model + "\"")) {
            System.err.println("Ollama is up at " + url + ", but the configured embedding model");
            System.err.println("'" + model + "' (application.ini → EmbeddingModel) is not installed.");
            System.err.println("Install it with:");
            System.err.println("    ollama pull " + model);
            return false;
        }
        return true;
    }

    /**
     * Stop the backend development server
     */
    public static void stop() {
        // Sending SHUTDOWN to a port nothing is listening on makes Tomcat's
        // Bootstrap throw, burying "it was already stopped" under a SEVERE
        // stack trace. Check first and say so in one line instead.
        String httpP = extractFromFile("tomcat/conf/server.xml",
                "<Connector port=\"(\\d+)\" protocol=\"HTTP/1\\.1\"", httpPort);
        if (!portListening("127.0.0.1", parseIntOr(httpP, 0))) {
            println("Server is not running (nothing listening on port " + httpP + ") -- nothing to stop.");
            return;
        }
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
        // Re-parse positionals here so flags can appear in any order and so
        // "--full" is never mistaken for the project name.
        boolean full = false;
        String target = null;
        for (String a : commandArgs) {
            if ("--full".equals(a))
                full = true;
            else if (a.startsWith("-"))
                System.err.println("unknown option: " + a);
            else if (target == null)
                target = a;
        }
        if (target != null)
            scanTarget = target;
        scanFull = full;
        if (scanTarget == null || scanTarget.isEmpty() || scanTarget.startsWith("-")) {
            System.err.println("Usage: ./bld scan <project|all> [--full]");
            return;
        }
        String httpP = extractFromFile("tomcat/conf/server.xml",
                "<Connector port=\"(\\d+)\" protocol=\"HTTP/1\\.1\"", httpPort);
        int port = parseIntOr(httpP, 0);
        if (!portListening("127.0.0.1", port)) {
            System.err.println("Server is not running on port " + httpP + ". Start it with './bld start' first.");
            return;
        }
        String baseUrl = "http://127.0.0.1:" + httpP + "/rest";

        // Reconcile DB state with rag-projects.json BEFORE scanning, so a newly
        // added project gets a schema, a removed project is dropped, and roots
        // added/removed since the last scan are surfaced. Reconciliation may
        // expand the scan target list (new projects, projects with new roots).
        java.util.List<String> reconcileAdds = reconcileBeforeScan(baseUrl);
        if (reconcileAdds == null)
            return;  // aborted (user said no, or fatal error already reported)

        // After reconcile, rag-projects.json is the source of truth.
        java.util.List<String> known = readProjectNames("src/main/backend/rag-projects.json");
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
        if ("all".equalsIgnoreCase(scanTarget)) {
            if (known.isEmpty()) {
                System.err.println("No projects found in src/main/backend/rag-projects.json");
                return;
            }
            targets.addAll(known);
        } else {
            if (!known.isEmpty() && !known.contains(scanTarget))
                System.err.println("warning: '" + scanTarget + "' is not in rag-projects.json (known: " + known + ")");
            targets.add(scanTarget);
            // New projects + projects that gained roots must be scanned even
            // when the user named just one specific project.
            targets.addAll(reconcileAdds);
        }

        for (String p : targets) {
            scanOne(baseUrl, p);
        }
        println("");
    }

    /**
     * Phase 1 of {@link #scan()}: ask the server to compute a reconcile plan,
     * print it, prompt the user if it includes destructive operations (drops
     * or root deletes), then execute it. Returns the union of newly-created
     * projects and projects with newly-added roots so the scan target list
     * can be extended to include them. Returns {@code null} if the user
     * declined or a fatal error occurred (caller should abort the scan).
     */
    private static java.util.List<String> reconcileBeforeScan(String baseUrl) {
        String planBody = "{\"_method\":\"reconcile\",\"_class\":\"services/RAGAdmin\",\"dryRun\":true}";
        String planResp = httpPostJson(baseUrl, planBody, 60_000);
        if (planResp.startsWith("error:")) {
            System.err.println("reconcile (dry-run) failed: " + planResp);
            return null;
        }
        String err = extractJsonString(planResp, "error");
        if (err != null) {
            System.err.println("reconcile: " + err);
            return null;
        }

        String plan = unescapeJsonString(extractJsonString(planResp, "humanPlan"));
        boolean requiresConfirm = planResp.contains("\"requiresConfirm\":true")
                              || planResp.contains("\"requiresConfirm\": true");
        java.util.List<String> creates    = extractJsonStringArray(planResp, "createsNames");
        java.util.List<String> rootAddPrj = extractJsonStringArray(planResp, "rootAddsProjects");
        java.util.LinkedHashSet<String> additions = new java.util.LinkedHashSet<>();
        additions.addAll(creates);
        additions.addAll(rootAddPrj);

        boolean anything = (plan != null && !plan.isEmpty());
        if (!anything)
            return new java.util.ArrayList<>(additions);  // nothing to reconcile, no additions

        println("");
        println("Reconcile plan (DB vs rag-projects.json):");
        println(plan);

        if (requiresConfirm && !assumeYes) {
            print("Proceed with reconciliation? [y/N] ");
            String ans = readLineFromStdin();
            if (ans == null || !ans.trim().toLowerCase().startsWith("y")) {
                println("Reconciliation declined; skipping scan.");
                return null;
            }
        }

        String execBody = "{\"_method\":\"reconcile\",\"_class\":\"services/RAGAdmin\",\"dryRun\":false}";
        String execResp = httpPostJson(baseUrl, execBody, 300_000);
        if (execResp.startsWith("error:")) {
            System.err.println("reconcile (execute) failed: " + execResp);
            return null;
        }
        String err2 = extractJsonString(execResp, "error");
        if (err2 != null) {
            System.err.println("reconcile: " + err2);
            return null;
        }
        String summary = unescapeJsonString(extractJsonString(execResp, "humanPlan"));
        if (summary != null && !summary.isEmpty()) {
            println("Reconciliation done:");
            println(summary);
        }
        // Use the executed response's list — names actually created may
        // differ from the dry-run plan if anything was blocked.
        java.util.List<String> createsDone   = extractJsonStringArray(execResp, "createsNames");
        java.util.List<String> rootAddsAfter = extractJsonStringArray(execResp, "rootAddsProjects");
        java.util.LinkedHashSet<String> after = new java.util.LinkedHashSet<>();
        after.addAll(createsDone);
        after.addAll(rootAddsAfter);
        return new java.util.ArrayList<>(after);
    }

    /** Read one line from stdin; returns null on EOF or read error. */
    private static String readLineFromStdin() {
        try {
            return new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** print() without a trailing newline (BuildUtils.println always adds one). */
    private static void print(String s) {
        System.out.print(s);
        System.out.flush();
    }

    /** Reverse the standard JSON string escapes used in humanPlan; handles backslash-u escapes too. */
    private static String unescapeJsonString(String s) {
        if (s == null)
            return null;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n':  b.append('\n'); break;
                    case 't':  b.append('\t'); break;
                    case 'r':  b.append('\r'); break;
                    case '"':  b.append('"');  break;
                    case '\\': b.append('\\'); break;
                    case '/':  b.append('/');  break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException nfe) {
                                b.append('\\').append('u');
                            }
                        } else {
                            b.append('\\').append('u');
                        }
                        break;
                    default:   b.append('\\').append(n); break;
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Extract a flat JSON array of strings by key name. Returns empty list if absent. */
    private static java.util.List<String> extractJsonStringArray(String json, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find())
            return out;
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile("\"((?:\\\\\"|[^\"])*)\"").matcher(m.group(1));
        while (sm.find())
            out.add(unescapeJsonString(sm.group(1)));
        return out;
    }

    /** Reindex one project synchronously, printing progress as files/chunks accumulate. */
    private static void scanOne(String baseUrl, String project) {
        println("");
        println("Scanning " + project + (scanFull ? " (full rebuild)..." : "..."));

        String reindexBody = "{\"_method\":\"reindex\",\"_class\":\"services/RAGAdmin\",\"project\":\"" + project + "\""
                + (scanFull ? ",\"full\":true" : "") + "}";
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

    /** Issue a JSON POST with the default 15s read timeout. */
    private static String httpPostJson(String url, String body) {
        return httpPostJson(url, body, 15000);
    }

    /** Issue a JSON POST and return the response body (or "error: ..." on failure). */
    private static String httpPostJson(String url, String body, int readTimeoutMs) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(readTimeoutMs);
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

    // ----- Retrieval eval ----------------------------------------------------
    //
    // ./bld eval <project> [--baseline] [--compare <file>] [--queries <file>]
    //
    // Drives services/RAGAdmin.eval, which runs the golden query set through
    // the same RAGSearch code path the live MCP tool uses. Without --baseline,
    // the run is automatically diffed against eval/baseline.json when present,
    // because the delta is the only number that matters while tuning.

    private static final String EVAL_DIR = "eval";

    /**
     * Run the retrieval-quality eval for one project and report the scores.
     *
     * <p>Query set defaults to {@code eval/<project>-queries.jsonl}. Results
     * are always written to {@code eval/results-<timestamp>.json}; with
     * {@code --baseline} they are additionally written to
     * {@code eval/baseline.json}, which later runs diff against.</p>
     */
    public static void eval() {
        String project = null;
        String queriesPath = null;
        String comparePath = null;
        String granularity = "chunk";
        boolean makeBaseline = false;
        for (int i = 0; i < commandArgs.length; i++) {
            String a = commandArgs[i];
            if ("--baseline".equals(a)) {
                makeBaseline = true;
            } else if ("--compare".equals(a) && i + 1 < commandArgs.length) {
                comparePath = commandArgs[++i];
            } else if ("--queries".equals(a) && i + 1 < commandArgs.length) {
                queriesPath = commandArgs[++i];
            } else if ("--granularity".equals(a) && i + 1 < commandArgs.length) {
                granularity = commandArgs[++i];
            } else if (a.startsWith("-")) {
                System.err.println("unknown option: " + a);
                return;
            } else if (project == null) {
                project = a;
            }
        }
        if (project == null || project.isEmpty()) {
            System.err.println("Usage: ./bld eval <project> [--baseline] [--compare <file>] [--queries <file>]");
            return;
        }
        if (queriesPath == null)
            queriesPath = EVAL_DIR + "/" + project + "-queries.jsonl";
        java.io.File qf = new java.io.File(queriesPath);
        if (!qf.isFile()) {
            System.err.println("Query set not found: " + qf.getPath());
            System.err.println("Create it, or point at one with --queries <file>.");
            return;
        }

        String httpP = extractFromFile("tomcat/conf/server.xml",
                "<Connector port=\"(\\d+)\" protocol=\"HTTP/1\\.1\"", httpPort);
        if (!portListening("127.0.0.1", parseIntOr(httpP, 0))) {
            System.err.println("Server is not running on port " + httpP + ". Start it with './bld start' first.");
            return;
        }

        JSONObject req = new JSONObject();
        req.put("_class", "services/RAGAdmin");
        req.put("_method", "eval");
        req.put("project", project);
        req.put("queryFile", qf.getAbsolutePath());
        req.put("granularity", granularity);

        println("");
        println("Running eval: " + project + "  (" + qf.getPath() + ")");
        long t0 = System.currentTimeMillis();
        // Generous timeout: the first query pays Ollama's model load.
        String resp = httpPostJson("http://127.0.0.1:" + httpP + "/rest", req.toString(), 600_000);
        if (resp.startsWith("error:")) {
            System.err.println(resp);
            return;
        }
        JSONObject res;
        try {
            res = new JSONObject(resp);
        } catch (JSONException e) {
            System.err.println("Unparseable response: " + (resp.length() > 300 ? resp.substring(0, 300) + "…" : resp));
            return;
        }
        if (res.has("error")) {
            System.err.println("eval failed: " + res.getString("error"));
            return;
        }
        // Kiss reports transport/auth failures via _Success/_ErrorMessage rather
        // than the service's own "error" key. Without this the report below
        // silently prints an all-zero table.
        if (!res.getBoolean("_Success", Boolean.TRUE)) {
            System.err.println("eval failed: " + res.getString("_ErrorMessage", "(no message)"));
            if (res.getInt("_ErrorCode", 0) == 2)
                System.err.println("  services/RAGAdmin.eval must be allowlisted in KissInit.groovy "
                        + "via MainServlet.allowWithoutAuthentication.");
            return;
        }
        println("  completed in " + ((System.currentTimeMillis() - t0) / 1000L) + "s");

        printEvalReport(res);

        // Persist. Timestamp is filesystem-safe and sorts chronologically.
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        new java.io.File(EVAL_DIR).mkdirs();
        String resultsPath = EVAL_DIR + "/results-" + stamp + ".json";
        if (!writeTextFile(resultsPath, res.toString(2)))
            return;
        println("");
        println("  wrote " + resultsPath);
        if (makeBaseline) {
            if (writeTextFile(EVAL_DIR + "/baseline.json", res.toString(2)))
                println("  wrote " + EVAL_DIR + "/baseline.json  (future runs diff against this)");
        }

        // Diff against an explicit --compare target, else the baseline.
        String against = comparePath != null ? comparePath : (EVAL_DIR + "/baseline.json");
        if (!makeBaseline && new java.io.File(against).isFile()) {
            try {
                JSONObject base = new JSONObject(readTextFile(against));
                printEvalDiff(base, res, against);
            } catch (Exception e) {
                System.err.println("  could not diff against " + against + ": " + e.getMessage());
            }
        }
        println("");
    }

    /**
     * Index VCS commit history (git and Subversion) for one project or all.
     * Incremental after the first run.
     */
    public static void history() {
        java.util.List<String> targets = resolveProjectTargets("history");
        if (targets == null)
            return;
        String baseUrl = liveRestUrl();
        if (baseUrl == null)
            return;
        for (String p : targets) {
            JSONObject req = new JSONObject();
            req.put("_class", "services/RAGAdmin");
            req.put("_method", "history");
            req.put("project", p);
            println("");
            println("Indexing history for " + p + "...");
            String resp = httpPostJson(baseUrl, req.toString(), 60_000);
            JSONObject r = parseOrNull(resp);
            if (r == null || !r.getBoolean("started", Boolean.FALSE)) {
                System.err.println("  " + (r == null ? resp : r.getString("message", resp)));
                continue;
            }
            pollUntilIdle(baseUrl, p);
        }
        println("");
    }

    /**
     * Report real usage: is anything actually calling the tool?
     *
     * Retrieval metrics say the tool can find things; this says whether Claude
     * Code is asking it to. If searches stay at zero, ranking work is not
     * reaching the agent and the routing rules are the thing to fix.
     */
    public static void usage() {
        java.util.List<String> targets = resolveProjectTargets("usage");
        if (targets == null)
            return;
        String baseUrl = liveRestUrl();
        if (baseUrl == null)
            return;
        for (String p : targets) {
            JSONObject req = new JSONObject();
            req.put("_class", "services/RAGAdmin");
            req.put("_method", "usage");
            req.put("project", p);
            JSONObject r = parseOrNull(httpPostJson(baseUrl, req.toString(), 120_000));
            if (r == null || r.has("error")) {
                System.err.println(p + ": " + (r == null ? "(no response)" : r.getString("error")));
                continue;
            }
            long searches = r.getLong("searches", 0L);
            long fetches = r.getLong("fetches", 0L);
            println("");
            println("  " + p);
            println("    searches: " + searches + "   follow-up fetches: " + fetches
                    + "   zero-result: " + r.getLong("zeroResultSearches", 0L)
                    + "   avg " + r.getLong("avgLatencyMs", 0L) + "ms");
            if (searches == 0) {
                println("    NOT BEING USED. Retrieval quality is irrelevant until something calls it —");
                println("    check the routing block in <root>/CLAUDE.local.md and the MCP registration.");
                continue;
            }
            println(String.format("    follow-up rate: %.0f%% (how often a result was actually opened)",
                    100.0 * fetches / searches));
            JSONArray daily = r.getJSONArray("daily");
            if (daily != null && daily.length() > 0) {
                println("    recent days:");
                for (int i = 0; i < Math.min(7, daily.length()); i++) {
                    JSONObject d = daily.getJSONObject(i);
                    println(String.format("      %s  %4d searches  %4d fetches",
                            d.getString("date", "?"), d.getLong("searches", 0L), d.getLong("fetches", 0L)));
                }
            }
            JSONArray top = r.getJSONArray("topQueries");
            if (top != null && top.length() > 0) {
                println("    most frequent queries:");
                for (int i = 0; i < Math.min(5, top.length()); i++) {
                    JSONObject q = top.getJSONObject(i);
                    String qq = q.getString("query", "");
                    println(String.format("      %2dx  %s", q.getLong("count", 0L),
                            qq.length() > 62 ? qq.substring(0, 62) + "…" : qq));
                }
            }
        }
        println("");
    }

    /** Backfill the import graph for files indexed before it existed. */
    public static void deps() {
        java.util.List<String> targets = resolveProjectTargets("deps");
        if (targets == null)
            return;
        String baseUrl = liveRestUrl();
        if (baseUrl == null)
            return;
        for (String p : targets) {
            JSONObject req = new JSONObject();
            req.put("_class", "services/RAGAdmin");
            req.put("_method", "deps");
            req.put("project", p);
            println("Building import graph for " + p + "...");
            JSONObject r = parseOrNull(httpPostJson(baseUrl, req.toString(), 900_000));
            if (r == null || r.has("error")) {
                System.err.println("  failed: " + (r == null ? "(no response)" : r.getString("error")));
                continue;
            }
            println("  " + r.getLong("edges", 0L) + " import edges from "
                    + r.getInt("filesScanned", 0) + " files in " + r.getLong("elapsedSec", 0L) + "s");
        }
        println("");
    }

    /** Extract symbol definitions with ctags into rag_def (the code-graph index). */
    public static void defs() {
        java.util.List<String> targets = resolveProjectTargets("defs");
        if (targets == null)
            return;
        String baseUrl = liveRestUrl();
        if (baseUrl == null)
            return;
        for (String p : targets) {
            JSONObject req = new JSONObject();
            req.put("_class", "services/RAGAdmin");
            req.put("_method", "defs");
            req.put("project", p);
            println("Extracting definitions for " + p + "...");
            JSONObject r = parseOrNull(httpPostJson(baseUrl, req.toString(), 900_000));
            if (r == null || r.has("error")) {
                System.err.println("  failed: " + (r == null ? "(no response)" : r.getString("error")));
                continue;
            }
            println("  " + r.getInt("definitions", 0) + " definitions across "
                    + r.getInt("roots", 0) + " root(s) in " + r.getLong("elapsedSec", 0L) + "s");
            String detail = r.getString("detail", "");
            if (!detail.isEmpty())
                println("  " + detail);
        }
        println("");
    }

    /**
     * Generate per-file LLM summaries. Long-running (roughly a second per
     * file) and GPU-bound; polls until each project finishes.
     */
    public static void summarize() {
        java.util.List<String> targets = resolveProjectTargets("summarize");
        if (targets == null)
            return;
        String baseUrl = liveRestUrl();
        if (baseUrl == null)
            return;
        boolean regenerate = false;
        boolean symbols = false;
        for (String a : commandArgs) {
            if ("--regenerate".equals(a))
                regenerate = true;
            else if ("--symbols".equals(a))
                symbols = true;
        }

        for (String p : targets) {
            JSONObject req = new JSONObject();
            req.put("_class", "services/RAGAdmin");
            req.put("_method", "summarize");
            req.put("project", p);
            req.put("regenerate", regenerate);
            req.put("symbols", symbols);
            println("");
            println("Summarizing " + p + (symbols ? " (per-symbol, large files only)..."
                                                  : regenerate ? " (regenerate all)..." : "..."));
            String resp = httpPostJson(baseUrl, req.toString(), 60_000);
            JSONObject r = parseOrNull(resp);
            if (r == null || !r.getBoolean("started", Boolean.FALSE)) {
                System.err.println("  " + (r == null ? resp : r.getString("message", resp)));
                continue;
            }
            pollUntilIdle(baseUrl, p);
        }
        println("");
    }

    /**
     * Poll status until the project's background job finishes, printing any
     * progress it reports. Shared by history and summarize — both run behind
     * the same per-project lock, so "indexing" going false means done.
     */
    private static void pollUntilIdle(String baseUrl, String project) {
        JSONObject sreq = new JSONObject();
        sreq.put("_class", "services/RAGAdmin");
        sreq.put("_method", "status");
        sreq.put("project", project);
        long start = System.currentTimeMillis();
        while (true) {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            String resp = httpPostJson(baseUrl, sreq.toString(), 60_000);
            JSONObject r = parseOrNull(resp);
            if (r == null) {
                System.err.println("  status poll failed: " + resp);
                return;
            }
            long elapsed = (System.currentTimeMillis() - start) / 1000L;
            JSONObject meta = r.has("meta") ? r.getJSONObject("meta") : null;
            String prog = meta == null ? null : meta.getString("summarize_progress", null);
            if (prog != null) {
                JSONObject p = parseOrNull(prog);
                if (p != null)
                    println(String.format("  [%-8s]  %d/%d files  (eta %s)",
                            humanDuration(elapsed), p.getInt("done", 0), p.getInt("total", 0),
                            humanDuration(p.getInt("etaSec", 0))));
            }
            if (!r.getBoolean("indexing", Boolean.FALSE)) {
                println(String.format("  [%-8s]  DONE", humanDuration(elapsed)));
                return;
            }
        }
    }

    /** Shared "<project|all>" positional resolution for history/summarize. */
    private static java.util.List<String> resolveProjectTargets(String cmd) {
        String target = null;
        for (String a : commandArgs)
            if (!a.startsWith("-") && target == null)
                target = a;
        if (target == null || target.isEmpty()) {
            System.err.println("Usage: ./bld " + cmd + " <project|all>");
            return null;
        }
        java.util.List<String> known = readProjectNames(PROJECTS_JSON_PATH);
        if ("all".equalsIgnoreCase(target)) {
            if (known.isEmpty()) {
                System.err.println("No projects found in " + PROJECTS_JSON_PATH);
                return null;
            }
            return known;
        }
        if (!known.isEmpty() && !known.contains(target))
            System.err.println("warning: '" + target + "' is not in rag-projects.json (known: " + known + ")");
        return java.util.Collections.singletonList(target);
    }

    /** Base /rest URL of the running server, or null (with a message) if it is down. */
    private static String liveRestUrl() {
        String httpP = extractFromFile("tomcat/conf/server.xml",
                "<Connector port=\"(\\d+)\" protocol=\"HTTP/1\\.1\"", httpPort);
        if (!portListening("127.0.0.1", parseIntOr(httpP, 0))) {
            System.err.println("Server is not running on port " + httpP + ". Start it with './bld start' first.");
            return null;
        }
        return "http://127.0.0.1:" + httpP + "/rest";
    }

    /**
     * Parse a JSON-RPC reply, returning null on transport failure OR on a Kiss
     * framework-level failure. Without the {@code _Success} check a rejected
     * call parses cleanly, yields no fields, and the caller cheerfully reports
     * zeros — which is exactly how an auth failure once looked like "0 commits
     * across 0 roots".
     */
    private static JSONObject parseOrNull(String s) {
        if (s == null || s.startsWith("error:"))
            return null;
        JSONObject o;
        try {
            o = new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
        if (!o.getBoolean("_Success", Boolean.TRUE)) {
            System.err.println("  request rejected: " + o.getString("_ErrorMessage", "(no message)"));
            if (o.getInt("_ErrorCode", 0) == 2)
                System.err.println("  (the service must be allowlisted in KissInit.groovy via "
                        + "MainServlet.allowWithoutAuthentication)");
            return null;
        }
        return o;
    }

    /** Print the overall / per-split / per-tag score table. */
    private static void printEvalReport(JSONObject res) {
        JSONObject cfg = res.has("config") ? res.getJSONObject("config") : new JSONObject();
        println("");
        println("  " + res.getInt("queryCount", 0) + " queries, chunkK=" + res.getInt("chunkK", 0)
                + ", model=" + cfg.getString("embeddingModel", "?")
                + ", ef_search=" + cfg.getString("hnswEfSearch", "?"));
        println("  index: " + cfg.getLong("fileCount", 0L) + " files, "
                + cfg.getLong("chunkCount", 0L) + " chunks");
        println("");
        println(String.format("  %-16s %4s  %6s %6s %6s  %9s %7s %8s %5s  %6s %6s",
                "scope", "n", "hit@1", "hit@5", "hit@10", "recall@10", "mrr@10", "ndcg@10", "miss", "p50ms", "p95ms"));
        printEvalRow("overall", res.getJSONObject("overall"));
        if (res.has("bySplit")) {
            JSONObject bs = res.getJSONObject("bySplit");
            for (String k : new java.util.TreeSet<>(bs.keySet()))
                printEvalRow("split:" + k, bs.getJSONObject(k));
        }
        if (res.has("byTag")) {
            JSONObject bt = res.getJSONObject("byTag");
            for (String k : new java.util.TreeSet<>(bt.keySet()))
                printEvalRow("tag:" + k, bt.getJSONObject(k));
        }
    }

    private static void printEvalRow(String label, JSONObject m) {
        println(String.format("  %-16s %4d  %6.3f %6.3f %6.3f  %9.3f %7.3f %8.3f %5d  %6d %6d",
                label,
                m.getInt("n", 0),
                m.getDouble("hit@1", 0.0),
                m.getDouble("hit@5", 0.0),
                m.getDouble("hit@10", 0.0),
                m.getDouble("recall@10", 0.0),
                m.getDouble("mrr@10", 0.0),
                m.getDouble("ndcg@10", 0.0),
                m.getInt("totalMisses", 0),
                m.getLong("p50TotalMs", 0L),
                m.getLong("p95TotalMs", 0L)));
    }

    /**
     * Print metric deltas plus the per-query rank movements. The per-query
     * list is what you actually use while tuning — an aggregate that improves
     * while five specific queries regress is worth knowing about.
     */
    private static void printEvalDiff(JSONObject base, JSONObject now, String againstPath) {
        println("");
        println("  vs " + againstPath + ":");
        JSONObject bo = base.getJSONObject("overall");
        JSONObject no = now.getJSONObject("overall");
        String[] metrics = {"hit@1", "hit@5", "hit@10", "recall@10", "mrr@10", "ndcg@10"};
        println(String.format("    %-12s %9s %9s %9s", "metric", "baseline", "now", "delta"));
        for (String k : metrics) {
            double b = bo.getDouble(k, 0.0);
            double n = no.getDouble(k, 0.0);
            println(String.format("    %-12s %9.3f %9.3f %+9.3f", k, b, n, n - b));
        }
        long bp = bo.getLong("p50TotalMs", 0L), np = no.getLong("p50TotalMs", 0L);
        println(String.format("    %-12s %9d %9d %+9d", "p50ms", bp, np, np - bp));

        // Per-query rank movement, keyed by query text.
        java.util.Map<String, Integer> baseRank = new java.util.HashMap<>();
        JSONArray bq = base.getJSONArray("perQuery");
        for (int i = 0; i < bq.length(); i++) {
            JSONObject r = bq.getJSONObject(i);
            baseRank.put(r.getString("q"), r.getInt("firstRelevantRank", 0));
        }
        java.util.List<String> better = new java.util.ArrayList<>();
        java.util.List<String> worse = new java.util.ArrayList<>();
        int same = 0;
        JSONArray nq = now.getJSONArray("perQuery");
        for (int i = 0; i < nq.length(); i++) {
            JSONObject r = nq.getJSONObject(i);
            String q = r.getString("q");
            if (!baseRank.containsKey(q))
                continue;
            int b = baseRank.get(q);
            int n = r.getInt("firstRelevantRank", 0);
            if (b == n) {
                same++;
                continue;
            }
            // Rank 0 means "never found"; treat it as worse than any real rank.
            int bEff = (b == 0) ? Integer.MAX_VALUE : b;
            int nEff = (n == 0) ? Integer.MAX_VALUE : n;
            String line = String.format("      %-6s %s -> %s   \"%s\"",
                    nEff < bEff ? "better" : "worse",
                    b == 0 ? "miss" : Integer.toString(b),
                    n == 0 ? "miss" : Integer.toString(n),
                    q.length() > 64 ? q.substring(0, 64) + "…" : q);
            if (nEff < bEff)
                better.add(line);
            else
                worse.add(line);
        }
        println("");
        println("    rank of first expected file:  better=" + better.size()
                + "  worse=" + worse.size() + "  unchanged=" + same);
        for (String s : worse)
            println(s);
        for (String s : better)
            println(s);
    }

    private static boolean writeTextFile(String path, String content) {
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(path),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (java.io.IOException e) {
            System.err.println("cannot write " + path + ": " + e.getMessage());
            return false;
        }
    }

    private static String readTextFile(String path) throws java.io.IOException {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                java.nio.charset.StandardCharsets.UTF_8);
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
    // ----- Project / root management ----------------------------------------
    //
    // These four tasks edit src/main/backend/rag-projects.json, sync the
    // runtime copies under work/exploded and tomcat/webapps so the running
    // server picks them up, drive bld scan (which reconciles + indexes),
    // and (for new/remove-project) maintain MCP entries in whichever
    // clients are installed (Claude Code, Codex CLI).

    private static final String PROJECTS_JSON_PATH = "src/main/backend/rag-projects.json";
    private static final String APP_INI_PATH       = "src/main/backend/application.ini";
    private static final String[] RUNTIME_PROJECTS_COPIES = {
            "work/exploded/WEB-INF/backend/rag-projects.json",
            "tomcat/webapps/ROOT/WEB-INF/backend/rag-projects.json"
    };
    private static final String MCP_BASE_URL = "http://127.0.0.1:17080/rag-mcp";
    private static final java.util.regex.Pattern PROJECT_NAME_RE =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]*");

    /**
     * Add a new project to rag-projects.json with the given roots, sync
     * runtime copies, scan it (which bootstraps the schema via reconcile
     * + indexes the roots), and register MCP entries with any installed
     * clients.
     */
    public static void newProject() {
        String[] projectDirHolder = new String[1];
        String[] remaining = consumeProjectDirFlag(commandArgs, projectDirHolder);
        if (remaining.length < 2) {
            System.err.println("Usage: ./bld new-project <name> [--project-dir <dir>] <root> [<root>...]");
            System.exit(1);
        }
        String name = canonicalizeProjectName(remaining[0]);
        if (!requireValidProjectName(name))
            System.exit(1);
        JSONObject cfg = loadProjectsJson();
        if (cfg == null)
            System.exit(1);
        if (findProject(cfg, name) != null) {
            System.err.println("Project '" + name + "' already exists in rag-projects.json (use add-root to extend it).");
            System.exit(1);
        }
        JSONArray roots = resolveRootsStrict(remaining, 1);
        if (roots == null)
            System.exit(1);
        String projectDir = null;
        if (projectDirHolder[0] != null) {
            projectDir = validateProjectDir(projectDirHolder[0], roots);
            if (projectDir == null)
                System.exit(1);
        }
        JSONObject project = new JSONObject();
        project.put("name", name);
        if (projectDir != null)
            project.put("project_dir", projectDir);
        project.put("roots", roots);
        cfg.getJSONArray("projects").put(project);
        if (!saveProjectsJson(cfg))
            System.exit(1);
        syncProjectsRuntimeCopies();
        scanTarget = name;
        scan();
        registerMcpEntries(name, roots, projectDir);
    }

    /**
     * Remove a project from rag-projects.json (refuses on the last
     * remaining project), sync runtime copies, deregister MCP entries
     * from installed clients, then run bld -y scan all so reconcile
     * drops the schema without re-prompting.
     */
    public static void removeProject() {
        if (commandArgs.length != 1) {
            System.err.println("Usage: ./bld remove-project <name>");
            System.exit(1);
        }
        String name = canonicalizeProjectName(commandArgs[0]);
        if (!requireValidProjectName(name))
            System.exit(1);
        JSONObject cfg = loadProjectsJson();
        if (cfg == null)
            System.exit(1);
        if (findProject(cfg, name) == null) {
            System.err.println("Project '" + name + "' is not in rag-projects.json.");
            System.exit(1);
        }
        JSONArray projects = cfg.getJSONArray("projects");
        if (projects.length() == 1) {
            System.err.println("Cannot remove '" + name + "': it is the only configured project.");
            System.err.println("Add another project first, or drop the schema manually with:");
            System.err.println("    psql -d code_rag -c 'DROP SCHEMA " + name + " CASCADE'");
            System.exit(1);
        }
        // Capture roots + project_dir before mutating cfg — we need them to
        // know which .mcp.json files (and which CLAUDE.local.md block) to clean up
        // after the project is gone from the JSON.
        JSONObject existingProj = findProject(cfg, name);
        JSONArray departingRoots = existingProj.getJSONArray("roots");
        String departingProjectDir = existingProj.has("project_dir")
                ? existingProj.getString("project_dir", null) : null;
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < projects.length(); i++) {
            JSONObject p = projects.getJSONObject(i);
            if (!name.equals(p.getString("name")))
                remaining.put(p);
        }
        cfg.put("projects", remaining);
        if (!saveProjectsJson(cfg))
            System.exit(1);
        syncProjectsRuntimeCopies();
        deregisterMcpEntries(name, departingRoots, departingProjectDir);
        // bld -y scan all so reconcile drops the schema without prompting
        // (the user already committed by typing remove-project).
        assumeYes = true;
        scanTarget = "all";
        scan();
    }

    /**
     * Add one or more roots to an existing project. Rejects duplicates.
     */
    public static void addRoot() {
        if (commandArgs.length < 2) {
            System.err.println("Usage: ./bld add-root <name> <root> [<root>...]");
            System.exit(1);
        }
        String name = canonicalizeProjectName(commandArgs[0]);
        if (!requireValidProjectName(name))
            System.exit(1);
        JSONObject cfg = loadProjectsJson();
        if (cfg == null)
            System.exit(1);
        JSONObject project = findProject(cfg, name);
        if (project == null) {
            System.err.println("Project '" + name + "' is not in rag-projects.json (use new-project to create it).");
            System.exit(1);
        }
        JSONArray newRoots = resolveRootsStrict(commandArgs, 1);
        if (newRoots == null)
            System.exit(1);
        JSONArray existing = project.getJSONArray("roots");
        java.util.Set<String> existingSet = new java.util.HashSet<>();
        for (int i = 0; i < existing.length(); i++)
            existingSet.add(existing.getString(i));
        java.util.List<String> dupes = new java.util.ArrayList<>();
        for (int i = 0; i < newRoots.length(); i++)
            if (existingSet.contains(newRoots.getString(i)))
                dupes.add(newRoots.getString(i));
        if (!dupes.isEmpty()) {
            System.err.println("Already configured for '" + name + "':");
            for (String d : dupes)
                System.err.println("    " + d);
            System.exit(1);
        }
        for (int i = 0; i < newRoots.length(); i++)
            existing.put(newRoots.getString(i));
        if (!saveProjectsJson(cfg))
            System.exit(1);
        syncProjectsRuntimeCopies();
        scanTarget = name;
        scan();
        // Drop a .mcp.json into each newly added root so Claude Code sessions
        // launched from there see the project's tools. Existing roots already
        // have their .mcp.json from new-project (or a previous bld start
        // migration pass), so we only register the new ones. The project_dir
        // (if any) is also already registered — pass null so we don't
        // re-write the CLAUDE.local.md block on every add-root.
        registerMcpEntries(name, newRoots, null);
    }

    /**
     * Remove one or more roots from a project. Rejects roots that aren't
     * currently configured. Refuses to leave the project with zero roots.
     * Lenient about path existence — the directory may be gone on disk.
     */
    public static void removeRoot() {
        if (commandArgs.length < 2) {
            System.err.println("Usage: ./bld remove-root <name> <root> [<root>...]");
            System.exit(1);
        }
        String name = canonicalizeProjectName(commandArgs[0]);
        if (!requireValidProjectName(name))
            System.exit(1);
        JSONObject cfg = loadProjectsJson();
        if (cfg == null)
            System.exit(1);
        JSONObject project = findProject(cfg, name);
        if (project == null) {
            System.err.println("Project '" + name + "' is not in rag-projects.json.");
            System.exit(1);
        }
        JSONArray toRemove = resolveRootsLenient(commandArgs, 1);
        JSONArray existing = project.getJSONArray("roots");
        java.util.Set<String> existingSet = new java.util.HashSet<>();
        for (int i = 0; i < existing.length(); i++)
            existingSet.add(existing.getString(i));
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (int i = 0; i < toRemove.length(); i++)
            if (!existingSet.contains(toRemove.getString(i)))
                missing.add(toRemove.getString(i));
        if (!missing.isEmpty()) {
            System.err.println("Not currently configured for '" + name + "':");
            for (String m : missing)
                System.err.println("    " + m);
            System.exit(1);
        }
        java.util.Set<String> removeSet = new java.util.HashSet<>();
        for (int i = 0; i < toRemove.length(); i++)
            removeSet.add(toRemove.getString(i));
        JSONArray kept = new JSONArray();
        for (int i = 0; i < existing.length(); i++)
            if (!removeSet.contains(existing.getString(i)))
                kept.put(existing.getString(i));
        if (kept.length() == 0) {
            System.err.println("Cannot remove all roots from '" + name + "'. Use remove-project to drop it entirely, or add a new root first.");
            System.exit(1);
        }
        project.put("roots", kept);
        if (!saveProjectsJson(cfg))
            System.exit(1);
        syncProjectsRuntimeCopies();
        scanTarget = name;
        scan();
        // Clean up the .mcp.json entry from each removed root. The project
        // itself still exists, so we only deregister from the departing roots
        // — the surviving roots keep their entries.
        boolean hasClaude = isOnPath("claude");
        if (hasClaude) {
            for (int i = 0; i < toRemove.length(); i++) {
                java.io.File root = new java.io.File(toRemove.getString(i));
                if (!root.isDirectory())
                    continue;
                if (runSilentInDir(root, "claude", "mcp", "remove", name, "-s", "project") == 0)
                    println("removed MCP entry from Claude Code (project scope) in " + root.getAbsolutePath());
            }
        }
    }

    // ----- Project-management helpers -----

    /**
     * Project names become PostgreSQL schema identifiers, which can't
     * contain dashes. Dash is the only character with an obvious
     * unambiguous fix (underscore), so we silently rewrite it and
     * print a note. Other invalid characters fall through to
     * {@link #requireValidProjectName} which prints the rule.
     */
    private static String canonicalizeProjectName(String name) {
        if (name == null || name.indexOf('-') < 0)
            return name;
        String fixed = name.replace('-', '_');
        println("note: project name '" + name + "' rewritten to '" + fixed
                + "' (dashes are not valid in PostgreSQL schema names).");
        return fixed;
    }

    /**
     * Validate a project name against the same {@code [a-z][a-z0-9_]*}
     * rule the server enforces. On failure, print the rule, why it
     * matters (becomes a PostgreSQL schema name), and a sanitized
     * suggestion when one can be safely derived.
     */
    private static boolean requireValidProjectName(String name) {
        // "all" is already the wildcard for `bld scan all`, and it reads as the
        // cross-project endpoint (/rag-mcp/_all). A project literally named
        // "all" would make both ambiguous.
        if ("all".equals(name)) {
            System.err.println("Project name 'all' is reserved: 'bld scan all' means every project, "
                    + "and cross-project search lives at /rag-mcp/_all.");
            return false;
        }
        if (PROJECT_NAME_RE.matcher(name).matches())
            return true;
        String suggested = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        System.err.println("Project name '" + name + "' is invalid.");
        System.err.println("Names become PostgreSQL schema names — they must match [a-z][a-z0-9_]*");
        System.err.println("(lowercase letters, digits, underscores; must start with a letter).");
        if (!suggested.isEmpty() && !suggested.equals(name)
                && PROJECT_NAME_RE.matcher(suggested).matches()) {
            System.err.println("Try '" + suggested + "' instead.");
        }
        return false;
    }

    /** Load rag-projects.json into a JSONObject, or null on read/parse error (with stderr msg). */
    private static JSONObject loadProjectsJson() {
        java.nio.file.Path p = java.nio.file.Paths.get(PROJECTS_JSON_PATH);
        if (!java.nio.file.Files.exists(p)) {
            System.err.println(PROJECTS_JSON_PATH + " does not exist.");
            return null;
        }
        try {
            String content = new String(java.nio.file.Files.readAllBytes(p),
                    java.nio.charset.StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(content);
            if (!obj.has("projects") || !(obj.get("projects") instanceof JSONArray)) {
                System.err.println(PROJECTS_JSON_PATH + " has no top-level 'projects' array.");
                return null;
            }
            return obj;
        } catch (java.io.IOException | JSONException e) {
            System.err.println("Cannot read/parse " + PROJECTS_JSON_PATH + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Write the JSON back atomically. Temp file is created in the same
     * directory as the target so the rename stays on one filesystem
     * (ATOMIC_MOVE fails across mount points — e.g. when /tmp is tmpfs
     * and the repo lives on a separate disk).
     */
    private static boolean saveProjectsJson(JSONObject obj) {
        String content = obj.toString(2);
        java.nio.file.Path dest = java.nio.file.Paths.get(PROJECTS_JSON_PATH);
        java.nio.file.Path dir  = dest.getParent();
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile(dir, "rag-projects-", ".json.tmp");
            java.nio.file.Files.write(tmp, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.move(tmp, dest,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (java.io.IOException e) {
            System.err.println("Cannot write " + PROJECTS_JSON_PATH + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Copy the canonical rag-projects.json into the runtime locations
     * (work/exploded and tomcat/webapps) so the running server picks up
     * the change. ProjectRegistry reads from the deployed copy. Each
     * destination is skipped with a warning if its parent doesn't exist
     * (e.g. server has never been built).
     */
    private static void syncProjectsRuntimeCopies() {
        for (String dest : RUNTIME_PROJECTS_COPIES) {
            java.nio.file.Path destPath = java.nio.file.Paths.get(dest);
            java.nio.file.Path parent = destPath.getParent();
            if (parent == null || !java.nio.file.Files.isDirectory(parent)) {
                println("warning: " + parent + " does not exist; skipped (start the server once to create it)");
                continue;
            }
            try {
                java.nio.file.Files.copy(java.nio.file.Paths.get(PROJECTS_JSON_PATH),
                        destPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                System.err.println("warning: copy to " + dest + " failed: " + e.getMessage());
            }
        }
    }

    private static JSONObject findProject(JSONObject cfg, String name) {
        JSONArray arr = cfg.getJSONArray("projects");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (name.equals(p.getString("name", null)))
                return p;
        }
        return null;
    }

    /**
     * Resolve each positional arg from {@code args[offset..]} to an
     * absolute canonical path. Requires each to be an existing
     * directory. Returns null and prints an error on the first
     * unresolvable / non-directory entry. The returned JSONArray
     * contains String entries suitable for inserting directly into
     * rag-projects.json.
     */
    private static JSONArray resolveRootsStrict(String[] args, int offset) {
        JSONArray out = new JSONArray();
        for (int i = offset; i < args.length; i++) {
            java.io.File f = new java.io.File(args[i]);
            String abs;
            try {
                abs = f.getCanonicalPath();
            } catch (java.io.IOException e) {
                System.err.println("Cannot resolve root '" + args[i] + "': " + e.getMessage());
                return null;
            }
            if (!new java.io.File(abs).isDirectory()) {
                System.err.println("Root does not exist or is not a directory: " + args[i]);
                return null;
            }
            out.put(abs);
        }
        return out;
    }

    /**
     * Pop {@code --project-dir <dir>} from {@code args} (anywhere in the
     * positional list). The extracted value (or null) is returned via
     * {@code out[0]}; the rest of the positionals come back as a fresh
     * array. Exits with an error if the flag appears more than once or
     * has no value.
     */
    private static String[] consumeProjectDirFlag(String[] args, String[] out) {
        out[0] = null;
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--project-dir".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("--project-dir requires a value.");
                    System.exit(1);
                }
                if (out[0] != null) {
                    System.err.println("--project-dir specified more than once.");
                    System.exit(1);
                }
                out[0] = args[i + 1];
                i++;
            } else {
                kept.add(args[i]);
            }
        }
        return kept.toArray(new String[0]);
    }

    /**
     * Resolve {@code --project-dir <dir>} to an absolute canonical path
     * and validate it. Returns the canonical path, or null with a stderr
     * message on failure. Rules:
     * <ul>
     *   <li>must exist as a directory</li>
     *   <li>must not equal any configured root (would duplicate the
     *       .mcp.json that's already written there)</li>
     *   <li>must not be {@code $HOME} or {@code /} — both are too
     *       broad to safely drop a managed CLAUDE.local.md block into</li>
     * </ul>
     */
    private static String validateProjectDir(String raw, JSONArray roots) {
        java.io.File f = new java.io.File(raw);
        String abs;
        try {
            abs = f.getCanonicalPath();
        } catch (java.io.IOException e) {
            System.err.println("Cannot resolve --project-dir '" + raw + "': " + e.getMessage());
            return null;
        }
        if (!new java.io.File(abs).isDirectory()) {
            System.err.println("--project-dir does not exist or is not a directory: " + raw);
            return null;
        }
        String home = System.getProperty("user.home");
        if (abs.equals(home) || abs.equals("/")) {
            System.err.println("--project-dir refuses '" + abs
                    + "': too broad to drop a managed CLAUDE.local.md block into.");
            return null;
        }
        if (roots != null) {
            for (int i = 0; i < roots.length(); i++) {
                if (abs.equals(roots.getString(i))) {
                    System.err.println("--project-dir equals a configured root: " + abs);
                    System.err.println("That root already gets its own .mcp.json — specifying it as project_dir is redundant.");
                    return null;
                }
            }
        }
        return abs;
    }

    // ----- CLAUDE.local.md managed block (at project_dir) -----

    /**
     * When project_dir is set, bld drops a managed block into
     * {@code <project_dir>/CLAUDE.local.md} that biases Claude Code toward
     * the MCP search_code tool for conceptual queries and toward Grep
     * for literal-token lookups. Without this block the .mcp.json
     * alone tends not to shift Claude Code's tool-selection heuristics
     * — see Setup.md / ClaudeCode.md for the rationale.
     *
     * <p>The block is delimited by stable HTML comments so we can find
     * and rewrite (or excise) it on subsequent runs. Anything outside
     * the markers — including a user's hand-written content —
     * is left untouched.</p>
     */
    private static final String CLAUDE_MD_BLOCK_BEGIN =
            "<!-- BEGIN code-rag managed block — do not edit between markers; bld rewrites it -->";
    private static final String CLAUDE_MD_BLOCK_END =
            "<!-- END code-rag managed block -->";
    private static final String CLAUDE_MD_BLOCK_BODY =
            "This repository is indexed by Code-RAG, a local MCP server. Choose your\n" +
            "search tool by WHAT YOU KNOW, not by habit:\n" +
            "\n" +
            "- You know the exact token (symbol, string, file name, import) →\n" +
            "  **Grep / Glob**. Nothing beats it when you can name the thing.\n" +
            "- You know the CONCEPT but not the token (\"where do we enforce the\n" +
            "  session timeout\", \"what calculates benefit cost\", \"how is X wired\n" +
            "  up\") → **`search_code`**. Guessing keywords for Grep is the failure\n" +
            "  mode it exists to remove.\n" +
            "- You want to know WHY something is the way it is, when it changed, or\n" +
            "  what moved with it → **`search_history`** (indexed git and Subversion\n" +
            "  commit messages). No amount of reading the tree answers this.\n" +
            "\n" +
            "`search_code` returns ranked FILES by default, each with the symbols that\n" +
            "matched and an excerpt already widened to the whole enclosing function, so\n" +
            "one call is usually enough — Read the returned path and line range only\n" +
            "when you need more context around it. It covers every repository in this\n" +
            "project at once, which a directory-scoped Grep cannot.\n" +
            "\n" +
            "If the answer might live in a DIFFERENT repository, use the `code_rag_all`\n" +
            "server, which searches every indexed project and tags each hit with the\n" +
            "project it came from.\n" +
            "\n" +
            "After you create or edit files, call `reindex_path` on them if you intend\n" +
            "to search for them in this session — the background sweep runs only every\n" +
            "few minutes, so your own new code is otherwise invisible to `search_code`.\n";

    /**
     * Create or update {@code <projectDir>/CLAUDE.local.md} so it contains the
     * managed block. If the file does not exist, it is created containing
     * only the block. If it exists and already contains the markers, the
     * block between them is replaced (preserving everything outside).
     * Otherwise the block is appended.
     */
    private static void writeClaudeMdBlock(java.io.File projectDir) {
        // CLAUDE.local.md, not CLAUDE.md -- the same choice already made for
        // indexed roots. CLAUDE.md is routinely committed and hand-maintained,
        // and some repositories explicitly forbid editing it; this block is a
        // fact about THIS machine's local index, so it belongs in the
        // local-only file. Excise any block an earlier release left behind in
        // CLAUDE.md so the migration happens automatically rather than
        // orphaning a block bld no longer manages.
        removeManagedBlock(new java.io.File(projectDir, "CLAUDE.md"));
        writeManagedBlock(new java.io.File(projectDir, "CLAUDE.local.md"));
    }

    /**
     * Write the routing block into an explicit markdown file, replacing any
     * previous managed block and preserving everything outside the markers.
     */
    private static void writeManagedBlock(java.io.File md) {
        String existing;
        boolean preexisting = md.exists();
        if (preexisting) {
            try {
                existing = new String(java.nio.file.Files.readAllBytes(md.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                System.err.println("warning: cannot read " + md + ": " + e.getMessage());
                return;
            }
        } else {
            existing = "";
        }
        String managed = CLAUDE_MD_BLOCK_BEGIN + "\n" + CLAUDE_MD_BLOCK_BODY + CLAUDE_MD_BLOCK_END + "\n";
        int beginIdx = existing.indexOf(CLAUDE_MD_BLOCK_BEGIN);
        int endIdx = existing.indexOf(CLAUDE_MD_BLOCK_END);
        String updated;
        if (beginIdx >= 0 && endIdx > beginIdx) {
            int endLineEnd = existing.indexOf('\n', endIdx);
            if (endLineEnd < 0)
                endLineEnd = existing.length();
            else
                endLineEnd++;
            updated = existing.substring(0, beginIdx) + managed + existing.substring(endLineEnd);
        } else {
            StringBuilder sb = new StringBuilder(existing);
            if (!existing.isEmpty() && !existing.endsWith("\n"))
                sb.append("\n");
            if (!existing.isEmpty())
                sb.append("\n");
            sb.append(managed);
            updated = sb.toString();
        }
        if (updated.equals(existing))
            return;
        try {
            java.nio.file.Files.write(md.toPath(),
                    updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            println(preexisting
                    ? "updated " + md.getAbsolutePath() + " (Code-RAG block)"
                    : "wrote " + md.getAbsolutePath());
        } catch (java.io.IOException e) {
            System.err.println("warning: cannot write " + md + ": " + e.getMessage());
        }
    }

    /**
     * Excise the managed block from the project_dir routing files (no-op
     * if either the file or the markers are absent). If removing the
     * block leaves the file effectively empty, the file is deleted.
     */
    private static void removeClaudeMdBlock(java.io.File projectDir) {
        removeManagedBlock(new java.io.File(projectDir, "CLAUDE.local.md"));
        // Legacy location, written by releases before the block moved.
        removeManagedBlock(new java.io.File(projectDir, "CLAUDE.md"));
    }

    /**
     * Excise the managed block from an explicit markdown file (no-op if either
     * the file or the markers are absent). If removing the block leaves the
     * file effectively empty, the file is deleted.
     */
    private static void removeManagedBlock(java.io.File md) {
        if (!md.exists())
            return;
        String existing;
        try {
            existing = new String(java.nio.file.Files.readAllBytes(md.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            System.err.println("warning: cannot read " + md + ": " + e.getMessage());
            return;
        }
        int beginIdx = existing.indexOf(CLAUDE_MD_BLOCK_BEGIN);
        int endIdx = existing.indexOf(CLAUDE_MD_BLOCK_END);
        if (beginIdx < 0 || endIdx < beginIdx)
            return;
        int endLineEnd = existing.indexOf('\n', endIdx);
        if (endLineEnd < 0)
            endLineEnd = existing.length();
        else
            endLineEnd++;
        String updated = existing.substring(0, beginIdx) + existing.substring(endLineEnd);
        // Collapse the extra blank line we inserted on append, so successive
        // add/remove cycles don't accumulate vertical whitespace.
        while (updated.contains("\n\n\n"))
            updated = updated.replace("\n\n\n", "\n\n");
        if (updated.trim().isEmpty()) {
            if (md.delete())
                println("removed " + md.getAbsolutePath());
            return;
        }
        try {
            java.nio.file.Files.write(md.toPath(),
                    updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            println("removed Code-RAG block from " + md.getAbsolutePath());
        } catch (java.io.IOException e) {
            System.err.println("warning: cannot write " + md + ": " + e.getMessage());
        }
    }

    /** Like {@link #resolveRootsStrict}, but accepts paths that no longer exist on disk. */
    private static JSONArray resolveRootsLenient(String[] args, int offset) {
        JSONArray out = new JSONArray();
        for (int i = offset; i < args.length; i++) {
            java.io.File f = new java.io.File(args[i]);
            String abs;
            try {
                abs = f.getCanonicalPath();
            } catch (java.io.IOException e) {
                abs = f.getAbsolutePath();
            }
            out.put(abs);
        }
        return out;
    }

    // ----- MCP client registration -----

    /** True iff a program named {@code prog} is found on $PATH and executable. */
    private static boolean isOnPath(String prog) {
        String path = System.getenv("PATH");
        if (path == null)
            return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(dir, prog);
            if (f.isFile() && f.canExecute())
                return true;
        }
        return false;
    }

    private static String codexConfigPath() {
        return System.getProperty("user.home") + "/.codex/config.toml";
    }

    /** Read RAGMCPSharedSecret from application.ini, "" on missing/empty (auth is off). */
    /** One value from application.ini, or a default when absent/empty. */
    private static String readIniValue(String key, String dflt) {
        java.util.Map<String, String> cfg = readIni(APP_INI_PATH);
        String v = cfg.get(key);
        return (v == null || v.isEmpty()) ? dflt : v;
    }

    private static String readSharedSecret() {
        java.util.Map<String, String> cfg = readIni(APP_INI_PATH);
        String secret = cfg.get("RAGMCPSharedSecret");
        if (secret == null || secret.isEmpty()) {
            System.err.println("warning: RAGMCPSharedSecret is empty in " + APP_INI_PATH
                    + " — MCP auth is off (registering anyway).");
            return "";
        }
        return secret;
    }

    /**
     * Register the MCP entry for {@code name} with whichever clients are
     * installed. Silent no-op if neither claude nor codex is detected
     * (the user just hasn't installed an agent yet).
     *
     * <p>Claude Code entries are written at <b>project scope</b>: one
     * {@code .mcp.json} per root listed in {@code rag-projects.json}, so
     * a Claude Code session only sees the Code-RAG tools when launched
     * inside that project's tree. Codex doesn't have a scope concept —
     * its entry stays in {@code ~/.codex/config.toml} and is visible
     * globally.</p>
     */
    private static void registerMcpEntries(String name, JSONArray roots, String projectDir) {
        String url = MCP_BASE_URL + "/" + name;
        String secret = readSharedSecret();
        boolean hasClaude = isOnPath("claude");
        boolean hasCodex  = isOnPath("codex")
                || new java.io.File(codexConfigPath()).exists();
        JSONArray claudeDirs = combinedRegistrationDirs(roots, projectDir);
        if (hasClaude)
            registerClaudeEntry(name, url, secret, claudeDirs);
        if (hasCodex)
            registerCodexEntry(name, url, secret);
        if (projectDir != null && !projectDir.isEmpty())
            writeClaudeMdBlock(new java.io.File(projectDir));
        if (!hasClaude && !hasCodex && (projectDir == null || projectDir.isEmpty()))
            println("Neither claude nor codex detected — skipping MCP client registration.");
    }

    private static void deregisterMcpEntries(String name, JSONArray roots, String projectDir) {
        boolean hasClaude = isOnPath("claude");
        boolean hasCodex  = isOnPath("codex")
                || new java.io.File(codexConfigPath()).exists();
        JSONArray claudeDirs = combinedRegistrationDirs(roots, projectDir);
        if (hasClaude)
            deregisterClaudeEntry(name, claudeDirs);
        if (hasCodex)
            deregisterCodexEntry(name);
        if (projectDir != null && !projectDir.isEmpty())
            removeClaudeMdBlock(new java.io.File(projectDir));
    }

    /** roots ++ projectDir (if set), as a fresh JSONArray for Claude registration. */
    private static JSONArray combinedRegistrationDirs(JSONArray roots, String projectDir) {
        JSONArray out = new JSONArray();
        if (roots != null)
            for (int i = 0; i < roots.length(); i++)
                out.put(roots.getString(i));
        if (projectDir != null && !projectDir.isEmpty())
            out.put(projectDir);
        return out;
    }

    /**
     * Write a {@code .mcp.json} entry under each project root via
     * {@code claude mcp add -s project}. Pre-removes any prior entry
     * for {@code name} in <i>any</i> scope (user, local, project) so
     * users on the old user-scope registration get migrated cleanly
     * the next time bld touches their project.
     */
    private static void registerClaudeEntry(String name, String url, String secret, JSONArray roots) {
        // Migration sweep: clear any pre-existing user-scope or local-scope
        // entry for this name. user-scope is a single global registration, so
        // one cwd-less remove handles it. local-scope is keyed by the cwd it
        // was registered under, so a cwd-less remove only hits the one under
        // bld's cwd — which is usually NOT where the legacy entry lives. We
        // have to read claude.json and remove from each entry's actual cwd.
        runSilent("claude", "mcp", "remove", name, "-s", "user");
        sweepLegacyLocalScopeEntries(name);
        if (roots == null || roots.length() == 0) {
            System.err.println("warning: project '" + name + "' has no roots — skipping Claude Code MCP registration.");
            return;
        }
        boolean anySucceeded = false;
        for (int i = 0; i < roots.length(); i++) {
            java.io.File root = new java.io.File(roots.getString(i));
            if (!root.isDirectory()) {
                System.err.println("warning: root '" + root + "' does not exist — skipping Claude Code MCP registration there.");
                continue;
            }
            // Idempotency: clear any prior project-scope entry in this root's .mcp.json
            // before re-adding, so re-running this command never errors on "already exists".
            runSilentInDir(root, "claude", "mcp", "remove", name, "-s", "project");
            String[] captured = new String[1];
            int code = runCapturedInDir(root, captured, "claude", "mcp", "add",
                    "--transport", "http", "-s", "project", name, url,
                    "--header", "X-RAG-Token: " + secret);
            if (code == 0) {
                println("registered MCP entry with Claude Code (project scope) in " + root.getAbsolutePath() + "/.mcp.json");
                anySucceeded = true;
            } else {
                System.err.println("warning: claude mcp add failed for '" + name + "' in " + root + ".");
                if (captured[0] != null && !captured[0].isEmpty())
                    System.err.println(captured[0]);
            }
        }
        if (!anySucceeded) {
            System.err.println("Register manually with (from each project root):");
            System.err.println("    claude mcp add --transport http -s project " + name + " "
                    + url + " --header \"X-RAG-Token: ...\"");
        }
    }

    /**
     * Find any local-scope Claude Code entries for {@code name} that
     * point at a Code-RAG URL, and remove each one by invoking
     * {@code claude mcp remove -s local} from the cwd it was originally
     * registered under (which is how local scope identifies entries).
     */
    private static void sweepLegacyLocalScopeEntries(String name) {
        java.io.File claudeCfg = new java.io.File(System.getProperty("user.home"), ".claude.json");
        if (!claudeCfg.exists())
            return;
        for (ClientEntry e : readClaudeCodeEntries(claudeCfg)) {
            if (!"local".equals(e.scope))
                continue;
            if (!name.equals(e.name))
                continue;
            if (e.registeredUnder == null)
                continue;
            java.io.File dir = new java.io.File(e.registeredUnder);
            if (!dir.isDirectory())
                continue;
            if (runSilentInDir(dir, "claude", "mcp", "remove", name, "-s", "local") == 0)
                println("removed legacy local-scope MCP entry registered under " + e.registeredUnder);
        }
    }

    /**
     * Remove the project-scope {@code .mcp.json} entry from each root,
     * and also sweep any legacy user-scope or local-scope entry for the
     * same name. Silent no-op if nothing matches.
     */
    private static void deregisterClaudeEntry(String name, JSONArray roots) {
        // Legacy sweep — pre-2026 versions wrote user-scope and local-scope entries.
        runSilent("claude", "mcp", "remove", name, "-s", "user");
        sweepLegacyLocalScopeEntries(name);
        if (roots == null)
            return;
        for (int i = 0; i < roots.length(); i++) {
            java.io.File root = new java.io.File(roots.getString(i));
            if (!root.isDirectory())
                continue;
            if (runSilentInDir(root, "claude", "mcp", "remove", name, "-s", "project") == 0)
                println("removed MCP entry from Claude Code (project scope) in " + root.getAbsolutePath());
        }
    }

    /** Append/replace [mcp_servers.<name>] in ~/.codex/config.toml. */
    private static void registerCodexEntry(String name, String url, String secret) {
        String path = codexConfigPath();
        java.io.File f = new java.io.File(path);
        java.io.File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            System.err.println("warning: cannot create " + parent + " — skipped Codex registration");
            return;
        }
        try {
            if (!f.exists() && !f.createNewFile()) {
                System.err.println("warning: cannot create " + path + " — skipped Codex registration");
                return;
            }
            removeCodexSection(path, name, true);
            String existing = readUtf8(f);
            // Trim trailing blank lines so consecutive add/remove cycles don't accumulate.
            while (existing.endsWith("\n\n"))
                existing = existing.substring(0, existing.length() - 1);
            StringBuilder sb = new StringBuilder(existing);
            if (!existing.isEmpty() && !existing.endsWith("\n"))
                sb.append("\n");
            if (!existing.isEmpty())
                sb.append("\n");
            sb.append("[mcp_servers.").append(name).append("]\n");
            sb.append("url = \"").append(url).append("\"\n");
            sb.append("http_headers = { \"X-RAG-Token\" = \"").append(secret).append("\" }\n");
            writeUtf8(f, sb.toString());
            println("added [mcp_servers." + name + "] to " + path);
        } catch (java.io.IOException e) {
            System.err.println("warning: failed to update " + path + ": " + e.getMessage());
        }
    }

    private static void deregisterCodexEntry(String name) {
        String path = codexConfigPath();
        if (!new java.io.File(path).exists())
            return;
        if (removeCodexSection(path, name, false))
            println("removed [mcp_servers." + name + "] from " + path);
    }

    /**
     * Delete the {@code [mcp_servers.<name>]} section (header + everything
     * up to the next {@code [...]} header or EOF) from the given TOML file.
     * Returns true if a section was actually removed.
     */
    private static boolean removeCodexSection(String path, String name, boolean quiet) {
        java.io.File f = new java.io.File(path);
        if (!f.exists())
            return false;
        String target = "[mcp_servers." + name + "]";
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.List<String> out = new java.util.ArrayList<>(lines.size());
            boolean skipping = false;
            boolean found = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.equals(target)) {
                    skipping = true;
                    found = true;
                    continue;
                }
                if (skipping && trimmed.startsWith("["))
                    skipping = false;
                if (!skipping)
                    out.add(line);
            }
            if (!found)
                return false;
            StringBuilder sb = new StringBuilder();
            for (String l : out)
                sb.append(l).append("\n");
            writeUtf8(f, sb.toString());
            return true;
        } catch (java.io.IOException e) {
            if (!quiet)
                System.err.println("warning: failed to edit " + path + ": " + e.getMessage());
            return false;
        }
    }

    private static String readUtf8(java.io.File f) throws java.io.IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeUtf8(java.io.File f, String content) throws java.io.IOException {
        java.nio.file.Files.write(f.toPath(),
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Run a command discarding stdio; returns exit code (-1 on launch failure). */
    private static int runSilent(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            return pb.start().waitFor();
        } catch (java.io.IOException | InterruptedException e) {
            return -1;
        }
    }

    /**
     * Run a command in {@code dir}, capturing its combined stdout+stderr
     * into {@code outCapture[0]}. The output is NOT echoed — the caller
     * decides whether to print it (typically only on non-zero exit, so
     * normal runs stay quiet but failures retain the underlying error).
     */
    private static int runCapturedInDir(java.io.File dir, String[] outCapture, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null)
                    sb.append(line).append('\n');
            }
            int code = p.waitFor();
            if (outCapture != null && outCapture.length > 0)
                outCapture[0] = sb.toString().trim();
            return code;
        } catch (java.io.IOException | InterruptedException e) {
            if (outCapture != null && outCapture.length > 0)
                outCapture[0] = "Failed to run " + String.join(" ", cmd) + " in " + dir + ": " + e.getMessage();
            return -1;
        }
    }

    /** Like {@link #runSilent} but with a specified working directory. */
    private static int runSilentInDir(java.io.File dir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            return pb.start().waitFor();
        } catch (java.io.IOException | InterruptedException e) {
            return -1;
        }
    }

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
        java.util.LinkedHashMap<String, String> projectDirs =
                readProjectDirs("src/main/backend/rag-projects.json");
        java.io.File claudeCfg = new java.io.File(System.getProperty("user.home"), ".claude.json");
        java.io.File codexCfg  = new java.io.File(System.getProperty("user.home"), ".codex/config.toml");
        java.util.List<ClientEntry> claudeAll = readClaudeCodeEntries(claudeCfg);
        // Also pick up project-scope entries living in each project root's .mcp.json
        // and in any configured project_dir — that's where bld writes them now.
        java.util.LinkedHashSet<String> projectScopeDirs = new java.util.LinkedHashSet<>();
        for (java.util.List<String> rs : projects.values())
            projectScopeDirs.addAll(rs);
        projectScopeDirs.addAll(projectDirs.values());
        claudeAll.addAll(readProjectScopeClaudeEntries(projectScopeDirs));
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
            println("  Note: Claude Code MCP entries are written at project scope —");
            println("  one .mcp.json per project root. A Claude Code session sees the");
            println("  entry only when launched from somewhere under that root. Each");
            println("  line below states the root path so you can compare against the");
            println("  directory you actually start 'claude' in. Any 'user scope'");
            println("  entries shown are legacy registrations — 'bld start' migrates");
            println("  them to project scope automatically.");
        }

        if (!projects.isEmpty()) {
            println("");
            println("  Projects (rag-projects.json):");
            for (java.util.Map.Entry<String, java.util.List<String>> p : projects.entrySet()) {
                String name = p.getKey();
                java.util.List<String> roots = p.getValue();
                println("");
                println("    " + name);
                String pdir = projectDirs.get(name);
                if (pdir != null && !pdir.isEmpty())
                    println("      project_dir:  " + pdir);
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

    /** One-line description of where this entry is visible — never judges, just states. */
    private static String visibilityNote(ClientEntry e, boolean cwdScoped) {
        if (!cwdScoped)
            return "(global — visible to any Codex session)";
        switch (e.scope) {
            case "project":
                return e.registeredUnder;
            case "local":
                return "registered under " + e.registeredUnder
                        + "   (only visible when 'claude' is launched from there)";
            case "user":
            default:
                return "(user scope — visible from any directory; legacy — bld start migrates these to project scope)";
        }
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
     * Parallel to {@link #readProjects} — returns the {@code project_dir}
     * value for each project that has one. Projects without {@code project_dir}
     * are omitted from the map. Uses the same lightweight regex strategy.
     */
    private static java.util.LinkedHashMap<String, String> readProjectDirs(String file) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        java.nio.file.Path p = java.nio.file.Paths.get(file);
        if (!java.nio.file.Files.exists(p)) return out;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher nameMatcher =
                    java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            java.util.regex.Pattern dirPat =
                    java.util.regex.Pattern.compile("\"project_dir\"\\s*:\\s*\"([^\"]+)\"");
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
                java.util.regex.Matcher dm = dirPat.matcher(slice);
                if (dm.find())
                    out.put(names.get(i), dm.group(1));
            }
        } catch (java.io.IOException ignored) {}
        return out;
    }

    /**
     * A single MCP entry found in a client's config.
     * <ul>
     *   <li>{@link #name} — the key in claude.json / .mcp.json, or the TOML
     *       section name in Codex.</li>
     *   <li>{@link #scope} — Claude Code's registration scope:
     *       <code>"user"</code> (top-level <code>mcpServers</code> in
     *       <code>~/.claude.json</code> — visible everywhere),
     *       <code>"local"</code> (under <code>projects.&lt;cwd&gt;.mcpServers</code>
     *       in <code>~/.claude.json</code> — only visible from that cwd),
     *       <code>"project"</code> (a <code>.mcp.json</code> in a project
     *       root — visible from anywhere under that tree),
     *       <code>"global"</code> (Codex; visible to any Codex session).</li>
     *   <li>{@link #registeredUnder} — the absolute directory the entry is
     *       keyed to. For <code>local</code> scope it's the cwd in
     *       <code>~/.claude.json</code>; for <code>project</code> scope it's the
     *       project root containing the <code>.mcp.json</code>. {@code null}
     *       for entries not cwd-keyed (user scope, or Codex).</li>
     *   <li>{@link #urlPort} — the port in the entry's URL. If this differs
     *       from the server's current HTTP port the entry is stale.</li>
     *   <li>{@link #urlProject} — the project segment from the URL path
     *       (<code>…/rag-mcp/&lt;project&gt;</code>). May be empty if the URL has no
     *       project segment.</li>
     * </ul>
     */
    static class ClientEntry {
        final String name;
        final String scope;
        final String registeredUnder;
        final String urlPort;
        final String urlProject;
        ClientEntry(String name, String registeredUnder, String scope, String urlPort, String urlProject) {
            this.name = name;
            this.scope = scope;
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
                String scope = (regUnder == null) ? "user" : "local";
                out.add(new ClientEntry(lastName, regUnder, scope, port, project));
            }
        } catch (java.io.IOException ignored) {}
        return out;
    }

    /**
     * Walk each project's roots looking for a {@code .mcp.json} file and
     * collect any MCP entries inside it whose URL targets a Code-RAG
     * server. Entries are reported with {@code scope = "project"} and
     * {@code registeredUnder} set to the root path.
     *
     * <p>A {@code .mcp.json} is shared with other tooling (it's the
     * agreed-upon location for project-scope MCP entries), so a single
     * root may carry unrelated entries — they're filtered out by the URL
     * pattern matching {@code http://127.0.0.1:&lt;port&gt;/rag-mcp/...}.</p>
     */
    private static java.util.List<ClientEntry> readProjectScopeClaudeEntries(
            java.util.Collection<String> dirs) {
        java.util.List<ClientEntry> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String root : dirs) {
            if (!seen.add(root))
                continue;  // a dir could appear under multiple projects
            java.io.File mcp = new java.io.File(root, ".mcp.json");
            if (!mcp.isFile())
                continue;
            try {
                String content = new String(java.nio.file.Files.readAllBytes(mcp.toPath()),
                                            java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Pattern urlPat = java.util.regex.Pattern.compile(
                        "http://127\\.0\\.0\\.1:(\\d+)/rag-mcp(?:/([a-zA-Z0-9_.-]+))?");
                java.util.regex.Pattern namePat =
                        java.util.regex.Pattern.compile("\"([a-zA-Z0-9_.-]+)\"\\s*:\\s*\\{");
                java.util.regex.Matcher um = urlPat.matcher(content);
                while (um.find()) {
                    String port = um.group(1);
                    String project = um.group(2);
                    String before = content.substring(0, um.start());
                    java.util.regex.Matcher m = namePat.matcher(before);
                    String lastName = null;
                    while (m.find()) lastName = m.group(1);
                    if (lastName == null)
                        continue;
                    out.add(new ClientEntry(lastName, root, "project", port, project));
                }
            } catch (java.io.IOException ignored) {}
        }
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
        out.add(new ClientEntry(entryName, null, "global", rm.group(1), rm.group(2)));
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
        dep.add(LIBS, "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.53.1.0/sqlite-jdbc-3.53.1.0.jar");
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
