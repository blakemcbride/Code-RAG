# Claude-RAG — Design Reference

This is the design doc. Read it after [Overview.md](Overview.md) when you
want to extend, debug, or fork the system. The README, Overview, Setup,
and Running docs are written for users; this one is written for the next
developer.

## 1. Goal

Give Claude Code a fast, local, private semantic-search facility over one
or more local codebases. Claude Code reaches the system through an MCP
tool call; the tool returns the most relevant chunks of source / docs so
Claude can `Read` exactly the right lines rather than blindly grepping
or stuffing files into context.

Hard constraints:

- **Local only.** No outbound network calls. The local LLM (Ollama) is
  used only for embeddings; generation stays with whatever cloud model
  Claude Code is already configured to use.
- **Project isolation.** A query against project A never returns chunks
  from project B.
- **Single-process.** One Kiss/Tomcat instance, one PostgreSQL database,
  one Ollama daemon, one box. No cloud, no Kubernetes, no
  microservices.

## 2. Architecture

```
+----------------+        MCP / JSON-RPC over HTTP        +------------------------+
|  Claude Code   | --------- search_code, etc. ---------> |  Kiss / Tomcat server  |
|  CLI session   | <----- hits: path + lines + score ---- |  /rag-mcp/<project>    |
+----------------+                                        +-----------+------------+
                                                                      |
                       +------------------------+ ----- JDBC -------> |
                       |  admin svc + cron      |                     |
                       |  (Groovy, hot-reload)  |                     v
                       +-----------+------------+      +-------------------------------+
                                   |                   |  PostgreSQL (claude_rag DB)   |
                                   v                   |  one schema per project       |
                       +------------------------+      |    <project>.rag_file         |
                       |  RAGIndexer (Groovy)   | ---> |    <project>.rag_chunk        |
                       |  walk → chunk → embed  |      |    <project>.rag_meta         |
                       +-----+-------------+----+      |  + pgvector HNSW index        |
                             |             |           +-------------------------------+
                             |             v
                             |     +---------------+
                             |     |    Ollama     |
                             |     | nomic-embed-  |
                             |     | text:v1.5     |
                             |     +---------------+
                             v
                       +------------+
                       | your code  |
                       | (read-only)|
                       +------------+
```

The four moving pieces:

| Piece | Implementation | Lives in |
|---|---|---|
| MCP server | Java, extends `MCPServerBase` | `src/main/precompiled/org/kissweb/rag/RAGMCPServer.java` |
| Indexer + admin + cron | Groovy, hot-reloadable | `src/main/backend/{scripts,services,CronTasks}/` |
| Project config + schema bootstrap | Java + Groovy | `ProjectRegistry.java`, `ProjectBootstrap.groovy` |
| Vector store | PostgreSQL + pgvector | `claude_rag.<project>.*` |

Everything but Claude Code itself runs on `127.0.0.1`. Tomcat binds to
localhost only.

## 3. Vector store

One database (`claude_rag`), one schema per project, identical table
layout in each schema.

```sql
CREATE EXTENSION IF NOT EXISTS vector;          -- once, in claude_rag
CREATE SCHEMA  IF NOT EXISTS <project>;          -- once per project, automatic

-- Per project — `<project>.rag_meta`:
CREATE TABLE IF NOT EXISTS <project>.rag_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
-- Seeded rows: embedding_model, embedding_dim, schema_version,
-- reindex_running (the per-project lock), last_sweep (JSON blob).

-- Per project — `<project>.rag_file`:
CREATE TABLE IF NOT EXISTS <project>.rag_file (
    file_id     BIGSERIAL PRIMARY KEY,
    repo        TEXT        NOT NULL,    -- last segment of the root path
    path        TEXT        NOT NULL,    -- relative to that root
    sha256      CHAR(64)    NOT NULL,    -- content hash at last index
    mtime       TIMESTAMPTZ NOT NULL,
    size_bytes  BIGINT      NOT NULL,
    language    TEXT,
    indexed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (repo, path)
);

-- Per project — `<project>.rag_chunk`:
CREATE TABLE IF NOT EXISTS <project>.rag_chunk (
    chunk_id    BIGSERIAL PRIMARY KEY,
    file_id     BIGINT      NOT NULL REFERENCES <project>.rag_file(file_id) ON DELETE CASCADE,
    chunk_no    INT         NOT NULL,
    start_line  INT         NOT NULL,
    end_line    INT         NOT NULL,
    symbol      TEXT,                    -- enclosing fn/class if recognized
    content     TEXT        NOT NULL,
    token_est   INT         NOT NULL,
    embedding   vector(768) NOT NULL
);
CREATE INDEX rag_chunk_embedding_hnsw
    ON <project>.rag_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX rag_chunk_file_idx ON <project>.rag_chunk(file_id);
CREATE INDEX rag_file_repo_path ON <project>.rag_file(repo, path);
```

