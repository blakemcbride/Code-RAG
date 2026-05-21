# Running Claude-RAG

Step-by-step instructions for using the local RAG with Claude Code, starting
from a fresh system reboot. Every command is meant to be copy-paste-able.

Substitute your own paths for `/path/to/claude-rag` and your own project
name for `myproj` throughout.

---

## 1. After every reboot — bring services up

The RAG pipeline has three independent processes: PostgreSQL, Ollama, and
the Kiss/Tomcat server. PostgreSQL and Ollama usually auto-start; Kiss does
not. Check the first two, then start Kiss.

### 1a. PostgreSQL

```bash
# Verify it is running and accepting connections
pg_isready -h localhost -p 5432
# Expect: localhost:5432 - accepting connections
```

If it is not running:

```bash
sudo systemctl start postgresql
sudo systemctl enable postgresql   # one-time, so it auto-starts on reboot
```

Quick sanity check that the RAG database is still there:

```bash
psql -U postgres -d claude_rag -tAc "SELECT schema_name FROM information_schema.schemata
                                     WHERE schema_name NOT IN ('information_schema','pg_catalog','pg_toast','public');"
# Should list every project schema you have configured.
```

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
cd /path/to/claude-rag
./bld -v start-backend
```

You should see `***** SERVER IS RUNNING *****`. The server listens on
`http://127.0.0.1:8080`.

Wait for the HTTP endpoint to be live (occasionally takes a few seconds
after "running"):

```bash
until curl -sf -o /dev/null --max-time 3 -X POST http://localhost:8080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"listProjects","_class":"services/RAGAdmin"}'; do
  sleep 1
done
echo "ready"
```

If Tomcat refuses to start with a `tomcat/bin/debug: cd: ...: No such file or directory`
error, that's a stale debug script from a different working directory.
Delete it once; `bld` will regenerate it with the right path:

```bash
rm -f tomcat/bin/debug tomcat/bin/stopdebug
./bld -v start-backend
```

---

## 2. Quick health check

```bash
curl -s -X POST http://localhost:8080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"listProjects","_class":"services/RAGAdmin"}' | python3 -m json.tool
```

Should print every configured project plus current file/chunk counts. Empty
projects show `files: 0, chunks: 0` — that means the schema exists but you
have not run the first index yet.

---

## 3. Working with an existing project

Existing projects are already in `src/main/backend/rag-projects.json` and
their schemas already exist in `claude_rag`. The cron sweeps them every
10 minutes — file changes flow in automatically. To use one from Claude
Code, jump to **§5 Register with Claude Code**.

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
./bld stop-backend
./bld -v start-backend
```

`KissInit.init2` runs at startup; it creates the new schema in `claude_rag`
with the right tables, indexes, and seed `rag_meta` rows (idempotent — safe
to run repeatedly).

### 4c. Kick off the first full index

```bash
curl -s -X POST http://localhost:8080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"reindex","_class":"services/RAGAdmin","project":"myproj","full":true}' \
  | python3 -m json.tool
# Expect: { ..., "started": true, ... }
```

Watch progress:

```bash
while [ "$(psql -U postgres -d claude_rag -tAc \
    "SELECT value FROM myproj.rag_meta WHERE key='reindex_running'")" = "true" ]; do
  psql -U postgres -d claude_rag -tAc \
    "SELECT 'files=' || (SELECT count(*) FROM myproj.rag_file) || \
            ', chunks=' || (SELECT count(*) FROM myproj.rag_chunk)"
  sleep 10
done
echo "indexing done"
```

Throughput is roughly 5–15 files/sec on a modern GPU; a 10k-file codebase
finishes in ~10–25 minutes.

---

## 5. Register with Claude Code

One MCP server per project the session needs. The URL carries the project
name; the same shared secret authenticates everything.

Grab your secret from `application.ini` and pass it as a header:

```bash
SECRET=$(grep '^RAGMCPSharedSecret' src/main/backend/application.ini | sed 's/.*=\s*//' | tr -d ' ')

