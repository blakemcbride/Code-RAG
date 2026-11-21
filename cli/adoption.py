#!/usr/bin/env python3
"""
code-rag adoption [days]   — is the index actually being used, and do its
                             results get opened?

`code-rag usage` reads the server's own log, so it can only count calls that
arrived. It cannot see the two things that decide whether the tool is earning
its keep:

  * the DENOMINATOR — how often the agent searched some other way instead
    (shell grep/rg/find, or the client's Grep/Glob tools); and
  * the FOLLOW-UP — whether a returned file was then opened. Results are read
    with the client's own Read tool, which the server never sees. The old
    server-side "follow-up rate" counted get_chunk calls and so read 0% no
    matter how useful the results were.

Both are in the client's session transcripts. This reads Claude Code's
(~/.claude/projects/**/*.jsonl), attributes each session to the indexed
project its working directory falls in, and reports per project:

    index searches   MCP tool calls + `code-rag search|why|symbol|dependents`
    other searches   grep / rg / find / ag in Bash, plus Grep and Glob
    opened           index searches where a returned path was Read or named
                     in a later command within the next FOLLOW_WINDOW tool calls

Read-only. Nothing is sent anywhere. Default window: 30 days.
"""
import json
import os
import re
import sys
import time
from collections import defaultdict

HOME = os.environ.get("CODE_RAG_HOME") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRANSCRIPTS = os.path.expanduser("~/.claude/projects")
FOLLOW_WINDOW = 15

MCP_RE = re.compile(r"^mcp__[A-Za-z0-9_]+__(search_code|search_history|find_symbol|find_dependents)$")
CLI_RE = re.compile(r"(?:^|[\s;&|(/])code-rag\s+(search|why|symbol|dependents)\b")
CLI_TOOL = {"search": "search_code", "why": "search_history",
            "symbol": "find_symbol", "dependents": "find_dependents"}
SHELL_RE = re.compile(r"(?:^|[\s;&|(])(?:grep|egrep|fgrep|rg|ag|find)\s")
PATH_RE = re.compile(r"(/[\w.+@~-]+(?:/[\w.+@~-]+)+\.\w+)")


def load_projects():
    try:
        with open(os.path.join(HOME, "src", "main", "backend", "rag-projects.json"), encoding="utf-8") as f:
            projects = json.load(f).get("projects", [])
    except (OSError, ValueError) as e:
        sys.exit("code-rag: cannot read rag-projects.json: %s" % e)
    dirs = []
    for p in projects:
        for d in list(p.get("roots") or []) + ([p["project_dir"]] if p.get("project_dir") else []):
            dirs.append((os.path.realpath(d).rstrip("/"), p["name"]))
    dirs.sort(key=lambda x: -len(x[0]))
    return dirs


def project_of(cwd, dirs, cache={}):
    if cwd not in cache:
        rc = os.path.realpath(cwd) if cwd else ""
        cache[cwd] = next((name for d, name in dirs if rc == d or rc.startswith(d + "/")), None)
    return cache[cwd]


def new_stat():
    st = defaultdict(int)
    st["sessions"] = set()
    return st


def result_text(block):
    c = block.get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "\n".join(x.get("text", "") for x in c if isinstance(x, dict))
    return ""


