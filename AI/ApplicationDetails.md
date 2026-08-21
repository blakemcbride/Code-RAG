# Code-RAG — Application Details

Project-specific instructions for Claude sessions started inside this
repo. See `AI/KnowledgeBase.md` for the underlying Kiss-framework
reference.

## What this is

Code-RAG is a single-machine, local MCP server that serves semantic
code search to MCP-aware coding agents (Claude Code, OpenAI Codex CLI,
etc.). It indexes one or more configured project trees into pgvector,
exposes eight MCP tools per project — `search_code`, `search_history`,
`find_symbol`, `find_dependents`, `reindex_path`, `get_chunk`,
`list_repos`, `index_status` — and is consumed via short HTTP calls.
Two indexes back these: a **semantic** one (embeddings + per-file LLM
summaries) and a **structural** one (ctags definitions + import graph)
that needs no LLM.
Built on Kiss + Tomcat 11 + PostgreSQL/pgvector + Ollama.

The repo is named "Code-RAG" (renamed from Claude-RAG); the server
itself is plain MCP, with no client-specific assumptions.

## Documentation map (read these before diving in)

| For | Read |
|---|---|
| What the system does, architecture, write/read paths | `Overview.md` |
| Step-by-step install + first index | `Setup.md` |
| Day-to-day operations, troubleshooting | `Running.md` |
| Full design reference, design rationale, gotchas | `RAGPlan.md` |
| Registering this server with Claude Code | `ClaudeCode.md` |
| Registering this server with OpenAI Codex CLI | `Codex.md` |
| Running on Windows (WSL2 + cross-boundary path translation) | `Windows.md` |
| Kiss framework reference (loaded automatically) | `AI/KnowledgeBase.md` |

If a user asks about behavior that conflicts with these docs, the docs
are authoritative — update them rather than reinventing.

## File structure — what lives where

| Path | Role |
|---|---|
| `src/main/precompiled/org/kissweb/rag/RAGMCPServer.java` | The MCP server. URL `/rag-mcp/<project>`, plus the reserved `/rag-mcp/_all` cross-project endpoint. Thin: arg parsing, MCP envelope, path translation |
| `src/main/precompiled/org/kissweb/rag/RAGSearch.java` | **The whole read path** — query embedding, hybrid retrieval, RRF fusion, rerank, path resolution. Shared by the MCP server and the eval harness so the two cannot drift |
| `src/main/precompiled/org/kissweb/rag/RAGTokenizer.java` | Identifier-aware tokenizer (camelCase/snake_case splitting) + query stoplist. Shared by indexer and search — a divergence here silently disables lexical matching |
| `src/main/precompiled/org/kissweb/rag/ProjectRegistry.java` | Loads `rag-projects.json`, validates names against `[a-z][a-z0-9_]*` |
| `src/main/backend/scripts/RAGEval.groovy` | Retrieval-quality eval. Scores a golden query set through `RAGSearch` |
| `src/main/backend/scripts/RAGHistory.groovy` | Indexes commit history into `rag_commit`. Handles **git and Subversion** — the roots are not all git |
| `src/main/backend/scripts/RAGSummarizer.groovy` | Generates + embeds one-paragraph LLM summaries per file (`./bld summarize`) |
| `src/main/backend/scripts/RAGSummarizer.groovy` (`--symbols`) | Also generates per-symbol summaries for large files into `rag_symbol` |
| `eval/<project>-queries.jsonl` | Golden query sets (committed) |
| `src/main/backend/scripts/RAGDefs.groovy` | ctags symbol definitions into `rag_def` — the structural half of the index (`find_symbol`) |
| `rag_def` / `rag_dep` (per project) | The structural index: ctags definitions, and the import graph. Neither needs an LLM or a rebuild |
| `eval/turns.py` | Turns-to-answer harness: search_code vs grep, tool calls to answer |
| `eval/mine.py` | Builds candidate query-set entries from `rag_query_log` (real usage) |
| `eval/baseline.json`, `eval/results-*.json` | Eval output; `./bld eval` diffs against the baseline |
| `src/main/precompiled/Tasks.java` | The `bld` build script. Contains `start`, `stop`, `status`, `scan`, port options, server.xml rewriter |
| `src/main/backend/scripts/RAGIndexer.groovy` | Walker, chunker (50+ languages), embedder, change detection |
| `src/main/backend/scripts/ProjectBootstrap.groovy` | `CREATE SCHEMA` + tables per project; returns empty-rag_file list for auto-scan |
| `src/main/backend/services/RAGAdmin.groovy` | JSON-RPC admin endpoints (`status`, `reindex`, `listProjects`) |
| `src/main/backend/CronTasks/RAGSweep.groovy` + `CronTasks/crontab` | Cron-driven sweep (enabled by default, `*/10 * * * *`) |
| `src/main/backend/KissInit.groovy` | Server-startup hooks; spawns the startup auto-scan |
| `src/main/backend/application.ini.example` | Config template (committed) |
| `src/main/backend/application.ini` | Live config (**gitignored** — has the shared secret) |
| `src/main/backend/rag-projects.json.example` | Projects template (committed) |
| `src/main/backend/rag-projects.json` | Live projects list (**gitignored**) |
| `setup.sh` | First-run setup — copies `.example` → real config + generates random shared secret |
| `deploy/code-rag.service.example` | systemd **user** service template (start at login, waits for PostgreSQL + Ollama). The server being down is invisible to the agent — it just falls back to Grep — so autostart is the fix for the failure mode that costs the most |
| `code-rag` | Top-level shell wrapper — `cd $CODE_RAG_HOME && exec ./bld "$@"`. Lets `bld` tasks be invoked from any cwd. Requires only `CODE_RAG_HOME`; everything else is handled by `bld`. Defines one wrapper-only subcommand: `code-rag monitor` runs `watch -n 1 'echo; nvidia-smi; ollama ps'` for a live GPU + Ollama view (Ctrl-C exits). `monitor` is **not** a bld task — it exists only here. |

