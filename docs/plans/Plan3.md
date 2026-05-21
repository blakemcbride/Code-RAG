# Plan3 — Public-release Hardening

> _Historical design doc — describes the system as it was built. Code, package names, and paths have moved on since; the README and Running.md are the source of truth for how the released system looks._

Author: Blake McBride
Date: 2026-05-21
Status: Draft — needs your sign-off before execution
Builds on: [RAGPlan.md](RAGPlan.md), [Plan2.md](Plan2.md), [Running.md](Running.md)

## 1. Goal

Turn the working single-user Claude-RAG into something a stranger can
`git clone`, follow a README, and run on their own machine with zero
secrets leaked and zero personal paths hard-coded. The runtime behavior
stays identical; this is a hardening + repackaging pass.

## 2. What I'd put in scope

### A. Stop committing secrets

- `application.ini` currently has a real `RAGMCPSharedSecret = <uuid>` value committed in git.
  Rotate that secret; do not commit the replacement.
- `Running.md` also embeds the secret in the `claude mcp add` example. Same fix.
- Going forward: `application.ini` and `rag-projects.json` are **gitignored**;
  the repo ships `application.ini.example` and `rag-projects.json.example`
  with placeholder values.
- A `setup.sh` (one-shot) does the initial copy + secret generation:
  ```bash
  cp src/main/backend/application.ini.example src/main/backend/application.ini
  python3 -c 'import uuid; print(uuid.uuid4())' \
      | xargs -I {} sed -i "s/REPLACE_WITH_RANDOM_UUID/{}/" \
      src/main/backend/application.ini
  cp src/main/backend/rag-projects.json.example src/main/backend/rag-projects.json
  ```
  Idempotent — re-running does not overwrite an existing live file.

### B. Remove personal paths from defaults

- `rag-projects.json.example` carries one illustrative project with a
  comment ("change the name and roots; this is just a sample"), not your
  Stack360 layout.
- The current `rag-projects.json` (which has your real paths) is moved to
  the gitignored real-config side and never tracked again.
- `Running.md` is rewritten to use `myproj` / `/path/to/your/code` examples
  instead of `stack360` / `/home/blake/Stack360`.

### C. Drop the Stack360-specific migration code

`ProjectBootstrap.migratePublicIfNeeded` is hard-coded to move
`public.rag_*` into the `stack360` schema. For a public release that block
has no business firing on anyone else's machine. Two viable options:

1. **Delete the migration code entirely.** Cleanest. You and I already ran
   it; we don't need it again. New users have no `public.rag_*` to move.
2. **Generalize it** as "if `public.rag_*` exists and the configured first
   project doesn't, move it into that project's schema." More code, very
   limited value because no future user will hit that exact pre-state.

I'd recommend (1).

### D. Rename `io.blake.rag` → `org.kissweb.rag`

- Matches the Kiss framework's existing package convention.
- One renamed dir + an import sweep across 6 files.
- Bonus: gives the project an identity that isn't tied to me.

### E. Repo hygiene

- Strengthen `.gitignore`: add `tomcat/`, `work/`, `target/`,
  `node_modules/`, `tomcat/logs/`, `.codex/`, `.gradle-home/`,
  `application.ini`, `rag-projects.json`, the tracked-into-history
  `*.save` and `Test.{java,groovy}` etc.
- Drop the working-tree clutter that snuck in (`Announcement.txt`,
  `Test.java`, `Test.groovy`, `codex.md`, `test.ini`,
  `src/main/backend/FixPhoneNumbers.groovy`,
  `src/main/backend/DB.sqlite.save`).
- `Plan2.md` and `Plan3.md` move under `docs/plans/`; `RAGPlan.md` and
  `Running.md` stay top-level (they're user-facing).
- Add a top-level `README.md` (new) — quick "what is this / how do I run it"
  pointer to `Running.md`.

### F. LICENSE / attribution

- The repo already ships Kiss's `LICENSE.txt` (Apache 2.0). For our
  add-on code I'd just keep the same license — simplest, no ambiguity.
- Add a brief `NOTICE` (or top-of-`README.md`) noting Kiss as the
  underlying framework with a link.

### G. README.md content (new top-level)

Short, with these sections:

1. What it is — one paragraph.
2. Requirements — Postgres 17+/pgvector, Ollama, Java 21.
3. Five-step quick start — clone, `./setup.sh`, edit `rag-projects.json`,
   start Kiss, register MCP server with Claude Code.
4. Pointers to `Running.md` for daily operation, `RAGPlan.md` for design,
   `docs/plans/Plan2.md` and `docs/plans/Plan3.md` for history.

## 3. What I'd *not* do in this pass

- No CI yet (GitHub Actions, Docker, etc.). A clean clone-and-run is
  enough for v1.
- No automatic schema-version migrations. Users on later versions will get
  a clear error message if `rag_meta.schema_version` doesn't match —
  good enough until that's actually a problem.
- No reranker, no chunker rewrites. Quality work is its own phase.
- No documentation site. Markdown files are fine.

## 4. Phased rollout

**3.1 — strip & gitignore.** Move `application.ini` → `application.ini.example`
with placeholders; same for `rag-projects.json`. Add to `.gitignore`. Delete
the live ones from git history (`git rm --cached`). Add `setup.sh`. Rotate
the leaked secret in your live `application.ini`.

**3.2 — package rename.** Move `io/blake/rag/` to `org/kissweb/rag/`. Update
the 6 callers. Rebuild + restart. Smoke-test.

**3.3 — drop Stack360 migration.** Delete `migratePublicIfNeeded` and its
helpers from `ProjectBootstrap`. Adjust the comment block.

**3.4 — docs/repo hygiene.** Add `README.md`. Rewrite `Running.md` to drop
`/home/blake/` and `stack360` references; switch to generic
placeholders. Move the plan files into `docs/plans/`. Tighten
`.gitignore`.

**3.5 — final sanity pass.** Grep for "blake", "Stack360", "/home/", the
old secret. Anything left should be in `.gitignore`-protected paths
(your real `application.ini`, your real `rag-projects.json`) or in
text the user controls (`Running.md` references their own setup).

Each sub-phase is committable on its own.

## 5. Decisions you need to make

Before I start, please ack or amend:

1. **License**: keep Apache 2.0 (matches Kiss's `LICENSE.txt`)? Or
   something else (MIT)?
2. **Drop the Stack360 migration entirely** (option 2.C.1) versus generalize
   (option 2.C.2)?
3. **Repo name / package name**: `org.kissweb.rag` good as the new
   precompiled package? Or do you want a separate name (e.g.
   `org.kissweb.cclaude_rag` or some other identifier you prefer)?
4. **`setup.sh` flavor**: bash-only (Linux/macOS) is what I'd write;
   Windows users would have to follow the README's manual steps.
   Acceptable?
5. **History scrubbing of the old secret**: I'd not rewrite git history
   (that's destructive). Treat the leaked secret as compromised, rotate
   it in your live config, and document the rotation in
   `application.ini.example`. Acceptable?

## 6. Scope estimate

About the same as one prior phase — a couple of hundred lines of edits
across config templates, the bootstrap class, the package rename, and the
docs rewrite. Half the work is moving text around.

---

Once you ack §5 (or amend), I'll execute 3.1 → 3.5 in order.
