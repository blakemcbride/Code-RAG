# Local RAG System for Claude Code — Plan

> _Historical design doc — describes the system as it was built. Code, package names, and paths have moved on since; the README and Running.md are the source of truth for how the released system looks._

Author: Blake McBride
Date: 2026-05-21
Status: Draft

## 1. Goal

Give Claude Code a fast, local, private semantic-search facility over a large
codebase (initially Stack360, ~thousands of files across Backend, Frontend,
Mobile, etc.). Claude Code reaches the system through an MCP tool call; the
tool returns the most relevant chunks of source/documentation so Claude can
read them with `Read` rather than blindly grepping or stuffing files into
context.

Non-goals:
- This is **not** a chatbot. The local LLM is used **only** for embeddings,
  not for generation. Generation stays with Claude (cloud).
- Not a code-execution sandbox.
- Not a replacement for `Grep`/`Read` — it is an additional retrieval channel
  for when keyword search is too narrow or too noisy.

## 2. Three-Piece Architecture

```
+-----------------+        MCP/JSON-RPC (HTTP)        +-----------------------+
|  Claude Code    | --------------------------------> |   Kiss MCP Server     |
|  (CLI)          | <-------------------------------- |   (this project)      |
+-----------------+        tool results               +-----------+-----------+
                                                                  |
                                                                  | JDBC
                                                                  v
+-----------------------+   HTTP    +--------------+        +-----------+
|   Indexer / Watcher   |---------->|   Ollama     |        | PostgreSQL|
|   (Kiss CronTask +    |  embed    | nomic-embed- |        | 18.3 +    |
|    one-shot tool)     |<----------| text         |        | pgvector  |
+-----------+-----------+   vectors +--------------+        +-----------+
            |                                                     ^
            | walk + chunk + hash                                  |
            v                                                     |
   +-----------------+                                            |
   | Stack360 source |                                            |
   | tree (read-only)|--------------------- writes chunks --------+
   +-----------------+
```

The three pieces the user identified, mapped concretely:

| Piece | Implementation | Lives in |
|---|---|---|
| MCP server | Java class extending `MCPServerBase` | `src/main/precompiled/` |
| Indexer / vector-DB builder | Groovy services + CronTask | `src/main/backend/` |
| Embedding LLM | Ollama running `nomic-embed-text` | local daemon, port 11434 |
| Vector store | PostgreSQL 18.3 + `pgvector` | local Postgres |

Everything runs on one machine; no external network calls.

## 3. Vector Store: PostgreSQL + pgvector

PostgreSQL 18.3 is already installed locally. Add the `pgvector` extension
(packaged as `postgresql-18-pgvector` on Fedora, or build from source).

### Schema (sketch)

```sql
CREATE EXTENSION IF NOT EXISTS vector;

-- One row per indexed source file. Lets us detect deletions and re-chunk
-- only changed files.
CREATE TABLE rag_file (
    file_id     BIGSERIAL PRIMARY KEY,
    repo        TEXT        NOT NULL,        -- 'Stack360/Backend', etc.
    path        TEXT        NOT NULL,        -- repo-relative path
    sha256      CHAR(64)    NOT NULL,        -- content hash at last index
    mtime       TIMESTAMPTZ NOT NULL,
    size_bytes  BIGINT      NOT NULL,
    language    TEXT,                        -- 'java', 'groovy', 'js', 'md', ...
    indexed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (repo, path)
);

-- One row per chunk. embedding dimensionality matches the chosen model.
-- nomic-embed-text => 768. Pick model at design time; do not mix dims.
CREATE TABLE rag_chunk (
    chunk_id    BIGSERIAL PRIMARY KEY,
    file_id     BIGINT      NOT NULL REFERENCES rag_file(file_id) ON DELETE CASCADE,
    chunk_no    INT         NOT NULL,        -- 0-based ordinal within file
    start_line  INT         NOT NULL,
    end_line    INT         NOT NULL,
    symbol      TEXT,                        -- enclosing fn/class if known
    content     TEXT        NOT NULL,
    token_est   INT         NOT NULL,
    embedding   vector(768) NOT NULL
);

CREATE INDEX rag_chunk_embedding_hnsw
    ON rag_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX rag_chunk_file_idx ON rag_chunk(file_id);
CREATE INDEX rag_file_repo_path ON rag_file(repo, path);
```