## Don't modify (Kiss framework code)

- `src/main/core/**` — Kiss framework source
- `src/main/frontend/kiss/**` — Kiss frontend components
- `libs/**` — third-party JARs
- `tomcat/**` — generated; regenerated by `setupTomcat()` on each `./bld start`
- `work/**` — build output; regenerated

If a change is genuinely needed in Kiss core, surface it explicitly so
the user can port it upstream rather than carrying a local fork.

## Safe to modify

- `src/main/backend/**` — application services and indexer (hot-reloaded by Kiss, no server restart needed for Groovy file edits)
- `src/main/precompiled/**` — shared utility classes (requires `./bld -v build` + `./bld stop && ./bld start` to take effect)
- The user-facing `.md` files at the repo root

## bld commands

```
./bld start                                       # preflight Ollama, build, start Tomcat in background
./bld stop                                        # graceful shutdown
./bld status                                      # is it running? which ports? config? projects? MCP entries?
./bld scan <project|all>                          # reconcile rag-projects.json with DB, then incremental sweep
./bld scan <project|all> --full                   # TRUNCATE + rebuild from scratch (required after a schema migration)
./bld -y scan <project|all>                       # same, but skip the destructive-action confirmation prompt
./bld eval <project> [--baseline] [--granularity file|chunk]
                                                  # score retrieval; --baseline records the comparison point
./bld history <project|all>                        # index git + svn commit history (search_history)
./bld summarize <project|all> [--symbols]          # per-file (or per-symbol) LLM summaries; GPU-bound
python3 eval/turns.py <queries> <root> <url> <tok> # tool calls to answer: search_code vs grep
./bld defs <project|all>                          # extract ctags symbol definitions (find_symbol)
./bld deps <project|all>                          # backfill the import graph (find_dependents)
./bld usage <project|all>                         # is anything actually calling the tool?
python3 eval/mine.py <project>                    # candidate query-set entries from real usage
                                                  #   eval diffs against eval/baseline.json when present and
                                                  #   reports per-query rank movements. Server must be running.
./bld new-project <name> [--project-dir <dir>] <root> [<root>...]
                                                  # add a project, scan it, auto-register MCP entries with Claude Code / Codex
                                                  # --project-dir <dir>: umbrella directory above the roots (NOT indexed);
                                                  #   bld drops a .mcp.json there and a managed CLAUDE.local.md routing snippet
./bld remove-project <name>                       # drop a project (refuses on last one), auto-deregister MCP entries
./bld add-root <name> <root> [<root>...]          # add roots to an existing project + scan
./bld remove-root <name> <root> [<root>...]       # remove roots from a project + scan
./bld build                                       # compile precompiled + backend; do not run
./bld -dp PORT / -hp PORT / -sp PORT              # override JDWP / HTTP / shutdown port for this invocation
```

