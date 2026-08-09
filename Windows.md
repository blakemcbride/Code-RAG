# Running Code-RAG on Windows

Code-RAG was developed on Linux/macOS. Windows works, but the cleanest
path is to run the server inside **WSL2** (Windows Subsystem for Linux)
and reach it from either side. This file covers three scenarios:

| Scenario | Where the server runs | Where the agent (Claude Code / Codex) runs | Difficulty |
|---|---|---|---|
| A — Pure WSL2 | WSL2 | WSL2 | Easy. Identical to Linux. |
| B — Cross-boundary | WSL2 | Windows-native | Medium. Two MCP registrations + the path-translation header. |
| C — Pure Windows | Windows | Windows | Hard. Server runs, but MCP auto-registration breaks; no `code-rag` / `setup.sh` wrappers. |

If you're undecided, **start with A**. You can always add Windows-side
access later (scenario B). Scenario C is documented for completeness
but isn't recommended.

---

## Scenario A — pure WSL2 (recommended)

Everything runs inside the WSL2 VM. Code-RAG sees a normal Linux
environment; the user sees `Ubuntu` (or whatever distro) in a Windows
Terminal tab.

### 1. Install WSL2

From an elevated PowerShell:

```powershell
wsl --install
```

That installs WSL2 + Ubuntu by default. Reboot when asked, then open
the new Ubuntu shortcut and complete the first-run user setup.

To pick a specific distro instead:

```powershell
wsl --list --online
wsl --install -d Debian        # or Ubuntu-22.04, etc.
```

### 2. Install prerequisites inside WSL2

Inside the WSL2 shell, follow the Linux instructions in
[Setup.md](Setup.md) §0 (or the macOS subsection if you prefer
Homebrew-style; Linuxbrew works in WSL2 too). All examples below
assume Ubuntu 22.04+:

```bash
sudo apt update
sudo apt install -y postgresql postgresql-contrib postgresql-16-pgvector \
                    default-jdk curl python3 git build-essential
sudo systemctl start postgresql

# Ollama
curl -fsSL https://ollama.com/install.sh | sh
sudo systemctl start ollama
ollama pull nomic-embed-text:v1.5

# Claude Code (optional)
sudo apt install -y nodejs npm
sudo npm install -g @anthropic-ai/claude-code
```

### 3. Install and run Code-RAG inside WSL2

Same as Linux — follow [Setup.md](Setup.md) sections 1 onward:

```bash
git clone <repo-url> code-rag
cd code-rag
./setup.sh
$EDITOR src/main/backend/rag-projects.json    # point at your code
./bld start
```

Then either run `claude` from inside WSL2, or skip ahead to scenario B
below if you want to use Claude/Codex from Windows.

---

## Scenario B — server in WSL2, agent in Windows (cross-boundary)

This is the most common real-world setup: Code-RAG lives in WSL2
(because the Java stack is happier there), but your day-to-day
Claude Code or Codex CLI is the Windows-native install you already
have.

It works, with two pieces you need to wire up by hand. Code-RAG ships
with a path-translation feature specifically for this case.

### How the wiring fits together

```
   ┌────────────────────────────────────┐    ┌──────────────────────────────┐
   │              WSL2 VM               │    │       Windows host           │
   │                                    │    │                              │
   │   Code-RAG server                  │    │  Claude Code / Codex CLI     │
   │   (Tomcat) on 127.0.0.1:17080      │◀───┤  registers MCP entry         │
   │                                    │    │  with WSL2 server URL +      │
   │   Indexes:                         │    │  X-Path-Style: windows       │
   │     /home/blake/myproj   ◀── via ──┼────┤  header                      │
   │     /mnt/c/Users/blake/work        │    │                              │
   │                                    │    │  Read tool opens the         │
   │                                    │    │  translated Windows path     │
   └────────────────────────────────────┘    └──────────────────────────────┘
        bind 127.0.0.1                            connect to 127.0.0.1
                ▲                                          │
                └──────── WSL2 localhost forwarding ───────┘
                          (auto, Win10 2004+ / Win11)
```

**Two boundaries you need to bridge:**

1. **HTTP transport** — WSL2's localhost forwarder maps `127.0.0.1:17080`
   inside the VM to `127.0.0.1:17080` on the Windows host. No
   configuration needed; just verify it works (next section).

2. **Returned file paths** — when `search_code` (or `find_symbol`,
   `find_dependents`, `get_chunk`) returns a hit, the
   `absolute_path` field needs to be something the Windows-native
   `Read` tool can actually open. Code-RAG handles this with an opt-in
   header.

### 1. Confirm the server is reachable from Windows

Start Code-RAG inside WSL2:

```bash
./bld start
```

Then from PowerShell on the Windows side:

```powershell
curl http://127.0.0.1:17080/rest -Method POST `
     -ContentType 'application/json' `
     -Body '{"_method":"status","_class":"services/RAGAdmin"}'
```

