# Setup

Step-by-step procedure to install and configure Claude-RAG from scratch.

Each step gives the exact command and what a successful result looks like.
After the last step you'll have a working server, an indexed project, and
Claude Code able to query it.

## 0. Assumed prerequisites

These should already be **installed and working** on the machine. This
guide does not cover installing them.

- **Claude Code CLI** — `claude` resolves on your `$PATH`.
- **PostgreSQL** — running locally and you can connect as the `postgres`
  user (`psql -U postgres -c 'SELECT 1'` succeeds).
- **pgvector** — the extension is **available** in your PostgreSQL
  install. `SELECT * FROM pg_available_extensions WHERE name='vector';`
  must return one row. (We will create the extension inside the new
  database in step 4.)
- **Ollama** — daemon reachable at `http://localhost:11434`
  (`curl -s http://localhost:11434/api/tags` returns JSON).
- **Java 21+** — `java -version` shows 21 or newer.
- **Bash + Python 3 + curl** — already standard on Linux/macOS.

If any of those fail, fix them first; this guide assumes a clean slate
otherwise.

---

## 1. Get the code

```bash
git clone <repo-url> claude-rag
cd claude-rag
```

Replace `<repo-url>` with wherever you're cloning from. Every subsequent
command in this guide is run from the repo root.

---

## 2. Run the one-time setup script

```bash
./setup.sh
```

What this does:
- Copies `src/main/backend/application.ini.example` →
  `src/main/backend/application.ini` and replaces `REPLACE_WITH_RANDOM_UUID`
  with a freshly generated UUID (the MCP shared secret). Sets file mode to
  `600`.
- Copies `src/main/backend/rag-projects.json.example` →
  `src/main/backend/rag-projects.json`.

Expected output ends with:

```
created application.ini (shared secret generated, file is mode 600)
created rag-projects.json — EDIT IT before starting the server
```

Re-running is safe. If either real file already exists, it is left alone.

---

## 3. Tell Claude-RAG what to index

Edit `src/main/backend/rag-projects.json`. Replace the placeholder `demo`
project with one of your own:

```json
{
  "projects": [
    {
      "name": "myproj",
      "roots": [
        "/absolute/path/to/code-root-1",
        "/absolute/path/to/code-root-2"
      ],
      "excludeGlobs": [
        "**/node_modules", "**/node_modules/**",
        "**/.git", "**/.git/**",
        "**/build", "**/build/**",
        "**/target", "**/target/**",
        "**/dist", "**/dist/**",
        "**/*.jar"
      ]
    }
  ]
}
```

Rules:
- `name` must match `[a-z][a-z0-9_]*`. It becomes the PostgreSQL schema
  name and the MCP URL segment, so no hyphens or capitals.
- `roots` is one or more absolute paths.
- `excludeGlobs` is optional. The example above is a sensible starting
  point; the `**/X` + `**/X/**` pair lets the indexer prune whole subtrees.

You can declare additional projects in the same file later. Each becomes
an independent schema and an independent MCP URL.

---

## 4. Create the PostgreSQL database

One time only:

```bash
createdb -U postgres claude_rag
psql -U postgres -d claude_rag -c 'CREATE EXTENSION IF NOT EXISTS vector;'
```

Verify:

```bash
psql -U postgres -d claude_rag -c "\dx vector"
```

Expected: one row showing the `vector` extension.

Per-project schemas (`<name>.rag_file`, `<name>.rag_chunk`,
`<name>.rag_meta`) and indexes are created automatically when you start
the server in step 6 — you don't run any DDL yourself.

---

## 5. Pull the embedding model

```bash
ollama pull nomic-embed-text:v1.5
```

Confirm:

```bash
ollama list | grep nomic-embed-text
```

Expected: one line listing `nomic-embed-text:v1.5`.

This is the default model the indexer and the MCP server both use.
Changing it later means a full reindex; pick once and stick with it.

---

## 6. (Optional) Adjust global knobs