Project names are validated against `[a-z][a-z0-9_]*` (PostgreSQL
schema identifier rule). Dashes are silently rewritten to underscores
with a note (so `new-project my-proj` is accepted and stored as
`my_proj`). MCP client maintenance auto-detects: `claude` on `$PATH`
→ Claude Code is updated; `codex` on `$PATH` or `~/.codex/config.toml`
exists → Codex is updated; neither → silent skip.

**Claude Code uses project scope.** `bld new-project`, `add-root`,
`remove-root`, `remove-project`, and `bld start` invoke
`claude mcp add -s project` from each configured root, writing one
`.mcp.json` per root. Project-scope entries are only visible to
Claude Code sessions launched from somewhere under that root, which
is the deliberate design: Claude Code in unrelated directories no
longer triggers Ollama embeddings via speculative `search_code`
calls. `bld start` always re-asserts every project's entries, which
also migrates legacy `--scope user` entries written by pre-2026
releases. Codex has no project-scope concept — its
`[mcp_servers.<name>]` sections in `~/.codex/config.toml` are global
and Code-RAG writes them that way (the speculative-query problem
still exists for Codex; tracked as a follow-up).

**`project_dir`: umbrella directory above the roots.** A project
entry may carry an optional `project_dir` field — an absolute
directory that sits *above* the configured roots. When set, bld
treats it as an additional `.mcp.json` write target (so a Claude
Code session launched from the umbrella sees the MCP tool), and
also writes a marker-delimited managed block into
`<project_dir>/CLAUDE.local.md` containing a Grep-vs-`search_code` routing
rule (so Claude Code prefers `search_code` for conceptual queries
rather than defaulting to Grep). `project_dir` is **not** indexed —
only `roots[]` get scanned. `--project-dir <dir>` sets it on
`new-project`; for existing projects, hand-edit `rag-projects.json`
and run `./bld stop && ./bld start`. Validation refuses `$HOME`,
`/`, and any path equal to one of the roots. The block goes in
`CLAUDE.local.md`, never `CLAUDE.md`: `CLAUDE.md` is routinely committed,
hand-maintained, and sometimes explicitly marked do-not-edit, while this
block is a fact about *this machine's* local index. `writeClaudeMdBlock`
also excises any block an earlier release left in `CLAUDE.md`, so the
migration is automatic. `bld remove-project`
removes both the `.mcp.json` and the CLAUDE.local.md managed block at
`project_dir`. Removing `project_dir` from the JSON without
`remove-project` leaves the previously-written files orphaned.

`./bld scan` always reconciles DB state with `rag-projects.json` before
scanning: creates schemas for new projects, drops schemas for removed
projects (CASCADE), deletes `rag_file` rows whose `repo` is no longer a
configured root, and adds projects with new roots to the scan list. The
reconcile prompts `Proceed? [y/N]` only for schema drops — that is the
one mutation the cron sweep will never perform, so a prompt is the only
thing protecting against data loss from a JSON typo. Root-deletes are
not prompted: the cron sweep already deletes-on-disappearance on its
own schedule, so declining a root-delete prompt would only delay the
inevitable. `-y` skips the drop prompt for scripted use.

