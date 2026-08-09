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

Usage:  python3 eval/turns.py <queries.jsonl> <repo-root> <mcp-url> <token>
"""
import json
import subprocess
import sys
import urllib.request

RESULT_CAP = 10      # a result set larger than this is not directly actionable
MAX_GREP_CALLS = 6   # give up after this many; agent would change tactics
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


def rg_files(pattern, root):
    """Files containing a literal pattern, case-insensitive.

    Uses GNU grep rather than ripgrep: on this machine `rg` is only a shell
    function wrapping the Claude CLI, and a missing binary silently made every
    grep attempt fail, which would have scored the baseline at 0%.
    """
    cmd = ["grep", "-rIl", "-i", "-F"]
    for d in EXCLUDE_DIRS:
        cmd.append("--exclude-dir=" + d)
    cmd += ["--", pattern, root]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except Exception:
        return []
    # grep exits 1 on "no match", which is not an error.
    if p.returncode not in (0, 1):
        raise RuntimeError("grep failed: " + p.stderr[:200])
    return [l.strip() for l in p.stdout.splitlines() if l.strip()]


def grep_cost(query, expected_abs, root):
    """grep calls needed before an expected file is in an actionable result set."""
    ts = terms(query)
    calls = 0
    # Single terms first.
    for t in ts:
        if calls >= MAX_GREP_CALLS:
            return None
        calls += 1
        hits = rg_files(t, root)
        if hits and len(hits) <= RESULT_CAP and any(h in expected_abs for h in hits):
            return calls
    # Then two-term AND (approximated by intersecting two result sets).
    for i in range(len(ts)):
        for j in range(i + 1, len(ts)):
            if calls >= MAX_GREP_CALLS:
                return None
            calls += 1
            both = set(rg_files(ts[i], root)) & set(rg_files(ts[j], root))
            if both and len(both) <= RESULT_CAP and any(h in expected_abs for h in both):
                return calls
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
    qfile, root, url, token = sys.argv[1:5]
    rows = [json.loads(l) for l in open(qfile) if l.strip()]

    grep_total = rag_total = 0
    grep_solved = rag_solved = 0
    grep_one_call = rag_one_call = 0
    detail = []

    for r in rows:
        q = r["q"]
        rel = {e["path"] for e in r["expect"]}
        absset = {root.rstrip("/") + "/" + p for p in rel}

        gc = grep_cost(q, absset, root)
        paths = rag_rank(q, url, token, TOP_K)
        hit = next((i + 1 for i, p in enumerate(paths) if p in rel), 0)

        # GREP: grep calls + 1 Read, or the give-up ceiling.
        g = (gc + 1) if gc else (MAX_GREP_CALLS + 1)
        # RAG: 1 search + 1 Read on a hit; otherwise pay the search AND the grep.
        rc = 2 if hit else (1 + g)

        grep_total += g
        rag_total += rc
        grep_solved += 1 if gc else 0
        rag_solved += 1 if hit else 0
        grep_one_call += 1 if gc == 1 else 0
        rag_one_call += 1 if hit else 0
        detail.append((q, gc, hit, g, rc))

    n = len(rows)
    print(f"\n{n} queries   (grep result cap {RESULT_CAP} files, give-up after {MAX_GREP_CALLS} calls)\n")
    print(f"  {'metric':<34}{'GREP':>10}{'search_code':>14}")
    print(f"  {'-'*58}")
    print(f"  {'solved at all':<34}{grep_solved/n:>10.0%}{rag_solved/n:>14.0%}")
    print(f"  {'answer after ONE tool call':<34}{grep_one_call/n:>10.0%}{rag_one_call/n:>14.0%}")
    print(f"  {'mean tool calls to answer':<34}{grep_total/n:>10.2f}{rag_total/n:>14.2f}")
    print(f"\n  reduction in tool calls: {(1 - rag_total/grep_total):.0%}\n")

    worse = [d for d in detail if d[4] > d[3]]
    if worse:
        print(f"  queries where search_code cost MORE ({len(worse)}):")
        for q, gc, hit, g, rc in worse[:10]:
            print(f"    grep={g} rag={rc}  \"{q[:60]}\"")


if __name__ == "__main__":
    main()
