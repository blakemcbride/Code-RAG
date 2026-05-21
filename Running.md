# Running Code-RAG

Step-by-step instructions for using the local RAG with Claude Code, starting
from a fresh system reboot. Every command is meant to be copy-paste-able.

Substitute your own paths for `/path/to/code-rag` and your own project
name for `myproj` throughout.

---

## 1. After every reboot — bring services up

The RAG pipeline has three independent processes: PostgreSQL, Ollama, and
the Kiss/Tomcat server. PostgreSQL and Ollama usually auto-start; Kiss does
not. Check the first two, then start Kiss.

### 1a. PostgreSQL

Assumed to be up and accepting connections on `localhost:5432`. If it is
not, start it before proceeding.

### 1b. Ollama

```bash
# Health check via the local HTTP API
curl -s --max-time 3 http://localhost:11434/api/tags | head -c 200; echo
# Expect: a JSON object listing your installed models.
```

If it is not running, start it:

```bash
# Foreground (in its own terminal):
ollama serve

# OR, if installed as a systemd service:
sudo systemctl start ollama
sudo systemctl enable ollama       # one-time
```

Confirm the embedding model is present (the indexer will fail loudly if not):

```bash
ollama list | grep nomic-embed-text
# Expect: nomic-embed-text:v1.5
# If missing:  ollama pull nomic-embed-text:v1.5
```

### 1c. Kiss / Tomcat (the RAG server)

```bash
cd /path/to/code-rag
./bld -v start
```

You should see `***** SERVER IS RUNNING *****`. The server listens on
`http://127.0.0.1:17080`. Run `./bld status` to confirm the HTTP port
is listening and to see the per-project file/chunk counts.

If Tomcat refuses to start with a `tomcat/bin/debug: cd: ...: No such file or directory`
error, that's a stale debug script from a different working directory.
Delete it once; `bld` will regenerate it with the right path:

```bash
rm -f tomcat/bin/debug tomcat/bin/stopdebug
./bld -v start
```

---

## 2. Quick health check

```bash
./bld status
```

Reports `Status: RUNNING` or `not running`, the live ports, database +
Ollama config, and every configured project with its file/chunk counts.
Empty projects show 0 files / 0 chunks — that means the schema exists
but you haven't indexed it yet.

---

## 3. Working with an existing project

Existing projects are already in `src/main/backend/rag-projects.json` and
their schemas already exist in `code_rag`. The cron sweep refreshes
them every 10 minutes — incremental sweeps are cheap (SHA check per
file, no work when nothing has changed). You can also force a refresh
on demand with `./bld scan <project|all>` (see §7) or via the JSON-RPC
`reindex` method. To use a project from Claude Code, jump to **§5
Register with Claude Code**.

> The cron sweep is configured in `src/main/backend/CronTasks/crontab`.
> Comment out the `*/10 * * * * RAGSweep` line if you'd rather run
> sweeps only when you explicitly ask. Either way, the system
> auto-scans any project whose `rag_file` table is empty at server
> startup (e.g. a brand-new project you just added) — see §7.4.

---

## 4. Adding a brand-new project

### 4a. Edit `src/main/backend/rag-projects.json`

Add a JSON entry inside `"projects"`:

```json
{
  "name": "myproj",
  "roots": [
    "/absolute/path/to/code/root1",
    "/absolute/path/to/code/root2"
  ],
  "excludeGlobs": [
    "**/node_modules", "**/node_modules/**",
    "**/.git", "**/.git/**",
    "**/build", "**/build/**",
    "**/*.jar"
  ]
}
```

Rules:
- `name` must match `[a-z][a-z0-9_]*` (PostgreSQL identifier; no hyphens,
  no capitals). It becomes the schema name.
- `roots` is a list of absolute paths.
- `excludeGlobs` is optional. If omitted you get the default skip list.
- Each glob is matched against absolute paths. Use the `**/X` and `**/X/**`
  pair to make `preVisitDirectory` skip whole subtrees.

### 4b. Restart Kiss so the bootstrap creates the schema

```bash
./bld stop
./bld -v start
```

`KissInit.init2` runs at startup; it creates the new schema in `code_rag`
with the right tables, indexes, and seed `rag_meta` rows (idempotent — safe
to run repeatedly).

### 4c. The first index

You don't need to trigger it. The Kiss startup auto-scan (see §7.4) runs
an incremental sweep on any project whose `rag_file` is empty right
after `./bld start`, in a background thread. To watch it complete:

```bash
./bld scan myproj
```

`scan` reports `rejected: A reindex is already in progress...` while
the auto-scan is still running; once it finishes the next `./bld scan
myproj` triggers a quick incremental top-up and prints per-poll
progress until done.

Throughput is roughly 5–15 files/sec on a modern GPU; a 10k-file
codebase finishes in ~10–25 minutes for the very first index.

---

## 5. Register with your agent

Each project is exposed as one MCP server at
`http://127.0.0.1:17080/rag-mcp/<project>`, authenticated by the
`X-RAG-Token` header (value from `RAGMCPSharedSecret` in
`application.ini`). The exact registration command depends on which
client you use:

- **Claude Code** — see [ClaudeCode.md](ClaudeCode.md).
- **OpenAI Codex CLI** — see [Codex.md](Codex.md).

If you rotate the shared secret in `application.ini`, restart Kiss and
re-register the MCP entry in whichever client(s) you use.

---

## 6. Day-to-day operations

### Check status

```bash
./bld status
```

Reports whether the server is running, the live ports, database and
Ollama config, and every configured project with its file/chunk counts
and last-sweep stats.

### Trigger a manual reindex

The easiest way is the bld target — it polls the server and prints
per-project progress in your terminal:

