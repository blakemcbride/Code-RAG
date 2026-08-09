package scripts

import org.kissweb.rag.ProjectRegistry
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.kissweb.database.Connection
import org.kissweb.database.Record
import org.kissweb.restServer.MainServlet

/**
 * One-time-per-startup schema housekeeping for the multi-project RAG layout.
 *
 * Responsibilities:
 *   1) For each project listed in rag-projects.json, CREATE SCHEMA IF NOT
 *      EXISTS plus the per-project rag_file / rag_chunk / rag_meta tables
 *      and the indexes. Seeds the standard meta rows. Idempotent.
 *   2) Run the schema migration ladder to bring older schemas up to
 *      {@link #CURRENT_SCHEMA_VERSION}.
 *   3) Reset reindex_running='false' so a server crash mid-sweep does not
 *      leave any project permanently locked.
 *
 * Schema names come from ProjectRegistry, which validates them against
 * [a-z][a-z0-9_]*; string-interpolating them into SQL is safe.
 */
class ProjectBootstrap {

    private static final Logger logger = LogManager.getLogger(ProjectBootstrap.class)

    /**
     * Schema shape version. Bump this and add a step to {@link #applyMigration}
     * whenever the per-project tables change.
     *
     *   v1 — original rag_file / rag_chunk / rag_meta
     *   v2 — rag_chunk.lexemes + generated lexemes_tsv + GIN index (hybrid search)
     *   v3 — rag_chunk.sym_start_line / sym_end_line (parent-symbol expansion)
     *   v4 — rag_commit (VCS history) + rag_file summary columns (LLM summaries)
     *   v5 — rag_symbol (per-symbol LLM summaries for large files)
     *   v6 — rag_query_log (real usage, for mining an honest eval set)
     *   v7 — rag_def (ctags symbol definitions: the code-graph index)
     *   v8 — rag_dep (import graph: which file depends on which)
     *
     * v4-v8 require no rebuild: every addition is populated by its own pass
     * (./bld history, ./bld summarize, live usage) and none invalidates chunks.
     */
    static final int CURRENT_SCHEMA_VERSION = 8

    /**
     * Ensure each configured project's schema + tables exist and are migrated
     * to the current version, reset stale reindex_running flags, and report
     * which projects appear to have never been scanned (empty rag_file table).
     * The returned list is what KissInit.init2 uses to kick off an auto-scan
     * at startup for any fresh project.
     */
    static List<String> ensureAll(Connection db) {
        List<String> needsScan = []
        for (ProjectRegistry.Project p : ProjectRegistry.load()) {
            ensureSchema(db, p.name)
            migrate(db, p.name)
            // Recovery: a previous crash may have left the lock set.
            db.execute("UPDATE ${p.name}.rag_meta SET value = 'false' WHERE key = 'reindex_running'".toString())
            // "Never scanned" — empty rag_file table. True both for a
            // freshly-created schema and for one whose data was wiped.
            Record row = db.fetchOne("SELECT count(*) AS n FROM ${p.name}.rag_file".toString())
            long fileCount = (row != null) ? row.getLong("n") : 0L
            if (fileCount == 0L)
                needsScan.add(p.name)
        }
        db.commit()
        return needsScan
    }

    /**
     * Bootstrap a single project's schema + tables on demand (used by the
     * scan reconciler when rag-projects.json gains a new project entry).
     * Idempotent; safe to re-run.
     */
    static void ensureOne(Connection db, String project) {
        if (!ProjectRegistry.isValidName(project))
            throw new RuntimeException("Invalid project name '${project}'")
        ensureSchema(db, project)
        migrate(db, project)
        db.commit()
    }

    /** Embedding dimension from application.ini. Single source of truth with RAGIndexer. */
    private static int embeddingDim() {
        try {
            String v = MainServlet.getEnvironment("EmbeddingDim")
            if (v != null && !v.isEmpty())
                return Integer.parseInt(v)
        } catch (Exception ignored) { /* fall through to default */ }
        return 768
    }

