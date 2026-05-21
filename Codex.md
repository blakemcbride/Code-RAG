# Using Code-RAG with OpenAI Codex CLI

Code-RAG's server is a stock MCP implementation: standard JSON-RPC
over HTTP, the published MCP `2025-06-18` handshake, no client-specific
extensions. Any MCP-capable agent can drive it — this file documents
the specifics for [OpenAI's Codex CLI](https://developers.openai.com/codex/cli/reference),
which is the most common alternative to Claude Code.

The repo's existing documentation
([Setup.md](Setup.md), [Running.md](Running.md), [RAGPlan.md](RAGPlan.md))
explains how to bring the server up, index your projects, and operate it
day-to-day. **That part is unchanged** — start the server the same way,
maintain the same `application.ini` and `rag-projects.json`, run scans
with `./bld scan`. The only difference here is on the *client* side:
where Claude Code registers an MCP server via `claude mcp add`, Codex
registers one via an entry in `~/.codex/config.toml`.

## Two config files involved

Working with Code-RAG through Codex CLI touches two distinct config
files, owned by opposite sides of the system. The most common point of
confusion is mixing up their roles, so:

| File | What it does | Who edits it | When you touch it |
|---|---|---|---|
| `src/main/backend/rag-projects.json` | Tells the Code-RAG **server** which code trees to index. One JSON entry per project: `name`, absolute `roots[]`, optional `excludeGlobs`. Gitignored — the committed `.example` is just a template. | You (hand-edit). | When adding/removing a project or changing its roots. Restart Kiss after editing so the new schema gets bootstrapped. |
| `~/.codex/config.toml` | Tells **Codex CLI** which MCP servers exist. Holds Codex's general configuration plus a `[mcp_servers.*]` section per server. A `.codex/config.toml` inside a trusted project directory provides per-project scoping. | You (hand-edit TOML). | When registering this Code-RAG instance with Codex, or when the shared secret rotates. |

The two files don't communicate directly. The **bridge is the URL**: in
`~/.codex/config.toml` you set
`url = "http://127.0.0.1:17080/rag-mcp/<X>"`, and `<X>` must equal a
`name` in `rag-projects.json`. If they don't match the server replies
404 when Codex tries to call a tool.

Worth being precise about one subtlety: the TOML section name
(`[mcp_servers.<name>]`) is just Codex's local label for the server.
The server, on the other hand, routes on the URL **path segment** after
`/rag-mcp/`. We use the same string for both by convention — usually the
`name` from `rag-projects.json` — so all three agree, but they are
technically independent variables. The thing the server actually
requires to match is the URL path segment.

`rag-projects.json` is documented end-to-end in [Setup.md §3](Setup.md)
and [Running.md §4](Running.md). `~/.codex/config.toml` is Codex's own
file — its schema and behavior live with [Codex's MCP
reference](https://developers.openai.com/codex/mcp).

## Working directory and visible MCP entries

Codex reads MCP config from up to two places at startup:

1. **`~/.codex/config.toml`** — global. Loaded regardless of the
   directory you launch `codex` from.
2. **`.codex/config.toml`** inside a *trusted* project directory —
   per-project overrides and additions, loaded only when `codex` is
   started inside that tree and Codex has been told the project is
   trusted.

Unlike Claude Code — which keys its default-scope MCP entries by the
absolute working directory — Codex's global entries are visible
everywhere by default. Practical consequences:

- **A single `[mcp_servers.myproj]` in `~/.codex/config.toml` works
  from any cwd.** You don't have to re-register from every project
  directory.
- **Renaming or moving the Code-RAG working tree does NOT invalidate
  the entry**, because the entry isn't keyed by cwd. The only thing
  the entry references is the loopback URL (`http://127.0.0.1:17080/…`)
  and the project name in the URL path.
- **If you want an entry that only applies inside one checkout**, put
  a `[mcp_servers.<name>]` block in that checkout's
  `.codex/config.toml` and mark the project trusted. See [Codex's
  advanced-configuration
  docs](https://developers.openai.com/codex/config-advanced) for the
  trust mechanism.
- **Multiple Code-RAG projects** can coexist as separate global
  entries — pick a different TOML section name and URL path segment
  per project; both are independent of the cwd.

## 0. Prerequisites

- Code-RAG is installed and the server is running on its default
  loopback URL `http://127.0.0.1:17080/`. (See [Setup.md](Setup.md).)
- At least one project is configured in
  `src/main/backend/rag-projects.json` and its index is populated. Test
  with `./bld status` — under "Projects" you should see at least one
  entry with a non-zero file count.
- [Codex CLI](https://developers.openai.com/codex/cli/reference) is
  installed and you can run `codex` from your shell.

## 1. Add the MCP server entry

Codex's MCP config lives in `~/.codex/config.toml`. The HTTP-transport
schema is documented in
[Codex's MCP reference](https://developers.openai.com/codex/mcp). For
Code-RAG with the default port and a project named `myproj`, add:

```toml
[mcp_servers.myproj]
url = "http://127.0.0.1:17080/rag-mcp/myproj"
http_headers = { "X-RAG-Token" = "PASTE-YOUR-SECRET-HERE" }
```

Replace `PASTE-YOUR-SECRET-HERE` with the value of `RAGMCPSharedSecret`
from `src/main/backend/application.ini` — the same secret Claude Code
sends. Look it up with:

```bash
grep '^RAGMCPSharedSecret' src/main/backend/application.ini
```

If you prefer not to hard-code the secret in the TOML file, Codex
supports pulling header values from environment variables via
`env_http_headers` — same TOML section, replace `http_headers` with:

```toml
env_http_headers = { "X-RAG-Token" = "CLAUDE_RAG_TOKEN" }
```

then export the secret in your shell:

```bash
export CLAUDE_RAG_TOKEN="$(grep '^RAGMCPSharedSecret' src/main/backend/application.ini | sed 's/.*=\s*//' | tr -d ' ')"
```

## 2. One config block per project

The URL path segment (`/rag-mcp/<project>`) is how the server scopes the
search; it must match a configured project. If you index more than one
project, register them as separate MCP entries:

```toml
[mcp_servers.stack360]
url = "http://127.0.0.1:17080/rag-mcp/stack360"
http_headers = { "X-RAG-Token" = "..." }

[mcp_servers.other_project]
url = "http://127.0.0.1:17080/rag-mcp/other_project"
http_headers = { "X-RAG-Token" = "..." }
```

The TOML section name (`stack360`, `other_project`) is the *Codex* name
for the MCP server — pick whatever's convenient. The URL path segment
must exactly match the project's `name` in `rag-projects.json`.

## 3. Project-scoped vs global config

Codex supports a per-project config at `.codex/config.toml` inside a
trusted project directory. If you want a particular checkout to use the
RAG and nothing else, that's the cleaner place to put the entry.
Otherwise, `~/.codex/config.toml` registers the server globally.

## 4. Verify

Restart any open Codex session so it re-reads the config, then run a
prompt that invokes the tool, for example:

> *"Search the project index for where login is handled. Use the
> `search_code` tool from the `stack360` MCP server."*

Codex should call the tool, get back a list of hits (each with a
`repo`, `path`, `start_line`, `end_line`, `absolute_path`, `score`, and
`snippet`), then typically follow up with a file read on the top hit.

If Codex can't reach the server, sanity-check from the same shell:

```bash
SECRET=$(grep '^RAGMCPSharedSecret' src/main/backend/application.ini | sed 's/.*=\s*//' | tr -d ' ')
curl -s -X POST http://127.0.0.1:17080/rag-mcp/stack360 \
    -H "X-RAG-Token: $SECRET" \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' | python3 -m json.tool
```

If this returns the four tools (`search_code`, `get_chunk`, `list_repos`,
`index_status`), the server is healthy and the problem is in Codex's
config. If it doesn't, the server isn't running or the secret is
wrong — see [Running.md](Running.md).

## 5. Caveats and trade-offs

**The four tools are identical for any MCP client.** The server doesn't
know which agent is calling. So both Codex and Claude Code can register
against the same Code-RAG instance and even hit it simultaneously —
pgvector reads are concurrent-safe and there's no client identity in the
per-project lock model. Multiple agents on the same project at once is
fine; they only contend on the shared Ollama GPU during query
embedding, which is ~30 ms anyway.

**Tool name prefix.** Codex surfaces the four tools under whatever
naming convention it currently uses — that's a Codex implementation
detail, not something the server controls. The four tool names
themselves (`search_code`, `get_chunk`, `list_repos`, `index_status`)
are stable.

**Per-project MCP entry rather than one shared.** This isn't strictly
necessary — you could write a Codex hook that injects the project name
into each request URL based on the current working directory — but the
"one MCP entry per project" pattern matches what we recommend for
Claude Code, and Codex's TOML config makes it easy to keep them
separate.

**If your Codex version doesn't yet support `http_headers`.** Look at
the
[Codex MCP reference](https://developers.openai.com/codex/mcp) for
your installed version. As a fallback, you can set
`RAGMCPSharedSecret` to an empty string in `application.ini`, which
turns off the auth check (Tomcat is already loopback-only, so any
process on the host could connect anyway). Restart the server after
changing the secret.

## 6. Operations and triggering scans

Nothing changes for the operator. You still:

- Start the server: `./bld start`
- Check status: `./bld status`
- Scan one project: `./bld scan <project>`
- Scan all projects: `./bld scan all`
- Stop the server: `./bld stop`

The startup auto-scan, the per-project lock, the SHA-based change
detection, the disabled-by-default cron — all of it is independent of
which agent is asking questions on the read side. Codex sessions and
Claude Code sessions both work the same way as far as the indexer is
concerned.

## See also

- [ClaudeCode.md](ClaudeCode.md) — same instructions for Claude Code.
- [Setup.md](Setup.md) — installing and starting the server.
- [Running.md](Running.md) — day-to-day operations.
- [RAGPlan.md](RAGPlan.md) — design reference; §6 covers the MCP server.
- [OpenAI Codex MCP reference](https://developers.openai.com/codex/mcp) — authoritative source for Codex's MCP config format.
