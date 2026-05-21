# Plan2 — Multi-Project Support for the Local RAG System

> _Historical design doc — describes the system as it was built. Code, package names, and paths have moved on since; the README and Running.md are the source of truth for how the released system looks._

Author: Blake McBride
Date: 2026-05-21
Status: Draft — needs your sign-off before implementation
Builds on: [RAGPlan.md](RAGPlan.md)

## 1. Goals

- Index **multiple unrelated projects** in the same Kiss/RAG instance. Each
  project has its **own set of directory roots**, and its index is isolated
  from the others — `search_code` in project A must never return chunks from
  project B.
- Support **multiple simultaneous Claude Code sessions**, each scoped to one
  project. Concurrent reads (search) and a per-project reindex must work in
  parallel without stepping on each other.
- Keep one Kiss instance running. One PostgreSQL database. One Ollama daemon.
- No regression: the existing Stack360+Kiss index keeps working through the
  transition.

## 2. Non-Goals

- Cross-project search. By design.
- Per-project authentication. A single shared secret still gates the MCP
  endpoint — the user is the only client and trusts all of their projects.
- A web UI for managing projects. CLI/config-file driven only.
- Per-project embedding models. One model serves all projects (changing the
  model still implies a full rebuild — that constraint is unchanged).

## 3. Architecture at a glance

```
                  +-----------+   +-----------+   +-----------+
Claude Code A --> | MCP /rag/A|   | MCP /rag/B|   | MCP /rag/C|  <-- Claude Code C
                  +-----+-----+   +-----+-----+   +-----+-----+
                        |               |               |
                        v               v               v
                  +---------------------------------------+
                  |          One Kiss / Tomcat            |
                  +-------------------+-------------------+
                                      |
                            +---------+----------+
                            | RAGIndexer (per-   |
                            | project sweep)     |
                            +---------+----------+
                                      |
        +---------+-----------+-------+-------+---------+---------+
        v         v           v       v       v         v         v
   +---------+--------+--------+--------+-------+   +----------+
   | schema A| schema B|schema C|...    |       |   |  shared  |
   | rag_file| rag_file|rag_file|       |       |   |  Ollama  |
   | rag_chunk rag_chunk rag_chunk      |       |   |          |
   | rag_meta rag_meta rag_meta         |       |   +----------+
   +---------+--------+--------+--------+-------+
                  one PostgreSQL database
                       (claude_rag)
```

- **One Kiss process. One DB. One Ollama.** The only resource per project is
  a Postgres schema. Cheap.
- **MCP URL carries the project name.** Claude Code is configured with one
  MCP server per project, e.g. `http://127.0.0.1:8080/rag-mcp/stack360`.
- **Index isolation** is enforced by schema. Every SQL statement runs
  against `<project>.rag_file` / `<project>.rag_chunk` / `<project>.rag_meta`.

## 4. Config — how projects are declared

A new file alongside `application.ini`: **`src/main/backend/rag-projects.json`**.
Pure JSON, hand-edited, watched at startup (a Tomcat restart picks up
changes — same model as `application.ini`).

```json
{
  "projects": [
    {
      "name": "stack360",
      "roots": [
        "/path/to/Backend",
        "/path/to/Frontend",
        "/path/to/Mobile",
        "/path/to/Apply",
        "/path/to/Worker",
        "/path/to/Kiss"
      ],
      "excludeGlobs": [
        "**/node_modules", "**/node_modules/**",
        "**/work", "**/work/**",
        "**/target", "**/target/**",
        "**/.git", "**/.git/**",
        "**/*.jar",
        "**/tomcat", "**/tomcat/**",
        "**/build", "**/build/**",
        "**/Backend/src/java/com/arahant/db",
        "**/Backend/src/java/com/arahant/db/**"
      ]
    },
    {
      "name": "acme",
      "roots": ["/path/to/acme"],
      "excludeGlobs": ["**/node_modules/**", "**/.git/**", "**/*.jar"]
    }
  ]
}
```

Rules:
- **`name`** must match the PostgreSQL identifier rules: `[a-z][a-z0-9_]*`,
  lowercase, no hyphens. Enforced at startup; bad names abort the load with
  a clear error.
- **`roots`** is a list of absolute paths.
- **`excludeGlobs`** is optional; if missing, falls back to a sensible
  default list (the same one we settled on for stack360, minus the
  arahant-specific path).
- Removing a project from the file does **not** drop its schema — that's
  manual, so you can't lose data by typo.

