#!/usr/bin/env python3
"""
Mine a golden query set from real usage.

Every query set in eval/ was hand-written, which makes it a guess at what gets
asked. That is the one caveat tuning cannot remove: a retrieval metric is only
as honest as its queries. This turns actual use into candidate eval entries.

The signal is a follow-up fetch. When a search returns a list of files and the
agent then calls get_chunk on one of them, that choice is about as close to a
relevance judgement as observation can get — nobody opens a result they think
is wrong.

    search  ->  paths p1..pn returned at time T
    fetch   ->  path pi opened at time T+d  (d <= WINDOW seconds)
    =>  candidate {"q": <query>, "expect": [pi]}

Output is CANDIDATES, not a finished query set. Review before use: a fetch can
be exploratory, and a query nobody followed up on is silently absent. Merge the
good ones into eval/<project>-queries.jsonl by hand.

Usage:  python3 eval/mine.py <project> [window-seconds] [--include-unfetched]
"""
import json
import subprocess
import sys
from collections import OrderedDict

WINDOW = 180  # seconds between a search and a fetch for them to count as related


def psql(sql):
    p = subprocess.run(["psql", "-U", "postgres", "-h", "localhost", "-d", "code_rag",
                        "-tAF", "\t", "-c", sql],
                       capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit("psql failed: " + p.stderr.strip())
    return [l.split("\t") for l in p.stdout.splitlines() if l.strip()]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    project = sys.argv[1]
    window = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else WINDOW
    include_unfetched = "--include-unfetched" in sys.argv

    # paths holds newline-separated values, and psql emits rows line by line —
    # so newlines must be swapped for a sentinel or every multi-path row is
    # parsed as several broken rows.
    rows = psql(f"""
        SELECT log_id, extract(epoch from at)::bigint, kind,
               replace(coalesce(query,''), chr(10), ' '),
               replace(coalesce(paths,''), chr(10), '|')
          FROM {project}.rag_query_log
         ORDER BY at
    """)
    searches, fetches = [], []
    for row in rows:
        if len(row) < 5:
            continue
        log_id, ts, kind, query, paths = row[:5]
        rec = (int(ts), query, [p for p in paths.split("|") if p])
        (searches if kind == "search" else fetches).append(rec)

    if not searches:
        print(f"No searches logged for '{project}' yet. Use the tool, then re-run.")
        return

    # For each search, the first fetch inside the window that names one of the
    # paths it returned.
    out = OrderedDict()
    matched = 0
    for ts, query, paths in searches:
        if not query:
            continue
        chosen = None
        for fts, _, fpaths in fetches:
            if fts < ts or fts - ts > window:
                continue
            for fp in fpaths:
                if fp in paths:
                    chosen = fp
                    break
            if chosen:
                break
        if chosen:
            matched += 1
            expect = [chosen]
        elif include_unfetched and paths:
            expect = [paths[0]]   # unverified: the top hit, for manual review
        else:
            continue
        # Later evidence for the same query wins.
        out[query] = expect

    print(f"# mined from {len(searches)} searches / {len(fetches)} fetches in "
          f"'{project}'; {matched} had a follow-up fetch", file=sys.stderr)
    if include_unfetched:
        print("# WARNING: --include-unfetched entries are UNVERIFIED top hits, "
              "which will flatter the tool. Review each one.", file=sys.stderr)

    for query, expect in out.items():
        rec = {"q": query,
               "expect": [{"repo": e.split("/", 1)[0], "path": e.split("/", 1)[1]}
                          for e in expect if "/" in e],
               "tags": ["mined"],
               "split": "tune"}
        if rec["expect"]:
            print(json.dumps(rec))


if __name__ == "__main__":
    main()
