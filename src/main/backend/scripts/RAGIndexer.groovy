package scripts

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.kissweb.database.Connection
import org.kissweb.database.Record
import org.kissweb.json.JSONArray
import org.kissweb.json.JSONObject
import org.kissweb.restServer.MainServlet

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * RAGIndexer — walks configured source roots, chunks files, embeds the chunks
 * via Ollama, and upserts the results into the rag_file / rag_chunk tables.
 *
 * Entry points:
 *   runSweep(Connection db)         — incremental: only re-chunks files whose
 *                                     SHA-256 changed; removes deleted files.
 *   runFullRebuild(Connection db)   — truncates rag_chunk/rag_file and rebuilds.
 *
 * Configuration is read from application.ini via MainServlet.getEnvironment().
 */
class RAGIndexer {

    private static final Logger logger = LogManager.getLogger(RAGIndexer.class)

    private static final Map<String, String> LANG_MAP = [
            // ---- JVM family ----
            'java': 'java', 'groovy': 'groovy', 'gradle': 'groovy',
            'kt': 'kotlin', 'kts': 'kotlin',
            'scala': 'scala', 'sc': 'scala',
            'clj': 'clojure', 'cljs': 'clojure', 'cljc': 'clojure', 'edn': 'clojure',
            // ---- Web / JS family ----
            'js': 'javascript', 'mjs': 'javascript', 'cjs': 'javascript',
            'ts': 'typescript', 'tsx': 'typescript', 'jsx': 'javascript',
            'vue': 'javascript', 'svelte': 'javascript',
            'html': 'html', 'htm': 'html', 'xhtml': 'html',
            'css': 'css', 'scss': 'css', 'sass': 'css', 'less': 'css',
            // ---- C family ----
            'c': 'c', 'h': 'c',
            'cpp': 'cpp', 'cc': 'cpp', 'cxx': 'cpp',
            'hpp': 'cpp', 'hh': 'cpp', 'hxx': 'cpp',
            'cs': 'csharp', 'csx': 'csharp',
            'm': 'objc', 'mm': 'objc',
            // ---- Modern systems ----
            'rs': 'rust',
            'go': 'go',
            'swift': 'swift',
            'zig': 'zig',
            'd': 'd',
            // ---- Scripting / dynamic ----
            'py': 'python', 'pyw': 'python', 'pyi': 'python',
            'rb': 'ruby', 'rake': 'ruby', 'gemspec': 'ruby',
            'pl': 'perl', 'pm': 'perl', 't': 'perl',
            'php': 'php', 'phtml': 'php',
            'lua': 'lua',
            'r': 'r',
            'jl': 'julia',
            'tcl': 'tcl',
            // ---- Functional ----
            'hs': 'haskell', 'lhs': 'haskell',
            'ml': 'ocaml', 'mli': 'ocaml',
            'fs': 'fsharp', 'fsi': 'fsharp', 'fsx': 'fsharp',
            'ex': 'elixir', 'exs': 'elixir',
            'erl': 'erlang', 'hrl': 'erlang',
            // ---- Lisp family ----
            'lisp': 'lisp', 'lsp': 'lisp', 'cl': 'lisp', 'el': 'lisp',
            'scm': 'scheme', 'ss': 'scheme', 'rkt': 'racket',
            // ---- Mobile ----
            'dart': 'dart',
            // ---- Older / niche ----
            'f': 'fortran', 'f90': 'fortran', 'f95': 'fortran', 'f03': 'fortran', 'f08': 'fortran', 'for': 'fortran',
            'pas': 'pascal', 'pp': 'pascal',
            'ada': 'ada', 'adb': 'ada', 'ads': 'ada',
            'asm': 'asm', 's': 'asm',
            'vb': 'vbnet', 'vbs': 'vbnet',
            // ---- Markup / docs / data ----
            'md': 'markdown', 'markdown': 'markdown', 'mdx': 'markdown',
            'txt': 'text',
            'rst': 'rst',
            'adoc': 'asciidoc', 'asciidoc': 'asciidoc',
            'tex': 'latex',
            'sql': 'sql', 'xml': 'xml', 'xsd': 'xml', 'xsl': 'xml', 'xslt': 'xml',
            'json': 'json', 'jsonc': 'json', 'json5': 'json',
            'yml': 'yaml', 'yaml': 'yaml',
            'toml': 'toml',
            'ini': 'ini', 'cfg': 'ini', 'conf': 'ini', 'properties': 'ini', 'env': 'ini',
            // ---- Shells / config ----
            'sh': 'shell', 'bash': 'shell', 'zsh': 'shell', 'fish': 'shell',
            'ps1': 'powershell', 'psm1': 'powershell', 'psd1': 'powershell',
            'bat': 'batch', 'cmd': 'batch',
            'mk': 'makefile', 'mak': 'makefile',
            'cmake': 'cmake',
            // ---- API / schema ----
            'proto': 'protobuf',
            'graphql': 'graphql', 'gql': 'graphql',
            'thrift': 'thrift',
    ]