```bash
./bld scan myproj      # one project, incremental
./bld scan all         # every project in rag-projects.json, sequentially
```

Sample output:

```
Scanning stack360...
  [2s      ]  files=143      chunks=1450
  [12s     ]  files=1240     chunks=14500
  ...
  [18m 32s ]  DONE: files=10815   chunks=106674
```

If a sweep is already running for that project the second caller is
rejected by the per-project lock and reports `rejected: A reindex is
already in progress...`.

For a **full rebuild** (TRUNCATE + re-embed everything — slow), `./bld
scan` doesn't expose `full=true`, so call `RAGAdmin.reindex` directly:

```bash
curl -s -X POST http://localhost:17080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"reindex","_class":"services/RAGAdmin","project":"myproj","full":true}'
```

### Auto-scan on startup

Whenever Kiss starts (`./bld start`), `KissInit.init2` runs the
multi-project bootstrap; for any configured project whose `rag_file`
table is empty (i.e. never scanned, or wiped), a daemon thread runs the
indexer on it right then. Steady-state restarts are no-ops because every
project already has rows. Useful in two scenarios:

- You add a new project to `rag-projects.json` and restart.
- You wipe an index (`./bld realclean` or a manual `TRUNCATE`) and restart.

Look for `auto-scan starting` and `auto-scan finished` lines in
`tomcat/logs/catalina.out`.

### Watch the server log

```bash
tail -F tomcat/logs/catalina.out
# Or: ./bld view-log
```

Useful filters:

```bash
# Just our RAG INFO lines, no stack traces
grep -E 'RAGAdmin|RAGSweep|RAGIndexer.*sweep done' tomcat/logs/catalina.out | tail -50

# Any errors
grep ERROR tomcat/logs/catalina.out | tail -20
```

### Stop / restart Kiss

```bash
./bld stop     # stop
./bld -v start # start
```

A restart resets `reindex_running='false'` per project, so a crashed sweep
never permanently locks anything.

---

## 7. Removing a project

By design the system does **not** auto-drop a schema when you remove the
project from `rag-projects.json` — a typo would otherwise lose data. To
fully remove:

```bash
# 1. Delete the entry from src/main/backend/rag-projects.json and restart:
./bld stop
./bld -v start

# 2. Drop the schema explicitly (irreversible):
psql -U postgres -d code_rag -c "DROP SCHEMA myproj CASCADE;"

# 3. Remove the MCP entry from Claude Code:
claude mcp remove myproj
```

---

## 8. Troubleshooting

**Tomcat starts but `17080` is not listening.**
Look at the bottom of `tomcat/logs/catalina.out`. Most common: an exception
during `KissInit.init2` (often because PostgreSQL is down or
`rag-projects.json` has a bad project name). Fix the underlying issue and
restart.

**`pg_isready` says no, but PostgreSQL is installed.**

```bash
sudo systemctl start postgresql
journalctl -u postgresql -n 50    # see why if it refuses to start
```

**Ollama unreachable.**

```bash
curl -v http://localhost:11434/api/tags
# If "Connection refused": ollama is not running.
ollama serve   # foreground
```

**`indexing` is stuck at `true` forever.**
A crashed sweep can leave the flag set. The next Kiss restart clears it
automatically (`KissInit.init2` resets every project's `reindex_running`).
If you want to clear it without restarting:

```bash
psql -U postgres -d code_rag -c \
  "UPDATE <project>.rag_meta SET value='false' WHERE key='reindex_running';"
```

**Cron sweeps fail with `Cannot read the array length because "args" is null`.**
A backend Groovy file failed to compile — usually because of a recent edit.
Check `catalina.out`; the compile error is logged just above. The cron
retries each minute, so just fix the source and the next firing will succeed.

**Claude Code does not see the `mcp__rag-*__*` tools.**
- Verify the MCP server is registered: `claude mcp list`
- Verify Kiss is up: `curl http://localhost:17080/rag-mcp/<project> -X POST -H "X-RAG-Token: <token>" -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'`
- Restart the Claude Code session (MCP servers are connected at session start).

**Search results look noisy / miss obvious matches.**
The HNSW index defaults to a low `ef_search`. The MCP server already sets
it to 400 per query, which is fine for ~100k chunks. If you have a much
larger index (>500k chunks) and recall starts to slip, bump `HNSWEfSearch`
in `application.ini` and restart.

**A specific file fails to embed (logged as `RAGIndexer: failed on …`).**
The indexer auto-recovers (rollback per file, ON CONFLICT upsert, retry/half
the embed batch on context-length errors). A single chunk too dense to embed
even after truncation → that one file is skipped; the rest of the sweep
continues. Look at the log line to see which file.

---

## 9. Reference

| Thing | Where |
|---|---|
| Database | `code_rag` (PostgreSQL local) |
| Per-project schema | `code_rag.<project>.rag_file` / `rag_chunk` / `rag_meta` |
| Embedding model | `nomic-embed-text:v1.5` (Ollama) |
| Tomcat port | `17080` (localhost only) |
| MCP URL | `http://127.0.0.1:17080/rag-mcp/<project>` |
| MCP shared secret | `src/main/backend/application.ini` → `RAGMCPSharedSecret` |
| Project config | `src/main/backend/rag-projects.json` |
| Global knobs | `src/main/backend/application.ini` |
| Cron schedule | `src/main/backend/CronTasks/crontab` (every 10 min by default) |
| Logs | `tomcat/logs/catalina.out` |
| Indexer code | `src/main/backend/scripts/RAGIndexer.groovy` |
| MCP server code | `src/main/precompiled/org/kissweb/rag/RAGMCPServer.java` |
| Plans | [RAGPlan.md](RAGPlan.md) |