Notes:
- HNSW > IVFFlat for read-heavy, single-machine workloads at this scale.
- 768-dim float4 chunks ≈ 3 KB each; 200k chunks ≈ 600 MB plus index. Fits
  in RAM easily on a 64 GB box.
- All connection settings go through `MainServlet.getEnvironment()` from
  `application.ini` per Kiss conventions (do not hard-code DB creds).

## 4. Embedding Model (Ollama)

Run Ollama locally (already installed). Default model:

- **`nomic-embed-text`** — 768 dim, 8K context, Apache-licensed, strong on
  mixed code+prose. Fast on the 4070.

Alternatives to A/B later: `mxbai-embed-large` (1024 dim, higher quality,
slower), `bge-m3` (multilingual + long context).

Invocation: HTTP POST to `http://localhost:11434/api/embeddings`
with `{ "model": "nomic-embed-text", "prompt": "..." }`. The Kiss indexer
batches these calls (e.g., 16–64 chunks per HTTP request via `/api/embed`
when supported) to keep the GPU saturated.

Decisions captured here:
- **One embedding model per index.** Changing models means a full rebuild —
  store the model name + dim in a `rag_meta` row to enforce this.
- Same model is used for **both** ingestion and query embedding (required
  for cosine similarity to be meaningful).

## 5. Indexer / Vector-DB Builder

Lives under `src/main/backend/services/` (Groovy, hot-reloadable) and
`src/main/backend/CronTasks/` for the periodic sweep.

### 5.1 Chunking strategy

Goal: chunks small enough to embed cleanly (~512–1024 tokens), large enough
that a single chunk usually contains a self-contained idea (one method, one
section).

Approach, in priority order:

1. **Symbol-aware for code** — for `.java` / `.groovy` / `.js`, walk the
   file and split at top-level class/method boundaries. Fall back to
   line-window chunks if parsing fails. Tree-sitter via JNI is overkill for
   v1; a regex-based "class X" / "def X" / "function X" splitter is good
   enough to start.
2. **Markdown by heading** — split `.md` files at H1/H2 boundaries.
3. **Fixed-window for everything else** — 60-line windows with 10-line
   overlap.

Each chunk carries: file path, start/end line, enclosing symbol (when
known), language. These travel back through the MCP tool so Claude can
`Read` the exact lines.

### 5.2 Change detection

The indexer maintains the `rag_file` table. On each sweep:

1. Walk configured roots (see §8) honoring an exclude list (`node_modules`,
   `work/`, `target/`, `.git/`, binary blobs, `*.jar`, `tomcat/logs`, etc.).
2. For each file: compute SHA-256.
3. If file is new or hash changed → re-chunk + re-embed; replace rows in
   `rag_chunk` (cascade delete then insert).
4. Files no longer present → delete from `rag_file` (cascades).
5. Unchanged files → skip.

This makes the cold build expensive and subsequent updates near-free.

### 5.3 Triggers

- **CronTask** — sweeps the configured roots every N minutes (configurable;
  default 10).
- **Manual reindex** — exposed as an MCP tool (`reindex`) so Claude can
  request a rebuild after large edits, and exposed as a Kiss service
  endpoint for ad-hoc invocation.
- **Initial bulk build** — a one-shot Groovy script (under
  `src/main/backend/scripts/`) for the first ingestion; uses larger batch
  sizes than the cron sweep.

### 5.4 Throughput sizing

