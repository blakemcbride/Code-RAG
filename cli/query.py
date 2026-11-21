#!/usr/bin/env python3
"""
Shell front end for the Code-RAG query tools.

    code-rag search "<what the code does>"        semantic code search
    code-rag why "<why/when did X change>"        commit-history search
    code-rag symbol <Identifier>                  definitions + classified references
    code-rag dependents <file>                    who imports a file (--deps: what it imports)

WHY THIS EXISTS. The same four queries are available as MCP tools, and the
usage log showed agents almost never call them: roughly 15 searches against
some 40,000 shell greps over the same six weeks. An agent already lives in the
shell; an MCP tool whose schema must first be looked up, and which subagents
may not have at all, loses to `grep` on friction alone. This puts the index
where the agent already is. It is a thin client: it speaks the same MCP
endpoint, so the read path cannot drift from what the MCP tools return.

Output is terse plain text, not JSON: `path:start-end` lines an agent can hand
straight to Read, with a short excerpt. `--json` returns the raw envelope.

The project is taken from the current directory (the configured root or
project_dir that contains it). Use --project NAME to override, or --all to
search every indexed project.

Exit status: 0 results, 1 no results, 2 usage or configuration error,
3 server not running.
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

HOME = os.environ.get("CODE_RAG_HOME") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(HOME, "src", "main", "backend")
DEFAULT_PORT = 17080


def die(msg, code=2):
    print("code-rag: " + msg, file=sys.stderr)
    sys.exit(code)


def ini_value(key):
    try:
        with open(os.path.join(BACKEND, "application.ini"), encoding="utf-8") as f:
            for line in f:
                m = re.match(r"\s*" + re.escape(key) + r"\s*=\s*(.*?)\s*$", line)
                if m:
                    return m.group(1).strip('"')
    except OSError:
        pass
    return None


def http_port():
    """The port Tomcat was last started on (bld stamps it into server.xml)."""
    try:
        with open(os.path.join(HOME, "tomcat", "conf", "server.xml"), encoding="utf-8") as f:
            m = re.search(r'<Connector\s+port="(\d+)"\s+protocol="HTTP', f.read())
            if m:
                return int(m.group(1))
    except OSError:
        pass
    return DEFAULT_PORT


def load_projects():
    try:
        with open(os.path.join(BACKEND, "rag-projects.json"), encoding="utf-8") as f:
            return json.load(f).get("projects", [])
    except (OSError, ValueError) as e:
        die("cannot read rag-projects.json: %s" % e)


def project_for_cwd(projects):
    """Project whose root or project_dir contains the cwd; the deepest match wins."""
    cwd = os.path.realpath(os.getcwd())
    best, best_len = None, -1
    for p in projects:
        dirs = list(p.get("roots") or [])
        if p.get("project_dir"):
            dirs.append(p["project_dir"])
        for d in dirs:
            rd = os.path.realpath(d)
            if (cwd == rd or cwd.startswith(rd.rstrip("/") + "/")) and len(rd) > best_len:
                best, best_len = p["name"], len(rd)
    return best


def call(project, tool, arguments):
    token = ini_value("RAGMCPSharedSecret")
    if not token:
        die("RAGMCPSharedSecret is not set in application.ini")
    url = "http://127.0.0.1:%d/rag-mcp/%s" % (http_port(), project)
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": tool, "arguments": arguments}}).encode()
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "X-RAG-Token": token,
        "X-RAG-Source": os.environ.get("CODE_RAG_SOURCE", "cli"),
    })
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            reply = json.load(r)
    except urllib.error.HTTPError as e:
        die("server answered HTTP %d for project '%s'" % (e.code, project))
    except (urllib.error.URLError, OSError):
        die("the Code-RAG server is not running. Tell the user ('code-rag start' brings it up) "
            "and use grep for now.", 3)
    if "error" in reply:
        die(str(reply["error"].get("message", reply["error"])))
    result = reply.get("result", {})
    text = "\n".join(c.get("text", "") for c in result.get("content", []) if c.get("type") == "text")
    if result.get("isError"):
        die(text or "tool error")
    try:
        return json.loads(text)
    except ValueError:
        die(text or "unparseable reply")


def excerpt(text, max_lines):
    lines = [ln.rstrip() for ln in (text or "").splitlines()]
    while lines and not lines[0].strip():
        lines.pop(0)
    cut = lines[:max_lines]
    out = ["   | " + ln for ln in cut]
    if len(lines) > max_lines:
        out.append("   | ...")
    return out


def tag(h, all_projects):
    return "[%s] " % h["project"] if all_projects and h.get("project") else ""


def show_search(env, args):
    hits = env.get("hits", [])
    if not hits:
        print("no results")
        return 1
    for i, h in enumerate(hits, 1):
        loc = h.get("absolute_path") or h.get("path", "?")
        if h.get("start_line"):
            loc += ":%s-%s" % (h["start_line"], h.get("end_line", h["start_line"]))
        syms = h.get("symbols") or ([h["symbol"]] if h.get("symbol") else [])
        line = "%d. %s%s" % (i, tag(h, args.all), loc)
        if syms:
            shown = ", ".join(x if len(x) <= 40 else x[:37] + "..." for x in syms[:4])
            shown += ", ..." if len(syms) > 4 else ""
            line += "   (" + shown + ")"
        if h.get("stale"):
            line += "   [STALE: file changed since it was indexed]"
        print(line)
        if args.lines > 0:
            print("\n".join(excerpt(h.get("content"), args.lines)))
    return 0


def show_history(env, args):
    commits = env.get("commits", [])
    if not commits:
        print("no results")
        return 1
    for i, c in enumerate(commits, 1):
        rev = str(c.get("rev", "?"))
        rev = rev[:10] if c.get("vcs") == "git" else "r" + rev
        print("%d. %s%s  %s  %s  [%s]" % (i, tag(c, args.all), rev,
                                        str(c.get("committed_at", ""))[:10],
                                        c.get("author", ""), c.get("repo", "")))
        print("   " + (c.get("subject") or "").strip())
        body = (c.get("body") or "").strip()
        if body and args.lines > 0:
            print("\n".join(excerpt(body, args.lines)))
        files = [f for f in (c.get("files_changed") or "").splitlines() if f]
        if files:
            print("   files: " + ", ".join(files[:8]) + (" (+%d more)" % (len(files) - 8) if len(files) > 8 else ""))
    return 0


def show_symbol(env, args):
    defs, refs = env.get("definitions", []), env.get("references", [])
    if not defs and not refs:
        print("no results")
        return 1
    if defs:
        print("defined:")
        for d in defs:
            scope = (d.get("scope") + ".") if d.get("scope") else ""
            print("  %s%s:%s   %s %s%s%s" % (tag(d, args.all), d.get("absolute_path") or d.get("path"),
                                           d.get("line", "?"), d.get("kind", ""), scope,
                                           d.get("name", ""), d.get("signature") or ""))
    for kind, label in (("caller", "called from:"), ("test", "tests:"), ("doc", "docs:")):
        group = [r for r in refs if r.get("kind") == kind]
        if not group:
            continue
        print(label)
        for r in group:
            where = " in " + r["in_symbol"] if r.get("in_symbol") else ""
            print("  %s%s:%s-%s%s" % (tag(r, args.all), r.get("absolute_path") or r.get("path"),
                                     r.get("start_line", "?"), r.get("end_line", "?"), where))
    other = [r for r in refs if r.get("kind") not in ("caller", "test", "doc")]
    if other:
        print("other mentions: %d" % len(other))
    total = env.get("reference_count")
    if total and total > len(refs):
        print("(%d of %d references shown; raise -k for more)" % (len(refs), total))
    return 0


def show_dependents(env, args):
    res = env.get("results", [])
    what = "imported by" if env.get("direction", "dependents") == "dependents" else "imports"
    if not res:
        print("no results (%s %s: none found)" % (env.get("path", ""), what))
        return 1
    print("%s %s %d file(s):" % (env.get("path", ""), what, len(res)))
    for r in res:
        loc = r.get("absolute_path") or r.get("path") or r.get("target", "?")
        if r.get("line"):
            loc += ":%s" % r["line"]
        note = "" if r.get("confidence") == "exact" else "   [name match only, unverified]"
        print("  %s%s%s" % (tag(r, args.all), loc, note))
    return 0


def main():
    ap = argparse.ArgumentParser(prog="code-rag", add_help=True,
                                 description="Query the local Code-RAG index from the shell.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p, kdefault):
        p.add_argument("-k", type=int, default=kdefault, help="number of results (default %d)" % kdefault)
        p.add_argument("--project", help="project name (default: the one containing the current directory)")
        p.add_argument("--all", action="store_true", help="search every indexed project")
        p.add_argument("--json", action="store_true", help="print the raw JSON envelope")

    s = sub.add_parser("search", help="find code by what it does")
    s.add_argument("query", nargs="+")
    s.add_argument("--lang", help="language filter (java, groovy, javascript, ...)")
    s.add_argument("--path", help="path prefix relative to the repo root")
    s.add_argument("--repo", help="limit to one configured root")
    s.add_argument("-n", "--lines", type=int, default=12, help="excerpt lines per hit (0 = paths only; default 12)")
    common(s, 5)

    h = sub.add_parser("why", help="search commit history for why/when something changed")
    h.add_argument("query", nargs="+")
    h.add_argument("--repo", help="limit to one configured root")
    h.add_argument("-n", "--lines", type=int, default=8, help="commit-body lines per hit (default 8)")
    common(h, 5)

    y = sub.add_parser("symbol", help="where a symbol is defined, called and tested")
    y.add_argument("name")
    common(y, 20)

    d = sub.add_parser("dependents", help="which files import a file (what breaks if it changes)")
    d.add_argument("path")
    d.add_argument("--deps", action="store_true", help="reverse: what this file imports")
    common(d, 50)

    args = ap.parse_args()

    if args.all:
        project = "_all"
    else:
        projects = load_projects()
        project = args.project or project_for_cwd(projects)
        if not project:
            die("this directory is not inside an indexed project. Use --project NAME or --all. Indexed: "
                + ", ".join(sorted(p["name"] for p in projects)))

    if args.cmd == "search":
        a = {"query": " ".join(args.query), "k": args.k}
        if args.lines > 0:
            a["max_chars"] = max(400, args.lines * 110)
        for key, val in (("language", args.lang), ("path_prefix", args.path), ("repo", args.repo)):
            if val:
                a[key] = val
        tool, show = "search_code", show_search
    elif args.cmd == "why":
        a = {"query": " ".join(args.query), "k": args.k}
        if args.repo:
            a["repo"] = args.repo
        tool, show = "search_history", show_history
    elif args.cmd == "symbol":
        a, tool, show = {"symbol": args.name, "limit": args.k}, "find_symbol", show_symbol
    else:
        a = {"path": args.path, "limit": args.k, "direction": "dependencies" if args.deps else "dependents"}
        tool, show = "find_dependents", show_dependents

    env = call(project, tool, a)
    if args.json:
        print(json.dumps(env, indent=2))
        return 0
    return show(env, args)


if __name__ == "__main__":
    sys.exit(main())