Sizing notes:

- 768-dim `float4` vector ≈ 3 KB per chunk; HNSW index adds ~1 KB. A
  100k-chunk project is well under 1 GB total.
- Embedding dimensionality is locked at index time. Changing
  `EmbeddingModel` is a full rebuild — the seed `rag_meta.embedding_dim`
  row is checked at every sweep start and a mismatch aborts.
- The schema name **is** the project name. `ProjectRegistry` validates
  it against `[a-z][a-z0-9_]*` so string-interpolating it into SQL is
  safe (no escaping needed).

## 4. Embedding

Single model for both ingestion and query. Default `nomic-embed-text:v1.5`
served by Ollama. The model + dimension are stored in every project's
`rag_meta`; the indexer refuses to run if the configured model's
dimension does not match the stored one.

### Batching, the right way

Ollama's `/api/embed` checks the **cumulative** token count across the
whole `input` array against the model's context window, not each input
independently. A batch of 32 small chunks can fail with "input length
exceeds the context length" even though every individual chunk fits.
Sending `options.num_ctx` on embed requests is silently ignored.

So the indexer batches **by UTF-8 bytes**, not chunk count
(`EmbeddingMaxBatchBytes`, default 6000). If a batch is rejected with
400 / "context length", the range is halved and retried — recursively
down to one chunk, then to a truncated single chunk, before giving up
on that specific chunk and continuing the file. In practice the
chunker enforces a 1500-char per-chunk cap so this fallback almost
never fires.

### Query embedding

The MCP server embeds the user's query with the **same** model. Same
model is essential — cosine similarity between embeddings from
different models is meaningless.

## 5. Indexer

### 5.1 Walking

`Files.walkFileTree` from each configured project root. Glob exclude
patterns are applied at both `preVisitDirectory` (skip whole subtrees)
and `visitFile` (skip individual files). The default exclude list
covers `node_modules`, `.git`, `build`, `target`, `tomcat`, `*.jar`,
etc.; each project can override via `excludeGlobs` in
`rag-projects.json`.

File classification: by extension via `LANG_MAP`, then by basename via
`FILENAME_MAP` (for `Makefile`, `Dockerfile`, `Jenkinsfile`, etc.).
Unrecognized extensions are silently skipped. Binary files (NUL byte in
the first 8 KB) are also skipped. Files larger than `RAGMaxFileBytes`
(default 1 MB) are skipped.

### 5.2 Chunking

Three modes, picked by language:

1. **Markdown by heading** — `chunkMarkdown` splits at H1/H2 boundaries.
2. **Symbol-aware** — `chunkBySymbols` with one of seven per-family
   regex patterns:

   | Pattern | Used for |
   |---|---|
   | `JVM_LIKE_SYM_RE` | Java, Groovy, JS, TS, Kotlin, Scala, C#, Swift, Dart |
   | `C_LIKE_SYM_RE` | C, C++, Objective-C/C++ |
   | `KW_LED_SYM_RE` | Python, Ruby, Elixir |
   | `RUST_SYM_RE` | Rust |
   | `GO_SYM_RE` | Go |
   | `PHP_SYM_RE` | PHP |
   | `LISP_SYM_RE` | Lisp, Scheme, Racket, Clojure |

3. **Fixed-window fallback** — 60-line sliding window with 10-line
   overlap for any language that has no symbol regex (HTML, CSS, SQL,
   configs, plain text, etc.) or whose symbol regex fails to find
   matches.