    private static void ensureSchema(Connection db, String project) {
        int dim = embeddingDim()
        db.execute("CREATE SCHEMA IF NOT EXISTS ${project}".toString())

        db.execute("""
            CREATE TABLE IF NOT EXISTS ${project}.rag_meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """.toString())

        db.execute("""
            CREATE TABLE IF NOT EXISTS ${project}.rag_file (
                file_id     BIGSERIAL PRIMARY KEY,
                repo        TEXT        NOT NULL,
                path        TEXT        NOT NULL,
                sha256      CHAR(64)    NOT NULL,
                mtime       TIMESTAMPTZ NOT NULL,
                size_bytes  BIGINT      NOT NULL,
                language    TEXT,
                indexed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                UNIQUE (repo, path)
            )
        """.toString())

        // Created at the CURRENT shape. Pre-existing tables are brought
        // forward by migrate(); CREATE TABLE IF NOT EXISTS will not alter them.
        db.execute("""
            CREATE TABLE IF NOT EXISTS ${project}.rag_chunk (
                chunk_id    BIGSERIAL PRIMARY KEY,
                file_id     BIGINT      NOT NULL REFERENCES ${project}.rag_file(file_id) ON DELETE CASCADE,
                chunk_no    INT         NOT NULL,
                start_line  INT         NOT NULL,
                end_line    INT         NOT NULL,
                symbol      TEXT,
                content     TEXT        NOT NULL,
                token_est   INT         NOT NULL,
                lexemes     TEXT,
                sym_start_line INT,
                sym_end_line   INT,
                embedding   vector(${dim}) NOT NULL
            )
        """.toString())

        db.execute("CREATE INDEX IF NOT EXISTS rag_file_repo_path ON ${project}.rag_file(repo, path)".toString())
        db.execute("CREATE INDEX IF NOT EXISTS rag_chunk_file_idx ON ${project}.rag_chunk(file_id)".toString())
        db.execute("""
            CREATE INDEX IF NOT EXISTS rag_chunk_embedding_hnsw
                ON ${project}.rag_chunk USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
        """.toString())
        // A freshly-created schema is seeded at CURRENT_SCHEMA_VERSION, so
        // migrate() will not run for it. Every migration's structures must
        // therefore also be applied here, or new projects silently lack them.
        addLexemeSearchObjects(db, project)
        addHistoryTable(db, project, dim)
        addSummaryColumns(db, project, dim)
        addSymbolTable(db, project, dim)
        addQueryLogTable(db, project)
        addDefTable(db, project)
        addDepTable(db, project)

        // Seed meta rows. ON CONFLICT DO NOTHING preserves any pre-existing
        // values — critically, an older schema keeps its old schema_version so
        // migrate() can see it needs upgrading.
        String model = envOr("EmbeddingModel", "nomic-embed-text:v1.5")
        db.execute("""
            INSERT INTO ${project}.rag_meta(key, value) VALUES
                ('embedding_model', '${model}'),
                ('embedding_dim',   '${dim}'),
                ('schema_version',  '${CURRENT_SCHEMA_VERSION}'),
                ('reindex_running', 'false')
            ON CONFLICT (key) DO NOTHING
        """.toString())
    }

    // ----- Migration ladder ---------------------------------------------------

    /**
     * Bring one project's schema up to {@link #CURRENT_SCHEMA_VERSION}, one
     * step at a time. Every step is idempotent, so a half-applied migration
     * (server killed mid-run) re-runs harmlessly.
     *
     * A step that invalidates existing rows sets rag_meta.rebuild_required,
     * which RAGIndexer honors by refusing an incremental sweep — an
     * incremental sweep only touches files whose sha256 changed, so it would
     * silently leave old rows with unpopulated new columns.
     */
    static void migrate(Connection db, String project) {
        int v = schemaVersion(db, project)
        if (v > CURRENT_SCHEMA_VERSION)
            throw new RuntimeException("Project '${project}' is at schema version ${v}, " +
                    "newer than this build supports (${CURRENT_SCHEMA_VERSION}). Upgrade Code-RAG.")
        while (v < CURRENT_SCHEMA_VERSION) {
            int next = v + 1
            logger.info("ProjectBootstrap[${project}]: migrating schema v${v} -> v${next}")
            applyMigration(db, project, next)
            db.execute("UPDATE ${project}.rag_meta SET value = ? WHERE key = 'schema_version'".toString(),
                    String.valueOf(next))
            db.commit()
            v = next
        }
    }