Defaults: HTTP `17080`, shutdown `17005`, JDWP `17900`. All three
auto-stamp into `tomcat/conf/server.xml` and `tomcat/bin/debug` on
every `./bld start` so per-invocation overrides "stick" for that run.

## Database

- One PostgreSQL database: `code_rag` (set in `application.ini` →
  `DatabaseName`).
- One schema per project. Schema name = project name in
  `rag-projects.json` (validated `[a-z][a-z0-9_]*`).
- Three tables per project: `rag_file`, `rag_chunk`, `rag_meta`.
- `rag_file.path` is stored **relative to the project root**, not
  absolute. Absolute paths are recomputed at query time from
  `rag-projects.json`'s `roots[]`.

### Schema migrations

`ProjectBootstrap.ensureSchema` only does `CREATE TABLE IF NOT EXISTS`,
so it cannot evolve an existing table. **Any change to the per-project
tables must go through the migration ladder**, not by editing the CREATE
statements alone:

1. Bump `ProjectBootstrap.CURRENT_SCHEMA_VERSION`.
2. Add a case to `applyMigration()` using `ADD COLUMN IF NOT EXISTS` /
   `CREATE INDEX IF NOT EXISTS` (every step must be idempotent — a
   server killed mid-migration re-runs it).
3. Also update the `CREATE TABLE` in `ensureSchema` so fresh schemas are
   born at the current shape.
4. If the change adds a column the indexer must populate, call
   `markRebuildRequired()`. `RAGIndexer.doSweep` then refuses an
   incremental sweep — which would only revisit files whose sha256
   changed and silently leave most rows unfilled — until
   `./bld scan <project> --full` clears it.

Current version: **2** (v2 added `rag_chunk.lexemes`, the generated
`lexemes_tsv`, and its GIN index for hybrid search).

`EmbeddingDim` comes from `application.ini` in both `ProjectBootstrap`
and `RAGIndexer` — do not reintroduce a hardcoded dimension.

## Sweep / scan triggers (which thing causes an index update)

1. **Startup auto-scan** — `KissInit.init2` runs an incremental sweep
   on every project whose `rag_file` is empty at server start.
2. **Cron sweep** — `*/10 * * * * RAGSweep` (enabled). Each project,
   sequentially, every 10 minutes. Incremental no-op sweeps over a
   10k-file project take ~1 sec.
3. **`./bld scan <project|all>`** — reconciles DB vs `rag-projects.json`
   first (drops removed schemas, creates new ones, deletes/adds
   per-root entries), then runs a synchronous sweep with live progress.
   Prompts before destructive ops unless `-y` is given.
4. **JSON-RPC `RAGAdmin.reindex`** (e.g. `curl` or a script) — async.
   Use `full=true` for a TRUNCATE + rebuild.
5. **JSON-RPC `RAGAdmin.reconcile`** — the reconcile step in (3), exposed
   as its own endpoint. `dryRun=true` returns the plan; `dryRun=false`
   executes. Acquires the per-project lock before any destructive op.

All sweep paths share the per-project lock row
(`<project>.rag_meta(key='reindex_running')`), so two sweeps of the
same project cannot overlap; different projects can sweep in parallel.
Reconcile also takes the lock for drops and root-deletes (skipping any
project currently being indexed).

## Cross-project search (`/rag-mcp/_all`)

`_all` is a reserved URL segment, checked in `authenticate()` *before* the
registry lookup. It begins with an underscore, which the
`[a-z][a-z0-9_]*` project-name rule forbids, so it can never be shadowed
by a real project. `bld` additionally refuses to create a project named
`all`, since `bld scan all` already means "every project".

