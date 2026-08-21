#!/usr/bin/env python3
"""
Turns-to-answer harness: does search_code actually save an agent work?

Retrieval metrics (./bld eval) answer "is the right file in the result set".
They do NOT answer the question that matters, which is whether an agent using
this tool reaches the answer in fewer steps than one using Grep. This measures
that, by replaying the same golden query set through two strategies and
counting tool calls until the target file is in hand.

    GREP     tries content words from the query as grep patterns, rarest
             first, then two-term AND combinations. A search "succeeds" when a
             result set is small enough to act on (<= RESULT_CAP files) and
             contains an expected file. Cost = number of grep calls + 1 Read.

    RAG      one search_code call. Cost = 1 call + 1 Read when an expected file
             is in the top K. Otherwise the agent is assumed to fall back to
             grep, and pays the RAG call plus the full grep cost.

HONESTY NOTE: this is a simulation, not a live agent A/B. A real agent greps
more cleverly than this (it reads results, refines, follows imports), so treat
GREP numbers as an approximation. It is deliberately generous to grep: it is
given the ideal term ordering (rarest first, which a real agent cannot know in
advance) and unlimited case-insensitive matching.

Usage:  python3 eval/turns.py <queries.jsonl> <root>[,<root>...] <mcp-url> <token>

        <root> may be a comma-separated list when the project spans several
        repositories (expected paths are resolved against whichever root
        actually contains them).
"""
import json
import os
import subprocess
import sys
import urllib.request

RESULT_CAP = 10      # a result set larger than this is not directly actionable
MAX_GREP_CALLS = 6   # give up after this many; agent would change tactics
MAX_SIFT = 10        # Reads an agent will spend sifting an un-narrowed result set
TOP_K = 5            # how many hits search_code returns

STOP = set("""a an and are as at be been but by can could do does for from get gets
has have how if in into is it its make makes need not of on or our out over should
so some that the their them then there these they this to under until use used uses
using want was we were what when where which while who why will with would you your
find where's wheres does do i me my""".split())


def terms(q):
    """Content words from a query, longest first (a proxy for rarest first)."""
    ws = [w.strip(".,?'\"()[]{}").lower() for w in q.split()]
    # Underscores must be allowed: `w.isalnum()` silently discarded every
    # snake_case identifier (popup_open, base_setup), which handicapped the
    # grep baseline on exactly the queries it should win.
    ws = [w for w in ws
          if len(w) >= 3 and w not in STOP and w.replace("_", "").isalnum()]
    seen, out = set(), []
    for w in sorted(ws, key=len, reverse=True):
        if w not in seen:
            seen.add(w)
            out.append(w)
    return out


# Mirror the indexer's exclusions so neither strategy is scored against a
# corpus the other cannot see.
EXCLUDE_DIRS = [".git", "node_modules", "target", "build", "work", "dist",
                "out", "tomcat", ".idea", ".vscode", "coverage", "vendor"]


_RG_CACHE = {}


def rg_files(pattern, roots):
    """Files containing a literal pattern, case-insensitive.

    Uses GNU grep rather than ripgrep: on this machine `rg` is only a shell
    function wrapping the Claude CLI, and a missing binary silently made every
    grep attempt fail, which would have scored the baseline at 0%.

    Memoized: the two-term AND phase re-greps terms the single-term phase
    already ran, so without a cache the same scan is repeated many times over
    a large tree. This changes only wall-clock, never the reported call counts
    (`calls` still counts every logical grep an agent would have issued).
    """
    key = (pattern, tuple(roots))
    if key in _RG_CACHE:
        return _RG_CACHE[key]
    cmd = ["grep", "-rIl", "-i", "-F"]
    for d in EXCLUDE_DIRS:
        cmd.append("--exclude-dir=" + d)
    cmd += ["--", pattern] + list(roots)
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except Exception:
        return []
    # grep exits 1 on "no match", which is not an error.
    if p.returncode not in (0, 1):
        raise RuntimeError("grep failed: " + p.stderr[:200])
    out = [l.strip() for l in p.stdout.splitlines() if l.strip()]
    _RG_CACHE[key] = out
    return out


