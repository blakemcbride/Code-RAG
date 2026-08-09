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

## Config files involved

Working with Code-RAG through Claude Code touches up to three distinct
config files, owned by opposite sides of the system. The most common
point of confusion is mixing up their roles, so:

| File | What it does | Who edits it | When you touch it |
|---|---|---|---|
| `src/main/backend/rag-projects.json` | Tells the Code-RAG **server** which code trees to index. One JSON entry per project: `name`, absolute `roots[]`, optional `project_dir`, optional `excludeGlobs`. Gitignored — the committed `.example` is just a template. | You (hand-edit). | When adding/removing a project, changing its roots, or setting/clearing `project_dir`. Restart Kiss after editing so the new schema gets bootstrapped. |
| `<project_root>/.mcp.json` and `<project_dir>/.mcp.json` | Tells **Claude Code** which MCP servers are available when launched from this tree. One file per configured root, plus one in `project_dir` if set. Code-RAG writes them automatically. | `bld` (via `claude mcp add -s project`). Don't hand-edit unless you have to. | Maintained automatically by `bld new-project`, `add-root`, `remove-root`, `remove-project`, and `bld start`. |
| `<root>/CLAUDE.local.md` (managed block only) | Tells **Claude Code** when to prefer `search_code` / `search_history` over native Grep. Written into **every indexed root** on each `bld start`. `CLAUDE.local.md` rather than `CLAUDE.md` so a tracked, possibly shared file is never modified — and because some repos forbid editing their `CLAUDE.md`. Disable with `RAGWriteRoutingRules = false`. Bld manages a marker-delimited block; anything outside the markers is left alone. | `bld`. Don't edit *between* the markers — bld will overwrite it. | Maintained automatically alongside the matching `.mcp.json`. Removed by `bld remove-project`. |

The two files don't communicate directly. The **bridge is the URL**: in
each `.mcp.json` you have an MCP entry with URL
`http://127.0.0.1:17080/rag-mcp/<X>`, and `<X>` must equal a `name` in
`rag-projects.json`. If they don't match the server replies 404 when
Claude Code tries to call a tool.

Worth being precise about one subtlety: the MCP **entry name** in
`.mcp.json` (the key under `mcpServers`) is just Claude Code's local
label — it becomes the `mcp__<name>__…` tool prefix Claude Code
exposes. The server, on the other hand, routes on the URL **path
segment** after `/rag-mcp/`. We use the same string for both by
convention — always the `name` from `rag-projects.json` — so all three
agree, but they are technically independent variables. The thing the
server actually requires to match is the URL path segment.