`extractSymbolName` skips language keywords (`func`, `class`, `def`,
…) when picking the human-friendly label, and falls back to the last
non-keyword identifier on the line so `class Counter`, `module Words`,
`def initialize` get labeled correctly.

### 5.3 Char-budget split

Every chunk that comes out of the language-specific chunker is then
passed through `splitLargeChunk`, which recursively halves the chunk
by lines until each piece is ≤ `MAX_CHUNK_CHARS = 1500`. A single
oversize line (rare: minified JS) is sliced by character offset. This
strict cap is what lets us drop the embed-time truncation that
otherwise loses content from the index.

### 5.4 Change detection

`SHA-256(content)` is stored on the `rag_file` row. On each sweep:

1. New file → embed all chunks, INSERT.
2. SHA matches → "unchanged", skipped.
3. SHA differs → delete the file's existing chunks, re-embed, re-INSERT.
4. File present in `rag_file` but no longer on disk → DELETE (cascades
   to chunks).

The full-rebuild path TRUNCATEs both tables and restarts identity.

### 5.5 Transaction discipline

Kiss's `Connection` runs with `autoCommit=false`. Two specific things
the indexer does because of that:

- **Commit every `RAGCommitBatch` files** (default 50). One commit per
  file would amortize fsync cost poorly; one commit per sweep would
  hold an enormous transaction and lose everything on a mid-run
  failure. Commit-every-50 saves >95 % of the fsync overhead while
  keeping the recovery window small.
- **Rollback inside `visitFile`'s catch block.** If a chunk INSERT
  fails (most commonly because Ollama rejected an embed for a single
  pathological file), the JDBC transaction enters PostgreSQL's
  aborted-until-rollback state and every subsequent INSERT throws
  "current transaction is aborted, commands ignored". The catch
  explicitly rolls back so the next file starts a clean transaction.

The file insert itself uses `INSERT … ON CONFLICT DO UPDATE` keyed on
`(repo, path)`. Robust against a residual row from a partially
rolled-back prior attempt, and lets the indexer's logic be the same
for "new file" and "changed file".

### 5.6 Reindex lifecycle

Triggered from three places, all of which share the same per-project
lock row in `rag_meta`:

| Trigger | Code |
|---|---|
| Cron, every `RAGSweepMinutes` (default 10) | `CronTasks/RAGSweep.groovy` |
| Manual via JSON-RPC | `services/RAGAdmin.reindex` (admin endpoint at `/rest`) |
| Backend hot-reload | `RAGIndexer.runSweep / runFullRebuild` static methods |

