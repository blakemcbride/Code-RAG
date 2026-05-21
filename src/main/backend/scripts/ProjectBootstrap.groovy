package scripts

import org.kissweb.rag.ProjectRegistry
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.kissweb.database.Connection

/**
 * One-time-per-startup schema housekeeping for the multi-project RAG layout.
 *
 * Responsibilities:
 *   1) For each project listed in rag-projects.json, CREATE SCHEMA IF NOT
 *      EXISTS plus the per-project rag_file / rag_chunk / rag_meta tables
 *      and the HNSW index. Seeds the standard meta rows. Idempotent.
 *   2) Reset reindex_running='false' so a server crash mid-sweep does not
 *      leave any project permanently locked.
 *
 * Schema names come from ProjectRegistry, which validates them against
 * [a-z][a-z0-9_]*; string-interpolating them into SQL is safe.
 */
class ProjectBootstrap {

    private static final Logger logger = LogManager.getLogger(ProjectBootstrap.class)
    private static final int EMBEDDING_DIM = 768

    static void ensureAll(Connection db) {
        for (ProjectRegistry.Project p : ProjectRegistry.load()) {
            ensureSchema(db, p.name)
            // Recovery: a previous crash may have left the lock set.
            db.execute("UPDATE ${p.name}.rag_meta SET value = 'false' WHERE key = 'reindex_running'".toString())
        }
        db.commit()
    }

    private static void ensureSchema(Connection db, String project) {
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
                embedding   vector(${EMBEDDING_DIM}) NOT NULL
            )
        """.toString())

        db.execute("CREATE INDEX IF NOT EXISTS rag_file_repo_path ON ${project}.rag_file(repo, path)".toString())
        db.execute("CREATE INDEX IF NOT EXISTS rag_chunk_file_idx ON ${project}.rag_chunk(file_id)".toString())
        db.execute("""
            CREATE INDEX IF NOT EXISTS rag_chunk_embedding_hnsw
                ON ${project}.rag_chunk USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
        """.toString())

        // Seed meta rows. ON CONFLICT DO NOTHING preserves any pre-existing values.
        db.execute("""
            INSERT INTO ${project}.rag_meta(key, value) VALUES
                ('embedding_model', 'nomic-embed-text:v1.5'),
                ('embedding_dim',   '${EMBEDDING_DIM}'),
                ('schema_version',  '1'),
                ('reindex_running', 'false')
            ON CONFLICT (key) DO NOTHING
        """.toString())
    }
}