`rag-projects.json` is documented end-to-end in [Setup.md §3](Setup.md)
and [Running.md §4](Running.md). `.mcp.json` is Claude Code's standard
project-scope config — its schema and behavior live with [Claude
Code's documentation](https://docs.claude.com/en/docs/claude-code/overview).

> ⚠️ `.mcp.json` contains the `X-RAG-Token` shared secret. It lives at
> the top of each project root, where it's easy to accidentally commit.
> The secret only authenticates loopback requests to your local
> Code-RAG instance — so leaking it is low-severity — but if the
> project is in git, add `.mcp.json` to its `.gitignore` to keep the
> secret out of history. Code-RAG cannot edit `.gitignore` files in
> projects it doesn't own.

## Working directory and visible MCP entries

Claude Code looks for `.mcp.json` starting at the cwd it was launched
from and walking up to the nearest enclosing git root. So an entry
written under a project root is visible to every Claude Code session
launched from anywhere under that tree, and **invisible** to sessions
launched from unrelated directories.

This is by design. Old versions of Code-RAG registered at
`--scope user`, which put one entry in `~/.claude.json` visible from
*every* directory. That meant Claude Code in an unrelated directory
would still speculatively call `mcp__<project>__search_code`, which
embedded the query through Ollama for nothing. With project-scope
registration that whole class of wasted work goes away.

Practical consequences:

- **One `.mcp.json` per configured root.** A Code-RAG project can have
  multiple roots (`bld new-project foo /a /b /c`); each gets its own
  `.mcp.json` with identical contents. Claude Code launched in any of
  them sees the tools.
- **Subdirectories of a root work too.** Claude Code walks upward from
  cwd to find `.mcp.json`, so launching from `/a/src/feature` finds
  `/a/.mcp.json` just fine.
- **`claude mcp list` is cwd-sensitive.** A blank list means the
  current cwd is not inside any registered root.
- **Moving a root invalidates the entry.** The `.mcp.json` moves with
  the tree, so most relocations Just Work — but `rag-projects.json`
  still points at the old absolute path. Update it with
  `bld remove-root` + `bld add-root` and the new `.mcp.json` will be
  written automatically.
- **Legacy user-scope entries are migrated on `bld start`.** If you
  installed Code-RAG before this change and have entries under
  `~/.claude.json`'s top-level `mcpServers`, the next `bld start` will
  remove them and write project-scope `.mcp.json` files in each root.
  Idempotent — safe to re-run.
- **Umbrella directory (`project_dir`).** If you launch `claude` from
  a parent directory that sits *above* the configured roots — common
  for projects assembled from multiple sibling repos — neither the
  `.mcp.json` nor the `CLAUDE.md` from inside a root will be visible
  (Claude Code only walks *up* from cwd). For that case, declare a
  `project_dir` on the project:
  ```bash
  bld new-project myproj --project-dir /home/me/umbrella \
      /home/me/umbrella/api /home/me/umbrella/web
  ```
  bld then writes a second `.mcp.json` in the umbrella directory *and*
  inserts a managed routing snippet into `<umbrella>/CLAUDE.md` —
  see "Managed CLAUDE.md block" at the end of §5 for what that snippet
  contains and how it is rewritten. The umbrella itself is not
  indexed — only the roots are. To add `project_dir` to an existing
  project, hand-edit `rag-projects.json` and run
  `./bld stop && ./bld start`.

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

> **The common path is automatic — you do not run any `claude mcp`
> command by hand.** `bld new-project <name> <root>...` (or
> `code-rag new-project ...` if you've installed the wrapper) adds the
> project to `rag-projects.json`, scans it, and writes a `.mcp.json`
> with the MCP entry at *project scope* in each root. `bld add-root`,
> `bld remove-root`, `bld remove-project`, and `bld start` all keep
> those `.mcp.json` files in sync. Read the manual procedure below
> only if you need to understand the wiring, or if you're registering
> a project that already exists in `rag-projects.json` but never had a
> `.mcp.json` written.

The manual form, run **from inside the project root**:

```bash
SECRET=$(grep '^RAGMCPSharedSecret' $CODE_RAG_HOME/src/main/backend/application.ini | sed 's/.*=\s*//' | tr -d ' ')

cd /path/to/my/project_root
claude mcp add --transport http -s project myproj \
    http://127.0.0.1:17080/rag-mcp/myproj \
    --header "X-RAG-Token: $SECRET"
```

`-s project` is the load-bearing flag — it writes the entry to
`./.mcp.json` rather than `~/.claude.json`. Substitute `myproj` with
the project's `name` from `rag-projects.json` (it must match exactly —
that segment is how the server routes the request). If the project
has multiple roots, repeat the command (with the same arguments) from
each root.

Verify the registration from somewhere inside the project tree:

```bash
cd /path/to/my/project_root
claude mcp list
```

You should see a row like:

```
myproj: http://127.0.0.1:17080/rag-mcp/myproj (HTTP) - ✓ Connected
```

Running the same `claude mcp list` from outside the project tree will
*not* show the entry — that's the whole point of project scope.

## 2. One entry per project

The URL path segment (`/rag-mcp/<project>`) scopes the search to one
project's index. If you have more than one project configured in
`rag-projects.json`, each gets its own `.mcp.json` inside its own
root — registered separately and visible only from inside its own
tree. They become independent tool prefixes in Claude Code
(`mcp__stack360__search_code`, `mcp__other_project__search_code`,
etc.).

The MCP entry name (the first positional argument to `claude mcp add`)
is also the prefix Claude Code uses for the tools. By convention
Code-RAG always uses the project's `name` from `rag-projects.json`,
so the URL path segment, MCP entry name, and tool prefix all agree.

## 3. Rotating the shared secret

If you change `RAGMCPSharedSecret` in `application.ini`, restart Kiss
(`bld stop && bld start`) — the `bld start` step re-runs registration
for every project, rewriting each `.mcp.json` with the new secret.
You don't need to run any `claude mcp` command manually.

If for any reason you need to remove an entry by hand (e.g. you're
renaming a project), run from inside the project root:

```bash
claude mcp remove myproj -s project    # only needed if renaming the entry
```

## 4. Verify

Start a Claude Code session **inside the project's working tree**
(i.e. from somewhere at or below the root listed in
`rag-projects.json`), then try a prompt:

> *"Use `mcp__myproj__search_code` to find where login is handled."*

Claude will call `search_code`, get back a list of hits (each with a
`repo`, `path`, `start_line`, `end_line`, `absolute_path`, `score`,
and `snippet`), then typically follow up with `Read` on the top hit's
absolute path and line range.

If Claude can't see the `mcp__myproj__*` tools, check:

1. **You launched `claude` from inside the project tree.** Project-scope
   entries are invisible to sessions started from anywhere else. If
   `cd /path/to/project_root && claude mcp list` shows the entry but
   `cd /tmp && claude mcp list` doesn't, that's working as intended.
2. **The `.mcp.json` actually exists.** Run `ls /path/to/project_root/.mcp.json`.
   If it's missing, `bld start` should recreate it on the next restart;
   you can also re-run `bld new-project` (it refuses if the project
   already exists, but the error tells you to use `add-root` — which
   does re-register).
3. **The server is reachable.** From the same shell:
   ```bash
   SECRET=$(grep '^RAGMCPSharedSecret' $CODE_RAG_HOME/src/main/backend/application.ini | sed 's/.*=\s*//' | tr -d ' ')
   curl -s -X POST http://127.0.0.1:17080/rag-mcp/myproj \
       -H "X-RAG-Token: $SECRET" \
       -H 'Content-Type: application/json' \
       -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' | python3 -m json.tool
   ```
   If this returns the tools (`search_code`, `search_history`, `find_symbol`, `find_dependents`, `get_chunk`, `reindex_path`, `list_repos`, `index_status`), the server is healthy and the
   problem is on the Claude Code side. If it doesn't, the server isn't
   running or the secret is wrong — see [Running.md](Running.md).
4. **You restarted the Claude Code session after registering.** MCP
   servers connect at session start; an already-open session won't
   pick up a newly-added one.

## 5. Caveats and trade-offs

**Tool name prefix.** Claude Code surfaces each MCP server's tools as
`mcp__<entry-name>__<tool-name>`. So with the registration above the
tools are `mcp__myproj__search_code`, `mcp__myproj__search_history`,
`mcp__myproj__find_symbol`, `mcp__myproj__find_dependents`,
`mcp__myproj__reindex_path`, `mcp__myproj__get_chunk`,
`mcp__myproj__list_repos`, `mcp__myproj__index_status`.

**Project scope means no ad-hoc cross-project search.** If you launch
Claude Code from `~`, `/tmp`, or any directory not under one of the
configured roots, you won't see *any* `mcp__<project>__*` tools — even
if Code-RAG is running. That's the deliberate trade-off the project
scope makes: the local LLM doesn't get hit by speculative searches
when you're working on unrelated code, but you have to `cd` into the
project to use Code-RAG. If you need cross-project search from
arbitrary cwds, you can hand-add a user-scope entry
(`claude mcp add --transport http -s user ...`), but be aware
`bld start` will remove it again as part of the legacy-migration
sweep.

**The shared secret lives in each `.mcp.json`.** The file sits at the
top of the project root. Add `.mcp.json` to that project's
`.gitignore` if the project is checked into git — Code-RAG cannot do
that for you.

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

### Managed CLAUDE.md block (when `project_dir` is set)

When a project declares `project_dir`, bld additionally writes a
managed block into `<project_dir>/CLAUDE.md`. Registering the MCP
server alone isn't enough — Claude Code's tool-selection heuristics
prefer native Grep/Glob over MCP search tools for code exploration,
so the `mcp__<project>__search_code` tool often goes unused even
when available. The CLAUDE.md block ships a routing rule that biases
the selection: use `search_code` for conceptual queries, Grep for
literal-token lookups.

The block is delimited by HTML-comment markers
(`<!-- BEGIN code-rag managed block ... -->` / `<!-- END ... -->`).
Everything between them is bld-managed and will be rewritten on the
next `bld start` / `bld new-project` / `bld add-root`. Everything
outside the markers is left untouched — bld appends the block to an
existing CLAUDE.md, or creates the file if none exists.

`bld remove-project` excises the block on the way out. If the
resulting file is empty, bld deletes it; otherwise the surrounding
content is preserved.

If you decide you don't want the routing rule, removing
`project_dir` from `rag-projects.json` stops bld from re-asserting
the block — but the existing block stays put until you delete it
by hand (or run `remove-project` + `new-project`).

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