Manual reindex spawns a daemon `Thread`. It opens its **own** JDBC
connection (not from c3p0's pool) — c3p0's default
`unreturnedConnectionTimeout` of 60 s would otherwise forcibly close
the indexer's long-held connection mid-run. The bg thread pattern also
sidesteps Tomcat's 30-second async-context timeout for
`AsyncContext.startAsync`, which Kiss doesn't extend.

The lock is acquired with `UPDATE <project>.rag_meta SET value='true'
WHERE key='reindex_running' AND value='false' RETURNING key` — atomic
compare-and-set, no separate read-then-write race. `KissInit.init2`
resets every project's `reindex_running` row to `'false'` at every
startup so a crash mid-sweep does not permanently lock the project.

### 5.7 Sweep stats

Every sweep persists its `SweepStats` (scanned/skipped/indexed/
unchanged/deleted/errored/chunksInserted/elapsedMs/fullRebuild/
completedAt) as a JSON blob in `<project>.rag_meta(key='last_sweep')`.
Both the admin `status` service and the MCP `index_status` tool
surface this so callers know when the last sweep ran and how long it
took.

## 6. MCP server

Mapped at `/rag-mcp/*` so the first URL path segment can be the project
name. `MCPServerBase.doPost` is `final`, so the project name is
extracted inside `authenticate(request, response)` and stashed in a
`ThreadLocal` for `callTool` to read.

### 6.1 Auth

Single shared secret. The client must send `X-RAG-Token: <secret>`
matching `RAGMCPSharedSecret` in `application.ini`. Any other value, or
missing header, returns 401 before the request is dispatched.

A missing project (`/rag-mcp`), or a path segment that does not match a
configured project in `rag-projects.json`, returns 404. Project name
validation is the same `[a-z][a-z0-9_]*` rule used elsewhere, so this
is also where someone fuzzing the URL can't sneak SQL into the
schema-name string interpolation.

### 6.2 Tools

| Tool | Returns |
|---|---|
| `search_code` | Top-K hits (chunk_id, repo, path, absolute_path, start_line, end_line, symbol, score, snippet). Optional repo/language/path_prefix filters. |
| `get_chunk` | The full content + metadata of one chunk by id. |
| `list_repos` | One row per root configured for the project: name + file count + bytes + absolute root path. |
| `index_status` | File / chunk counts, last-sweep stats, embedding meta, `indexing` boolean. |

Reindex is deliberately **not** exposed via MCP. The single source of
truth for "is a sweep running for this project" is `rag_meta`, and
both the cron and the JSON-RPC `RAGAdmin.reindex` honor it. Adding a
third caller via MCP gains nothing and adds an attack surface. Manual
reindex happens via the admin endpoint.

### 6.3 HNSW search depth

pgvector's default `ef_search` is 40. At ~100k chunks, that depth
returns approximate nearest neighbors that visibly miss true top hits.
The MCP server sets `SET hnsw.ef_search = <HNSWEfSearch>` (default 400)
per query — comfortable up to ~100k chunks at roughly 5× the per-query
cost of the default. Tunable via `application.ini` for larger indexes.

## 7. Configuration

Three files. Each has a specific role.

### `application.ini` — global runtime knobs (gitignored)

Created by `setup.sh` from `application.ini.example`. Contains:

- DB connection (`DatabaseHost/Port/Name/User/Password`)
- Ollama (`OllamaURL`, `EmbeddingModel`, `EmbeddingDim`,
  `EmbeddingBatch`, `EmbeddingMaxBatchBytes`)
- Indexer (`RAGSweepMinutes`, `RAGMaxFileBytes`, `RAGCommitBatch`)
- pgvector (`HNSWEfSearch`)
- MCP auth (`RAGMCPSharedSecret`)
- c3p0 (`DatabaseMaxStatements=0`,
  `DatabaseMaxStatementsPerConnection=0`,
  `DatabaseUnreturnedTimeout=0` — required values explained below)

### `rag-projects.json` — what to index (gitignored)

Created by `setup.sh` from the `.example`. List of projects; each entry
has `name`, `roots` (absolute paths), and optional `excludeGlobs`. The
example file in the repo declares one placeholder `"demo"` project so
the schema rule is obvious.

### `CronTasks/crontab` — sweep schedule

Standard crontab format. Default is `*/10 * * * * RAGSweep` — every 10
minutes. The cron sweep iterates every configured project sequentially
and acquires the per-project lock around each one; a manual reindex
running for one project skips that project on this firing without
blocking the others.

## 8. Concurrency

| Scenario | Behavior |
|---|---|
| Two `search_code` calls (same or different projects) | Fully parallel; pgvector reads share the HNSW index |
| `search_code` while project A is reindexing | Parallel; HNSW reads see committed state, latest commit boundary is per-50-files |
| Two reindexes of the **same** project | Second is rejected (`started: false`) by the per-project lock |
| Reindex of project A while project B is reindexing | Allowed, but they queue on the shared Ollama GPU |
| Cron + manual reindex of the same project | Whichever wins the lock first runs; the other no-ops |

The lock survives Kiss's Groovy hot-reload because it's in PostgreSQL,
not in a JVM `AtomicBoolean`.

## 9. Failure modes and recovery

| Failure | How it's handled |
|---|---|
| Single file fails to embed (Ollama 400, malformed content, etc.) | `visitFile` catches, logs, rolls back the transaction, continues |
| Chunk INSERT fails | Same as above — the file is marked errored, txn rolled back, next file is clean |
| Bg thread crashes mid-sweep | Lock is reset on next Kiss startup; `KissInit.init2` does this |
| File listed in `rag_file` but deleted from disk | Detected during walk (seen-set diff), DELETEd on the next sweep |
| File modified on disk | SHA mismatch on next sweep → chunks deleted + re-embedded |
| Embedding model dimension changed | Mismatch with `rag_meta.embedding_dim` aborts the sweep with a clear error |
| Server crashes during full rebuild | TRUNCATE already committed; on restart the project's `rag_file`/`rag_chunk` are empty. Next sweep rebuilds. |
| Postgres restart while indexing | c3p0 reconnects; in-flight INSERT fails; visitFile catches and continues |
| Ollama down | Embed call fails on first request; sweep raises a clear error and aborts. Retry once Ollama is back |

## 10. Security

Single-user, local-only.

- Tomcat binds 17080 to `127.0.0.1` only (no LAN exposure).
- Every MCP request must carry `X-RAG-Token` matching the shared secret
  in `application.ini`.
- Admin endpoints at `/rest` are whitelisted in `KissInit.init` so they
  bypass Kiss's session auth. That's fine because Tomcat is loopback-
  only; if you ever expose 17080 to the network, restore the session
  check.
- Project schema names are validated against `[a-z][a-z0-9_]*`. The
  same regex gates JSON-RPC `project` parameters and MCP URL path
  segments. Any string interpolated into SQL has been through this
  filter.
- All secrets live in `application.ini`, which is **gitignored**. The
  `.example` template uses a `REPLACE_WITH_RANDOM_UUID` placeholder
  that `setup.sh` fills with a freshly generated UUID at first run,
  then `chmod 600`s the live file.

## 11. Notable design choices (and why)

These are the non-obvious calls that shaped the implementation.

**Per-project schema, not a discriminator column.**
Searches always restrict to one project. Schemas give crisp isolation
(can't accidentally cross-query), per-project HNSW indexes that don't
have to share statistics, and clean per-project backup / drop.

**`SET hnsw.ef_search = 400`, not the default 40.**
At ~100k chunks, the default ef_search misses true top hits often
enough to be visible in casual use. 400 is comfortable up to that scale
at modest cost.

**Char-budgeted chunks (≤ 1500), not token-budgeted.**
Cheap to enforce, language-agnostic, and the byte-budgeted Ollama
batcher then never trips the cumulative-token check. Doing this in
characters instead of tokens means no tokenizer dependency.

**Schema-qualified SQL, not `SET search_path`.**
Kiss's SQL API accepts `db.newRecord("<project>.rag_file")` and raw SQL
with `<project>.rag_*` table names directly. Qualified names mean no
connection state to track when the cron sweeps multiple projects
in a single Kiss connection.

**Background thread for reindex, dedicated JDBC connection.**
Tomcat's `AsyncContext.startAsync` default timeout is 30 s — Kiss
doesn't extend it. A 10-minute reindex via the HTTP service would die
mid-flight. The bg thread also opens a fresh JDBC connection rather
than checking one out of c3p0, because c3p0's default
`unreturnedConnectionTimeout=60` would forcibly close a long-held
pool connection.

**`DatabaseMaxStatements=0`, `DatabaseMaxStatementsPerConnection=0`.**
c3p0's async prepared-statement prefetcher races with the bulk indexer's
INSERT pattern; the prefetcher tries to acquire a cached statement on
a connection that gets closed under it, throwing
`PSQLException: This connection has been closed`. Both must be 0 to
fully disable the prefetcher.

**`SET unreturnedConnectionTimeout = 0`.**
Companion to the above. The default reaper would kill the bg thread's
connection at 60 s. We're single-user and trust services not to leak;
unset it.

**`INSERT … ON CONFLICT DO UPDATE` for `rag_file`, not Kiss's Record API.**
Robust against duplicate-key errors that arise when a prior attempt
left a row in an aborted-but-not-yet-rolled-back transaction. Also
collapses "insert new file" and "update changed file" into one code
path.

**Use `fetchAll`, not `fetchOne`, for `… RETURNING …`.**
Kiss's `fetchOne` wraps the SQL with `LIMIT 1`, which PostgreSQL
rejects after a `RETURNING` clause. `fetchAll` does no wrapping.

**Workaround for `Connection.tableExists` schema cache.**
Kiss's `tableExists("schema.table")` strips the schema before keying
its cache. A second schema-qualified lookup returns a stale answer
from the first. The bootstrap reads `information_schema.tables`
directly to avoid the cache.

**Cross-Groovy-file calls go through `GroovyService.run`.**
Kiss compiles each backend Groovy file in its own classloader; one
file can't import another's classes via a normal `import`. Calls
between admin/cron/indexer use `GroovyService.run("scripts",
"RAGIndexer", "runSweepJson", null, db, project)`. Shared utility
code that's referenced from multiple places (`ProjectRegistry`)
lives in `precompiled/` instead.

**Reindex not exposed via MCP.**
Reindex is destructive (full rebuild TRUNCATEs); the lock model needs
exactly one entry point per process to be sane. The admin endpoint
already exists; adding a second entry point for the same operation
isn't worth the surface area.

**One Ollama instance shared across projects.**
GPU contention is real but bounded. Indexing one project at a time
keeps embed throughput predictable; queries from other projects share
the GPU but each query is ~30 ms.

## 12. Hardware sizing

The reference machine for this build: 32-core CPU, 64 GB RAM, NVIDIA
4070 16 GB, NVMe SSD, Linux + PostgreSQL 18 + Java 21 + Ollama.
Numbers below are from that machine.

- **Full rebuild** of ~10 k files / ~100 k chunks: ~10–25 minutes.
  Bottleneck is Ollama embed throughput; the GPU is the limiter, not
  the CPU or disk.
- **Incremental sweep** of the same project (everything unchanged):
  ~1–2 seconds.
- **Query** via MCP: ~50–80 ms end-to-end (Ollama embed ~30 ms,
  pgvector HNSW search ~5 ms with ef_search=400, JSON round-trip
  the rest).
- **Disk**: ~3 KB per chunk vector + ~1 KB HNSW overhead. A 100k-chunk
  index is well under 1 GB.
- **RAM**: HNSW likes shared buffers, but the working set fits
  comfortably in 4 GB of `shared_buffers`. PostgreSQL defaults are
  fine.

CPU-only (no GPU) cuts embed throughput by an order of magnitude;
ten-minute rebuilds become two-hour rebuilds. Steady-state operation
is still fine on CPU because incremental sweeps almost never re-embed.

## 13. Language coverage

50+ extensions in `LANG_MAP`, plus `FILENAME_MAP` for extensionless
filenames (`Makefile`, `Dockerfile`, `Jenkinsfile`, …). Symbol-aware
chunking for: Java, Groovy, JavaScript, TypeScript, Kotlin, Scala,
C#, Swift, Dart, C, C++, Objective-C, Python, Ruby, PHP, Rust, Go,
Elixir, Lisp, Scheme, Racket, Clojure. Markdown gets heading-based
chunking. Every other recognized language gets the fixed-window
fallback (still searchable, just coarser).

Adding a new language family is a one-line addition to `LANG_MAP`
plus, optionally, a new symbol-pattern regex in
`RAGIndexer.symRegexFor`.

## 14. Non-goals

- Cross-project search. By design.
- Per-project secrets / per-project auth. Single shared secret is
  enough for single-user local use.
- Automatic dropping of a project's schema when you remove it from
  `rag-projects.json`. Manual `DROP SCHEMA … CASCADE` only — a typo
  in the config must not destroy data.
- Cloud-hosted embeddings. Deliberately local-only.
- A second LLM for generation. The local LLM is for embeddings;
  generation is the cloud model that Claude Code already calls.
- Web UI for browsing the index. `psql` and the admin endpoint are
  enough.

## 15. Where to look next

| For | Read |
|---|---|
| Pitch + quick start | [README.md](README.md) |
| What the system does and what it requires | [Overview.md](Overview.md) |
| Step-by-step install + first index | [Setup.md](Setup.md) |
| Daily operation + troubleshooting | [Running.md](Running.md) |
| This file | The design and decisions reference |