    /** Files without an extension that we still want to index, keyed by basename (case-sensitive). */
    private static final Map<String, String> FILENAME_MAP = [
            'Makefile':    'makefile',
            'makefile':    'makefile',
            'GNUmakefile': 'makefile',
            'Dockerfile':  'dockerfile',
            'Jenkinsfile': 'groovy',
            'Vagrantfile': 'ruby',
            'Rakefile':    'ruby',
            'Gemfile':     'ruby',
            'CMakeLists.txt': 'cmake',
    ]

    private static final int WINDOW_LINES = 60
    private static final int WINDOW_OVERLAP = 10
    // Strict per-chunk character budget. splitLargeChunk halves any oversize
    // body until each piece fits, so this is the *real* upper bound on what
    // ever reaches Ollama — no embed-time truncation, no lost content.
    // For nomic-embed-text:v1.5 (effective 2K context), 1500 chars of dense
    // code is well under the token budget, and the byte-budgeted batcher in
    // embedBatch keeps cumulative request size safe.
    private static final int MAX_CHUNK_CHARS = 1500

    // ----- Public entry points ------------------------------------------------
    //
    // Callers from other backend Groovy files reach these via
    // GroovyService.run("scripts", "RAGIndexer", "runSweepJson", null, db)
    // — so the caller never has to import the SweepStats type and the cross-file
    // class-loading hot-reload story stays simple. Direct static calls also work
    // when invoked from this file.

    static JSONObject runSweepJson(Connection db, String project) {
        return runSweep(db, project).toJSON()
    }

    static JSONObject runFullRebuildJson(Connection db, String project) {
        return runFullRebuild(db, project).toJSON()
    }

    static SweepStats runSweep(Connection db, String project) {
        return doSweep(db, project, false)
    }

    static SweepStats runFullRebuild(Connection db, String project) {
        if (!org.kissweb.rag.ProjectRegistry.isValidName(project))
            throw new RuntimeException("Invalid project name: " + project)
        logger.info("RAGIndexer[${project}]: full rebuild — truncating rag_chunk and rag_file")
        db.execute("TRUNCATE ${project}.rag_chunk, ${project}.rag_file RESTART IDENTITY".toString())
        db.commit()
        return doSweep(db, project, true)
    }

    // ----- Sweep orchestration ------------------------------------------------

