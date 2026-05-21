#!/usr/bin/env bash
# setup.sh — first-run setup for Claude-RAG.
#
# What it does:
#   1. Copies application.ini.example -> application.ini if the real file
#      does not yet exist, and fills the shared secret with a random UUID.
#   2. Copies rag-projects.json.example -> rag-projects.json (only the
#      first time — your edits are never overwritten).
#
# Safe to re-run. If your application.ini or rag-projects.json already exist,
# they are left strictly alone.
#
# Requires: bash, python3.

set -euo pipefail

repo_root="$(cd "$(dirname "$0")" && pwd)"
backend_dir="${repo_root}/src/main/backend"

if [[ ! -d "${backend_dir}" ]]; then
    echo "error: cannot find ${backend_dir}" >&2
    exit 1
fi

cd "${backend_dir}"

new_uuid() {
    python3 -c 'import uuid; print(uuid.uuid4())'
}

# application.ini
if [[ -f application.ini ]]; then
    echo "skip: application.ini already exists — leaving it alone"
else
    if [[ ! -f application.ini.example ]]; then
        echo "error: application.ini.example is missing" >&2
        exit 1
    fi
    cp application.ini.example application.ini
    secret="$(new_uuid)"
    # Portable in-place edit (GNU sed and BSD/macOS sed both handle -i'' the same way here)
    python3 - "$secret" <<'PY'
import sys, pathlib
p = pathlib.Path("application.ini")
p.write_text(p.read_text().replace("REPLACE_WITH_RANDOM_UUID", sys.argv[1]))
PY
    chmod 600 application.ini
    echo "created application.ini (shared secret generated, file is mode 600)"
fi

# rag-projects.json
if [[ -f rag-projects.json ]]; then
    echo "skip: rag-projects.json already exists — leaving it alone"
else
    if [[ ! -f rag-projects.json.example ]]; then
        echo "error: rag-projects.json.example is missing" >&2
        exit 1
    fi
    cp rag-projects.json.example rag-projects.json
    echo "created rag-projects.json — EDIT IT before starting the server"
fi

cat <<'NEXT'

Setup complete. Next steps:
  1. Edit src/main/backend/rag-projects.json to point at your real code roots.
  2. Make sure PostgreSQL (with pgvector) and Ollama are running. See Running.md.
  3. Start the server:  ./bld -v start-backend
  4. Register the MCP server with Claude Code (see Running.md §5).
  5. The shared secret you need is in src/main/backend/application.ini under
     RAGMCPSharedSecret.

NEXT