Global knobs (`EmbeddingModel`, `OllamaURL`, pool sizes, sweep interval, etc.)
stay in `application.ini`. Per-project knobs only go in the JSON.

## 5. Database layout

Already in `claude_rag` (one database). Add one schema per project:

```sql
CREATE SCHEMA IF NOT EXISTS stack360;
SET search_path TO stack360, public;       -- pgvector lives in public
CREATE TABLE IF NOT EXISTS rag_meta  ( ... same as today ... );
CREATE TABLE IF NOT EXISTS rag_file  ( ... same as today ... );
CREATE TABLE IF NOT EXISTS rag_chunk ( ... same as today, vector(768) ... );
CREATE INDEX IF NOT EXISTS rag_chunk_embedding_hnsw
    ON rag_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
-- meta rows: embedding_model, embedding_dim, schema_version, reindex_running
```

`pgvector` is a database-level extension, so it's shared by every schema.
Same with the embedding meta convention (each schema keeps its own copy so a
future per-project model switch stays possible).

## 6. Migration of the existing index

The current data lives in `claude_rag.public.rag_file` / `…rag_chunk` /
`…rag_meta`. New addressing is **`claude_rag.<project>.rag_*`** — one
database, one schema per project, table names unchanged.

One-time migration: move the existing tables from `public` into a schema
named `stack360`:

```sql
CREATE SCHEMA IF NOT EXISTS stack360;
ALTER TABLE public.rag_file  SET SCHEMA stack360;
ALTER TABLE public.rag_chunk SET SCHEMA stack360;
ALTER TABLE public.rag_meta  SET SCHEMA stack360;
-- the hnsw index follows its table automatically
```

Done as a Groovy migration step that `KissInit.init2` runs once, gated by
checking whether `stack360.rag_meta` already exists (idempotent — re-runs
are no-ops).

Other option considered: keep `public` as an unnamed default and add new
schemas alongside. Rejected — leaves a confusing two-tier model forever.

## 7. Indexer changes

`RAGIndexer` is currently project-blind. Kiss's SQL API already supports
schema-qualified table names — `db.newRecord("stack360.rag_file")` and SQL
strings like `INSERT INTO stack360.rag_chunk …` work today (see
`Connection.tableExists` for the parser; the dotted-name path is the
canonical way). So we **always qualify table references with the project
schema** rather than juggling `SET search_path` on every checkout.

New entry points:

```groovy
RAGIndexer.runSweepJson(Connection db, String project)
RAGIndexer.runFullRebuildJson(Connection db, String project)
```

Inside, every reference becomes `<project>.rag_file`, `<project>.rag_chunk`,
`<project>.rag_meta` — including `verifyMetaMatches`, the lock UPDATE, the
`last_sweep` upsert, the `INSERT … ON CONFLICT`, and the `Record` calls.
No connection state to remember, no risk of a leaky search_path bleeding
between project sweeps in the cron loop.

## 8. Cron sweep — multi-project

`RAGSweep.start(db)` becomes:

```groovy
for each project in ProjectRegistry.list():
    use a fresh Kiss Connection
    SET search_path TO <project>, public
    try: tryAcquireLock(db); RAGIndexer.runSweepJson(db, project); releaseLock(db)
    catch / log per-project failure but continue
```

Sequential — multiple parallel sweeps would only fight Ollama for the GPU.
Failure in one project doesn't block the others.

## 9. Admin service

`services/RAGAdmin` gets a `project` arg on each call:

| Method | Args |
|---|---|
| `status({project?})` | If `project` is supplied, scoped to that project; else a list of per-project summaries |
| `reindex({project, full?})` | `project` required |
| `listProjects()` | New: returns the contents of `rag-projects.json` plus per-project chunk/file counts |

The lock SQL stays per-project (each schema has its own `rag_meta`).

## 10. MCP server

URL pattern changes to **`@WebServlet(urlPatterns="/rag-mcp/*")`**. The
project is the first path segment of `request.getPathInfo()`. If missing or
unknown, return 404 with a clear message.

The four tools (`search_code`, `get_chunk`, `list_repos`, `index_status`)
stay; their implementations grow a `SET search_path` step at the start of
each pooled-connection use. Tool semantics are unchanged otherwise — Claude
sees the same interface as today.

Authentication is unchanged: shared `X-RAG-Token`.

Claude Code registration (one per project the user works with):

```bash
claude mcp add --transport http rag-stack360 \
    http://127.0.0.1:8080/rag-mcp/stack360 \
    --header "X-RAG-Token: <secret>"

claude mcp add --transport http rag-acme \
    http://127.0.0.1:8080/rag-mcp/acme \
    --header "X-RAG-Token: <secret>"
```