On that endpoint `search_code` fans out across every configured project,
embedding the query **once** and reusing the vector for each schema —
embedding dominates cost, so N projects is far cheaper than N searches.
Results come back as chunks, each tagged with its owning `project`;
`get_chunk` on `_all` therefore requires a `project` argument, because
chunk ids are only unique within a schema. `list_repos` and
`index_status` aggregate across projects.

Ordering caveat: per-project scores are RRF ranks, so each project's top
hit scores about the same regardless of corpus size. Merging by score
interleaves projects rather than strictly ordering by relevance — which
suits "which repo has this?" but is not the ranking a single-project
search would give.

## Authentication / security

- Tomcat binds to `127.0.0.1` only (loopback). No LAN exposure.
- Every MCP request requires `X-RAG-Token: <secret>` matching
  `RAGMCPSharedSecret` in `application.ini`.
- The secret is generated by `setup.sh` and lives only in the
  gitignored `application.ini`. Never commit it.

## Common pitfalls (worth knowing before editing)

- **`excludeGlobs` ADDS to the base excludes; it does not replace them.**
  `ProjectRegistry.BASE_EXCLUDES` (build output, VCS/IDE state, minified
  assets, source maps, generated jsdoc, lockfiles) is applied to every
  project unconditionally. The old behavior was "replace", so declaring
  one extra exclusion silently lost `node_modules`/`.git`/etc. and every
  project had to re-list the whole set defensively.
- **Never put a common domain word in a directory glob.** `**/vendor/**`
  looks obviously safe and is not: in a business application "vendor"
  means *supplier*, and that pattern deleted 44 files of real service
  code under `services/standard/misc/vendor/`. It is intentionally absent
  from `BASE_EXCLUDES`; genuine third-party directories are excluded
  per-project by explicit path. Always diff the indexed file list before
  and after an exclude change.
- **Changing excludes needs only an incremental sweep.** Excluded files
  are skipped during the walk, so they never enter `seenKeys` and the
  delete-on-disappearance pass removes them. `./bld scan <project>` is
  enough — no `--full`.
- **Import extraction lives in the indexer, not a separate pass.**
  `RAGIndexer.storeDeps` runs inside `indexOneFile`, where the file has
  already been read — so it costs nothing and can never go stale.
  `./bld deps` exists only to backfill projects indexed before the graph
  existed.
- **The dependency graph is heuristic and says so.** Import targets are
  matched by their last segment (class/module name), because real
  resolution needs per-language module paths and a classpath. Every result
  carries `confidence`: `exact` when the dotted target provably maps onto
  the file's path, `name` when only the trailing name agrees. Do not
  present `name` matches as certain.
- **References must be classified, not just matched.** A raw lexeme match
  answers "what calls this" badly: for `findJoinPath` it was 8 tests, 1
  doc and the definition itself around a single real caller.
  `findReferences` drops the defining chunk, labels each hit
  `caller`/`test`/`doc`, marks likely invocations, and ranks in that
  order. The `test` label doubles as "what covers this symbol".
- **ctags has no Groovy parser.** Every Groovy file would silently get zero
  definitions. `RAGDefs.LANGMAPS` maps `.groovy` onto the Java parser,
  whose declaration syntax is close enough. Check `ctags --list-maps`
  before assuming a language is covered.
- **The Groovy vararg trap bites repeatedly.** `GroovyService.run` resolves
  the target by the runtime classes of the trailing varargs, so growing the
  argument list breaks the binding with an opaque `NoSuchMethodException`.
  It has now caused three separate failures (`runJsonG`, `runJson`,
  `ingestFiles`). **Cross-file Groovy entry points take exactly two
  arguments: the Connection and one JSONObject of parameters.**
- **`rag_chunk.symbol` is not a reliable definition index.** The chunker's
  regex misses common declaration shapes — `public List<Edge> findJoinPath(...)`
  is not matched, so that chunk is attributed to the previous method. Use
  `rag_def` (ctags) for anything that needs to be correct about where a
  symbol is defined.
