# Setup

Step-by-step procedure to install and configure Code-RAG from scratch.

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

### Installing the prerequisites on macOS (Homebrew)

Skip this subsection if your prerequisites are already in place. Apple
Silicon and Intel both work — the only difference is whether Homebrew
installs under `/opt/homebrew` (Apple Silicon) or `/usr/local`
(Intel).

```bash
# PostgreSQL + pgvector
brew install postgresql@16 pgvector
brew services start postgresql@16

# pgvector is shipped as a formula; it's installed into the running
# postgres @16 automatically. No further action needed until step 4
# (where we create the `vector` extension inside the new database).

# Ollama
brew install ollama
brew services start ollama

# Java 21 (Eclipse Temurin)
brew install --cask temurin@21

# Claude Code (optional, but needed if you want to drive Code-RAG from it)
npm install -g @anthropic-ai/claude-code     # requires Node.js — `brew install node` first

# Codex CLI (optional)
npm install -g @openai/codex
```

`brew services start` is the macOS analogue of `systemctl start` —
the rest of this guide uses `systemctl` syntax in its examples; on
macOS substitute `brew services start <name>` / `brew services stop
<name>`.

Smoke-test that everything's reachable before moving on:

```bash
psql -U postgres -c 'SELECT 1'                         # PostgreSQL
psql -U postgres -c "SELECT * FROM pg_available_extensions WHERE name='vector'"   # pgvector available
curl -s http://localhost:11434/api/tags                # Ollama
java -version                                          # Java 21+
```

> **PostgreSQL user note (macOS):** Homebrew's PostgreSQL doesn't
> create a `postgres` superuser by default — it uses your macOS
> username. If `psql -U postgres` errors with "role does not exist",
> either create the role (`createuser -s postgres`) or substitute
> your own username in every `psql -U postgres` invocation in this
> guide. The Code-RAG server reads the username from
> `application.ini` (`DatabaseUser`), so it doesn't care which one
> you pick — just be consistent.

---

## 1. Get the code

```bash
git clone <repo-url> code-rag
cd code-rag
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

## 3. Tell Code-RAG what to index

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
createdb -U postgres code_rag
psql -U postgres -d code_rag -c 'CREATE EXTENSION IF NOT EXISTS vector;'
```

Verify:

```bash
psql -U postgres -d code_rag -c "\dx vector"
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

`./bld start` preflight-checks this: it refuses to launch Tomcat if
Ollama is unreachable or the configured `EmbeddingModel` is not
installed, and prints the exact `ollama pull` command to run.

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
- `RAGSweepMinutes` to change the cron sweep interval from its default
  (10 minutes). The cron sweep is enabled in `CronTasks/crontab`; comment
  out the `RAGSweep` line there if you'd rather drive scans manually.
- `HNSWEfSearch` if you have a much larger index than ~100k chunks and
  recall starts to slip (default 400 is fine up to that size).

The shared secret in `RAGMCPSharedSecret` is what `setup.sh` generated
for you. You can replace it any time — see step 9 for what to update on
the Claude Code side.

---

## 7. Build and start the server

```bash
./bld -v start
```

The build downloads any missing JARs the first time. Expected output ends
with:

```
***** SERVER IS RUNNING *****
```

`./bld status` will confirm `Status: RUNNING` with `HTTP port: 17080
(listening)` once the endpoint is up.

Verify the project's schema was bootstrapped:

```bash
psql -U postgres -d code_rag -c "SELECT schema_name FROM information_schema.schemata
                                    WHERE schema_name NOT IN ('information_schema','pg_catalog','pg_toast','public');"
```

Expected: one row per project listed in `rag-projects.json`.

---

## 8. The first index

You usually have **nothing to do here**. When Kiss started in step 7 it
detected that each project's `rag_file` table was empty and kicked off
the first index automatically in a background thread. The indexer keeps
running after `./bld start` returns; you can watch it with:

```bash
./bld scan myproj
```

`scan` is idempotent: if a sweep is already in flight (typical right
after a fresh start), it reports `rejected: A reindex is already in
progress...` rather than starting another. If the sweep has already
finished, it triggers a quick incremental re-scan that completes in
seconds.

If you'd rather watch directly from the database while the auto-scan
runs:

```bash
while [ "$(psql -U postgres -d code_rag -tAc \
    "SELECT value FROM myproj.rag_meta WHERE key='reindex_running'")" = "true" ]; do
  psql -U postgres -d code_rag -tAc \
    "SELECT 'files=' || (SELECT count(*) FROM myproj.rag_file) || \
            ', chunks=' || (SELECT count(*) FROM myproj.rag_chunk)"
  sleep 10