    private static void applyMigration(Connection db, String project, int toVersion) {
        switch (toVersion) {
            case 2:
                db.execute("ALTER TABLE ${project}.rag_chunk ADD COLUMN IF NOT EXISTS lexemes TEXT".toString())
                addLexemeSearchObjects(db, project)
                // Existing rows have NULL lexemes; only a full rebuild fills them.
                markRebuildRequired(db, project, "schema v2 added lexemes; existing chunks have none")
                break
            case 3:
                // Parent-symbol expansion: the line range of the enclosing
                // symbol, so a chunk that is one fragment of a large method can
                // be returned as the whole method.
                db.execute("ALTER TABLE ${project}.rag_chunk ADD COLUMN IF NOT EXISTS sym_start_line INT".toString())
                db.execute("ALTER TABLE ${project}.rag_chunk ADD COLUMN IF NOT EXISTS sym_end_line INT".toString())
                markRebuildRequired(db, project,
                        "schema v3 added sym_start_line/sym_end_line; existing chunks have none")
                break
            case 4:
                // Purely additive — no rebuild_required. Commit history and
                // per-file summaries are filled in by their own passes.
                addHistoryTable(db, project, embeddingDim())
                addSummaryColumns(db, project, embeddingDim())
                break
            case 5:
                addSymbolTable(db, project, embeddingDim())
                break
            case 6:
                addQueryLogTable(db, project)
                break
            case 7:
                addDefTable(db, project)
                break
            case 8:
                addDepTable(db, project)
                break
            default:
                throw new RuntimeException("No migration defined for schema version ${toVersion}")
        }
    }

    /**
     * Create the generated tsvector column and its GIN index for hybrid search.
     *
     * Called from BOTH {@link #ensureSchema} (fresh schemas, born at the current
     * version, which migrate() therefore skips) and the v2 migration step
     * (pre-existing schemas). Idempotent.
     *
     * {@code lexemes_tsv} is GENERATED from {@code lexemes} rather than written
     * by the indexer so the two can never drift out of sync. The 'simple'
     * configuration is required: 'english' would stem and stopword-strip
     * identifiers into mush.
     */
    private static void addLexemeSearchObjects(Connection db, String project) {
        db.execute("""
            ALTER TABLE ${project}.rag_chunk
                ADD COLUMN IF NOT EXISTS lexemes_tsv tsvector
                GENERATED ALWAYS AS (to_tsvector('simple', coalesce(lexemes, ''))) STORED
        """.toString())
        db.execute("""
            CREATE INDEX IF NOT EXISTS rag_chunk_lexemes_gin
                ON ${project}.rag_chunk USING gin (lexemes_tsv)
        """.toString())
    }

