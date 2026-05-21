# Overview

## What this system is

Claude-RAG is a local, single-machine retrieval-augmented-generation (RAG)
layer that lets [Claude Code](https://docs.claude.com/en/docs/claude-code/overview)
do semantic search over your own codebases. You point it at one or more
project directories. It walks them, chunks the source files, generates
vector embeddings via a local Ollama model, and stores everything in a
local PostgreSQL database with the `pgvector` extension. An MCP server
exposes four tools (`search_code`, `get_chunk`, `list_repos`,
`index_status`) that Claude Code can call to find relevant code by meaning,
not just by keyword.

Everything runs on your machine. The local LLM (Ollama with
`nomic-embed-text:v1.5` by default) is used **only** for embeddings;
generation stays with whatever Claude model the Claude Code session is
already using. Nothing leaves the host.

## How it works

```
+----------------+        MCP / JSON-RPC over HTTP        +-------------------------+
|  Claude Code   | --------- search_code, etc. ---------> |  Kiss / Tomcat server   |
|  CLI session   | <----- hits: path + lines + score ---- |  /rag-mcp/<project>     |
+----------------+                                         +-----------+-------------+
                                                                       |
                       +------------------------+ ------ JDBC -------> |
                       |    cron + admin svc    |                       |
                       |    (Groovy, in Kiss)   |                       v
                       +-----------+------------+      +-------------------------------+
                                   |                   |  PostgreSQL (claude_rag DB)   |
                                   v                   |  one schema per project       |
                       +------------------------+      |    <project>.rag_file         |
                       |  RAG indexer (Groovy)  | ---> |    <project>.rag_chunk        |
                       |  walk → chunk → embed  |      |    <project>.rag_meta         |
                       +-----+-------------+----+      |  + pgvector HNSW index        |
                             |             |           +-------------------------------+
                             |             v
                             |     +---------------+
                             |     |    Ollama     |
                             |     |  nomic-embed- |
                             |     |  text:v1.5    |
                             |     +---------------+
                             v
                       +------------+
                       | your code  |
                       | (read-only)|
                       +------------+
```

**Write path** (indexer):
1. Cron sweep (default every 10 minutes) or a manual reindex walks each
   project's configured roots.
2. Files are classified by extension / filename (50+ languages
   recognized), and either symbol-aware chunked (Java, Groovy, JS/TS,
   Kotlin, Scala, C#, Swift, Dart, C/C++/Obj-C, Python, Ruby, PHP, Rust,
   Go, Elixir, Lisp/Scheme/Racket/Clojure) or split with a 60-line
   sliding window.
3. Each chunk goes to Ollama for an embedding. Requests are byte-budgeted
   so the cumulative-tokens limit in `/api/embed` is never exceeded; if
   Ollama refuses, the batch halves and retries — still under the same
   transaction.
4. SHA-256 of file content tracks "did this file change since last sweep?";
   unchanged files are skipped.
5. Chunks land in pgvector via `INSERT ... ON CONFLICT DO UPDATE`, with
   per-file rollback on failure and a per-N-files commit (default 50).

**Read path** (MCP server):
1. Claude Code POSTs to `http://127.0.0.1:17080/rag-mcp/<project>` with an
   MCP `tools/call` envelope (`X-RAG-Token` header for auth).
2. The query string is embedded by Ollama using the same model used at
   index time.
3. pgvector's HNSW cosine-distance search returns the top-K most similar
   chunks (`ef_search=400`).
4. Each hit comes back with `chunk_id`, `repo`, `path`, `absolute_path`,
   `start_line`, `end_line`, `symbol`, `score`, and a `snippet`. Claude
   normally calls `Read` next on the absolute path / line range.

## Multi-project isolation

Each project becomes a separate PostgreSQL schema inside the same
`claude_rag` database. A search against `/rag-mcp/foo` only ever sees
`foo.rag_*` tables. Two simultaneous Claude Code sessions, each scoped to
a different project, can run in parallel with no cross-talk; they only
contend on the shared Ollama GPU (and pgvector reads are independent).

## What this system is good at

- **Conceptual code search.** *"Where does invoice generation happen?"*
  Returns the right file even when you don't know the function name.
- **Crossing layered or polyglot codebases.** Backend + frontend + docs
  + SQL — all in the same index, all queryable together.
- **Avoiding context-window bloat.** Claude gets pointers (file +
  lines), not whole files. It can then `Read` only what matters.

## What it explicitly does not do

- Generate text or write code itself. The local LLM does embeddings only;
  any generation comes from whatever cloud model Claude Code talks to.
- Replace `grep` for known-symbol lookups. Use both — pick the right
  tool for the question.
- Index private things you didn't tell it about. Only the directories
  listed in `rag-projects.json` are scanned.
- Phone home. No outbound network calls from this code beyond Ollama on
  localhost and PostgreSQL on localhost.
- Auto-drop a project's data when you remove it from the config. You
  drop the schema by hand — protects against typos losing the index.

## Requirements

### Software (all current versions or newer)

| Component | Minimum | Used for |
|---|---|---|
| **PostgreSQL** | 17.x | Index storage |
| **pgvector** | 0.8 | Vector type + HNSW index |
| **Ollama** | 0.10 | Local embedding service |
| **Embedding model** | `nomic-embed-text:v1.5` (default) | 768-dim cosine embeddings |
| **Java** | 21 (LTS) | Kiss runtime + indexer |
| **Bash** | any recent | `setup.sh` and ops |
| **Python** | 3.10+ | UUID generation in `setup.sh`, scripting in `Running.md` |
| **curl** | any | Triggering reindex / status |
| **Claude Code CLI** | current | The MCP client; not strictly required to run the server, but the whole point |

### Operating system

Developed and tested on Linux. macOS should work identically (bash +
PostgreSQL + Ollama + Java are first-class on macOS). Windows requires
WSL or manual translation of the bash scripts.

### Hardware

- **Disk**: ~3 KB per chunk in pgvector, plus HNSW index overhead. A
  100k-chunk index (about a 10k-file codebase) is well under 1 GB.
- **RAM**: comfortable on 16 GB and up. The vector index sits in shared
  buffers; PG's defaults are plenty.
- **GPU**: optional but very helpful. Ollama runs `nomic-embed-text:v1.5`
  fine on CPU; a modest GPU (e.g. anything CUDA-capable from the last
  several years, or Apple Silicon) cuts initial-rebuild time from hours
  to minutes. Steady-state operation barely needs it.

### Access / privileges

- Local PostgreSQL connection (defaults to peer/trust against `postgres`).
- Ability to `CREATE SCHEMA` and `CREATE EXTENSION vector` once at setup.
- Network: only `localhost`. The Kiss server binds 17080 to localhost only.

## Where things live

| Thing | Path |
|---|---|
| Per-project schemas | `claude_rag.<project>.{rag_file,rag_chunk,rag_meta}` |
| Project list + roots | `src/main/backend/rag-projects.json` (gitignored — your real config) |
| Project list template | `src/main/backend/rag-projects.json.example` (in repo) |
| Global knobs + secret | `src/main/backend/application.ini` (gitignored) |
| Config template | `src/main/backend/application.ini.example` (in repo) |
| Indexer (chunker + embed driver) | `src/main/backend/scripts/RAGIndexer.groovy` |
| Cron sweep | `src/main/backend/CronTasks/RAGSweep.groovy` + `crontab` |
| Admin JSON-RPC service | `src/main/backend/services/RAGAdmin.groovy` |
| MCP server | `src/main/precompiled/org/kissweb/rag/RAGMCPServer.java` |
| Project config reader | `src/main/precompiled/org/kissweb/rag/ProjectRegistry.java` |
| Schema bootstrap | `src/main/backend/scripts/ProjectBootstrap.groovy` |
| Logs | `tomcat/logs/catalina.out` |

## Documentation map

- **[README.md](README.md)** — pitch + 8-step quick start.
- **Overview.md** *(this file)* — what the system does and what it needs.
- **[Running.md](Running.md)** — operating manual: start/stop, adding a
  project, daily commands, troubleshooting.
- **[RAGPlan.md](RAGPlan.md)** — original design doc.