claude mcp add --transport http rag-myproj \
    http://127.0.0.1:8080/rag-mcp/myproj \
    --header "X-RAG-Token: $SECRET"
```

Repeat for each project you have. Rotating the secret means editing
`RAGMCPSharedSecret` in `application.ini`, restarting Kiss, and rerunning
the `claude mcp add` lines.

After registration:
1. Open Claude Code in a working directory belonging to that project.
2. Only enable the matching MCP server for that session. Different sessions
   pointed at different projects will not see each other's index.
3. Try a prompt that invokes the tool:
   *"Use mcp__rag-myproj__search_code to find where login is handled."*
   Claude will call `search_code`, then `Read` the absolute_path from a top hit.

---

## 6. Day-to-day operations

### Check status of one project

```bash
curl -s -X POST http://localhost:8080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"status","_class":"services/RAGAdmin","project":"myproj"}' \
  | python3 -m json.tool
```

Returns counts, last-sweep stats, and `indexing: true|false`.

### Check status of all projects at once

```bash
curl -s -X POST http://localhost:8080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"status","_class":"services/RAGAdmin"}' \
  | python3 -m json.tool
```

### Trigger a manual reindex

```bash
# Incremental (changed/new files only — fast):
curl -s -X POST http://localhost:8080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"reindex","_class":"services/RAGAdmin","project":"myproj","full":false}'

# Full rebuild (TRUNCATE + re-embed everything):
curl -s -X POST http://localhost:8080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"reindex","_class":"services/RAGAdmin","project":"myproj","full":true}'
```

If a reindex is already running for that project the response will be
`{"started": false, "message": "A reindex is already in progress..."}` —
that is the per-project lock doing its job.

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
./bld stop-backend     # stop
./bld -v start-backend # start
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
./bld stop-backend
./bld -v start-backend

# 2. Drop the schema explicitly (irreversible):
psql -U postgres -d claude_rag -c "DROP SCHEMA myproj CASCADE;"

# 3. Remove the MCP entry from Claude Code:
claude mcp remove rag-myproj
```

---

## 8. Troubleshooting

**Tomcat starts but `8080` is not listening.**
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
psql -U postgres -d claude_rag -c \
  "UPDATE <project>.rag_meta SET value='false' WHERE key='reindex_running';"
```

**Cron sweeps fail with `Cannot read the array length because "args" is null`.**
A backend Groovy file failed to compile — usually because of a recent edit.
Check `catalina.out`; the compile error is logged just above. The cron
retries each minute, so just fix the source and the next firing will succeed.

**Claude Code does not see the `mcp__rag-*__*` tools.**
- Verify the MCP server is registered: `claude mcp list`
- Verify Kiss is up: `curl http://localhost:8080/rag-mcp/<project> -X POST -H "X-RAG-Token: <token>" -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'`
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
| Database | `claude_rag` (PostgreSQL local) |
| Per-project schema | `claude_rag.<project>.rag_file` / `rag_chunk` / `rag_meta` |
| Embedding model | `nomic-embed-text:v1.5` (Ollama) |
| Tomcat port | `8080` (localhost only) |
| MCP URL | `http://127.0.0.1:8080/rag-mcp/<project>` |
| MCP shared secret | `src/main/backend/application.ini` → `RAGMCPSharedSecret` |
| Project config | `src/main/backend/rag-projects.json` |
| Global knobs | `src/main/backend/application.ini` |
| Cron schedule | `src/main/backend/CronTasks/crontab` (every 10 min by default) |
| Logs | `tomcat/logs/catalina.out` |
| Indexer code | `src/main/backend/scripts/RAGIndexer.groovy` |
| MCP server code | `src/main/precompiled/org/kissweb/rag/RAGMCPServer.java` |
| Plans | [RAGPlan.md](RAGPlan.md), [docs/plans/Plan2.md](docs/plans/Plan2.md), [docs/plans/Plan3.md](docs/plans/Plan3.md) |