def grep_cost(query, expected_abs, roots):
    """(calls, sift, narrowed) for grep, or None if it never matched at all.

    Two distinct outcomes have to be told apart, because collapsing them
    slanders grep at scale. On a large multi-repository corpus a single
    content word routinely matches hundreds of files ("password" -> 392,
    "orgGroup" -> 1625 across the six stack360 roots), so a rule of "the
    result set must be <= RESULT_CAP to be actionable" reports grep as
    solving 0% of queries -- which is false. grep FOUND the file; it just
    could not narrow to a set worth reading one by one.

      narrowed=True   the expected file is in a set of <= RESULT_CAP.
                      Cost = calls + 1 Read.
      narrowed=False  the expected file is in the matches, but the set is
                      too big to act on directly. The agent must sift; with
                      grep -l output in filesystem order the target sits on
                      average halfway down, so charge ceil(n/2) Reads,
                      capped at MAX_SIFT (past that a real agent changes
                      tactics rather than reading on).
    """
    ts = terms(query)
    calls = 0
    best = None            # (calls, set_size) for the smallest matching set seen
    def consider(c, hits):
        nonlocal best
        if hits and any(h in expected_abs for h in hits):
            if best is None or len(hits) < best[1]:
                best = (c, len(hits))
            return len(hits) <= RESULT_CAP
        return False

    for t in ts:
        if calls >= MAX_GREP_CALLS:
            break
        calls += 1
        if consider(calls, rg_files(t, roots)):
            return (calls, 1, True)
    # Then two-term AND (approximated by intersecting two result sets).
    for i in range(len(ts)):
        for j in range(i + 1, len(ts)):
            if calls >= MAX_GREP_CALLS:
                break
            calls += 1
            both = set(rg_files(ts[i], roots)) & set(rg_files(ts[j], roots))
            if consider(calls, both):
                return (calls, 1, True)
        if calls >= MAX_GREP_CALLS:
            break
    if best is not None:
        return (best[0], min(-(-best[1] // 2), MAX_SIFT), False)
    return None


def rag_rank(query, url, token, k):
    """1-based rank of the first expected file, or 0. Returns (rank, paths)."""
    req = urllib.request.Request(
        url, method="POST",
        data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                         "params": {"name": "search_code",
                                    "arguments": {"query": query, "k": k}}}).encode(),
        headers={"Content-Type": "application/json", "X-RAG-Token": token})
    with urllib.request.urlopen(req, timeout=120) as r:
        env = json.loads(json.load(r)["result"]["content"][0]["text"])
    return [h["path"] for h in env["hits"]]


def main():
    qfile, rootarg, url, token = sys.argv[1:5]
    # A project may span several repository roots (stack360 has six). Expected
    # paths are stored relative to whichever root owns them, so resolve each
    # against every root and keep the one that exists; grep sees all roots.
    roots = [r.rstrip("/") for r in rootarg.split(",") if r.strip()]
    for r in roots:
        if not os.path.isdir(r):
            sys.exit("not a directory: " + r)
    rows = [json.loads(l) for l in open(qfile) if l.strip()]

    grep_total = rag_total = 0
    grep_found = grep_narrowed = rag_solved = 0
    grep_one_call = rag_one_call = 0
    detail = []

    for r in rows:
        q = r["q"]
        rel = {e["path"] for e in r["expect"]}
        absset = set()
        for p in rel:
            for r in roots:
                cand = r + "/" + p
                if os.path.exists(cand):
                    absset.add(cand)
        gr = grep_cost(q, absset, roots)
        paths = rag_rank(q, url, token, TOP_K)
        hit = next((i + 1 for i, p in enumerate(paths) if p in rel), 0)

        # GREP: grep calls + the Reads needed to actually get the file in hand.
        if gr:
            calls, sift, narrowed = gr
            g = calls + sift
        else:
            calls, sift, narrowed = MAX_GREP_CALLS, 1, False
            g = MAX_GREP_CALLS + 1
        # RAG: 1 search + 1 Read on a hit; otherwise pay the search AND the grep.
        rc = 2 if hit else (1 + g)

        grep_total += g
        rag_total += rc
        grep_found += 1 if gr else 0
        grep_narrowed += 1 if narrowed else 0
        rag_solved += 1 if hit else 0
        grep_one_call += 1 if (narrowed and calls == 1) else 0
        rag_one_call += 1 if hit else 0
        detail.append((q, gr, hit, g, rc))

    n = len(rows)
    print(f"\n{n} queries   (grep result cap {RESULT_CAP} files, give-up after {MAX_GREP_CALLS} calls)\n")
    print(f"  {'metric':<34}{'GREP':>10}{'search_code':>14}")
    print(f"  {'-'*58}")
    print(f"  {'found the file at all':<34}{grep_found/n:>10.0%}{rag_solved/n:>14.0%}")
    print(f"  {'...and narrowed it to <=' + str(RESULT_CAP):<34}{grep_narrowed/n:>10.0%}{rag_solved/n:>14.0%}")
    print(f"  {'answer after ONE tool call':<34}{grep_one_call/n:>10.0%}{rag_one_call/n:>14.0%}")
    print(f"  {'mean tool calls to answer':<34}{grep_total/n:>10.2f}{rag_total/n:>14.2f}")
    print(f"\n  reduction in tool calls: {(1 - rag_total/grep_total):.0%}\n")

    worse = [d for d in detail if d[4] > d[3]]
    if worse:
        print(f"  queries where search_code cost MORE ({len(worse)}):")
        for q, gr, hit, g, rc in worse[:10]:
            print(f"    grep={g} rag={rc}  \"{q[:60]}\"")


if __name__ == "__main__":
    main()
