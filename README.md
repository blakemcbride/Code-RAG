# Code-RAG

A local, private RAG (retrieval-augmented generation) layer for
MCP-capable coding agents — primarily
[Claude Code](https://docs.claude.com/en/docs/claude-code/overview), and
also [OpenAI Codex CLI](https://developers.openai.com/codex/cli/reference)
(see [Codex.md](Codex.md)). Builds and serves a semantic-search index
over your own codebases via an MCP (Model Context Protocol) server, so
your agent can ask *"where does X happen in this code?"* and get
pointers to the right files without you having to know the symbol name.

Everything runs on one machine. The local LLM (Ollama) is used **only**
for embeddings; generation stays with whatever cloud model your agent
is already configured to use.

Built on top of the [Kiss](https://kissweb.org) web framework. The
server is plain MCP — no client-specific assumptions — so any MCP-aware
agent can drive it.

## Why this exists

`grep` works when you know the exact symbol; it doesn't when you only know
the concept. Stuffing whole files into an agent's context wastes the
context window and money. A small local RAG returns a handful of
relevant chunks (file path + line range + snippet) that your agent can
then `Read` with precision.

## Features

- Multiple projects in one server, fully isolated (one PostgreSQL schema
  per project).
- Each project has its own MCP URL → run multiple agent sessions in
  parallel, each scoped to one project, with no cross-talk. Works with
  Claude Code, OpenAI Codex CLI, or any other MCP-capable client.
- Cron sweep keeps the index current as files change.
- Symbol-aware chunking for Java, Groovy, JS/TS, Kotlin, Scala, C#, Swift,
  Dart, C/C++/Objective-C, Python, Ruby, PHP, Rust, Go, Elixir, and Lisp /
  Scheme / Racket / Clojure. Fixed-window fallback for everything else
  (HTML, CSS, SQL, configs, etc.).
- pgvector + HNSW for fast cosine-similarity search.
- All secrets stay local; no cloud calls except whatever your agent
  itself is configured to do.

## Requirements

- **PostgreSQL 17+** with the `pgvector` extension
- **Ollama** with an embedding model (default: `nomic-embed-text:v1.5`)
- **Java 21+** (LTS)
- **Bash + Python 3** for setup
- A modern GPU is recommended for indexing throughput, but not required —
  the indexer just takes longer on CPU.

## Quick start

```bash
# 1. Clone
git clone <repo-url> code-rag
cd code-rag

# 2. One-time setup — creates application.ini with a random secret and
# copies rag-projects.json from the template.
./setup.sh

# 3. Tell it what to index — edit src/main/backend/rag-projects.json,
# replacing the placeholder "demo" project with your real code roots.
$EDITOR src/main/backend/rag-projects.json

# 4. Create the PostgreSQL database (one-time).
createdb -U postgres code_rag
psql -U postgres -d code_rag -c 'CREATE EXTENSION IF NOT EXISTS vector;'

# 5. Pull the embedding model (one-time).
ollama pull nomic-embed-text:v1.5

# 6. Start the server. The first time a project's index is empty Kiss
#    will auto-scan it on startup in a background thread; you can watch
#    progress with `./bld scan <project>` or `./bld status`.
./bld -v start

# 7. Register the MCP server with your agent (one entry per project).
#    Pick the guide for the client you use:
#      Claude Code        → ClaudeCode.md
#      OpenAI Codex CLI   → Codex.md
```

In any subsequent agent session inside that project's working tree, ask
something like *"use mcp__myproj__search_code to find where the
authentication flow happens"* — the agent will call the tool, then `Read`
the top hit.

For the same procedure broken into named, verified steps (with expected
output at each one), see [Setup.md](Setup.md).

## Documentation

- [Overview.md](Overview.md) — what the system does, architecture, data
  flow, requirements. Start here if you want context before installing.
- [Setup.md](Setup.md) — step-by-step install + configuration from scratch.
- [Running.md](Running.md) — operating instructions: start/stop, adding a
  project, daily commands, troubleshooting.
- [ClaudeCode.md](ClaudeCode.md) — registering Code-RAG with Claude Code.
- [Codex.md](Codex.md) — registering Code-RAG with OpenAI Codex CLI.
- [RAGPlan.md](RAGPlan.md) — the original single-project design doc.

## License

Apache 2.0 — see [LICENSE.txt](LICENSE.txt). Built on the
[Kiss](https://kissweb.org) framework (also Apache 2.0). See
[NOTICE](NOTICE).