On a 4070, `nomic-embed-text` embeds roughly 1–3k short chunks/sec when
batched. A 200k-chunk cold build is on the order of minutes, not hours.
Incremental updates are dominated by file I/O.

## 6. MCP Server (Kiss)

A new class `RAGMCPServer` under `src/main/precompiled/` extending
`MCPServerBase`. Mirrors the pattern in `MCPServerExample.java`. Mapped at a
stable URL like `/rag-mcp`.

### 6.1 Tools exposed

| Tool | Args | Returns |
|---|---|---|
| `search_code` | `query` (string), `k` (int, default 8), `repo` (optional filter), `language` (optional filter), `path_prefix` (optional) | Array of `{ repo, path, start_line, end_line, symbol, score, snippet }` |
| `get_chunk` | `chunk_id` (number) | Full chunk text plus metadata |
| `list_repos` | — | Distinct `repo` values currently indexed |
| `index_status` | — | Counts: files, chunks, last sweep time, embedding model + dim |
| `reindex` | `repo` (optional), `path_prefix` (optional) | Triggers async sweep; returns job id |

Deliberately omitted from v1: prompt templates, resources, server-initiated
sampling. The base class does not implement them, and Claude Code does not
need them for retrieval.

Returned snippets stay short (a few hundred chars). The goal is **pointer +
preview**, not delivery of the whole file — Claude follows up with `Read` on
the path/line range. This keeps the model's context window healthy.

### 6.2 Authentication

Single-user, localhost-only. Override `authenticate()` to verify a static
shared-secret header pulled from `application.ini`
(`RAGMCPSharedSecret = ...`). No basic-auth, no sessions. Bind Tomcat to
`127.0.0.1` to make this safe.

### 6.3 Wiring into Claude Code

After `./bld -v build` and starting Tomcat:

```bash
claude mcp add --transport http rag http://127.0.0.1:8080/rag-mcp \
    --header "X-RAG-Token: <secret-from-application.ini>"
```

Claude Code will then see the `search_code`, `get_chunk`, etc. tools
alongside its built-in ones.

## 7. Query Path (what happens on a tool call)

1. Claude Code decides to call `search_code(query="how does the
   ApplicantTracking promotion to HR work?", k=8)`.
2. `RAGMCPServer.callTool` receives the JSON-RPC request.
3. Server embeds the query string via Ollama (single HTTP call, ~30 ms).
4. Server runs:
   ```sql
   SELECT c.chunk_id, f.repo, f.path, c.start_line, c.end_line,
          c.symbol, c.content,
          1 - (c.embedding <=> $1) AS score
     FROM rag_chunk c
     JOIN rag_file  f USING (file_id)
    WHERE ($2 IS NULL OR f.repo = $2)
      AND ($3 IS NULL OR f.language = $3)
      AND ($4 IS NULL OR f.path LIKE $4 || '%')
    ORDER BY c.embedding <=> $1
    LIMIT $5;
   ```
   (`<=>` is pgvector cosine distance; HNSW makes this fast.)
5. Server builds a JSON result with one entry per hit, shortened snippets,
   and returns it via `toolResult`.
6. Claude reads the result, then typically calls `Read` on the most
   promising hits.

## 8. Configuration

All in `src/main/backend/application.ini`:

```ini
# Vector store
DatabaseType      = PostgreSQL
DatabaseHost      = localhost
DatabaseName      = claude_rag
DatabaseUser      = ...
DatabasePassword  = ...

# Ollama
OllamaURL         = http://127.0.0.1:11434
EmbeddingModel    = nomic-embed-text
EmbeddingDim      = 768
EmbeddingBatch    = 32

# Indexer
RAGRoots          = /path/to/Backend,/path/to/Frontend,/path/to/Mobile,/path/to/Apply,/path/to/Worker
RAGExcludeGlobs   = **/node_modules/**,**/work/**,**/target/**,**/.git/**,**/*.jar,**/tomcat/logs/**
RAGSweepMinutes   = 10
RAGMaxFileBytes   = 1048576

# MCP auth
RAGMCPSharedSecret = <random-uuid>
```