    /**
     * Per-project VCS commit history. Answers "why was this done", which no
     * amount of code search can: the rationale lives in commit messages, not
     * in the tree.
     *
     * {@code vcs} and {@code rev} are text rather than a git-specific sha
     * column because the configured roots are not all git — Stack360 is
     * Subversion, where a revision is an integer.
     */
    private static void addHistoryTable(Connection db, String project, int dim) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS ${project}.rag_commit (
                commit_id     BIGSERIAL PRIMARY KEY,
                repo          TEXT NOT NULL,
                vcs           TEXT NOT NULL,
                rev           TEXT NOT NULL,
                author        TEXT,
                committed_at  TIMESTAMPTZ,
                subject       TEXT NOT NULL,
                body          TEXT,
                files_changed TEXT,
                lexemes       TEXT,
                lexemes_tsv   tsvector GENERATED ALWAYS AS
                                  (to_tsvector('simple', coalesce(lexemes, ''))) STORED,
                embedding     vector(${dim}) NOT NULL,
                UNIQUE (repo, rev)
            )
        """.toString())
        db.execute("""
            CREATE INDEX IF NOT EXISTS rag_commit_embedding_hnsw
                ON ${project}.rag_commit USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
        """.toString())
        db.execute("""
            CREATE INDEX IF NOT EXISTS rag_commit_lexemes_gin
                ON ${project}.rag_commit USING gin (lexemes_tsv)
        """.toString())
    }

    /**
     * Per-file LLM summary and its embedding.
     *
     * {@code summary_sha} mirrors {@code rag_file.sha256} at the time the
     * summary was written, so an incremental pass regenerates only what
     * actually changed — summarization is by far the most expensive operation
     * in the system.
     */
    private static void addSummaryColumns(Connection db, String project, int dim) {
        db.execute("ALTER TABLE ${project}.rag_file ADD COLUMN IF NOT EXISTS summary TEXT".toString())
        db.execute("ALTER TABLE ${project}.rag_file ADD COLUMN IF NOT EXISTS summary_model TEXT".toString())
        db.execute("ALTER TABLE ${project}.rag_file ADD COLUMN IF NOT EXISTS summary_sha CHAR(64)".toString())
        db.execute(("ALTER TABLE ${project}.rag_file ADD COLUMN IF NOT EXISTS summary_embedding vector("
                + dim + ")").toString())
        db.execute("""
            CREATE INDEX IF NOT EXISTS rag_file_summary_hnsw
                ON ${project}.rag_file USING hnsw (summary_embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
        """.toString())
    }

    /**
     * Per-symbol LLM summaries, for large files only.
     *
     * A file-level summary is one paragraph however big the file is. On a
     * 649-chunk class that single paragraph cannot describe any particular
     * method, so the summary leg — the strongest signal for prose queries —
     * effectively stops working on exactly the largest, most-asked-about files.
     * This gives those files one summary per symbol instead.
     *
     * Keyed on (file_id, sym_start_line) because that is what rag_chunk records
     * for the enclosing symbol; symbol NAMES are not unique within a file
     * (overloads).
     */
    private static void addSymbolTable(Connection db, String project, int dim) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS ${project}.rag_symbol (
                symbol_id      BIGSERIAL PRIMARY KEY,
                file_id        BIGINT NOT NULL REFERENCES ${project}.rag_file(file_id) ON DELETE CASCADE,
                symbol         TEXT,
                sym_start_line INT NOT NULL,
                sym_end_line   INT NOT NULL,
                summary        TEXT NOT NULL,
                summary_model  TEXT,
                summary_sha    CHAR(64),
                embedding      vector(${dim}) NOT NULL,
                UNIQUE (file_id, sym_start_line)
            )
        """.toString())
        db.execute("""
            CREATE INDEX IF NOT EXISTS rag_symbol_embedding_hnsw
                ON ${project}.rag_symbol USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
        """.toString())
        db.execute("CREATE INDEX IF NOT EXISTS rag_symbol_file_idx ON ${project}.rag_symbol(file_id)".toString())
    }

    /**
     * Log of real searches and follow-up fetches.
     *
     * Every golden query set in this repo was written by hand, which makes it a
     * guess at what gets asked. This records what is ACTUALLY asked and which
     * results actually get opened, so the eval set can be mined from real use
     * (eval/mine.py) instead of invented. That closes the one caveat no amount
     * of tuning can: a metric is only as honest as its queries.
     *
     * Local-only and disabled with RAGLogQueries=false. Queries are recorded
     * verbatim, so treat this table as you would shell history.
     */
    private static void addQueryLogTable(Connection db, String project) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS ${project}.rag_query_log (
                log_id     BIGSERIAL PRIMARY KEY,
                at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                kind       TEXT NOT NULL,
                tool       TEXT,
                query      TEXT,
                paths      TEXT,
                latency_ms INT
            )
        """.toString())
        db.execute(("CREATE INDEX IF NOT EXISTS rag_query_log_at ON " +
                    project + ".rag_query_log(at)").toString())
    }

    /**
     * Symbol definitions, extracted by Universal Ctags.
     *
     * This is the structural half of the index, and it answers what embeddings
     * fundamentally cannot: where exactly is this defined, what calls it, what
     * breaks if I change it. Those are exact relations, not similarity.
     *
     * It also exists because the chunker's symbol column cannot be trusted for
     * this: its regex misses common declaration shapes (a generic return type
     * like {@code public List<Edge> findJoinPath(...)} is not matched), so
     * chunks get attributed to the wrong symbol. ctags parses the languages
     * properly.
     */
    private static void addDefTable(Connection db, String project) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS ${project}.rag_def (
                def_id     BIGSERIAL PRIMARY KEY,
                file_id    BIGINT NOT NULL REFERENCES ${project}.rag_file(file_id) ON DELETE CASCADE,
                name       TEXT NOT NULL,
                name_lower TEXT NOT NULL,
                kind       TEXT,
                scope      TEXT,
                signature  TEXT,
                line       INT NOT NULL,
                UNIQUE (file_id, name, line)
            )
        """.toString())
        db.execute(("CREATE INDEX IF NOT EXISTS rag_def_name_lower ON " +
                    project + ".rag_def(name_lower)").toString())
        db.execute(("CREATE INDEX IF NOT EXISTS rag_def_file ON " +
                    project + ".rag_def(file_id)").toString())
    }

    /**
     * The import graph: which file pulls in which other file.
     *
     * Answers the question an agent asks before touching anything — "what
     * breaks if I change this" — which neither similarity search nor a symbol
     * index can. Definitions tell you where something lives; this tells you
     * what is standing on top of it.
     *
     * {@code target} is the raw import text; {@code target_tail} is its last
     * segment (the class or module name), which is what actually joins back to
     * a file. Resolution is deliberately heuristic — a real resolver would need
     * per-language module paths, build config and classpath — so this is an
     * import graph, not a linker.
     */
    private static void addDepTable(Connection db, String project) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS ${project}.rag_dep (
                dep_id       BIGSERIAL PRIMARY KEY,
                file_id      BIGINT NOT NULL REFERENCES ${project}.rag_file(file_id) ON DELETE CASCADE,
                target       TEXT NOT NULL,
                target_tail  TEXT NOT NULL,
                kind         TEXT,
                line         INT,
                UNIQUE (file_id, target, line)
            )
        """.toString())
        db.execute(("CREATE INDEX IF NOT EXISTS rag_dep_tail ON " +
                    project + ".rag_dep(target_tail)").toString())
        db.execute(("CREATE INDEX IF NOT EXISTS rag_dep_file ON " +
                    project + ".rag_dep(file_id)").toString())
    }

    /**
     * Flag a project as needing a full rebuild — but only if it actually has
     * data. A freshly-created schema has nothing stale in it, and flagging it
     * would block its own first sweep.
     */
    private static void markRebuildRequired(Connection db, String project, String reason) {
        Record r = db.fetchOne("SELECT count(*) AS n FROM ${project}.rag_chunk".toString())
        long n = (r != null) ? r.getLong("n") : 0L
        if (n == 0L)
            return
        db.execute("""
            INSERT INTO ${project}.rag_meta(key, value) VALUES ('rebuild_required', ?)
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
        """.toString(), reason)
        logger.warn("ProjectBootstrap[${project}]: full rebuild required — ${reason}. " +
                "Run './bld scan ${project} --full'.")
    }

    /** Current schema version for a project; 1 when the row predates versioning. */
    private static int schemaVersion(Connection db, String project) {
        Record r = db.fetchOne(
                "SELECT value FROM ${project}.rag_meta WHERE key = 'schema_version'".toString())
        if (r == null)
            return 1
        try {
            return Integer.parseInt(r.getString("value"))
        } catch (Exception ignored) {
            return 1
        }
    }

    private static String envOr(String key, String dflt) {
        try {
            String v = MainServlet.getEnvironment(key)
            return (v != null && !v.isEmpty()) ? v : dflt
        } catch (Exception ignored) {
            return dflt
        }
    }
}