In a given Claude Code session you enable only the project's MCP server. The
two sessions are then naturally isolated: each one only knows tools for its
own project's index.

## 11. Concurrency model

| Operation | Concurrency |
|---|---|
| Two sessions running `search_code` (different projects) | Fully parallel; independent connections, independent search_path, distinct tables |
| Two sessions running `search_code` (same project) | Parallel; pgvector reads share the index just fine |
| Reindex of project A while project B is being searched | Parallel; different tables, different locks |
| Two reindexes of the *same* project | Second is rejected by `rag_meta.reindex_running` (already implemented per-project) |
| Reindex of two different projects simultaneously | Allowed, but they will queue on Ollama (single GPU). Sequential in practice |
| Cron sweep mid-flight when a manual reindex starts | Manual reindex of that project waits / fails the lock. Cron of other projects keeps moving |

Bottom line: HTTP and Tomcat handle concurrent requests; PostgreSQL handles
concurrent schema access; the one shared resource is Ollama's GPU, and
embedding throughput is the natural rate limit.

## 12. Phased rollout

**Phase 2.1 — schema and config plumbing**
- Add `rag-projects.json` reader.
- Add `KissInit.init2` step that, for every project in the file:
  - Creates the schema if missing, runs the same DDL we used in Phase 0.
  - Seeds `rag_meta` (model, dim, schema_version, reindex_running=false).
- One-time migration step: `public` → `stack360`.

**Phase 2.2 — indexer / admin**
- Add `String project` param everywhere downstream of `RAGIndexer.runSweep`.
- `SET search_path` first thing in each project sweep.
- Update `RAGAdmin.status` / `reindex` to take `project`.
- Update `CronTasks/RAGSweep` to loop over projects.

**Phase 2.3 — MCP server**
- Change `@WebServlet` URL pattern to `/rag-mcp/*`.
- Parse project from path; 404 on unknown.
- Each tool method: `SET search_path TO <project>` before its SQL.

**Phase 2.4 — verify**
- Existing Stack360 queries still work (now via `/rag-mcp/stack360`).
- Add a second tiny project (a few files in `/tmp/test`) — confirm it
  indexes independently and that cross-project queries are impossible.
- Run two concurrent `search_code` calls from `curl` against different
  project URLs; confirm independence.
- Re-add MCP entries in Claude Code; verify.

Each sub-phase is a self-contained landing.

## 13. Decisions to confirm

Pick or ack each before we start cutting code:

1. **Schema name rule** — lowercase `[a-z][a-z0-9_]*`. Cuts off project names
   that contain hyphens or capitals; names need to be PG-safe. OK?
2. **Migrate `public` → `stack360`** at startup, idempotent and one-shot.
   Alternative is to keep `public` as the implicit default forever — I
   recommend against it; the explicit-only model is cleaner.
3. **`rag-projects.json` location**: `src/main/backend/rag-projects.json`
   next to `application.ini`. OK?
4. **Sequential cron across projects** rather than parallel. OK?
5. **No per-project secrets** — the single `RAGMCPSharedSecret` gates
   everything. OK for single-user local setup?
6. **Project removed from config does NOT drop its schema.** You'd `DROP
   SCHEMA <name> CASCADE` manually when you really mean it. OK?

## 14. Estimated scope

Roughly:
- ~80 lines: `rag-projects.json` reader + schema-init helper.
- ~30 lines: migration of `public` → `stack360`.
- ~60 lines: project param propagation through `RAGIndexer` / `RAGAdmin` /
  `RAGSweep`.
- ~40 lines: MCP URL parsing + per-tool `SET search_path`.
- ~30 lines: tests / verification (curl scripts, sample second project).

Order of magnitude: the same as one of the prior phases (a few hours
focused). Not too large to do in one pass, but enough that getting the
design right first is worth this plan.

## 15. Open questions / smaller decisions

These can be made during implementation, not blocking:

- **`status` with no project arg** — return a per-project summary list, or
  default to a "current" project? I'd lean toward "list everything".
- **Logging** — prefix each `RAGIndexer`/`RAGSweep` log line with the
  project name so multi-project sweeps are debuggable.
- **Project rename** — out of scope for v1; document as "drop the schema
  and re-index". If it becomes annoying we add an `ALTER SCHEMA RENAME`
  helper later.

---

Once you ack §13 (or amend it), I'll execute Phase 2.1 → 2.4 in order. The
total is comparable to a single previous phase.