def scan(path, cutoff_iso, dirs, stats):
    pending = {}     # tool_use_id -> project, awaiting its result
    watching = []    # [project, set(paths), calls_left]
    try:
        f = open(path, encoding="utf-8", errors="replace")
    except OSError:
        return
    with f:
        for line in f:
            if '"tool_use"' not in line and not (pending and '"tool_result"' in line):
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                continue
            if rec.get("timestamp", "9") < cutoff_iso:
                continue
            content = (rec.get("message") or {}).get("content")
            if not isinstance(content, list):
                continue
            proj = project_of(rec.get("cwd", ""), dirs)
            for b in content:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "tool_result" and b.get("tool_use_id") in pending:
                    p = pending.pop(b["tool_use_id"])
                    paths = set(PATH_RE.findall(result_text(b)))
                    if paths:
                        watching.append([p, paths, FOLLOW_WINDOW])
                    else:
                        stats[p]["empty"] += 1
                    continue
                if b.get("type") != "tool_use":
                    continue
                name, inp = b.get("name", ""), b.get("input") or {}
                cmd = inp.get("command", "") if name == "Bash" else ""
                used = " ".join(str(inp.get(k, "")) for k in ("file_path", "command", "path"))
                for w in watching:
                    if w[2] > 0 and any(p in used for p in w[1]):
                        stats[w[0]]["opened"] += 1
                        w[2] = 0
                    w[2] -= 1
                watching[:] = [w for w in watching if w[2] > 0]
                if proj is None:
                    continue
                st = stats[proj]
                m = MCP_RE.match(name)
                if m:
                    st["mcp"] += 1
                    st["tool:" + m.group(1)] += 1
                    pending[b.get("id")] = proj
                elif cmd and CLI_RE.search(cmd):
                    st["cli"] += 1
                    st["tool:" + CLI_TOOL[CLI_RE.search(cmd).group(1)]] += 1
                    pending[b.get("id")] = proj
                elif name in ("Grep", "Glob") or (cmd and SHELL_RE.search(cmd)):
                    st["other"] += 1
                st["sessions"].add(path)


def main():
    days = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 30
    cutoff = time.time() - days * 86400
    cutoff_iso = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(cutoff))
    dirs = load_projects()
    stats = defaultdict(new_stat)
    files = []
    for root, _, names in os.walk(TRANSCRIPTS):
        for n in names:
            if n.endswith(".jsonl"):
                p = os.path.join(root, n)
                try:
                    if os.path.getmtime(p) >= cutoff:
                        files.append(p)
                except OSError:
                    pass
    for p in files:
        scan(p, cutoff_iso, dirs, stats)
    rows = sorted(stats.items(), key=lambda kv: -(kv[1]["mcp"] + kv[1]["cli"] + kv[1]["other"]))
    print("\nCode-RAG adoption, last %d days (%d transcripts read)\n" % (days, len(files)))
    if not rows:
        print("  no sessions found inside an indexed project.\n")
        return 0
    print("  %-22s %8s %7s %7s %9s %8s %8s" % ("project", "sessions", "mcp", "cli", "other", "share", "opened"))
    tm = tc = to = top = 0
    for name, st in rows:
        idx = st["mcp"] + st["cli"]
        total = idx + st["other"]
        share = "%.2f%%" % (100.0 * idx / total) if total else "-"
        opened = "%d/%d" % (st["opened"], idx) if idx else "-"
        print("  %-22s %8d %7d %7d %9d %8s %8s" % (name, len(st["sessions"]), st["mcp"], st["cli"],
                                                  st["other"], share, opened))
        tm += st["mcp"]; tc += st["cli"]; to += st["other"]; top += st["opened"]
    tot = tm + tc + to
    print("  %-22s %8s %7d %7d %9d %8s %8s" % ("TOTAL", "", tm, tc, to,
          "%.2f%%" % (100.0 * (tm + tc) / tot) if tot else "-",
          "%d/%d" % (top, tm + tc) if tm + tc else "-"))
    by_tool = defaultdict(int)
    for _, st in rows:
        for k, v in st.items():
            if isinstance(k, str) and k.startswith("tool:"):
                by_tool[k[5:]] += v
    if by_tool:
        print("\n  by query type: " + ", ".join("%s %d" % kv for kv in sorted(by_tool.items(), key=lambda x: -x[1])))
    print("\n  mcp/cli = index searches by route; other = grep/rg/find/Grep/Glob;")
    print("  share = index searches as a fraction of all searches;")
    print("  opened = index searches whose result was then read (next %d tool calls).\n" % FOLLOW_WINDOW)
    return 0


if __name__ == "__main__":
    sys.exit(main())