You should get back a JSON `projects` array. If you get a connection
error, see [WSL2 networking gotchas](#wsl2-networking-gotchas) below.

### 2. Find your WSL2 distro name and the shared secret

From inside WSL2:

```bash
# Distro name — exactly as it appears, case-sensitive
wsl.exe --list --verbose                    # callable from inside WSL2 too
# Or from PowerShell:
#   wsl --list --verbose

# Shared secret
grep '^RAGMCPSharedSecret' src/main/backend/application.ini
```

Note the distro name (most often `Ubuntu`) — you'll need it in step 4.

### 3. Tell Code-RAG to translate paths for Windows clients

Edit `src/main/backend/application.ini` inside WSL2 and uncomment /
set:

```ini
WindowsWslDistro = Ubuntu          ; or whatever your distro is called
```

Restart Code-RAG so the new value is picked up:

```bash
./bld stop && ./bld start
```

> The `WindowsWslDistro` value is only used when a request arrives with
> the `X-Path-Style: windows` header (next step). Linux/WSL2 clients
> aren't affected — they keep getting Linux paths.

### 4. Register the MCP entry on the Windows side

The Code-RAG server has already registered itself with Claude Code /
Codex *inside WSL2* (that's `code-rag new-project`'s job). You now do
the same registration on the Windows side, pointing at the same URL
but with two extra headers.

**Claude Code (PowerShell, native Windows install):** run from inside
the Windows-side checkout of the project you indexed (so the
`.mcp.json` lands in the right tree):

```powershell
$secret = "PASTE-RAGMCPSharedSecret-HERE"
cd C:\path\to\my\project_root
claude mcp add --transport http -s project myproj `
    http://127.0.0.1:17080/rag-mcp/myproj `
    --header "X-RAG-Token: $secret" `
    --header "X-Path-Style: windows"
```

`-s project` writes the entry into `.\.mcp.json` so it's visible to
Claude Code sessions launched from anywhere under this tree — and
invisible elsewhere, which is the behavior you want. If you'd rather
have the entry visible from every Windows directory, swap
`-s project` for `-s user` (and accept the trade-off documented in
[ClaudeCode.md](ClaudeCode.md)).

Verify from inside the same tree:

```powershell
cd C:\path\to\my\project_root
claude mcp list
# myproj: http://127.0.0.1:17080/rag-mcp/myproj (HTTP) - ✓ Connected
```

**Codex CLI (Windows install):** add a section to
`%USERPROFILE%\.codex\config.toml`:

```toml
[mcp_servers.myproj]
url = "http://127.0.0.1:17080/rag-mcp/myproj"
http_headers = { "X-RAG-Token" = "PASTE-SECRET-HERE", "X-Path-Style" = "windows" }
```

The `X-Path-Style = "windows"` header is what activates the
translation. Without it, Windows-side queries would get back Linux
paths and `Read` would fail.

### 5. How paths translate

With `X-Path-Style: windows` and `WindowsWslDistro = Ubuntu`:

| Path indexed from inside WSL2 | Returned to a Windows client |
|---|---|
| `/home/blake/myproj/src/foo.java` | `\\wsl$\Ubuntu\home\blake\myproj\src\foo.java` |
| `/mnt/c/Users/blake/work/foo.java` | `C:\Users\blake\work\foo.java` |
| `/mnt/d/code/bar.py` | `D:\code\bar.py` |
| `/var/log/foo` | `\\wsl$\Ubuntu\var\log\foo` |

Two rules:
- Anything under `/mnt/<letter>/...` is a DrvFs mount of a Windows
  drive — rewritten to the drive letter directly. Fast: native NTFS
  access from Windows.
- Anything else is on the WSL2 ext4 — rewritten to the `\\wsl$\<distro>\...`
  UNC path. Slower (goes through the 9P file server), but works for
  any path inside the VM.

If your code lives on the Windows side and you want maximum
performance, put your project under `/mnt/c/Users/...` in
`rag-projects.json` so paths land in column 2 (drive letter) rather
than column 1 (UNC). Both work; the drive-letter form is just faster
for the Windows-side `Read`.

### 6. Verify end-to-end

In a Windows-side Claude Code session:

> *"Use `mcp__myproj__search_code` to find login handling."*

Claude should call `search_code`, get a hit back with an
`absolute_path` that looks like `\\wsl$\Ubuntu\home\…\Login.java` or
`C:\Users\…\Login.java`, then `Read` that path successfully.

If `Read` returns "file not found", the path style isn't being
applied — check that `claude mcp list` shows your entry with both
headers (run `claude mcp get myproj`).

### Keeping the two registrations in sync

`code-rag new-project foo /path` (inside WSL2) automatically registers
with WSL2's Claude Code / Codex. It does **not** touch the Windows-side
configs. After each `new-project`, repeat step 4 above for the new
project name on the Windows side.

`code-rag remove-project foo` similarly only deregisters on the WSL2
side. Remove the Windows-side entry manually (from inside the
project root for Claude Code, since project-scope removals are
cwd-sensitive):

```powershell
cd C:\path\to\my\project_root
claude mcp remove myproj -s project
# or edit %USERPROFILE%\.codex\config.toml and delete the section
```

If you find yourself doing this a lot, the registration is just a
one-liner per project — a small PowerShell function around
`claude mcp add` with the headers baked in keeps it ergonomic.

---

## Scenario C — pure native Windows (no WSL2)

This works for the server, breaks for the wrappers, and has a real
bug in the MCP auto-registration. Documented for completeness; not
recommended.

### What works

- `bld.cmd` — the Windows batch sibling of `bld`. Mirrors every
  bootstrap step. `bld.cmd start` builds and launches Tomcat on
  Windows using the Apache-provided `catalina.bat` script.
- Tomcat itself, PostgreSQL on Windows, Ollama on Windows. All
  native.
- All the server-side Java/Groovy code, including the MCP server.
  `Files`, `Paths`, `ProcessHandle` work cross-platform.

### What doesn't work

1. **`code-rag` and `setup.sh` are bash scripts.** PowerShell and
   cmd can't run them. You'd need Git Bash, MSYS2, or WSL — but if
   you're using WSL anyway, just use scenario A.

2. **MCP auto-registration probably fails.** When `Tasks.java` runs
   `new ProcessBuilder("claude", ...)`, Java on Windows uses
   `CreateProcess` directly, which doesn't honor `PATHEXT`. NPM-
   installed CLIs ship as `claude.cmd` / `codex.cmd` shims —
   `ProcessBuilder("claude", ...)` won't find them. The
   `isOnPath("claude")` check would also miss the `.cmd` shim. So
   `new-project` would silently skip both clients even when they're
   installed. Fixable in ~15 lines of Java if there's demand for
   first-class native Windows support; not currently implemented.

3. **`chmod 600 application.ini`** in `setup.sh` is a no-op on
   Windows filesystems. The file still gets created, just without
   POSIX permission bits.

4. **PostgreSQL on Windows** uses the Windows Services GUI,
   `pg_ctl`, or the EnterpriseDB installer's tray app — not
   `systemctl`. The Ollama installer adds a Windows service for
   you.

### If you really want to try scenario C

```cmd
:: First-run setup — these need bash. Use Git Bash:
bash setup.sh
notepad src\main\backend\rag-projects.json

:: Then native:
bld.cmd start
```

For MCP registration: do it by hand (`claude mcp add ...` from
PowerShell) since auto-registration won't fire. No
`X-Path-Style: windows` header is needed — the server's filesystem is
native NTFS, so the paths it returns are already Windows-style. The
translation feature is only relevant for WSL2-resident servers.

---

## WSL2 networking gotchas

When step 1 of scenario B fails — `curl` from Windows can't reach the
WSL2 server — work through these in order:

1. **Is Tomcat actually running inside WSL2?** From inside WSL2:
   ```bash
   ./bld status               # should say "RUNNING"
   curl http://127.0.0.1:17080/rest -X POST -H 'Content-Type: application/json' \
        -d '{"_method":"status","_class":"services/RAGAdmin"}'
   ```
   If this fails inside WSL2 first, the problem isn't the boundary.

2. **WSL version.** `wsl --list --verbose` from PowerShell — the
   `VERSION` column must say `2`. WSL1 doesn't have the localhost
   forwarder.

3. **Windows version.** Localhost forwarding works on Windows 10 build
   2004+ and all of Windows 11. Older builds: upgrade Windows.

4. **Has WSL2 lost the forwarding?** Sometimes after a long suspend or
   network change, the forwarder gets confused. From PowerShell:
   ```powershell
   wsl --shutdown
   ```
   Then re-launch WSL2 and re-start Code-RAG. Forwarders re-register
   on bind.

5. **Antivirus / firewall / VPN.** Corporate VPN clients are the most
   common cause of localhost forwarding silently breaking. Test with
   the VPN off.

6. **Tomcat not bound to 127.0.0.1.** Should not happen with stock
   Code-RAG (`bld` writes `127.0.0.1` into `server.xml`), but worth
   double-checking with `ss -tlnp` inside WSL2.

---

## Updating an existing WSL2 install when this feature lands

If you already have Code-RAG running in WSL2 and you're pulling these
changes:

1. Pull, then rebuild + restart:
   ```bash
   git pull
   ./bld stop
   ./bld -v build
   ./bld start
   ```

2. (Optional) Set `WindowsWslDistro = <yours>` in
   `src/main/backend/application.ini` if your distro isn't `Ubuntu`.
   Restart again.

3. For each project you want to use from Windows, run step 4 of
   scenario B (the manual `claude mcp add` / Codex TOML edit with
   the `X-Path-Style: windows` header).

That's it. Existing WSL2-side registrations are untouched and continue
to work without the path-translation header.

---

## See also

- [Setup.md](Setup.md) — server install + first index (Linux & macOS).
- [Running.md](Running.md) — daily operations.
- [ClaudeCode.md](ClaudeCode.md) — Claude Code MCP details (the
  manual registration in scenario B step 4 mirrors what's there).
- [Codex.md](Codex.md) — Codex MCP details, same idea.
- [RAGPlan.md](RAGPlan.md) — design reference; §6 covers the MCP
  server, which is where the path-translation lives.