Adding a new repo = edit `RAGRoots`, restart, let the next sweep index it.

## 9. Hardware Sizing (this machine)

- 64 GB RAM: ample headroom; pgvector index + working set sit fully in RAM.
- 4070 16 GB: more than enough for `nomic-embed-text`. Leaves room to also
  load a small chat model later if desired (e.g., `qwen2.5-coder:7b`) for
  experiments — explicitly out of scope for v1.
- Java 21: matches Kiss requirement.

## 10. Phased Roadmap

**Phase 0 — environment** (small, mostly setup)
- Install `pgvector` into the local PostgreSQL 18.3.
- `ollama pull nomic-embed-text`; confirm `/api/embeddings` works.
- Create `claude_rag` database and run the schema in §3.
- Add settings from §8 to `application.ini`.

**Phase 1 — indexer, no MCP yet**
- Groovy service `RAGIndexer` with chunking, hashing, Ollama batching,
  upsert into `rag_file` / `rag_chunk`.
- One-shot script to do the initial bulk build over `RAGRoots`.
- CronTask wrapping the incremental sweep.
- Smoke test by running raw SQL `SELECT ... ORDER BY embedding <=> ...`
  against typed queries; eyeball relevance.

**Phase 2 — MCP server**
- `RAGMCPServer` extending `MCPServerBase` with `search_code`, `get_chunk`,
  `list_repos`, `index_status`.
- Shared-secret auth.
- `claude mcp add` the server; verify Claude Code can call the tools.

**Phase 3 — quality**
- Add `reindex` tool.
- Symbol-aware chunking for Java/Groovy/JS (replace regex with something
  sturdier if regex misclassifies too often).
- Optional reranker pass (cross-encoder via Ollama) on the top-50 candidates
  if precision is lacking. Only add if real usage shows a need.

**Phase 4 — polish**
- Metrics: counts, p95 query latency, last-sweep duration — expose via
  `index_status` and log to `catalina.out`.
- Optional: separate the indexer into its own Tomcat or run it under a
  different schedule once it stabilizes.

## 11. Open Questions / Decisions to Confirm

1. **Embedding model** — sticking with `nomic-embed-text` (768) for v1, or
   start with `mxbai-embed-large` (1024) for higher quality at ~2× cost?
2. **Scope of `RAGRoots`** — index all of Stack360, or start narrower (e.g.,
   Backend + Frontend) and grow?
3. **Should Claude Code edits trigger reindex?** Easy to wire via a hook,
   but the 10-min sweep is probably fine. Decide after first week of use.
4. **Auth** — shared secret in header is enough for a single-user local
   setup. Confirm we are not exposing Tomcat on the LAN.
5. **One database vs. one schema per repo** — single `claude_rag` DB,
   `repo` column inside, is simplest. Sticking with that unless a reason
   surfaces.

## 12. Risks

- **Chunk boundaries** — bad chunking is the #1 reason RAG underperforms.
  Plan: keep chunking pluggable, eyeball results early, add language-aware
  splitters where the regex falls down.
- **Model/dim drift** — changing embedding model after the fact requires a
  full rebuild. Mitigated by `rag_meta` guard row.
- **MCP transport mismatch** — Kiss MCP is HTTP. Claude Code supports HTTP
  MCP; confirm with `claude mcp add --transport http ...` during Phase 2.
- **Large/binary files** — skip via `RAGExcludeGlobs` + `RAGMaxFileBytes`.

## 13. Out of Scope (for now)

- Multi-user / multi-tenant access control.
- Cloud-hosted embeddings (deliberately local-only).
- Local generation model integration.
- Cross-repo deduplication / near-duplicate suppression.
- Web UI for browsing the index — `psql` is fine for v1.
