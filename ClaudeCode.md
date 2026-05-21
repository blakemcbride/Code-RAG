# Using Code-RAG with Claude Code

Claude Code is the primary client Code-RAG was built for. The server
itself is plain MCP — see [Codex.md](Codex.md) for the OpenAI Codex CLI
flavor of these same instructions.

The rest of the repo's documentation
([Setup.md](Setup.md), [Running.md](Running.md), [RAGPlan.md](RAGPlan.md))
explains how to bring the server up, index your projects, and operate
it day-to-day. **That part is unchanged regardless of which agent you
use** — start the server the same way, maintain the same
`application.ini` and `rag-projects.json`, run scans with `./bld scan`.
The only difference is on the *client* side: how each agent learns
about the MCP server.

## Two config files involved

Working with Code-RAG through Claude Code touches two distinct config
files, owned by opposite sides of the system. The most common point of
confusion is mixing up their roles, so:

| File | What it does | Who edits it | When you touch it |
|---|---|---|---|
| `src/main/backend/rag-projects.json` | Tells the Code-RAG **server** which code trees to index. One JSON entry per project: `name`, absolute `roots[]`, optional `excludeGlobs`. Gitignored — the committed `.example` is just a template. | You (hand-edit). | When adding/removing a project or changing its roots. Restart Kiss after editing so the new schema gets bootstrapped. |
| `~/.claude.json` | Tells **Claude Code** which MCP servers exist for which working directory. Holds your *entire* Claude Code state (every project's settings + MCP entries), not just Code-RAG. | The `claude` CLI (`claude mcp add` / `remove` / `list`). Don't hand-edit unless you have to. | When registering this Code-RAG instance with a new Claude Code project, or when the shared secret rotates. |

The two files don't communicate directly. The **bridge is the URL**: in
`~/.claude.json` you add an MCP entry with URL
`http://127.0.0.1:17080/rag-mcp/<X>`, and `<X>` must equal a `name` in
`rag-projects.json`. If they don't match the server replies 404 when
Claude Code tries to call a tool.

Worth being precise about one subtlety: the MCP **entry name** in
`~/.claude.json` (the key under `mcpServers`) is just Claude Code's
local label — it becomes the `mcp__<name>__…` tool prefix Claude Code
exposes. The server, on the other hand, routes on the URL **path
segment** after `/rag-mcp/`. We use the same string for both by
convention — usually the `name` from `rag-projects.json` — so all three
agree, but they are technically independent variables. The thing the
server actually requires to match is the URL path segment.

`rag-projects.json` is documented end-to-end in [Setup.md §3](Setup.md)
and [Running.md §4](Running.md). `~/.claude.json` is Claude Code's own
file — its schema and behavior live with [Claude Code's
documentation](https://docs.claude.com/en/docs/claude-code/overview).

## Working directory and visible MCP entries

Claude Code identifies its "current project" by **the absolute working
directory `claude` was launched from**. There is no separate project
flag — `pwd` *is* the identity. Inside `~/.claude.json` your MCP entries
live under that path as a key:

```text
projects:
    /home/you/Stack360/Code-RAG:
        mcpServers:
            myproj:
                url:     "http://127.0.0.1:17080/rag-mcp/myproj"
                headers: { "X-RAG-Token": "..." }
    /home/you/somewhere-else:
        mcpServers: { ... }
```

The default `--scope local` (which is what plain `claude mcp add` uses)
writes the entry under the cwd you ran the command in. Practical
consequences:

- **Register from the directory you'll launch `claude` from.** Running
  `claude mcp add` from `/home/you/Stack360/Code-RAG` puts the entry
  under that key; launching `claude` from somewhere else will not show
  the `myproj` server.
- **`claude mcp list` is cwd-sensitive.** A blank list usually means
  you registered from a different directory.
- **Renaming or moving the working tree invalidates the entry.** The
  old project key no longer matches your new cwd. Re-run
  `claude mcp add` from the new path (or edit the key in
  `~/.claude.json` by hand).
- **`--scope user` makes an entry visible from any directory.** Useful
  if you want a single Code-RAG registration that works no matter
  where you launch `claude`. The trade-off: every Claude Code session
  on the machine sees that MCP entry, whether the project is relevant
  to the work or not.
- **`--scope project` writes a committable `.mcp.json` inside the
  project tree.** Visible from anywhere inside that tree. We don't
  recommend it for Code-RAG because the shared secret would end up in
  the file (and possibly in git).

## 0. Prerequisites

- Code-RAG is installed and the server is running on its default
  loopback URL `http://127.0.0.1:17080/`. (See [Setup.md](Setup.md).)
- At least one project is configured in
  `src/main/backend/rag-projects.json` and its index is populated.
  Test with `./bld status` — under "Projects" you should see at least
  one entry with a non-zero file count.
- [Claude Code](https://docs.claude.com/en/docs/claude-code/overview)
  is installed and you can run `claude` from your shell.

## 1. Register the MCP server

Claude Code stores MCP registrations in `~/.claude.json`. The
single-command form using the CLI:

```bash
SECRET=$(grep '^RAGMCPSharedSecret' src/main/backend/application.ini | sed 's/.*=\s*//' | tr -d ' ')

claude mcp add --transport http myproj \
    http://127.0.0.1:17080/rag-mcp/myproj \
    --header "X-RAG-Token: $SECRET"
```

Substitute `myproj` with the project's `name` from `rag-projects.json`
(it must match exactly — that segment is how the server routes the
request). The `--header` flag is how Claude Code sends the shared
secret on every request.

Verify the registration:

```bash
claude mcp list
```

You should see a row like:

```
myproj: http://127.0.0.1:17080/rag-mcp/myproj (HTTP) - ✓ Connected
```

## 2. One entry per project

The URL path segment (`/rag-mcp/<project>`) scopes the search to one
project's index. If you have more than one project configured in
`rag-projects.json`, register each one as its own MCP entry — they
become independent tool prefixes in Claude Code:

```bash
SECRET=$(grep '^RAGMCPSharedSecret' src/main/backend/application.ini | sed 's/.*=\s*//' | tr -d ' ')

claude mcp add --transport http stack360 \
    http://127.0.0.1:17080/rag-mcp/stack360 \
    --header "X-RAG-Token: $SECRET"

claude mcp add --transport http other_project \
    http://127.0.0.1:17080/rag-mcp/other_project \
    --header "X-RAG-Token: $SECRET"
```

The MCP entry name (the first positional argument — `stack360`,
`other_project`) is also the prefix Claude Code uses for the tools
(`mcp__stack360__search_code`, etc.). Pick whatever's convenient; the
URL path segment is the part that must match the project's `name`.

## 3. Rotating the shared secret

If you change `RAGMCPSharedSecret` in `application.ini`, restart Kiss
and re-run `claude mcp add` with the new secret. The command replaces
the existing entry under the same name; you do not need to remove it
first.

```bash
claude mcp remove myproj    # only needed if renaming the entry
```

## 4. Verify

Start a Claude Code session inside the project's working tree, enable
the matching MCP server for that session, then try a prompt:

> *"Use `mcp__myproj__search_code` to find where login is handled."*

Claude will call `search_code`, get back a list of hits (each with a
`repo`, `path`, `start_line`, `end_line`, `absolute_path`, `score`,
and `snippet`), then typically follow up with `Read` on the top hit's
absolute path and line range.

If Claude can't see the `mcp__myproj__*` tools, check:

1. The server is reachable. From the same shell:
   ```bash
   SECRET=$(grep '^RAGMCPSharedSecret' src/main/backend/application.ini | sed 's/.*=\s*//' | tr -d ' ')
   curl -s -X POST http://127.0.0.1:17080/rag-mcp/myproj \
       -H "X-RAG-Token: $SECRET" \
       -H 'Content-Type: application/json' \
       -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' | python3 -m json.tool
   ```
   If this returns the four tools (`search_code`, `get_chunk`,
   `list_repos`, `index_status`), the server is healthy and the
   problem is on the Claude Code side. If it doesn't, the server isn't
   running or the secret is wrong — see [Running.md](Running.md).
2. The registration is visible: `claude mcp list` should show your
   entry with `✓ Connected`.
3. You restarted the Claude Code session after registering. MCP
   servers connect at session start; an already-open session won't
   pick up a newly-added one.

## 5. Caveats and trade-offs

**Tool name prefix.** Claude Code surfaces each MCP server's tools as
`mcp__<entry-name>__<tool-name>`. So with the registration above the
tools are `mcp__myproj__search_code`, `mcp__myproj__get_chunk`,
`mcp__myproj__list_repos`, `mcp__myproj__index_status`.

**Multiple agents at once is fine.** Both Claude Code and Codex (or
two Claude Code sessions) can register against the same Code-RAG
instance and even hit it simultaneously. pgvector reads are
concurrent-safe and there's no client identity in the per-project
lock model. The only shared bottleneck is the Ollama GPU during query
embedding, which is ~30 ms anyway.

**The MCP URL is not the same as the entry name.** The URL path
segment (`/rag-mcp/<project>`) is the server's routing — it must
match a real project's `name` in `rag-projects.json`. The MCP entry
name is just Claude Code's local label for that registration. They
can differ (though conventionally we use the project name for both).

## 6. Operations and triggering scans

Nothing changes for the operator. You still:

- Start the server: `./bld start`
- Check status: `./bld status`
- Scan one project: `./bld scan <project>`
- Scan all projects: `./bld scan all`
- Stop the server: `./bld stop`

The startup auto-scan, the per-project lock, the SHA-based change
detection, the cron sweep — all of it is independent of which client
is asking questions on the read side.

## See also

- [Codex.md](Codex.md) — same instructions for OpenAI Codex CLI.
- [Setup.md](Setup.md) — installing and starting the server.
- [Running.md](Running.md) — day-to-day operations.
- [RAGPlan.md](RAGPlan.md) — design reference; §6 covers the MCP server.