`src/main/backend/application.ini` already has working defaults. Edit only
if you need to:

- `DatabaseUser`, `DatabasePassword`, `DatabaseHost`, `DatabasePort` if
  your PostgreSQL setup is not `localhost:5432` with peer/trust auth to
  the `postgres` user.
- `OllamaURL` if Ollama is not on `127.0.0.1:11434`.
- `EmbeddingModel` if you want a different Ollama model (must match
  `EmbeddingDim`; the schema and stored embeddings are dimension-locked).
- `RAGSweepMinutes` to change the cron interval (default 10 minutes).
- `HNSWEfSearch` if you have a much larger index than ~100k chunks and
  recall starts to slip (default 400 is fine up to that size).

The shared secret in `RAGMCPSharedSecret` is what `setup.sh` generated
for you. You can replace it any time — see step 9 for what to update on
the Claude Code side.

---

## 7. Build and start the server

```bash
./bld -v start-backend
```

The build downloads any missing JARs the first time. Expected output ends
with:

```
***** SERVER IS RUNNING *****
```

Wait for the HTTP endpoint to be live (a few seconds):

```bash
until curl -sf -o /dev/null --max-time 3 -X POST http://localhost:8080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"listProjects","_class":"services/RAGAdmin"}'; do
  sleep 1
done
echo "ready"
```

Verify the project's schema was bootstrapped:

```bash
psql -U postgres -d claude_rag -c "SELECT schema_name FROM information_schema.schemata
                                    WHERE schema_name NOT IN ('information_schema','pg_catalog','pg_toast','public');"
```

Expected: one row per project listed in `rag-projects.json`.

---

## 8. Trigger the first full index

For each project name in `rag-projects.json`:

```bash
curl -s -X POST http://localhost:8080/rest \
    -H 'Content-Type: application/json' \
    -d '{"_method":"reindex","_class":"services/RAGAdmin","project":"myproj","full":true}' \
  | python3 -m json.tool
```

Expected:

```json
{ "started": true, "project": "myproj", ... }
```

The work runs in a background thread. Watch progress with:

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

Throughput is ~5–15 files/sec with a GPU on the Ollama side; ~10–25
minutes for a 10k-file codebase, slower on CPU.

After the first full index, the cron (every 10 minutes by default) keeps
the index current. You won't need to rerun this command unless you add a
new project or want a clean rebuild.

---

## 9. Register the MCP server with Claude Code

Once per project:

```bash
SECRET=$(grep '^RAGMCPSharedSecret' src/main/backend/application.ini | sed 's/.*=\s*//' | tr -d ' ')

claude mcp add --transport http rag-myproj \
    http://127.0.0.1:8080/rag-mcp/myproj \
    --header "X-RAG-Token: $SECRET"
```

Verify:

```bash
claude mcp list
```

Expected: `rag-myproj` appears in the list.

If you ever rotate the secret in `application.ini`, restart the server
and re-run `claude mcp add` (it will replace the existing entry).

---

## 10. Use it from Claude Code

Start a Claude Code session inside the project's working tree. Enable the
matching MCP server for that session, then try a prompt:

> *Use `mcp__rag-myproj__search_code` to find where login is handled.*

Claude will call `search_code`, get a list of hits with file paths and
line ranges, then typically follow up with `Read` on the top hit's
`absolute_path` and line range.

If you don't see the `mcp__rag-myproj__*` tools, check:

1. The server is running: `curl http://localhost:8080/rag-mcp/myproj -X POST -H "X-RAG-Token: $SECRET" -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'` returns JSON.
2. The registration is visible: `claude mcp list`.
3. You restarted the Claude Code session after registering (MCP servers
   connect at session start).

---

## What to do next

- Daily operations (status, manual reindex, log tail, stop/restart,
  troubleshooting) → [Running.md](Running.md).
- Adding a second project → §4 of [Running.md](Running.md) (edit
  `rag-projects.json`, restart the server, run a full index).
- Architecture and design rationale → [Overview.md](Overview.md).
