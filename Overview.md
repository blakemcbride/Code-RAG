# Overview

## What this system is

Code-RAG is a local, single-machine retrieval-augmented-generation (RAG)
layer that lets [Claude Code](https://docs.claude.com/en/docs/claude-code/overview)
do semantic search over your own codebases. You point it at one or more
project directories. It walks them, chunks the source files, generates
vector embeddings via a local Ollama model, and stores everything in a
local PostgreSQL database with the `pgvector` extension. An MCP server
exposes eight tools that Claude Code can call:

| Tool | Answers |
|---|---|
| `search_code` | "where do we handle X" — meaning, not keyword |
| `search_history` | "why is this like this" — indexed git **and** Subversion commit messages |
| `find_symbol` | "where is this defined, what calls it, what tests cover it" |
| `find_dependents` | "what breaks if I change this file" |
| `get_chunk` | full text of one result |
| `reindex_path` | make a file you just wrote searchable immediately |
| `list_repos` | which repositories a project covers |
| `index_status` | what is indexed, how fresh, and whether a rebuild is pending |

Those split into two indexes that answer different kinds of question. The
**semantic** index (embeddings, plus an LLM-written summary of every file)
finds code by what it is *about*. The **structural** index (ctags symbol
definitions and an import graph) answers exact relations — what calls this,
what depends on this — which similarity search fundamentally cannot. The
structural half needs no LLM and no embeddings.

Everything runs on your machine. Ollama is used **only** for embeddings and
for the offline file summaries; generation for your actual questions stays
with whatever Claude model the Claude Code session is already using.
Nothing leaves the host.

## How it works

```
+----------------+        MCP / JSON-RPC over HTTP        +-------------------------+
|  Claude Code   | ------ search_code / find_symbol ----> |  Kiss / Tomcat server   |
|  CLI session   | <----- hits: path + lines + score ---- |  /rag-mcp/<project>     |
+----------------+                                         +-----------+-------------+
                                                                       |
                       +------------------------+ ------ JDBC -------> |
                       |    cron + admin svc    |                       |
                       |    (Groovy, in Kiss)   |                       v
                       +-----------+------------+      +-------------------------------+
                                   |                   |  PostgreSQL (code_rag DB)   |
                                   v                   |  one schema per project       |
                       +------------------------+      |    <project>.rag_file         |
                       |  RAG indexer (Groovy)  | ---> |    <project>.rag_chunk        |
                       |  walk → chunk → embed  |      |    <project>.rag_commit       |
                       +-----+-------------+----+      |    <project>.rag_def / _dep   |
                                                       |    <project>.rag_meta         |
                                                       |  + pgvector HNSW index        |
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
1. A sweep walks the configured roots. Triggers: the cron task fires
   every 10 minutes by default; the startup auto-scan covers
   never-scanned projects; `./bld scan <project|all>` and the JSON-RPC
   `reindex` endpoint cover manual on-demand runs.
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

**Read path** (MCP server) — `search_code` is a hybrid of three retrievers,
not a single vector lookup:
1. Claude Code POSTs to `http://127.0.0.1:17080/rag-mcp/<project>` with an
   MCP `tools/call` envelope (`X-RAG-Token` header for auth).
2. The query is embedded by Ollama using the model used at index time.
3. Three retrievals run over the same filtered corpus:
   - **dense** — pgvector HNSW cosine over chunk embeddings (`ef_search=400`),
   - **lexical** — a GIN index over identifier-aware lexemes, which splits
     `camelCase` and `snake_case` so exact symbols still win,
   - **summary** — cosine over the LLM-written one-paragraph summary of each
     file, which is prose and therefore matches prose questions directly.
4. The three are fused with Reciprocal Rank Fusion, then reranked with cheap
   structural signals (symbol/filename overlap, a per-file diversity cap).
5. Results are returned as ranked **files** by default, each with the symbols
   that matched and an excerpt already widened to the whole enclosing
   function — so one call usually answers the question without a follow-up
   `Read`. Pass `granularity=chunk` for individual chunks.

Why three legs: the dominant failure mode was never the similarity math, it
was that the words people search with are often absent from the code being
searched. The summary leg puts those words into the index; the lexical leg
covers the opposite case, where you know the exact identifier.

## Multi-project isolation

Each project becomes a separate PostgreSQL schema inside the same
`code_rag` database. A search against `/rag-mcp/foo` only ever sees
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
- Silently drop a project's schema when you remove it from the config.
  `./bld scan` reconciles DB state with `rag-projects.json` and *will*
  drop a removed project's schema, but it prints the plan and asks
  `Proceed? [y/N]` first — a JSON typo can't drop a schema unattended.
  (Per-root deletions are not prompted, because the cron sweep already
  removes orphan files on its own schedule.)

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
| Per-project schemas | `code_rag.<project>.{rag_file,rag_chunk,rag_meta}` |
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