done
echo "indexing done"
```

Throughput is ~5–15 files/sec with a GPU on the Ollama side; ~10–25
minutes for a 10k-file codebase, slower on CPU.

After this first run, the index refreshes itself every 10 minutes via
the cron sweep configured in `src/main/backend/CronTasks/crontab`. The
sweep is incremental — it hashes each file and only re-embeds the ones
whose content has changed — so a no-op sweep over a large repo takes
just a few seconds. If you want to force an immediate refresh, use
`./bld scan <project|all>`; to disable the cron sweep entirely, comment
out the `*/10 * * * * RAGSweep` line in that crontab.

---

## 9. Register the MCP server with your agent

Each project is exposed as one MCP server at
`http://127.0.0.1:17080/rag-mcp/<project>`, authenticated by the
`X-RAG-Token` header (value: `RAGMCPSharedSecret` in
`application.ini`). The exact registration command differs by client
— follow the one for your agent:

- **Claude Code** — [ClaudeCode.md](ClaudeCode.md).
- **OpenAI Codex CLI** — [Codex.md](Codex.md).

Each guide also covers verification and how to rotate the shared
secret.

---

## 10. (Optional) Install the `code-rag` wrapper

A tiny shell script at the repo root, `code-rag`, delegates everything
to `bld` — `cd $CODE_RAG_HOME && exec ./bld "$@"`. All real work
(status, scan, new-project, remove-project, add-root, remove-root,
register/deregister MCP entries with Claude Code and Codex) lives in
`bld` (Tasks.java). The wrapper exists so you don't have to be in
`$CODE_RAG_HOME` to use those commands. It needs nothing beyond bash
and a Java install (Java is already required for `bld`).

### 10a. **Required: set `CODE_RAG_HOME`**

> ⚠️ **The `code-rag` script will refuse to run until you set the
> `CODE_RAG_HOME` environment variable.** It must hold the **absolute
> path** of this Code-RAG installation directory — the same directory
> that contains `bld`, `setup.sh`, and `src/`. Without it, every
> invocation of `code-rag` exits immediately with:
>
>     code-rag: CODE_RAG_HOME is not set. Set it to the absolute path of your Code-RAG installation.

Set it in your shell startup file so every new terminal session has
it. Pick the file your shell actually reads (`~/.bashrc` for bash,
`~/.zshrc` for zsh, `~/.profile` for sh-style logins):

```bash
echo "export CODE_RAG_HOME=$(pwd)" >> ~/.bashrc    # run this from inside the Code-RAG dir
exec bash                                          # reload the shell
```

Verify the variable is set and points where you expect:

```bash
echo "$CODE_RAG_HOME"            # should print the absolute path
ls "$CODE_RAG_HOME/bld"          # should list the bld script
```

If either of those is wrong, `code-rag` cannot work — fix it before
continuing.

### 10b. Copy the script onto `$PATH`

```bash
sudo cp code-rag /usr/local/bin/code-rag    # system-wide
# or, no sudo required:
mkdir -p ~/.local/bin && cp code-rag ~/.local/bin/    # per-user
# make sure ~/.local/bin is on your $PATH if you took the per-user route
```

### 10c. Smoke test

```bash
code-rag status     # should print the same block as ./bld status
```

If you get `CODE_RAG_HOME is not set` here, the shell hasn't picked up
the change yet — open a fresh terminal or `exec bash` again.

### 10d. Command summary

```bash
code-rag start                               # build + start the server
code-rag status                              # is it running? which projects?
code-rag scan <project|all>                  # incremental reconcile + scan
code-rag new-project <name> <root>...        # add a project, index it, register MCP entries
code-rag remove-project <name>               # remove a project (drops schema, deregisters MCP entries)
code-rag add-root <name> <root>...           # add roots to an existing project
code-rag remove-root <name> <root>...        # remove roots from an existing project
code-rag stop                                # stop the server
```

Each `*-project` / `*-root` command edits
`$CODE_RAG_HOME/src/main/backend/rag-projects.json`, syncs the live
copies under `work/exploded/.../backend/` and
`tomcat/webapps/ROOT/.../backend/`, then runs `bld scan` so the
reconcile step takes effect immediately. `new-project` and
`remove-project` additionally maintain `~/.claude.json` (via
`claude mcp add` / `remove`) and `~/.codex/config.toml` (direct
section edit) for whichever clients are installed; if a client isn't
installed, that registration is silently skipped.

Project names must match `[a-z][a-z0-9_]*` (they become PostgreSQL
schema names). Dashes are auto-rewritten to underscores with a
note — `code-rag new-project my-proj /path` is accepted and stored as
`my_proj`.

---

## What to do next

- Daily operations (status, manual reindex, log tail, stop/restart,
  troubleshooting) → [Running.md](Running.md).
- Adding a second project → §4 of [Running.md](Running.md) (edit
  `rag-projects.json` or use `code-rag new-project`).
- Architecture and design rationale → [Overview.md](Overview.md).
