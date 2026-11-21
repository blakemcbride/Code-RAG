#!/usr/bin/env python3
"""
Live agent A/B: does Code-RAG save a real agent work?

eval/turns.py SIMULATES an agent (a fixed grep strategy against one
search_code call). This runs the real thing: each task is given to a headless
Claude Code session twice, in the project's own directory with its own
CLAUDE.md —

    with      the index available (shell commands + MCP tools, routing block)
    without   the index withheld (no MCP server, `code-rag` denied, and the
              session told it is offline so the routing block is not followed
              into a wall)

— and the transcript is read for what the turns.py model can only assume:
tool calls made, tokens spent, wall-clock, and whether the answer named a
correct file.

Both arms are read-only: only search/read tools are allowed, and any other
tool call is denied by the non-interactive permission mode. Subagents are
disallowed in both so every search is visible in one transcript. Each run is
memory-capped (systemd-run) and runs are strictly sequential. Index calls are
tagged X-RAG-Source: eval, so they stay out of the usage/adoption figures.

Cost warning: this spends real model tokens — two full agent sessions per
task.

Usage:  python3 eval/ab.py <tasks.jsonl> <project> [--runs N] [--only id,id]
                           [--model M] [--out results.json]

  tasks.jsonl lines: {"id", "kind", "task", "expect": [basename fragments]}
  A run is "correct" when its final answer contains any expected fragment.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

HOME = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(HOME, "src", "main", "backend")
READ_ONLY = ["Read", "Grep", "Glob"] + ["Bash(%s:*)" % c for c in
             ("grep", "egrep", "rg", "find", "ls", "cat", "head", "tail", "wc", "sort", "uniq", "cut")]
DENY = ["Edit", "Write", "NotebookEdit", "Agent", "Task", "WebSearch", "WebFetch"]
SUFFIX = ("\n\nThis is a read-only investigation: do not modify, create or delete anything. "
          "Finish with the path of the primary file (or files) and one sentence of explanation.")
OFFLINE = ("The Code-RAG index is offline for this session. Do not run `code-rag` commands or "
           "call its MCP tools; search with grep, rg and find instead.")
INDEX_CMD = re.compile(r"(?:^|[\s;&|(/])code-rag\s+(search|why|symbol|dependents)\b")
SHELL_SEARCH = re.compile(r"(?:^|[\s;&|(])(?:grep|egrep|rg|find)\s")


def ini(key):
    with open(os.path.join(BACKEND, "application.ini"), encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\s*" + re.escape(key) + r"\s*=\s*(.*?)\s*$", line)
            if m:
                return m.group(1).strip('"')
    sys.exit("missing %s in application.ini" % key)


def project_dir(project):
    with open(os.path.join(BACKEND, "rag-projects.json"), encoding="utf-8") as f:
        for p in json.load(f)["projects"]:
            if p["name"] == project:
                return p.get("project_dir") or p["roots"][0]
    sys.exit("unknown project: " + project)


def port():
    try:
        with open(os.path.join(HOME, "tomcat", "conf", "server.xml"), encoding="utf-8") as f:
            m = re.search(r'<Connector\s+port="(\d+)"\s+protocol="HTTP', f.read())
            return int(m.group(1)) if m else 17080
    except OSError:
        return 17080


def run(task, arm, project, cwd, model):
    cmd = ["systemd-run", "--user", "--scope", "-q", "-p", "MemoryMax=4G", "-p", "MemorySwapMax=0",
           "timeout", "900", "claude", "-p", task["task"] + SUFFIX,
           "--output-format", "stream-json", "--verbose", "--strict-mcp-config"]
    if model:
        cmd += ["--model", model]
    if arm == "with":
        mcp = {"mcpServers": {project: {
            "type": "http", "url": "http://127.0.0.1:%d/rag-mcp/%s" % (port(), project),
            "headers": {"X-RAG-Token": ini("RAGMCPSharedSecret"), "X-RAG-Source": "eval"}}}}
        cmd += ["--mcp-config", json.dumps(mcp),
                "--allowedTools"] + READ_ONLY + ["Bash(code-rag:*)", "mcp__" + project,
                "--disallowedTools"] + DENY
    else:
        cmd += ["--mcp-config", '{"mcpServers":{}}', "--append-system-prompt", OFFLINE,
                "--allowedTools"] + READ_ONLY + ["--disallowedTools"] + DENY + ["Bash(code-rag:*)"]
    env = dict(os.environ, CODE_RAG_SOURCE="eval")
    t0 = time.time()
    proc = subprocess.run(cmd, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    r = {"id": task["id"], "kind": task.get("kind"), "arm": arm, "rc": proc.returncode,
         "wall_s": round(time.time() - t0, 1), "tools": 0, "index": 0, "shell_search": 0,
         "reads": 0, "denied": 0, "answer": "", "tokens": 0, "out_tokens": 0, "cost": 0.0,
         "turns": 0, "model": None}
    for line in proc.stdout.splitlines():
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if ev.get("type") == "system" and ev.get("subtype") == "init":
            r["model"] = ev.get("model")
            r["mcp"] = [s.get("name") + ":" + str(s.get("status")) for s in ev.get("mcp_servers", [])]
        content = (ev.get("message") or {}).get("content")
        if ev.get("type") == "assistant" and isinstance(content, list):
            for b in content:
                if b.get("type") != "tool_use":
                    continue
                r["tools"] += 1
                name, cmdline = b.get("name", ""), (b.get("input") or {}).get("command", "")
                if name.startswith("mcp__"):
                    r["index"] += 1
                    r["index_mcp"] = r.get("index_mcp", 0) + 1
                elif name == "Bash" and INDEX_CMD.search(cmdline):
                    r["index"] += 1
                    r["index_cli"] = r.get("index_cli", 0) + 1
                elif name == "ToolSearch":
                    r["tool_search"] = r.get("tool_search", 0) + 1
                elif name in ("Grep", "Glob") or (name == "Bash" and SHELL_SEARCH.search(cmdline)):
                    r["shell_search"] += 1
                elif name == "Read" or name == "Bash":
                    r["reads"] += 1
        if ev.get("type") == "user" and isinstance(content, list):
            for b in content:
                if b.get("type") == "tool_result" and b.get("is_error"):
                    r["denied"] += 1
        if ev.get("type") == "result":
            u = ev.get("usage") or {}
            r["answer"] = ev.get("result") or ""
            r["out_tokens"] = u.get("output_tokens", 0)
            r["tokens"] = (u.get("input_tokens", 0) + u.get("cache_creation_input_tokens", 0)
                           + u.get("cache_read_input_tokens", 0) + u.get("output_tokens", 0))
            r["cost"] = ev.get("total_cost_usd") or 0.0
            r["turns"] = ev.get("num_turns", 0)
    r["correct"] = any(x.lower() in r["answer"].lower() for x in task["expect"])
    if not r["answer"]:
        r["stderr"] = proc.stderr[-400:]
    return r


def mean(xs):
    xs = list(xs)
    return sum(xs) / len(xs) if xs else 0.0


def report(results):
    print("\n  %-15s %-8s %5s %6s %6s %6s %9s %7s %6s  %s" % (
        "task", "arm", "tools", "index", "shell", "reads", "tokens", "cost", "secs", "correct"))
    for r in results:
        print("  %-15s %-8s %5d %6d %6d %6d %9d %7.3f %6.0f  %s" % (
            r["id"], r["arm"], r["tools"], r["index"], r["shell_search"], r["reads"],
            r["tokens"], r["cost"], r["wall_s"], "yes" if r["correct"] else "NO"))
    print("")
    for arm in ("with", "without"):
        rs = [r for r in results if r["arm"] == arm]
        if not rs:
            continue
        print("  %-8s n=%-3d correct %d/%d   mean tool calls %.1f   mean tokens %.0f   mean cost $%.3f   mean secs %.0f"
              % (arm, len(rs), sum(r["correct"] for r in rs), len(rs), mean(r["tools"] for r in rs),
                 mean(r["tokens"] for r in rs), mean(r["cost"] for r in rs), mean(r["wall_s"] for r in rs)))
    w = [r for r in results if r["arm"] == "with"]
    if w:
        print("  'with' runs that used the index at all: %d/%d" % (sum(1 for r in w if r["index"]), len(w)))
        print("  index calls by route: shell %d, MCP %d; schema lookups (ToolSearch) %d" % (
            sum(r.get("index_cli", 0) for r in w), sum(r.get("index_mcp", 0) for r in w),
            sum(r.get("tool_search", 0) for r in w)))
    print("")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tasks")
    ap.add_argument("project")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--only")
    ap.add_argument("--model")
    ap.add_argument("--out")
    a = ap.parse_args()
    tasks = [json.loads(l) for l in open(a.tasks, encoding="utf-8") if l.strip() and not l.startswith("#")]
    if a.only:
        tasks = [t for t in tasks if t["id"] in a.only.split(",")]
    cwd = project_dir(a.project)
    out = a.out or os.path.join(HOME, "eval", "ab-%s-%s.json" % (a.project, time.strftime("%Y%m%d-%H%M%S")))
    results = []
    n = 0
    for _ in range(a.runs):
        for t in tasks:
            # Alternate which arm goes first so neither systematically
            # benefits from a warm filesystem cache.
            for arm in (("with", "without") if n % 2 == 0 else ("without", "with")):
                r = run(t, arm, a.project, cwd, a.model)
                results.append(r)
                print("%s/%s: %d tool calls, %d tokens, correct=%s" % (
                    r["id"], arm, r["tools"], r["tokens"], r["correct"]), flush=True)
                with open(out, "w", encoding="utf-8") as f:
                    json.dump(results, f, indent=1)
            n += 1
    report(results)
    print("  results: " + out)


if __name__ == "__main__":
    main()