- **Staleness is detected by mtime, so mtime must be kept honest.** Search
  hits carry `stale: true` when the file's mtime on disk differs from the
  one recorded at index time. The sweep detects *content* change by
  sha256 and skips unchanged files — so a file whose timestamp moved
  without its content changing (a `touch`, a checkout, a build) would
  never have its stored mtime corrected and would read as stale forever.
  `indexOneFile` therefore re-syncs mtime on the unchanged path. If you
  add another early return there, re-sync mtime too.
- **A retrieval score is only as honest as its expectations.** The first
  stack360 eval read as a catastrophe (hit@1 0.257) and was almost
  entirely an artifact of the query set naming one arbitrary file where
  several were equally correct — the `.js`/`.html` twin of a screen, the
  `Bxxx` business class vs the `xxx` bean. Fixing the expectations moved
  hit@5 from 0.371 to 0.886. **Always inspect the misses before believing
  a bad number**, and prefer `eval/mine.py` (real usage) over invented
  queries.
- **Weighted legs must contribute once per file, not once per match.**
  `fuseSymbolSummaryLeg` originally added a score for every matching
  symbol, so a large file with many mediocre matches outranked a file with
  one excellent match — it halved hit@1 (0.62 → 0.24). Only a file's best
  match may score.
- **Do not run `./bld build` while the server is running.** It writes into
  `tomcat/webapps`, Tomcat auto-redeploys, and any background job
  (indexing, history, summarize) is killed mid-flight — the visible
  symptom is a 404 on the next status poll. Stop, build, start.
- **A long job cannot run inside the HTTP request.** The first stack360
  history import outlived its request and Tomcat recycled the response
  underneath it (`response object has been recycled`). `reindex`,
  `history` and `summarize` all run on background threads behind the
  per-project `reindex_running` lock and are polled via `status`.
- **Fixed character caps on embedded text are not safe.** Subversion
  paths tokenize far denser than prose; caps of 4000 and 2000 characters
  both still overran the embedding context window. `RAGHistory.embedCommit`
  halves and retries on rejection (as `RAGIndexer.embedBatch` already did)
  and gives up on a single commit rather than failing a whole repository.
- **Only the core `groovy-4.x.jar` is on the classpath** — no
  `groovy-xml`, so `XmlSlurper` does not resolve in backend scripts. Use
  the JDK DOM parser (see `RAGHistory.readSvn`).
- **Never leave a valueless key in `application.ini`.** Writing
  `RAGRerankModel =` with nothing after the `=` throws inside Kiss's ini
  parser, which aborts `KissInit` entirely. The failure is silent and
  deeply confusing downstream: no c3p0 pool, no auth allowlist, and every
  service then receives a **null** `Connection` (`Cannot invoke method
  fetchAll() on null object`). Comment the key out instead.
- **Adding a parameter to a cross-file Groovy method can break dispatch.**
  `GroovyService.run(...)` resolves the target by the runtime classes of
  the trailing varargs, and `getMethod2` maps a null argument to
  `Object.class`. Growing the argument list shifted the binding and
  produced an opaque `NoSuchMethodException: runJsonG(java.lang.Object,
  ...)`. Pass a single `JSONObject` of parameters instead — the signature
  then stays stable as options accumulate (see `RAGEval.runJson`).
- **An interrupted full rebuild used to look healthy.** `runFullRebuild`
  TRUNCATEs first, so the index is partial until the sweep ends; the
  `rebuild_required` flag is now *set* before the truncate and cleared
  only on successful completion. Killing the server mid-rebuild (a plain
  `./bld stop`) leaves the flag set, and the next incremental sweep
  refuses rather than serving a half-populated index.
- **Kiss `Connection.tableExists("schema.table")` caches by table name
  without schema** — gives stale results across schemas. Use a direct
  `information_schema.tables` query when checking per-schema. Already
  worked around in `ProjectBootstrap.groovy`.
- **Backend Groovy files load in isolation** — one file cannot
  `import` another's classes via the normal Groovy classpath. Use
  `GroovyService.run("scripts", "Class", "method", null, args...)` for
  cross-file calls. See `KissInit.init2` → `ProjectBootstrap.ensureAll`
  for an example.