    private static SweepStats doSweep(Connection db, String project, boolean fullRebuild) {
        Config cfg = loadConfig(project)
        verifyMetaMatches(db, cfg)
        SweepStats stats = new SweepStats()
        long t0 = System.currentTimeMillis()

        // Map of (repo, path) → existing rag_file row, so we can detect deletions
        Map<String, Map<String, Object>> existing = [:]
        if (!fullRebuild) {
            List<Record> rows = db.fetchAll(
                    "SELECT file_id, repo, path, sha256 FROM ${project}.rag_file".toString())
            for (Record r : rows) {
                String key = r.getString("repo") + "::" + r.getString("path")
                existing[key] = [
                        file_id: r.getLong("file_id"),
                        sha256: r.getString("sha256")
                ]
            }
        }
        Set<String> seenKeys = new HashSet<>()

        for (RootDir root : cfg.roots) {
            walkRoot(root, cfg, db, existing, seenKeys, stats)
        }

        // Delete files that disappeared from disk
        if (!fullRebuild) {
            for (Map.Entry<String, Map<String, Object>> entry : existing.entrySet()) {
                if (!seenKeys.contains(entry.key)) {
                    db.execute(
                            "DELETE FROM ${project}.rag_file WHERE file_id = ?".toString(),
                            entry.value.file_id)
                    stats.filesDeleted++
                }
            }
            if (stats.filesDeleted > 0)
                db.commit()
        }

        // Flush any work accumulated since the last batch boundary.
        db.commit()

        long dt = System.currentTimeMillis() - t0
        stats.elapsedMs = dt
        stats.fullRebuild = fullRebuild
        stats.completedAt = new java.sql.Timestamp(System.currentTimeMillis()).toString()
        logger.info("RAGIndexer[${project}] sweep done in ${dt} ms: " +
                "scanned=${stats.filesScanned} skipped=${stats.filesSkipped} " +
                "indexed=${stats.filesIndexed} unchanged=${stats.filesUnchanged} " +
                "deleted=${stats.filesDeleted} chunks=${stats.chunksInserted} " +
                "errored=${stats.filesErrored}")

        // Persist for status() consumers.
        String statsJson = stats.toJSON().toString()
        db.execute(
                "INSERT INTO ${project}.rag_meta(key, value) VALUES('last_sweep', ?) ".toString() +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", statsJson)
        db.commit()
        return stats
    }

    private static void walkRoot(RootDir root, Config cfg, Connection db,
                                 Map<String, Map<String, Object>> existing,
                                 Set<String> seenKeys, SweepStats stats) {
        Path rootPath = root.path.toPath()
        if (!Files.isDirectory(rootPath)) {
            logger.warn("RAG root does not exist or is not a directory: ${rootPath}")
            return
        }
        Files.walkFileTree(rootPath, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (matchesExcluded(dir, cfg.excludes))
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE
                return java.nio.file.FileVisitResult.CONTINUE
            }
            @Override
            java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                stats.filesScanned++
                try {
                    if (matchesExcluded(file, cfg.excludes)) {
                        stats.filesSkipped++
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    if (attrs.size() > cfg.maxFileBytes) {
                        stats.filesSkipped++
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    String language = languageOf(file)
                    if (language == null) {
                        stats.filesSkipped++
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    String relPath = rootPath.relativize(file).toString()
                    String repoKey = root.repo + "::" + relPath
                    seenKeys.add(repoKey)
                    indexOneFile(db, cfg, root, file.toFile(), relPath, language, attrs.size(), existing[repoKey], stats)
                } catch (Exception ex) {
                    logger.error("RAGIndexer: failed on " + file, ex)
                    stats.filesErrored++
                    // If a chunk INSERT failed, the JDBC transaction is now
                    // in aborted state and every subsequent statement on the
                    // same connection will throw "current transaction is
                    // aborted, commands ignored until end of transaction
                    // block". Explicitly roll back so the next file starts
                    // a clean transaction.
                    try { db.rollback() } catch (Exception ignored) { /* tolerate */ }
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    @SuppressWarnings("unused")
    private static void indexOneFile(Connection db, Config cfg, RootDir root, File file,
                                      String relPath, String language, long size,
                                      Map<String, Object> existingRow, SweepStats stats) {
        byte[] bytes = file.bytes
        if (looksBinary(bytes)) {
            stats.filesSkipped++
            return
        }
        String content = new String(bytes, StandardCharsets.UTF_8)
        String sha = sha256(bytes)

        if (existingRow != null && existingRow.sha256 == sha) {
            stats.filesUnchanged++
            return
        }

        List<Chunk> chunks = chunkFile(content, language)
        if (chunks.isEmpty()) {
            stats.filesSkipped++
            return
        }
        // Chunker now enforces MAX_CHUNK_CHARS; embed full content directly.
        List<String> texts = chunks.collect { it.content }
        List<float[]> vectors = embedBatch(cfg, texts)
        if (vectors.size() != chunks.size())
            throw new RuntimeException("Embedding count mismatch: got ${vectors.size()} for ${chunks.size()} chunks in ${file}")

        long fileId
        java.sql.Timestamp mtime = new java.sql.Timestamp(file.lastModified())
        String proj = cfg.project
        // Single upsert path. INSERT ... ON CONFLICT DO UPDATE handles both
        // "new file" and "changed file" cleanly, and is robust against a
        // residual row from a partially-rolled-back prior attempt (the only
        // way the same (repo, path) could appear twice in a single sweep).
        List<Record> upserted = db.fetchAll(
                "INSERT INTO ${proj}.rag_file (repo, path, sha256, mtime, size_bytes, language, indexed_at) ".toString() +
                "VALUES (?, ?, ?, ?, ?, ?, now()) " +
                "ON CONFLICT (repo, path) DO UPDATE SET " +
                "    sha256 = EXCLUDED.sha256, " +
                "    mtime = EXCLUDED.mtime, " +
                "    size_bytes = EXCLUDED.size_bytes, " +
                "    language = EXCLUDED.language, " +
                "    indexed_at = now() " +
                "RETURNING file_id",
                root.repo, relPath, sha, mtime, size, language)
        fileId = upserted[0].getLong("file_id")
        // Drop any prior chunks for this file (no-op when it is a fresh insert).
        db.execute("DELETE FROM ${proj}.rag_chunk WHERE file_id = ?".toString(), fileId)

        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks[i]
            String vecLit = vectorToLiteral(vectors[i])
            db.execute(
                    "INSERT INTO ${proj}.rag_chunk (file_id, chunk_no, start_line, end_line, symbol, content, token_est, embedding) ".toString() +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector)",
                    fileId, i, c.startLine, c.endLine, c.symbol, c.content, estimateTokens(c.content), vecLit)
            stats.chunksInserted++
        }
        stats.filesIndexed++
        // Kiss Connection runs with autoCommit=false. Commit every commitBatch
        // files so the transaction never grows unbounded but we are not paying
        // an fsync per file. 0 means "commit only at the end of the sweep".
        if (cfg.commitBatch > 0 && (stats.filesIndexed % cfg.commitBatch) == 0)
            db.commit()
    }

    // ----- Chunking -----------------------------------------------------------

    static List<Chunk> chunkFile(String content, String language) {
        if (content == null || content.isEmpty())
            return []
        String[] lines = content.split("\n", -1)
        if (language == 'markdown')
            return chunkMarkdown(lines)
        java.util.regex.Pattern symRe = symRegexFor(language)
        if (symRe != null)
            return chunkBySymbols(lines, language, symRe)
        return chunkFixedWindow(lines, null)
    }

    /**
     * Per-language regex for "this line starts a function/class/method".
     * Returns null when we have no good per-symbol heuristic — the caller
     * falls back to fixed-window chunking (still works, just coarser).
     */
    private static java.util.regex.Pattern symRegexFor(String language) {
        switch (language) {
            // JVM/JS family + Kotlin, Scala, C#, Swift — Java-style modifier-led declarations
            case 'java':
            case 'groovy':
            case 'javascript':
            case 'typescript':
            case 'kotlin':
            case 'scala':
            case 'csharp':
            case 'swift':
            case 'dart':
                return JVM_LIKE_SYM_RE
            // C-family — permissive return type, free-form names
            case 'c':
            case 'cpp':
            case 'objc':
                return C_LIKE_SYM_RE
            // Keyword-led (def / class / module)
            case 'python':
            case 'ruby':
            case 'elixir':
                return KW_LED_SYM_RE
            case 'rust':
                return RUST_SYM_RE
            case 'go':
                return GO_SYM_RE
            case 'php':
                return PHP_SYM_RE
            // Lisp family
            case 'lisp':
            case 'scheme':
            case 'racket':
            case 'clojure':
                return LISP_SYM_RE
            default:
                return null
        }
    }

    private static List<Chunk> chunkMarkdown(String[] lines) {
        List<int[]> boundaries = []
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] =~ /^#{1,2}\s+/) {
                boundaries.add([i, -1] as int[])
            }
        }
        if (boundaries.isEmpty())
            return chunkFixedWindow(lines, null)
        // Fill in end indices
        for (int i = 0; i < boundaries.size(); i++) {
            int start = boundaries[i][0]
            int end = (i + 1 < boundaries.size()) ? boundaries[i + 1][0] - 1 : lines.length - 1
            boundaries[i][1] = end
        }
        List<Chunk> out = []
        for (int[] b : boundaries) {
            String header = lines[b[0]].replaceFirst(/^#+\s+/, '').trim()
            String body = sliceLines(lines, b[0], b[1])
            if (body.trim().isEmpty())
                continue
            out.addAll(splitLargeChunk(body, b[0] + 1, b[1] + 1, header))
        }
        return out
    }

    // -------- Per-language symbol regexes --------
    //
    // Each pattern only needs to recognize the *start* of a function / class /
    // method. The chunker uses one matched start as the boundary for the
    // previous chunk; misses fall through to the fixed-window chunker.

    /** Java/Groovy/JS/TS/Kotlin/Scala/C#/Swift/Dart — modifier-led + keyword/type. */
    private static final java.util.regex.Pattern JVM_LIKE_SYM_RE = java.util.regex.Pattern.compile(
            /^\s{0,8}(?:public|private|protected|internal|static|final|abstract|synchronized|sealed|virtual|override|partial|extern|unsafe|inline|export|async|open|operator|suspend|tailrec|infix|@\w+)*\s*(?:class|interface|enum|record|trait|struct|object|protocol|namespace|delegate|event|fun|func|def|function|void|int|long|double|float|boolean|String|char|byte|short|bool|let|val|var|const|typealias|extension|init|deinit)\s+[A-Za-z_][\w\$]*\s*[<({:]/)

    /**
     * C / C++ / Objective-C — function declarations with arbitrary type prefixes.
     * Allows "char *strrev(...)" (no space before *name) as well as "char* strrev(...)".
     * Form: leading modifiers, then return type (one identifier, maybe ::ns:: chain, maybe stars/refs),
     * then optional whitespace, then function name (or Class::method), then "(".
     */
    private static final java.util.regex.Pattern C_LIKE_SYM_RE = java.util.regex.Pattern.compile(
            /^(?:(?:static|inline|extern|const|volatile|unsigned|signed|register|virtual|explicit|constexpr|noexcept|template\s*<[^>]*>)\s+)*[A-Za-z_]\w*(?:\s*::\s*[A-Za-z_]\w*)*[ \t]*[\*&]*[ \t]*[A-Za-z_]\w*(?:\s*::~?[A-Za-z_]\w*)?[ \t]*\(/)

    /** Python / Ruby / Elixir — keyword-led declarations. */
    private static final java.util.regex.Pattern KW_LED_SYM_RE = java.util.regex.Pattern.compile(
            /^\s{0,12}(?:async\s+)?(?:def|defp|class|module|defmodule|defmacro|defprotocol|defimpl)\s+[A-Za-z_][\w\.]*/)

    /** Rust — fn / struct / enum / impl / trait / mod with optional visibility. */
    private static final java.util.regex.Pattern RUST_SYM_RE = java.util.regex.Pattern.compile(
            /^\s{0,8}(?:pub(?:\s*\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?(?:fn|struct|enum|impl|trait|mod|type|const|static|union)\s+[A-Za-z_]/)

    /** Go — func / type / var / const at column 0. */
    private static final java.util.regex.Pattern GO_SYM_RE = java.util.regex.Pattern.compile(
            /^(?:func(?:\s*\([^)]*\))?\s+[A-Za-z_]|type\s+[A-Za-z_]|var\s+[A-Za-z_]|const\s+[A-Za-z_])/)

    /** PHP — function / class with optional modifiers, plus the leading <?php is irrelevant. */
    private static final java.util.regex.Pattern PHP_SYM_RE = java.util.regex.Pattern.compile(
            /^\s{0,8}(?:public|private|protected|static|final|abstract)*\s*(?:function|class|interface|trait|namespace)\s+[A-Za-z_]/)

    /** Lisp / Scheme / Racket / Clojure — top-level definition forms. */
    private static final java.util.regex.Pattern LISP_SYM_RE = java.util.regex.Pattern.compile(
            /^\(\s*(?:defun|defmacro|defmethod|defclass|defparameter|defvar|defconstant|defstruct|defgeneric|defpackage|in-package|define|define-syntax|define-record-type|defmulti|defmethod|defprotocol|defrecord|deftype|defn|defn-|defmacro)\s+[A-Za-z_]/)

    private static List<Chunk> chunkBySymbols(String[] lines, String language, java.util.regex.Pattern symRe) {
        List<int[]> starts = []
        for (int i = 0; i < lines.length; i++) {
            if (symRe.matcher(lines[i]).find())
                starts.add([i, -1] as int[])
        }
        if (starts.isEmpty())
            return chunkFixedWindow(lines, null)
        // Prepend a "preamble" chunk if first symbol isn't at the top
        List<Chunk> out = []
        int firstSymLine = starts[0][0]
        if (firstSymLine > 0) {
            String preamble = sliceLines(lines, 0, firstSymLine - 1)
            if (!preamble.trim().isEmpty())
                out.addAll(splitLargeChunk(preamble, 1, firstSymLine, null))
        }
        for (int i = 0; i < starts.size(); i++) {
            int s = starts[i][0]
            int e = (i + 1 < starts.size()) ? starts[i + 1][0] - 1 : lines.length - 1
            String symbol = extractSymbolName(lines[s])
            String body = sliceLines(lines, s, e)
            if (body.trim().isEmpty())
                continue
            out.addAll(splitLargeChunk(body, s + 1, e + 1, symbol))
        }
        return out
    }

    /** Identifiers that are language keywords / modifiers and should not be returned as symbol names. */
    private static final Set<String> KEYWORDS_TO_SKIP = [
            'public','private','protected','internal','static','final','abstract','synchronized',
            'sealed','virtual','override','partial','extern','unsafe','inline','export','async',
            'open','operator','suspend','tailrec','infix','data','annotation','companion',
            'def','defp','defmodule','defmacro','defprotocol','defimpl','class','interface',
            'enum','record','trait','struct','union','protocol','object','namespace','package',
            'module','impl','fn','fun','func','function','let','val','var','const','typealias',
            'extension','init','deinit','async','await','yield','new','return','this','self','super',
            'using','typedef','template','typename','if','for','while','switch','case','do','else',
            'try','catch','finally','throw','throws','break','continue','default','delegate','event',
            'void','int','long','double','float','boolean','bool','char','byte','short','String',
            'auto','noexcept','constexpr','explicit','volatile','unsigned','signed','register',
            'pub','crate','where','unsafe','mut','dyn','move','ref'
    ] as Set

    /**
     * Pick a human-friendly symbol name from a declaration line. Tries the first
     * identifier-followed-by `<({:` that is not a language keyword; falls back to
     * the last identifier on the line (which catches "class Counter", "module Words",
     * "def initialize", "struct Foo" style declarations).
     */
    private static String extractSymbolName(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(/([A-Za-z_][\w$]*)\s*[<({:]/).matcher(line)
        while (m.find()) {
            String name = m.group(1)
            if (!KEYWORDS_TO_SKIP.contains(name))
                return name
        }
        // Fallback: last identifier on the line that is not a keyword.
        String[] toks = line.trim().split(/[^\w$]+/)
        for (int i = toks.length - 1; i >= 0; i--) {
            String t = toks[i]
            if (t.isEmpty())
                continue
            if (KEYWORDS_TO_SKIP.contains(t))
                continue
            if (!t.matches(/[A-Za-z_][\w$]*/))
                continue
            return t
        }
        return null
    }

    private static List<Chunk> chunkFixedWindow(String[] lines, String symbol) {
        List<Chunk> out = []
        int i = 0
        while (i < lines.length) {
            int end = Math.min(i + WINDOW_LINES - 1, lines.length - 1)
            String body = sliceLines(lines, i, end)
            if (!body.trim().isEmpty())
                out.addAll(splitLargeChunk(body, i + 1, end + 1, symbol))
            if (end + 1 >= lines.length)
                break
            i = end + 1 - WINDOW_OVERLAP
            if (i <= 0)
                i = WINDOW_LINES
        }
        return out
    }

    /**
     * Enforce MAX_CHUNK_CHARS by recursively halving the body until each piece
     * fits. Lines are kept together when possible — we halve at line boundaries
     * for everything that has more than one line. A single oversize line (rare:
     * minified JS, embedded SQL strings) is sliced by character offset; we lose
     * line-fidelity inside such lines but they were not really lines anyway.
     */
    private static List<Chunk> splitLargeChunk(String body, int startLine, int endLine, String symbol) {
        if (body.length() <= MAX_CHUNK_CHARS)
            return [new Chunk(content: body, startLine: startLine, endLine: endLine, symbol: symbol)]

        String[] sub = body.split("\n", -1)
        if (sub.length > 1) {
            int mid = Math.max(1, sub.length.intdiv(2))
            String top = sliceLines(sub, 0, mid - 1)
            String bot = sliceLines(sub, mid, sub.length - 1)
            List<Chunk> out = []
            out.addAll(splitLargeChunk(top, startLine, startLine + mid - 1, symbol))
            out.addAll(splitLargeChunk(bot, startLine + mid, endLine, symbol))
            return out
        }

        // Single oversize line — slice by characters.
        List<Chunk> out = []
        int i = 0
        while (i < body.length()) {
            int end = Math.min(i + MAX_CHUNK_CHARS, body.length())
            out.add(new Chunk(content: body.substring(i, end),
                              startLine: startLine, endLine: endLine, symbol: symbol))
            i = end
        }
        return out
    }

    private static String sliceLines(String[] lines, int startInc, int endInc) {
        StringBuilder sb = new StringBuilder()
        for (int i = startInc; i <= endInc; i++) {
            sb.append(lines[i])
            if (i < endInc)
                sb.append('\n')
        }
        return sb.toString()
    }

    // ----- Ollama embedding ---------------------------------------------------
    //
    // Two batching gotchas with Ollama's /api/embed (verified empirically):
    //   1. The cumulative tokens across the whole `input` array are checked
    //      against the model's context window, not each input independently.
    //      A batch of N small chunks can fail with "input length exceeds the
    //      context length" even though every individual chunk fits.
    //   2. Sending `options.num_ctx` is silently ignored on embed requests.
    //
    // So we cap each request by total byte size (not chunk count), and on a
    // 400 from Ollama we halve and retry — eventually down to a single chunk,
    // and finally a truncated single chunk if necessary.

    static List<float[]> embedBatch(Config cfg, List<String> texts) {
        if (texts.isEmpty())
            return []
        List<float[]> out = new ArrayList<>(texts.size())
        int idx = 0
        while (idx < texts.size()) {
            // Greedy pack: take as many texts as fit within both the chunk-count
            // cap and the byte budget. Always take at least one.
            int end = idx
            int bytes = 0
            while (end < texts.size() && end - idx < cfg.embeddingMaxChunks) {
                int next = utf8Len(texts.get(end))
                if (end > idx && bytes + next > cfg.embeddingMaxBatchBytes)
                    break
                bytes += next
                end++
            }
            embedRange(cfg, texts, idx, end, out)
            idx = end
        }
        return out
    }

    /** Embed texts[from..to), appending results to out. Halves the range on 400, truncates on size-1 failure. */
    private static void embedRange(Config cfg, List<String> texts, int from, int to, List<float[]> out) {
        List<String> sub = texts.subList(from, to)
        try {
            out.addAll(callOllamaEmbed(cfg, sub))
            return
        } catch (RuntimeException e) {
            // Only retry on the specific "context length" failure mode.
            if (!isContextLengthError(e))
                throw e
        }
        if (to - from > 1) {
            int mid = from + (to - from) / 2
            embedRange(cfg, texts, from, mid, out)
            embedRange(cfg, texts, mid, to, out)
            return
        }
        // Single chunk still failing — should be very rare now that the chunker
        // enforces MAX_CHUNK_CHARS. Halve and try again; bail at 256 chars.
        String t = texts.get(from)
        if (t.length() > 256) {
            int trimTo = t.length().intdiv(2)
            List<String> oneShot = Collections.singletonList(t.substring(0, trimTo))
            out.addAll(callOllamaEmbed(cfg, oneShot))
            return
        }
        throw new RuntimeException("Ollama rejected single ${t.length()}-char chunk; cannot truncate further")
    }

    private static boolean isContextLengthError(Throwable e) {
        String msg = e?.getMessage()
        return msg != null && (msg.contains("context length") || msg.contains("returned 400"))
    }

    private static List<float[]> callOllamaEmbed(Config cfg, List<String> texts) {
        JSONObject body = new JSONObject()
        body.put("model", cfg.embeddingModel)
        JSONArray arr = new JSONArray()
        for (String t : texts)
            arr.put(t)
        body.put("input", arr)
        JSONObject resp = httpPostJson(cfg.ollamaURL + "/api/embed", body)
        JSONArray vecs = resp.getJSONArray("embeddings")
        if (vecs.length() != texts.size())
            throw new RuntimeException("Ollama returned ${vecs.length()} embeddings for ${texts.size()} inputs")
        List<float[]> out = new ArrayList<>(vecs.length())
        for (int i = 0; i < vecs.length(); i++) {
            JSONArray v = vecs.getJSONArray(i)
            float[] f = new float[v.length()]
            for (int j = 0; j < v.length(); j++)
                f[j] = (float) v.getDouble(j)
            out.add(f)
        }
        return out
    }

    private static int utf8Len(String s) {
        // Cheap UTF-8 length estimate without allocating bytes[].
        // Ollama tokenizes from bytes, so this is the right unit for the budget.
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length
    }

    private static JSONObject httpPostJson(String url, JSONObject body) {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
        conn.setRequestMethod("POST")
        conn.setDoOutput(true)
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(120000)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.withWriter("UTF-8") { it.write(body.toString()) }
        int code = conn.responseCode
        if (code != 200) {
            String err
            try { err = conn.errorStream?.getText("UTF-8") } catch (Exception ignored) { err = "" }
            throw new RuntimeException("Ollama POST ${url} returned ${code}: ${err}")
        }
        String text = conn.inputStream.getText("UTF-8")
        return new JSONObject(text)
    }

    static String vectorToLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12 + 2)
        sb.append('[')
        for (int i = 0; i < v.length; i++) {
            if (i > 0)
                sb.append(',')
            sb.append(Float.toString(v[i]))
        }
        sb.append(']')
        return sb.toString()
    }

    // ----- Configuration ------------------------------------------------------

    static Config loadConfig(String project) {
        if (!org.kissweb.rag.ProjectRegistry.isValidName(project))
            throw new RuntimeException("Invalid project name: " + project)
        org.kissweb.rag.ProjectRegistry.Project proj = org.kissweb.rag.ProjectRegistry.get(project)
        if (proj == null)
            throw new RuntimeException("Unknown project '" + project + "'; check rag-projects.json")

        Config c = new Config()
        c.project = project
        c.ollamaURL = trimSlash(env("OllamaURL", "http://127.0.0.1:11434"))
        c.embeddingModel = env("EmbeddingModel", "nomic-embed-text:v1.5")
        c.embeddingDim = Integer.parseInt(env("EmbeddingDim", "768"))
        c.embeddingMaxChunks = Integer.parseInt(env("EmbeddingBatch", "32"))
        c.embeddingMaxBatchBytes = Integer.parseInt(env("EmbeddingMaxBatchBytes", "6000"))
        c.maxFileBytes = Long.parseLong(env("RAGMaxFileBytes", "1048576"))
        c.commitBatch = Integer.parseInt(env("RAGCommitBatch", "50"))

        // Project-specific from rag-projects.json (not application.ini).
        List<RootDir> roots = []
        for (String p : proj.roots) {
            File f = new File(p)
            roots.add(new RootDir(repo: f.name, path: f))
        }
        c.roots = roots

        List<PathMatcher> ms = []
        for (String g : proj.excludeGlobs) {
            String t = g.trim()
            if (t.isEmpty())
                continue
            ms.add(FileSystems.default.getPathMatcher("glob:" + t))
        }
        c.excludes = ms
        return c
    }

    private static void verifyMetaMatches(Connection db, Config cfg) {
        Record rec = db.fetchOne(
                "SELECT value FROM ${cfg.project}.rag_meta WHERE key = 'embedding_dim'".toString())
        if (rec == null)
            throw new RuntimeException("${cfg.project}.rag_meta.embedding_dim missing — was the bootstrap run?")
        int storedDim = Integer.parseInt(rec.getString("value"))
        if (storedDim != cfg.embeddingDim)
            throw new RuntimeException("Embedding dim mismatch for '${cfg.project}': " +
                    "application.ini=${cfg.embeddingDim}, rag_meta=${storedDim}. Rebuild required.")
    }

    private static String env(String key, String dflt) {
        try {
            String v = MainServlet.getEnvironment(key)
            return (v != null && !v.isEmpty()) ? v : dflt
        } catch (Exception ignored) {
            return dflt
        }
    }

    private static String trimSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s
    }

    // ----- Misc helpers -------------------------------------------------------

    private static boolean matchesExcluded(Path p, List<PathMatcher> excludes) {
        for (PathMatcher m : excludes) {
            if (m.matches(p))
                return true
        }
        return false
    }

    private static String extOf(String filename) {
        int dot = filename.lastIndexOf('.')
        if (dot < 0 || dot == filename.length() - 1)
            return ""
        return filename.substring(dot + 1).toLowerCase()
    }

    /**
     * Classify a file by extension first, then by basename (Makefile, Dockerfile, etc.).
     * Returns null when we do not recognize the file (the walker will skip it).
     */
    private static String languageOf(Path file) {
        String name = file.fileName.toString()
        String ext = extOf(name)
        String lang = LANG_MAP[ext]
        if (lang != null)
            return lang
        return FILENAME_MAP[name]
    }

    private static String sha256(byte[] bytes) {
        MessageDigest md = MessageDigest.getInstance("SHA-256")
        byte[] d = md.digest(bytes)
        StringBuilder sb = new StringBuilder(64)
        for (byte b : d)
            sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private static int estimateTokens(String s) {
        // 1 token ≈ 4 chars for English/code mix — good enough for sanity bookkeeping.
        return Math.max(1, s.length().intdiv(4))
    }

    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 8192)
        for (int i = 0; i < n; i++) {
            if (bytes[i] == (byte) 0)
                return true
        }
        return false
    }

    // ----- Value types --------------------------------------------------------

    static class Config {
        String project               // schema name, also the project's identifier
        String ollamaURL
        String embeddingModel
        int embeddingDim
        int embeddingMaxChunks       // hard cap on chunks per /api/embed request
        int embeddingMaxBatchBytes   // soft cap on cumulative UTF-8 bytes per request
        long maxFileBytes
        int commitBatch
        List<RootDir> roots
        List<PathMatcher> excludes
    }

    static class RootDir {
        String repo
        File path
    }

    static class Chunk {
        String content
        int startLine
        int endLine
        String symbol
    }

    static class SweepStats {
        int filesScanned, filesSkipped, filesIndexed, filesUnchanged, filesDeleted, filesErrored
        int chunksInserted
        long elapsedMs
        boolean fullRebuild
        String completedAt    // ISO-ish; set when the sweep ends

        JSONObject toJSON() {
            JSONObject o = new JSONObject()
            o.put("filesScanned", filesScanned)
            o.put("filesSkipped", filesSkipped)
            o.put("filesIndexed", filesIndexed)
            o.put("filesUnchanged", filesUnchanged)
            o.put("filesDeleted", filesDeleted)
            o.put("filesErrored", filesErrored)
            o.put("chunksInserted", chunksInserted)
            o.put("elapsedMs", elapsedMs)
            o.put("fullRebuild", fullRebuild)
            o.put("completedAt", completedAt)
            return o
        }
    }
}
