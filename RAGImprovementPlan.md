# Code-RAG Improvement Plan

Goal: make `search_code` reliably better than what Claude Code can do
with Grep alone, and then make it do things Grep structurally cannot.

---

## Staleness: results now admit when they are out of date

The index is only as current as the last sweep. Between an edit and the next
sweep, `search_code` returned the OLD content and OLD line numbers with no
indication anything was wrong — an agent could read stale code and act on it
confidently. That is a correctness problem, not a ranking one.

Hits now carry `stale: true` (file changed on disk since indexing) or
`missing: true` (file gone), with a count and an explicit instruction at the
envelope level so it is not missed while scanning results. Stale hits are still
returned and still ranked normally: the file is almost certainly still the right
answer, only the snapshot is old.

Building it surfaced a flaw in the obvious implementation. The sweep detects
change by **sha256**, so a file whose mtime moved without its content changing
— a `touch`, a checkout, a build — is skipped and its stored mtime never
corrected. Comparing disk mtime against that stale record would have flagged
such files as out of date *permanently*. `indexOneFile` now re-syncs mtime on
the unchanged path; measured drift afterwards is 0 of 334 files on kiss and
0 of 472 on ownsona.

Verified end to end: clean, then `stale=true` with an envelope warning after a
`touch`, then clear again after a sweep.

## Impact analysis, reference precision, and proof of use

**Import graph (`find_dependents`).** 46,000 import edges across the three
projects, extracted in under 3 seconds with no LLM and no rebuild. Answers the
question asked before editing anything — "what breaks if I change this" — which
neither similarity search nor a symbol index can. Extraction runs inside
`indexOneFile`, where the file is already in memory, so it is free and cannot
go stale; `./bld deps` exists only to backfill.