- **`fetchOne` adds `LIMIT 1`** — breaks `INSERT … RETURNING`. Use
  `fetchAll` for RETURNING queries.
- **c3p0 `unreturnedConnectionTimeout` defaults to 60 s** — kills
  long-running indexer connections mid-sweep. Already set to 0 in
  `application.ini.example`. Don't lower it.
- **The MCP server sets `hnsw.ef_search = 400` per query** — needed
  for good recall above ~10k chunks. Don't drop it back to pgvector's
  default 40 without checking.
- **`rag_chunk.content` is the RAW chunk body; what gets embedded is
  not.** `RAGIndexer.buildEmbedText` prepends a `repo:/file:/lang:/symbol:`
  header before embedding, because a bare method body carries none of
  the vocabulary a natural-language query uses. The header is retrieval
  scaffolding only — it is never stored and never returned. Changing it
  requires a full rebuild.
- **The tokenizer is shared on purpose.** `RAGTokenizer` lives in
  precompiled/ so the indexer (Groovy) and search (Java) apply identical
  rules. If they diverge, the lexical leg stops matching and nothing
  fails loudly.
- **Rerank boosts must stay proportional.** `RAGSearch.rerank` scales the
  symbol/path boost by `overlapFraction` — how much of the identifier the
  query accounts for. An earlier any-token-overlap version fired on every
  loosely related symbol and measurably pushed correct hits off the top
  (hit@1 0.34 → 0.22).
- **Do not penalize comment-heavy chunks.** An earlier boilerplate
  detector counted `*` / `//` lines, which demoted javadoc-rich chunks —
  the single richest natural-language description of what code does.
  It cut vocab-query hit@5 from 0.73 to 0.47. The detector now matches
  only import/package/include preamble.
- **Kiss's default c3p0 pool is (cores × 4, min 20)** — 64 connections on
  a 16-core box, for a tool serving one agent. Combined with any other
  local PostgreSQL client that exhausts `max_connections` (default 100)
  and the server fails to start with "sorry, too many clients already",
  *after* which schema migrations silently do not run.
  `application.ini` pins `DatabaseMaxPoolSize = 16`.
- **A NUL byte anywhere past 8 KB used to lose the whole file.** PostgreSQL
  `text` cannot hold U+0000; an INSERT carrying one fails with `invalid byte
  sequence for encoding "UTF8": 0x00` and the *file* is lost, not just a chunk.
  `looksBinary` only inspects the first 8192 bytes — deliberately, since reading
  every byte of every file to classify it is wasteful — so a real source file
  with a stray NUL further in sails past it and then dies at insert time. This
  was silent: 135 logged failures across 16 files (old Lisp/C trees, plus
  `RAGEval.groovy`, which uses a NUL as a field separator at byte 9218).
  `decodeText` now strips NULs after decoding. Stripping, not widening the
  binary check: these are genuine sources that belong in the index, and a NUL
  can never appear in a query, so removing it costs no accuracy. Files that are
  *actually* binary still get caught, because their NUL is in the first 8 KB
  (`m16.asm` at byte 2). `amigastu.c` had its NUL at byte 8192 exactly — one
  past the window — which is how narrow the old gap was.
- **Embed batching is byte-budgeted, not count-budgeted** — Ollama's
  `/api/embed` checks cumulative tokens across the whole input array.
  See `EmbeddingMaxBatchBytes` and the recursive-halving fallback in
  `RAGIndexer.groovy`.

## When in doubt

- For "how do I run X?" — `Running.md` is authoritative.
- For "why is X built this way?" — `RAGPlan.md` has the design notes.
- For "what does the bld script do?" — read `Tasks.java`; it's
  intentionally small and self-contained.
- For "how does MCP-protocol-level Y work?" — `RAGMCPServer.java`
  extends `MCPServerBase`; the protocol bits are in Kiss core.