Deliberately honest about resolution: targets are matched on their last
segment, and every result is labelled `exact` (the dotted target provably maps
onto the file's path) or `name` (only the trailing name agrees). On
`BPerson.java` all 8 dependents came back `exact`.

**Reference precision.** The `find_symbol` references shipped earlier were
close to unusable for their headline question. Measured on `findJoinPath`: 11
matches = 8 tests, 1 doc, and the definition itself — one real caller buried
under ten. Now the defining chunk is dropped, each hit is labelled
`caller`/`test`/`doc`, likely invocations are marked, and results are ranked in
that order. The same query now returns `QueryBuilder.java:962` — the actual
caller — first. The `test` label answers "what covers this symbol" for free.

**`./bld usage` — proof, or its absence.** Every retrieval number in this
document is conditional on the tool being called at all, which was never
verified. It reads `rag_query_log` and reports searches/day, follow-up rate,
zero-result queries and top queries. Its first run was immediately useful:
**stack360 shows 0 searches**, and the report says so in those words rather
than printing an empty table.

One hypothesis measured and dropped: I suspected the chunker's symbol
attribution was corrupting the rerank boost. It is wrong on 3.3% of blocks in
kiss and 0.8% in stack360 — `findJoinPath` was an unlucky example, not
representative. Not worth a rebuild.

## Adoption, freshness, and the structural index

**1. Routing rules — the tool was invisible.** No project set `project_dir`,
so bld had never written a routing snippet, and none of the four `CLAUDE.md`
files mentioned `search_code`. Claude Code saw the tool and had no instruction
about when it beats Grep — and Grep is the reflex. Every retrieval number in
this document was conditional on a call that may not have been happening.

bld now writes a marker-delimited block into `<root>/CLAUDE.local.md` for every
indexed root, on every start. `CLAUDE.local.md` rather than `CLAUDE.md`
deliberately: this is a fact about *this machine's* local index, not about the
repository; it keeps a tracked file from being modified in five repos; and
`Kiss/CLAUDE.md` explicitly forbids editing.

**3. Edit-time freshness — `reindex_path`.** The sweep runs every 10 minutes,
so an agent that had just written code could not find it — exactly when it is
most likely to look. `reindex_path` re-chunks, re-embeds *and* re-extracts
definitions for named files in about a second. Verified: a new file was
invisible, then rank 1 after one call.

**2. The structural index — `find_symbol`.** Embeddings answer "what code is
about X"; they cannot answer "what calls this", "where exactly is this
defined", "what breaks if I change this signature". Universal Ctags now
populates `rag_def`: **218,794 definitions** across the three projects, in
~12 seconds. References reuse the identifier lexeme index already built for
hybrid search, so no new indexing pass was needed for them.

It found the exact case that exposed the chunker's weakness: `findJoinPath` is
declared at `SchemaGraph.java:483`, but `rag_chunk.symbol` had recorded
`hasTable` there — the regex does not match a generic return type
(`public List<Edge> findJoinPath(...)`). ctags gets it right, with kind, scope
and signature.

Honest about what it is: a **textual** reference index, not a type-resolved
call graph. It does not disambiguate overloads or resolve types.

Three bugs found by running it: ctags ships **no Groovy parser** (fixed by
mapping `.groovy` onto the Java parser — otherwise every Groovy file would
have silently had zero definitions); the Groovy vararg-arity trap struck a
**third** time; and `reindex_path` initially updated chunks but not
definitions, so the two halves of the index disagreed.

## Post-Phase-4 round: does it genuinely help?

**Turns-to-answer (`eval/turns.py`) — the measurement the Phase 3 gate asked
for and never ran.** Replays the golden set through two strategies and counts
tool calls to the answer:

| | GREP | search_code |
|---|---|---|
| solved at all | 30% | **92%** |
| answered after ONE call | 14% | **92%** |
| mean tool calls | 5.90 | **2.48** |

**58% fewer tool calls.** Caveats stated honestly: this is a simulation, not a
live agent A/B, and the grep baseline is an approximation (given ideal term
ordering, which a real agent cannot know). Two harness bugs were found and
fixed while building it — `rg` on this machine is a shell function wrapping the
Claude CLI, not a binary, so the first run scored grep at 0%; and the term
filter discarded every snake_case identifier, handicapping grep on exactly the
queries it should win. The per-tag shape validates the result: grep is best on
`symbol` (40%) and worst on `crossfile` (20%), as expected.

**stack360 eval — and a lesson about trusting your own metric.** The first run
looked catastrophic (hit@1 0.257, hit@5 0.371, 10 misses). Inspecting the
misses showed the tool was right and the *expectations* were wrong: "employee
payroll" returned `EmployeePayroll.html` where I had listed `.js`; "work
orders" returned the `WorkOrder` bean where I had listed the `BWorkOrder`
business class; "how much vacation banked up" returned `ListMyBalances.groovy`,
a better answer than the file I guessed. In an 11,637-file business app most
questions have several equally-correct answers, and naming one punishes correct
behavior. After broadening the expectations by inspection:

| | before fix | after fix |
|---|---|---|
| hit@1 | 0.257 | **0.429** |
| hit@5 | 0.371 | **0.886** |
| hit@10 | 0.543 | **0.943** |
| misses | 10 | **2** |

**Retune.** The lexical weight optimum is corpus-dependent — dense recall
degrades as the index grows, so a bigger tree wants a louder lexical leg.
stack360 prefers 0.5 (hit@1 0.429→0.486); kiss loses ~0.02 hit@1 at 0.5.
**Global 0.5** chosen; a single value serves both, so no per-project config.

**Two defects fixed.** Summaries had no refresh path at all — the cron sweep
re-chunked changed files but left their summaries describing old content, so
the strongest signal silently rotted on the newest code. Now topped up per
sweep, capped, and only for projects that already have summaries. And 331 files
were indexed twice (Kiss is both its own project and a subdirectory of
Stack360), wasting cross-project result slots on duplicates.

**Per-symbol summaries — small win, one instructive bug.** First version
*halved* hit@1 (0.62 → 0.24): every matching symbol of a file added its own
score, so a big file with many mediocre matches beat a file with one excellent
match. With only the best symbol counting, at weight 0.5: hit@5 0.920→0.940,
misses 1→0, +15 ms. Measured only on kiss, which has just 24 large files — this
likely understates the benefit on stack360 (604 large files, 21,889 symbols,
~8 GPU-hours to generate).

**Query expansion (HyDE) — implemented, measured, REJECTED.** On stack360 it
bought rank-1 precision and lost everything else, at eight times the latency
(p50 82 → 646 ms). Left in the code, disabled, with the numbers recorded.

**Usage mining (`eval/mine.py`).** Searches and follow-up `get_chunk` fetches
are now logged, so the eval set can be built from what is really asked rather
than from hand-written guesses — closing the one caveat that tuning cannot.
Verified end to end.

---

## STATUS: Phases 0–4 complete (measured on the `kiss` corpus)

| Metric | Baseline | Final | Factor |
|---|---|---|---|
| hit@1 | 0.080 | **0.640** | **8.0×** |
| hit@5 | 0.600 | **0.920** | 1.5× |
| hit@10 | 0.800 | **0.960** | 1.2× |
| recall@10 | 0.690 | **0.898** | 1.3× |
| MRR@10 | 0.282 | **0.738** | 2.6× |
| nDCG@10 | 0.368 | **0.740** | 2.0× |
| p50 latency | 27 ms | 37 ms | +10 ms |

Exact-symbol queries — the regression guard — went from hit@1 **0.000**
to **1.000**, with MRR and nDCG both 1.000.

The two changes that mattered most, by a wide margin, were **1.1**
(putting file/symbol context into the embedded text) and **4.3**
(embedding an LLM summary of each file as a second signal). Both work for
the same reason: the retrieval problem here was never the index or the
similarity math, it was that the words people search with were not
present in the thing being embedded.

### Phase 1 checkpoint (for reference)

| Metric | Baseline | After Phase 1 |
|---|---|---|
| hit@1 | 0.080 | 0.340 |
| hit@5 | 0.600 | 0.880 |
| hit@10 | 0.800 | 0.960 |
| recall@10 | 0.690 | 0.873 |
| MRR@10 | 0.282 | 0.544 |
| nDCG@10 | 0.368 | 0.604 |

Per-tag, baseline → now (hit@1 / hit@5):

| Tag | Baseline | Now |
|---|---|---|
| symbol (regression guard) | 0.000 / 0.700 | **0.800 / 1.000** |
| conceptual | 0.050 / 0.650 | **0.250 / 0.950** |
| vocab-mismatch | 0.200 / 0.533 | **0.200 / 0.800** |
| crossfile | 0.000 / 0.400 | **0.200 / 0.600** |

The held-out `test` split (10 queries, never used for tuning) moved
hit@1 0.000 → 0.500 and hit@10 0.700 → 1.000, so the gains are not an
artifact of tuning against the query set.

**Gate: passed.** Latency is far inside the 500 ms budget, and the
exact-symbol tag improved rather than regressed.

### What actually paid, in order

1. **Context-enriched embedding text (1.1) — the big one.** On its own it
   took hit@1 0.080 → 0.300 and MRR 0.282 → 0.472. Prepending
   `repo/file/lang/symbol` before embedding was worth more than every
   retrieval-side change combined.
2. **Proportional rerank (1.4).** hit@5 0.820 → 0.880, MRR 0.476 → 0.544.
   Only after the boosts were scaled by overlap fraction; the naive
   any-token version was net-negative.
3. **Hybrid + RRF (1.2) — real but modest, and only at low weight.**
   At equal weighting it was *worse than pure dense*. At 0.25 it adds
   hit@5 +0.06 and nDCG +0.012 over dense-only. Its value is widening the
   candidate pool, not ranking it.

### Two findings worth carrying into later phases

- **1.1 subsumed most of what 1.2 was meant to fix.** Putting the symbol
  and path into the embedding gave the dense retriever the exact
  signal the lexical leg was supposed to supply. Symbol queries reached
  recall@10 = 1.000 from 1.1 alone. Expect the lexical leg to matter more
  on `stack360` (107k chunks), where dense recall degrades with corpus
  size — re-sweep the weight there rather than assuming 0.25.
- **Generated documentation pollutes the corpus.** `manual/jsdoc/*.html`,
  `manual/man/*.html` and `AI/KnowledgeBase.md` outrank real source on
  many queries; at baseline, "DateUtils toInt" returned
  `manual/jsdoc/DateUtils.html` and missed `DateUtils.java` entirely.
  The `kiss` project has no `excludeGlobs`, so it inherits defaults that
  do not exclude generated docs. Excluding them is a corpus-hygiene
  decision, not a retrieval one — it was deliberately **not** done, so
  the Phase 1 numbers above reflect the corpus as actually configured.

### Phase 2.1 / 2.2 complete

**2.1 — model verification.** `verifyMetaMatches` now compares
`embedding_model` as well as dimension. A model change used to be
silently accepted: the INSERTs succeed and the index quietly ends up
holding vectors from two incomparable models, degrading recall with no
error anywhere. An incremental sweep now refuses; a full rebuild adopts
the new model. This guard fired correctly during the bake-off.

**2.2 — embedding model bake-off. Result: keep `nomic-embed-text:v1.5`.**

Measured on a scratch schema (`evaltmp`) over the same Kiss root, same
50-query set, full pipeline:

| | nomic-embed-text:v1.5 | embeddinggemma:latest |
|---|---|---|
| hit@1 | 0.340 | **0.380** |
| hit@5 | **0.880** | 0.800 |
| hit@10 | **0.960** | 0.900 |
| recall@10 | **0.873** | 0.823 |
| MRR@10 | 0.544 | **0.562** |
| nDCG@10 | 0.604 | 0.605 |
| build time | **67 s** | 163 s |
| p50 / p95 latency | **38 / 52 ms** | 89 / 113 ms |

embeddinggemma is slightly better at rank 1 and on MRR, but materially
worse on hit@5, hit@10 and recall@10 — and those are the metrics that
decide whether one call answers the question. It also costs 2.3× query
latency and 2.4× build time. **The Phase 2 gate explicitly required
beating the incumbent on recall@10; it loses there, so the incumbent
stays.**

Larger code-specialized embedders (e.g. 7B-class) were not pulled: at
768 dimensions embeddinggemma already cost 2.3× latency, so a model an
order of magnitude larger is very unlikely to fit the sub-500 ms budget
regardless of its quality. Worth revisiting only if latency budget grows.

**Bug found and fixed via this exercise.** Fresh schemas were being
created with the `lexemes` column but *without* the generated
`lexemes_tsv` column or its GIN index — those existed only in the v2
migration step, which a newly-created schema (born at the current
version) skips entirely. Every new project would have silently had a
dead lexical leg. Both paths now call one shared
`addLexemeSearchObjects()`.

### Phase 2.3 complete — semantic chunk splitting (kept, with one caveat)

`splitLargeChunk` no longer halves at the blind midpoint. It now picks a
split at a blank line at the shallowest brace depth within the central
half of the block, falling back to the shallowest line, then the
midpoint. `MAX_CHUNK_CHARS` is now configurable as `RAGMaxChunkChars`.

Cap sweep (semantic splitting, full pipeline):

| cap | chunks | hit@1 | hit@5 | hit@10 | recall@10 | nDCG@10 |
|---|---|---|---|---|---|---|
| 1500 | 6241 | 0.380 | 0.880 | 0.920 | 0.833 | **0.605** |
| 2400 | 4720 | 0.400 | 0.840 | 0.860 | 0.750 | 0.576 |

Bigger chunks are clearly worse — coarser granularity costs recall.
**1500 retained.**

Semantic vs midpoint splitting at cap 1500 is a genuine trade, not a
clean win. Overall nDCG is a tie (0.605 vs 0.604); hit@1 and MRR improve,
hit@10 and recall@10 decline. It was kept because the breakdown is
lopsided in its favor:

| | midpoint (Phase 1) | semantic |
|---|---|---|
| **held-out test split** hit@1 / hit@5 / MRR | 0.500 / 0.800 / 0.638 | **0.700 / 1.000 / 0.792** |
| vocab hit@1 / MRR | 0.200 / 0.468 | **0.400 / 0.549** |
| crossfile hit@5 / MRR | 0.600 / 0.376 | **1.000 / 0.583** |
| symbol | 0.800 / 1.000 | unchanged |
| **conceptual** hit@5 / recall@10 | **0.950 / 0.925** | 0.800 / 0.825 |

Three of four tags improve and the held-out split improves sharply — the
strongest available evidence against overfitting. **The entire regression
is confined to the `conceptual` tag**, which is worth investigating: those
queries may benefit from coarser, more contextual chunks, which points at
Phase 2.4 (parent-symbol expansion) as the likely fix rather than a
reason to revert.

### Corpus hygiene complete — the cheapest win of the whole project

Generated and vendored artifacts were competing with real source. On the
Kiss tree `manual/jsdoc` alone was **31% of the entire index** (1941
chunks from 34 files), and `jsdoc/Utils.html` was 2.2× the size of the
`Utils.js` it documents.

Two mechanical changes:

1. `ProjectRegistry.BASE_EXCLUDES` now applies to **every** project
   always; `excludeGlobs` *adds* to it. Previously a project declaring
   any exclusion silently lost `node_modules`/`.git`/`target`, which is
   why stack360 had to re-list the defaults by hand.
2. The base list gained build output, IDE state, minified assets, source
   maps, generated jsdoc/texinfo HTML, and lockfiles. Hand-written prose
   (`.md`, `.tex`, design notes) is deliberately kept — it carries
   rationale found nowhere in the code.

Effect on `kiss` — 395 → 331 files, 6241 → **3977 chunks (−36%)**:

| Metric | Before hygiene | After |
|---|---|---|
| hit@1 | 0.380 | **0.420** |
| hit@5 | 0.880 | **0.900** |
| hit@10 | 0.920 | **0.960** |
| recall@10 | 0.833 | **0.883** |
| MRR@10 | 0.567 | **0.622** |
| nDCG@10 | 0.605 | **0.656** |
| p50 latency | 37 ms | **26 ms** |

Every metric improved *and* it got faster, on a third less data. It also
recovered most of the `conceptual` regression 2.3 introduced
(recall@10 0.825 → 0.925), which was the open question from that phase.

`stack360`: 11817 → 11618 files, 116401 → **110790 chunks**. 199 files
dropped — 126 IDE state, 58 generated docs, 7 third-party, 7 minified,
1 lockfile. Zero `.java`/`.groovy` source among them.

**A near-miss worth recording.** The first pass included `**/vendor/**`
in the base excludes. That silently deleted **44 files of real
application source** under
`Backend/src/java/com/arahant/services/standard/misc/vendor/` — in a
business application "vendor" means *supplier*, not third-party code.
It was caught only by diffing the indexed file list before and after,
and it uniquely caught nothing but four stylesheets (every genuine
third-party bundle was already matched by the minified-asset patterns).
`**/vendor/**` was removed from the base list; real vendor directories
are now excluded per-project by explicit path. **Always diff the file
list after an exclude change.**

### Cumulative: baseline → now

| Metric | Baseline | Now | Factor |
|---|---|---|---|
| hit@1 | 0.080 | **0.420** | 5.3× |
| hit@5 | 0.600 | **0.900** | 1.5× |
| hit@10 | 0.800 | **0.960** | 1.2× |
| recall@10 | 0.690 | **0.883** | 1.3× |
| MRR@10 | 0.282 | **0.622** | 2.2× |
| nDCG@10 | 0.368 | **0.656** | 1.8× |
| p50 latency | 27 ms | **26 ms** | faster |

### Phase 2.4 complete — parent-symbol expansion

Schema v3 adds `rag_chunk.sym_start_line` / `sym_end_line`, stamped on
every fragment of a split symbol. A hit that is fragment 2 of 3 gives the
agent the middle of a method with no signature and no return — a match
that is technically correct and practically useless. `search_code` now
stitches the siblings back together and returns the whole enclosing
function (`expand=symbol`, the default; `expand=none` opts out).

Reconstruction reads sibling chunks from the database rather than the
file on disk: the stored chunks are what the recorded line numbers
actually describe, whereas the file may have changed since the sweep.

On `kiss`, **1657 of 3977 chunks (42%) are fragments** that this affects.
Ranking is unchanged by design, and the eval confirmed it (identical
scores before and after) — this is a payload improvement, measured
instead by turns-to-answer.

### Phase 3 complete

**3.1 File-level granularity — measured, and the default.** Chunk hits
are collapsed into ranked *files*, each scored by the sum of its best
three chunk scores rather than by first appearance. A file matching in
three places is a stronger answer than one matching once as well, and
first-appearance ordering cannot express that.

| | granularity=chunk | granularity=file |
|---|---|---|
| hit@1 | 0.420 | **0.500** |
| hit@5 | 0.900 | **0.920** |
| hit@10 | 0.960 | 0.960 |
| recall@10 | 0.883 | **0.888** |
| MRR@10 | 0.622 | **0.662** |
| nDCG@10 | 0.656 | **0.689** |
| conceptual hit@5 | 0.850 | **0.950** |
| symbol hit@1 | 0.800 | **0.900** |

**3.2 Neighbor context.** `include_neighbors` (default 1) attaches
adjacent chunks — but only for chunks with no symbol structure, where the
window boundary fell arbitrarily. Symbol chunks are skipped; 2.4 already
handles those properly.

**3.3 LLM rerank — implemented, disabled by default.** The gate for
building it was a gap between "answer is in the pool" and "answer is at
the top", and that gap is real (hit@1 0.500 vs hit@10 0.960). One batched
generation ranks all candidates from their headers; any timeout or
malformed output falls back silently to the heuristic order, because a
reranker must never be able to break search. It stays off
(`RAGRerankModel` commented out) because a generation step turns a ~30 ms
search into a multi-second one, and the agent blocks on this call.
Enable and measure with `./bld eval` before trusting it.

**3.4 Tool surface.** `search_code`'s description now states what it
actually does — returns ranked files with matched symbols and
already-expanded excerpts, across *every* repo in the project, which a
directory-scoped Grep cannot do. `index_status` promotes
`schema_version`, `embedding_model` and `rebuild_required` to top-level
fields so a stale or partial index is visible rather than buried.

### Cumulative: baseline → end of Phase 3

| Metric | Baseline | Now | Factor |
|---|---|---|---|
| hit@1 | 0.080 | **0.500** | 6.3× |
| hit@5 | 0.600 | **0.920** | 1.5× |
| hit@10 | 0.800 | **0.960** | 1.2× |
| recall@10 | 0.690 | **0.888** | 1.3× |
| MRR@10 | 0.282 | **0.662** | 2.3× |
| nDCG@10 | 0.368 | **0.689** | 1.9× |
| p50 latency | 27 ms | ~32 ms | — |

### Phase 4.1 complete — cross-project search

`/rag-mcp/_all` searches every configured project at once. This is the
capability with no Grep substitute at any effort: an agent launched in
one repository still finds the answer living in a sibling repository.

- `_all` is checked before the registry lookup and starts with an
  underscore, which the `[a-z][a-z0-9_]*` name rule forbids — so it can
  never be shadowed by a real project. `bld` also refuses to create a
  project named `all`.
- The query is embedded **once** and the vector reused across schemas.
  Embedding dominates cost, so searching N projects is far cheaper than
  N searches.
- Hits are tagged with their `project`; `get_chunk` on `_all` requires
  that tag back, since chunk ids are unique only within a schema.
  `list_repos` and `index_status` aggregate across projects.
- A failing project is logged and skipped rather than taking down the
  whole search.

Ordering caveat, recorded honestly: per-project scores are RRF ranks, so
each project's top hit scores about the same whatever its corpus size.
Merging by score interleaves projects rather than strictly ordering by
relevance. That suits "which repo has this?" but is not the ranking a
single-project search produces. Improving it would need a
cross-comparable signal (raw cosine) carried through fusion.

### Phase 4.2 complete — commit history, git AND Subversion

`search_history` indexes commit messages so search can answer *why*, which
no amount of reading the tree can. Built with a VCS abstraction because
the roots are not all git.

| Project | Commits indexed |
|---|---|
| kiss | 1358 (git) |
| ownsona | 70 (git) |
| stack360 | **6993** — Backend 3169, Frontend 1909, Kiss 1359 (git), Mobile 313, Worker 137, Apply 106 |

Verified end to end: "why did we switch MCP registration to project
scope" surfaces the MCP commits in kiss; "why was the benefit enrollment
calculation changed" surfaces the 2013 coverage-calculation commits in
Stack360's Subversion history.

Four failures worth recording, all found only by running it:

- **`fetchOne` appends its own `LIMIT 1`** — the documented Kiss gotcha,
  which turned an explicit `LIMIT 1` into `LIMIT 1 LIMIT 1`.
- **Backend Groovy files load in isolation**, so `RAGIndexer.Config` and
  `RAGIndexer.embedBatch` are unreachable from another backend file. Both
  new scripts now go through precompiled classes (`ProjectRegistry`,
  `RAGSearch`) instead.
- **Only the core groovy jar is on the classpath**, not `groovy-xml`, so
  `XmlSlurper` does not resolve. The svn log parser uses the JDK's DOM
  parser.
- **Fixed character caps on embedded text are not safe.** Subversion
  paths tokenize far denser than prose, so a commit touching many deeply
  nested paths overran the context window at a length that is fine for
  ordinary text. Caps at 4000 and then 2000 characters both still failed.
  It now halves and retries on rejection — the same strategy the chunk
  indexer already used — and returns null rather than letting one
  pathological commit abort an entire repository's import.

Also: a long synchronous job cannot run over the HTTP request. The first
stack360 import outlived its request and Tomcat recycled the response out
from under it. `history` now runs on a background thread behind the same
per-project lock as indexing and summarization, polled via `status`.

### Phase 4.3 complete — LLM file summaries as a third retrieval leg

The largest single quality gain of the entire project after 1.1.

Each file gets a one-paragraph LLM description written in the vocabulary
of *asking* rather than *implementing*, embedded with the same model as
the code, and fused into the file-level ranking as a third RRF leg.
Incremental via `summary_sha`; a separate `./bld summarize` pass, since
it is GPU-bound at roughly 1.3 s/file.

Measured on kiss (331 files summarized), summary leg off vs on:

| Metric | leg OFF | leg ON |
|---|---|---|
| hit@1 | 0.500 | **0.640** |
| hit@5 | 0.920 | 0.920 |
| recall@10 | 0.888 | **0.898** |
| MRR@10 | 0.662 | **0.738** |
| nDCG@10 | 0.689 | **0.740** |
| conceptual hit@1 | 0.350 | **0.500** |
| vocab hit@1 | 0.467 | **0.600** |
| crossfile hit@1 | 0.400 | **0.600** |
| **symbol** hit@1 / MRR / nDCG | 0.900 / 0.950 / 0.963 | **1.000 / 1.000 / 1.000** |
| p50 latency | 31 ms | 37 ms |

Every tag improved, for 6 ms. Symbol retrieval is now perfect on this
query set. `RAGSummaryWeight = 0` disables the leg for A/B.

### Superseded note — Stack360 is Subversion

The plan assumed git. Surveying the actual roots:

| Root | VCS | Commits |
|---|---|---|
| GitHub.blakemcbride/Kiss | git | 1358 |
| GitHub.blakemcbride/Ownsona | git | 70 |
| Stack360/Kiss | git | 1359 |
| Stack360/{Backend,Frontend,Mobile,Apply,Worker} | **svn** | — |

So a `git log`-only implementation would cover the two small projects and
**miss Stack360's application code entirely** — the codebase where "why
was this done" is most valuable. History indexing needs a small VCS
abstraction (`git log` and `svn log -v --xml` both yield the same shape:
id, author, date, message, changed paths) rather than the git-specific
design written above.

**Still open: 4.2** (history + docs corpus, pending the VCS decision) and
**4.3** (LLM file summaries as a third RRF leg — the largest remaining
quality lever, but an overnight GPU job that should be scheduled
deliberately).

The plan is five phases. Phase 0 is a hard prerequisite — without it
every later change is guesswork. Phases 1–3 raise retrieval quality on
the existing corpus. Phase 4 changes what the corpus *is*, which is
where the durable advantage lives.

Decision gates are stated at the end of each phase. They are real: if a
gate fails, the honest move is to stop, not to keep tuning.

---

## Current state (baseline being replaced)

| Aspect | Today | Where |
|---|---|---|
| Retrieval | Pure dense cosine, single SQL `ORDER BY embedding <=> q` | `RAGMCPServer.doSearch` |
| Lexical/hybrid | None | — |
| Reranking | None | — |
| Embedded text | Raw chunk body only, no path/symbol context | `RAGIndexer.groovy:299` |
| Model | `nomic-embed-text:v1.5`, 768-dim, prose-trained | `application.ini:32` |
| Chunk cap | 1500 chars, recursive halving splits function bodies | `RAGIndexer.groovy:130,563` |
| Result payload | 300-char truncated snippet | `RAGMCPServer.SNIPPET_LEN` |
| Corpus | Source files only, one project scope per endpoint | `RAGIndexer.walkRoot` |
| Schema migration | None — `CREATE TABLE IF NOT EXISTS` only | `ProjectBootstrap.ensureSchema` |

---

## Cross-cutting prerequisite: a schema migration ladder

Every phase below adds columns. `ProjectBootstrap.ensureSchema` currently
only creates tables that don't exist; it cannot evolve one that does.

**Work:**

- Add `migrate(Connection db, String project)` to `ProjectBootstrap.groovy`,
  driven by `rag_meta.schema_version` (currently `'1'`).
- Each version is an idempotent step: `v1→v2`, `v2→v3`, … applying
  `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` and index creation, then
  bumping `schema_version` in the same transaction.
- Call it from `ensureAll` / `ensureOne` right after `ensureSchema`.
- Where a migration invalidates existing embeddings, record
  `rag_meta.rebuild_required = 'true'`; `doSweep` refuses to run an
  incremental sweep while that flag is set and tells the user to run
  `./bld scan --full`.

**Also fix now:** `ProjectBootstrap.EMBEDDING_DIM` is hardcoded to `768`
while `RAGIndexer.loadConfig` reads `EmbeddingDim` from
`application.ini`. Two sources of truth for the same number, and Phase 2
changes it. Make the bootstrap read the ini value.

*Effort: ~0.5 day.*

---

## Phase 0 — Measurement (do this first, no exceptions)

Nothing after this point can be evaluated without it.

### 0.1 Extract the search path into a testable class

`doSearch` is currently welded to the servlet: it reads a ThreadLocal for
the project, builds SQL inline, and returns an MCP envelope. The eval
harness must exercise the *same* code the MCP tool uses, or the numbers
are meaningless.

- New `src/main/precompiled/org/kissweb/rag/RAGSearch.java`:
  ```java
  public static SearchResult search(Connection conn, String project, SearchRequest req)
  ```
  with `SearchRequest{query, k, repo, language, pathPrefix}` and
  `SearchResult{List<Hit>}`.
- `RAGMCPServer.doSearch` becomes: parse args → call `RAGSearch.search`
  → format envelope + path translation. Nothing else.
- Embedding (`embedQuery`, `vectorToLiteral`) moves to `RAGSearch` too;
  `RAGIndexer` keeps its own batch path.

### 0.2 Golden query set

`eval/<project>-queries.jsonl`, one JSON object per line:

```json
{"q": "where do we enforce the MCP shared secret",
 "expect": [{"repo": "Code-RAG", "path": "src/main/precompiled/org/kissweb/rag/RAGMCPServer.java"}],
 "tags": ["auth"]}
```

- Target **40–60 queries**, hand-written, drawn from questions actually
  asked of this codebase. This is the one part that cannot be automated
  and is worth doing carefully — the eval is only as good as the set.
- Mix deliberately: ~40% conceptual ("where do we handle X"), ~30%
  vocabulary-mismatch (the query words do *not* appear in the target
  file — this is the case that justifies the whole project), ~20% exact
  symbol lookups (where Grep should win; confirms we don't regress),
  ~10% cross-file ("what talks to the indexer").
- `expect` may list several acceptable files. Optional `symbol` field
  tightens a match to a line range.

### 0.3 Eval runner

- `src/main/backend/scripts/RAGEval.groovy` — loads the JSONL, calls
  `RAGSearch.search` directly (no HTTP, no Tomcat), scores results.
- Metrics: **recall@1 / @5 / @10**, **MRR@10**, **nDCG@10**, plus p50/p95
  query latency (embedding time reported separately — it dominates).
- Per-tag breakdown, so a change that helps conceptual queries while
  wrecking symbol lookups is visible rather than averaged away.
- `./bld eval <project> [--baseline] [--compare <file>]` task in
  `Tasks.java`. Writes `eval/results-<timestamp>.json`; `--baseline`
  additionally writes `eval/baseline.json`.
- Output a diff table against baseline: per-metric delta and the list of
  queries that changed rank bucket. The per-query regression list is what
  you'll actually use while tuning.

### 0.4 Record the baseline

Run against the current index, commit `eval/baseline.json`. Every later
phase reports against it.

*Effort: ~1.5–2 days, most of it writing the query set.*

**Gate:** none — this phase is infrastructure. But if baseline recall@10
is already above ~0.85, the retrieval is not the problem and you should
skip to Phase 3/4.

---

## Phase 1 — Retrieval quality, same model

Four changes, all mechanical, all measurable. Do them as four separate
commits with an eval run between each so you learn which one paid.

### 1.1 Context-enriched embedding text

Today a 20-line method body is embedded with zero signal about where it
lives. Fix at `RAGIndexer.indexOneFile:299`.

- New `buildEmbedText(RootDir root, String relPath, String language, Chunk c)`
  producing a header prepended to the body **for embedding only**:
  ```
  repo: Code-RAG
  file: src/main/precompiled/org/kissweb/rag/RAGMCPServer.java
  lang: java
  symbol: doSearch
  ---
  <chunk body>
  ```
- `rag_chunk.content` continues to store the **raw body** — the header is
  retrieval scaffolding, not something the agent should read back.
- Header must be regenerable for debugging; add a `--show-embed-text`
  flag to the eval runner rather than storing a duplicate column
  (storing it would roughly double table size for no runtime benefit).
- Header counts against `MAX_CHUNK_CHARS`; budget ~150 chars for it and
  drop the body cap accordingly so nothing newly overflows the model.

**Requires a full rebuild** (`./bld scan --full`) — every vector changes.

### 1.2 Hybrid lexical retrieval + RRF

This is what makes the tool stop losing to Grep on exact tokens.

Don't fight the Postgres text parser over `camelCase` — tokenize in
Groovy where you control it.

- `tokenizeForSearch(String)`: split on non-alphanumeric, then split
  camelCase boundaries; lowercase; emit **both** the whole identifier and
  its parts (`getUserName` → `getusername get user name`). Same function
  used at index time and on the query.
- Schema (migration v1→v2):
  ```sql
  ALTER TABLE <p>.rag_chunk ADD COLUMN IF NOT EXISTS lexemes TEXT;
  ALTER TABLE <p>.rag_chunk ADD COLUMN IF NOT EXISTS lexemes_tsv tsvector
      GENERATED ALWAYS AS (to_tsvector('simple', coalesce(lexemes,''))) STORED;
  CREATE INDEX IF NOT EXISTS rag_chunk_lex_gin
      ON <p>.rag_chunk USING gin (lexemes_tsv);
  ```
  `'simple'` (not `'english'`) — stemming and stopword removal mangle
  identifiers.
- `RAGSearch.search` runs two retrievals, each `LIMIT 50`:
  dense (existing HNSW) and lexical (`ts_rank_cd` over `lexemes_tsv`),
  then fuses with **Reciprocal Rank Fusion**:
  `score(d) = Σ_retrievers 1 / (60 + rank_r(d))`.
  RRF needs no score normalization between two incomparable scales, which
  is exactly the problem here.
- Filters (`repo`, `language`, `path_prefix`) apply to both legs.
- Config: `RAGHybridWeightDense` / `RAGHybridWeightLexical` (default 1.0
  each), `RAGRRFConstant` (default 60), `RAGOverfetch` (default 50).
  Setting the lexical weight to 0 restores today's behavior — keep that
  escape hatch for A/B runs.

### 1.3 Return whole chunks

Delete `SNIPPET_LEN` truncation. Chunks are capped at 1500 chars; 8 hits
is ~12KB, which is nothing against the round-trips it saves.

- Add a `max_chars` input (default ~1200/chunk, 0 = unlimited) so the
  agent can ask for terse results when it's scanning broadly.
- Keep `get_chunk` — it's still the right tool for "expand this one".

### 1.4 Overfetch + cheap rerank

With overfetch already in place from 1.2, add a deterministic reranker
before truncating to `k`:

- Boost: query token appears in `symbol` (strong), in the path (moderate),
  chunk is the file's preamble/class-declaration chunk (mild), file
  recently modified (very mild).
- Penalize: chunks that are pure imports/license headers; near-duplicate
  chunks from the same file (keep the best, note the count).
- Diversity cap: at most N chunks per file in the top-k (default 2), so
  one large file cannot occupy the whole result set.

All heuristic, no model call, sub-millisecond.

*Effort: ~3–4 days total.*

**Gate:** re-run `./bld eval`. Expect a **meaningful** lift in recall@10
and MRR@10, driven mostly by 1.1 and 1.2, with no regression on the
exact-symbol tag. If the combined lift is marginal, stop and go read the
per-query regressions before spending Phase 2's rebuild time.

---

## Phase 2 — Embedding model and chunk shape

### 2.1 Make dimension config-driven end to end

Prerequisite for any model swap. Covered in the migration ladder section;
also extend `verifyMetaMatches` to compare `embedding_model` (not just
`embedding_dim`) and set `rebuild_required` on mismatch, rather than
silently indexing new vectors into an index built by a different model.

### 2.2 Model bake-off

Do not pick a model from reputation — the eval exists now, so measure.

- Survey what the local Ollama registry currently offers for
  code-oriented embeddings, pull 2–3 candidates plus the incumbent.
- For each: full rebuild into a **scratch project schema** (e.g. `eval_a`,
  `eval_b`) pointed at the same roots, then `./bld eval`. Scratch schemas
  mean no downtime and a real A/B.
- Score on: recall@10, MRR@10, index build wall-clock, query latency,
  and vector storage size. A model that is 3% better and 4× slower to
  query is not better — the agent is blocking on this call.
- Add `./bld eval-sweep <project> <modelA> <modelB> ...` to automate the
  bootstrap → rebuild → eval → teardown loop.

### 2.3 Chunk integrity

`splitLargeChunk` recursively halves at line boundaries, so any function
over ~1500 chars is cut mid-body and each fragment embeds poorly.

- Most code-oriented embedding models carry substantially larger context
  than nomic's effective 2K. Once 2.2 picks one, raise `MAX_CHUNK_CHARS`
  to fit whole functions (make it a config key,
  `RAGMaxChunkChars`, rather than a constant).
- Change `splitLargeChunk` to prefer **semantic** split points before
  falling back to halving: blank lines at the lowest brace depth, then
  statement boundaries, then the current behavior.
- When a symbol *is* split, mark the fragments (`chunk_no > 0` within a
  symbol) so 2.4 can rejoin them.

### 2.4 Parent-symbol expansion

- Migration: `ALTER TABLE <p>.rag_chunk ADD COLUMN IF NOT EXISTS
  sym_start_line INT, ADD COLUMN IF NOT EXISTS sym_end_line INT;`
  populated by `chunkBySymbols`.
- `search_code` gains `expand` (default `symbol`): a hit that is a
  fragment returns the enclosing symbol's full line range instead of the
  fragment. `expand=none` restores raw-chunk behavior.
- Expansion reads from `content` where the sibling chunks cover it; falls
  back to reading the file at `absolute_path` when they don't. Cap
  expansion at `RAGMaxExpandChars` so a 2000-line class doesn't blow up
  the response.

*Effort: ~3–4 days, plus rebuild wall-clock per candidate model.*

**Gate:** the winning model must beat the Phase 1 configuration on
recall@10 by enough to justify a full rebuild of every project and the
permanent latency change. If nothing beats the incumbent, keep
`nomic-embed-text` and bank the Phase 1 gains — that is a legitimate
outcome, and 2.3/2.4 are still worth doing on their own.

---

## Phase 3 — Agent ergonomics

Retrieval quality is necessary but not sufficient. The tool also has to
cost fewer turns than Grep, or the agent will keep reaching for Grep.

### 3.1 File-level results

Agents navigate by file, not by chunk. Add a `granularity` input to
`search_code` (`chunk` | `file`, default `file`):

- `file` aggregates chunk hits by `file_id`, scores each file by the sum
  of its top-3 chunk RRF scores, and returns per file: `absolute_path`,
  aggregate score, the matched symbols with their line ranges, and the
  single best chunk body.
- This is usually the answer to "where do we handle X" in one call.

### 3.2 Neighbor context

`include_neighbors` (default 1): attach the immediately preceding and
following chunk of the same file, so a hit lands with surrounding
context rather than an isolated window.

### 3.3 Optional LLM rerank

Only after 1.4's heuristic reranker is in and measured — and only if the
eval says the top-50 contains the right answer but the top-8 doesn't
(that gap is exactly what a reranker fixes; if it's absent, skip this).

- One batched call to the local Ollama chat model: query plus 30–50
  candidate headers (path + symbol + first two lines), asking for the
  ranked ids. One small generation, not one per candidate.
- Config `RAGRerankModel` (empty = disabled) and `RAGRerankTimeoutMs`
  (default ~2000) with a hard fallback to the heuristic ranking on
  timeout or malformed output. The agent is blocking; never let the
  rerank turn a 200ms call into a 10s call.
- Report reranked-vs-not in the eval as a separate row.

### 3.4 Tool surface

- Rewrite the `search_code` description around what it now does: hybrid,
  file-granular, returns full context. State plainly that it covers
  **all repos in the project**, which Grep cannot.
- `index_status` should surface `schema_version`, `embedding_model`, and
  `rebuild_required`, so a stale index is visible rather than silently
  degrading results.
- Revisit the managed `CLAUDE.md` routing snippet (`project_dir` block)
  once the tool is genuinely better — the current wording is arguing for
  a tool that didn't yet earn it.

*Effort: ~2–3 days.*

**Gate:** measure *turns to answer*, not just recall. Take 10 realistic
tasks, run them with the tool available and with it disabled, and count
tool calls to a correct answer. If the tool doesn't reduce turns, 3.1/3.2
haven't done their job.

---

## Phase 4 — The things Grep structurally cannot do

Phases 1–3 make Code-RAG competitive on single-repo search. Phase 4 is
where it becomes worth running at all. Prioritize accordingly.

### 4.1 Cross-project search

Claude Code greps the tree it was launched from. Semantic search across
Kiss + LLMChat + OwnSona + Code-RAG simultaneously is something the
built-in tools cannot do at any effort level.

- Reserved endpoint segment handled in `authenticate()` **before** the
  registry lookup. Note `ProjectRegistry.isValidName` is `[a-z][a-z0-9_]*`,
  so `_all` is not a legal project name and is safe as a sentinel; also
  make `new-project` refuse the literal name `all` to avoid ambiguity.
- New tool `search_all_projects` on that endpoint: fan out the same
  `RAGSearch.search` across every configured project's schema (they are
  independent schemas, so this parallelizes cleanly), RRF-fuse the
  per-project result lists, and tag every hit with its `project`.
- Guard: cap total projects searched, and reuse one query embedding
  across all of them (embed once, query N schemas).
- Registration: write the `_all` endpoint into the `project_dir`
  `.mcp.json` alongside the project-scoped one.

### 4.2 Non-code artifacts

Grep can find *what* the code does. It cannot find *why*.

- **Git history.** New table (migration step):
  ```sql
  CREATE TABLE IF NOT EXISTS <p>.rag_commit (
      commit_id     BIGSERIAL PRIMARY KEY,
      repo          TEXT NOT NULL,
      sha           CHAR(40) NOT NULL,
      author        TEXT,
      committed_at  TIMESTAMPTZ,
      subject       TEXT NOT NULL,
      body          TEXT,
      files_changed TEXT,
      embedding     vector(<dim>) NOT NULL,
      UNIQUE (repo, sha)
  );
  ```
  Populated by `git log --format=... --name-only` in the sweep;
  incremental via the newest indexed `sha`. Embed
  `subject + body + files_changed`. New tool `search_history` —
  "why did we switch to project scope for MCP registration" is answerable
  from commit `3a4b77c`'s message and from nothing else in the tree.
- **Design docs / decision notes.** Already partly covered by the
  markdown chunker; make sure the root `.md` files and `AI/*.md` are in
  scope for every project rather than only source trees.

### 4.3 LLM-generated summaries (dual-vector retrieval)

The single largest quality jump available, and the most expensive.

Natural-language queries match natural-language summaries far better than
they match raw source. The user asks "where do we prevent two sweeps
overlapping"; the code says `tryAcquireLock`. A summary bridges that.

- Migration:
  ```sql
  ALTER TABLE <p>.rag_file
      ADD COLUMN IF NOT EXISTS summary           TEXT,
      ADD COLUMN IF NOT EXISTS summary_embedding vector(<dim>),
      ADD COLUMN IF NOT EXISTS summary_model     TEXT,
      ADD COLUMN IF NOT EXISTS summary_sha       CHAR(64);
  CREATE INDEX IF NOT EXISTS rag_file_summary_hnsw
      ON <p>.rag_file USING hnsw (summary_embedding vector_cosine_ops);
  ```
  `summary_sha` mirrors `rag_file.sha256`; regenerate only when it
  differs, so incremental sweeps stay cheap.
- Generation: local Ollama chat model, one call per file, prompt asking
  for a tight paragraph — what the file does, what calls it, what it
  calls, key concepts in domain vocabulary rather than identifiers.
  Config `RAGSummaryModel` (empty = feature off) and
  `RAGSummaryMaxFileBytes`.
- Add as a **third retrieval leg** in the RRF fusion (dense chunk +
  lexical + summary), not a replacement. Summaries are file-granular and
  pair naturally with 3.1's file granularity.
- Cost: roughly 1s/file on GPU — a 10k-file corpus is an overnight
  one-time build, near-free incrementally. Run it as a separate
  `./bld summarize <project>` pass so a failure or interruption never
  blocks ordinary indexing, and so it can be resumed.
- Because this is a big, resumable, offline job, it should honor the same
  per-project `reindex_running` lock and report progress the way `scan`
  does.

*Effort: 4.1 ~2 days, 4.2 ~3 days, 4.3 ~4 days plus build time.*

---

## Sequencing and effort

| # | Item | Effort | Requires rebuild | Depends on |
|---|---|---|---|---|
| — | Migration ladder + dim unification | 0.5d | no | — |
| 0 | Extract `RAGSearch`, query set, `./bld eval`, baseline | 1.5–2d | no | — |
| 1.1 | Context-enriched embed text | 0.5d | **full** | 0 |
| 1.2 | Hybrid lexical + RRF | 1.5d | reindex (lexemes) | ladder, 0 |
| 1.3 | Return whole chunks | 0.25d | no | 0 |
| 1.4 | Overfetch + heuristic rerank | 1d | no | 1.2 |
| 2.1 | Config-driven dim/model verification | 0.5d | no | ladder |
| 2.2 | Model bake-off | 1.5d + build | **full** per candidate | 0, 2.1 |
| 2.3 | Chunk integrity | 1d | **full** | 2.2 |
| 2.4 | Parent-symbol expansion | 1d | reindex | 2.3 |
| 3.1 | File-level results | 1d | no | 1.2 |
| 3.2 | Neighbor context | 0.5d | no | — |
| 3.3 | Optional LLM rerank | 1d | no | 1.4 |
| 3.4 | Tool surface / descriptions | 0.5d | no | 3.1 |
| 4.1 | Cross-project search | 2d | no | 0 |
| 4.2 | Git history + docs corpus | 3d | additive | ladder |
| 4.3 | LLM summaries, third RRF leg | 4d + build | additive | 1.2 |

Roughly 3–4 weeks of focused work end to end. Phases 0+1 alone are about
a week and produce the decision gate that determines whether the rest is
worth doing.

**Suggested order if you want value early:** ladder → 0 → 1.3 → 1.1 →
1.2 → 1.4 → *gate* → 4.1 → 3.1 → 2.x → 4.2 → 4.3.

That pulls 4.1 (cross-project) forward, ahead of Phase 2/3 polish,
because it is the one feature with no Grep substitute and it needs no
model change or rebuild.

---

## Risks and kill criteria

- **Latency.** Every added leg costs time on a call the agent blocks on.
  Budget the whole `search_code` round trip at **under ~500ms p95**,
  embedding included. Track it in the eval from day one; if hybrid +
  rerank push past that, cut the rerank before cutting recall.
- **Rebuild cost.** 1.1, 2.2, 2.3 each invalidate all vectors. Batch them
  into as few full rebuilds as possible, and keep `rebuild_required`
  honest so a half-migrated index can't silently serve bad results.
- **Query-set overfitting.** 40–60 queries is enough to detect a real
  regression and not enough to tune against safely. Hold back ~10 as a
  test set never used during tuning.
- **Kill criterion.** If, after Phase 1 with the gate met and Phase 4.1
  shipped, you still find yourself reaching for Grep in normal work, the
  answer is that single-repo semantic search is not the right tool for
  how you work. Retire `search_code` to cross-repo and history search
  (4.1, 4.2) and drop the rest. That is a successful outcome, not a
  failure — it's just a smaller tool than originally scoped.

---

## Documentation to update as you go

- `RAGPlan.md` — design rationale for hybrid retrieval, RRF, summaries.
- `Running.md` — `./bld eval`, `./bld summarize`, `--full` rebuild
  guidance, `rebuild_required` recovery.
- `Overview.md` — the read path changes substantially; the diagram and
  the tool list both need revising.
- `AI/ApplicationDetails.md` — new files, new config keys, new bld tasks,
  the schema-migration rule, and the "chunk content is raw, embed text is
  decorated" distinction, which is exactly the kind of thing a future
  session will otherwise get wrong.
