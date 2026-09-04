# Installing the IPED MCP server in Codex

Target: from an installed IPED to your first answer about a real case, in under 15 minutes. No
prior experience with agent integrations assumed.

## Before you start

You need two things:

1. **An installed IPED** — the folder containing `iped.jar`, `conf/` and `lib/`. Referred to below
   as `<IPED_ROOT>`.
2. **A processed case** — the IPED output folder, the one containing an `iped` subfolder. Referred
   to below as `<CASE_PATH>`.

Java comes with the IPED release, under `<IPED_ROOT>/jre`. Nothing else to install.

## Step 1 — Register the server

Open Codex's configuration file, `~/.codex/config.toml` (on Windows,
`%USERPROFILE%\.codex\config.toml`), and add:

**Windows**

```toml
[mcp_servers.iped]
command = "C:\\path\\to\\IPED\\jre\\bin\\java.exe"
args = [
  "-Diped.mcp.ipedRoot=C:\\path\\to\\IPED",
  "-cp", "C:\\path\\to\\IPED\\lib\\*",
  "iped.mcp.McpServerMain"
]
```

**Linux**

```toml
[mcp_servers.iped]
command = "/path/to/IPED/jre/bin/java"
args = [
  "-Diped.mcp.ipedRoot=/path/to/IPED",
  "-cp", "/path/to/IPED/lib/*",
  "iped.mcp.McpServerMain"
]
```

Replace the paths with your own. On Windows, backslashes inside TOML strings must be doubled, as
shown.

## Step 2 — Install the guidance

**Look for `~/.codex/skills/` first** (`%USERPROFILE%\.codex\skills` on Windows). Recent Codex builds
load skills from there, one folder each, and that is the shorter route. Older ones have no such
folder and read guidance only from `AGENTS.md`. Both routes are below; use the one your build
supports.

### If `~/.codex/skills/` exists

Drop the folder in and you are done — no pointer, no editing. `$CODEX_HOME/skills/<name>/SKILL.md`
with a `references/` beside it is exactly the layout Codex's own preinstalled skills use, and the
frontmatter this skill already carries (`name`, `description`) is the format they use.

**Windows**

```
xcopy /E /I "<IPED_ROOT>\skills\codex\iped-forensics" "%USERPROFILE%\.codex\skills\iped-forensics"
```

**Linux**

```
cp -r "<IPED_ROOT>/skills/codex/iped-forensics" ~/.codex/skills/
```

If you set `CODEX_HOME`, that is the root the skills folder hangs off — not `~/.codex`.

### If it does not

Codex reads project and user instructions from `AGENTS.md`. Put the folder anywhere readable:

```
cp -r "<IPED_ROOT>/skills/codex/iped-forensics" ~/.codex/
```

and add a pointer at the top of the `AGENTS.md` of the project you work cases in — or to
`~/.codex/AGENTS.md` for all of them:

```markdown
When working with IPED forensic cases, read ~/.codex/iped-forensics/SKILL.md before anything else.
The files it links under references/ are relative to that folder.
```

Name the folder, not just the file: without it the two references — the query syntax and the worked
workflows — are never opened, and those carry the field-name escaping rule that makes metadata
queries work.

### Pointing instead of copying

Nothing here needs a copy. If you already have the skill on disk — from a clone of the IPED source,
or from an installation you keep updated — link to it instead, and edits show up without a second
install:

```
ln -s "<IPED_ROOT>/skills/codex/iped-forensics" ~/.codex/skills/iped-forensics
```

```
mklink /J "%USERPROFILE%\.codex\skills\iped-forensics" "<IPED_ROOT>\skills\codex\iped-forensics"
```

`/J` is a directory junction and needs no administrator; `/D` does. **Confirm it took**: ask Codex
which skills it has. If a linked skill does not show up, this build is not following links — copy it,
or use the `AGENTS.md` pointer, which takes an absolute path anywhere and never depends on that.

The guidance is the same text used by every harness. That is deliberate: divergent guidance would
produce divergent analyses of the same evidence.

## Step 3 — Check it

Start Codex and ask it to list its available tools. Tools named `iped_*` should be there. Ask it for
its skills too: `iped-forensics` should be among them if you took the skills-folder route.

If the tools are not there, run the command from step 1 by hand in a terminal. The server logs its
startup diagnostics; each failure says what to fix. You can also check the surface without a harness
at all — feed it two lines and read the answer:

```
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

A healthy server answers both on stdout and puts nothing else there. Startup logging belongs on
stderr, which is what the `-Dlog4j.configurationFile` above is for.

## Step 4 — Ask something

```
Open the case at <CASE_PATH> and tell me what is in it.
```

Then a real question:

```
Find documents mentioning "contract" that were modified in 2024.
```

## What to expect on the first run

- **A warning at session open about what may be transmitted.** By default the server does not
  restrict evidence content, so item text, thumbnails and raw bytes go to OpenAI's API. If that is
  not acceptable for your material, see [opencode.md](opencode.md) — running against a local model
  keeps everything on the workstation, and it is the recommended configuration for sensitive cases.
- **Read-only by default.** Bookmarks and selection cannot be changed until an examiner enables it.
- **An audit trail.** Every call is recorded before it runs, and the trail is copied into the case
  folder automatically.

## Enabling writes

1. Open `<IPED_ROOT>/conf/McpServerConfig.txt`.
2. Set `accessMode = READ_WRITE`.
3. Restart Codex.

## Codex in WSL, case on Windows

A common setup on a Windows workstation: Codex installed inside a WSL2 distribution, the IPED
installation and the cases on the Windows side. Two things to know before anything else.

**The WSL Codex has its own `~/.codex`.** `/home/<you>/.codex` and `C:\Users\<you>\.codex` are
different directories with different `config.toml`, `AGENTS.md` and credentials. Configuring one does
nothing for the other, and that is the most common way this setup appears broken.

**The `jre/` in the release is Windows.** `jre/bin/java.exe` will not run under Linux, so you have two
options and they are not equivalent.

### Launch the Windows JVM from WSL (recommended)

WSL runs Windows executables directly. Point `command` at `java.exe` through `/mnt/c`, and give the
arguments as **Windows** paths, because the process that reads them is a Windows process:

```toml
[mcp_servers.iped]
command = "/mnt/c/path/to/IPED/jre/bin/java.exe"
args = [
  "-Diped.mcp.ipedRoot=C:/path/to/IPED",
  "-Dlog4j.configurationFile=file:///C:/path/to/IPED/conf/Log4j2ConfigurationMcp.xml",
  "-cp", "C:/path/to/IPED/lib/*",
  "iped.mcp.McpServerMain"
]
startup_timeout_sec = 30
```

Forward slashes are fine for the JVM on Windows, and TOML strings in single quotes take backslashes
raw if you prefer them. `startup_timeout_sec` matters: loading the engine configuration takes a few
seconds before the server answers.

Two things follow, and both are why this is the recommended option:

- **Every path in the tool surface stays a Windows path.** You open `D:\cases\operation`, and the
  `exportRoots` in `conf/McpServerConfig.txt` — written as Windows paths — match what the server
  checks.
- **The index is read natively.** Reading a large Lucene index through `/mnt/c` costs real time; here
  only the launch crosses the boundary.

### Or run a Linux JVM inside WSL

You supply the JVM — Java 11 through 15; the serialization library the engine loads cannot reflect on
16 and later. Then `command` is your `java`, and `-Diped.mcp.ipedRoot`, `-cp` and every case path you
ever pass become `/mnt/c/...` paths. Expect two frictions: the `exportRoots` in
`McpServerConfig.txt` no longer match the paths the server now sees, and every index read goes through
`/mnt/c`.

### What this arrangement is not

It is **not** isolation. Both options above depend on `/mnt/c`, and WSL2 mounts the Windows drives
there by default, writable, as your user — the agent can reach the case folder directly, whatever the
tool surface says. If isolation is the goal, that is the next section, and it requires
`automount.enabled=false` in `/etc/wsl.conf` to be real. You cannot have the convenience of `/mnt/c`
and the isolation at the same time.

## Running the server on another machine

Use this when the agent must not be able to reach the evidence **at all** — because it runs in a VM,
in a container, or under an account with no business touching the case folder. The server stays
beside the evidence; the harness goes wherever it is isolated; between them there is one connection,
one shared secret, and no other path to the case than the tool surface.

**Know what this protects and what it does not.** The connection carries evidence content — item
text, thumbnails, raw bytes — and it is **not encrypted**. Authentication proves the connection was
authorized; it does nothing for what travels afterwards. Use it when the traffic stays inside one
physical machine (a VM talking to its host over a forwarded loopback port) or on a network segment
you trust. Between physical machines on a shared network, the material is readable to anyone
watching the wire.

### On the machine holding the case

Put the secret somewhere the release does not ship and version control does not reach:

```powershell
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 40 | % {[char]$_}) |
    Set-Content -NoNewline D:\pericia\segredo-mcp.txt
```

```bash
head -c 30 /dev/urandom | base64 > /opt/pericia/segredo-mcp.txt
```

Then in `<IPED_ROOT>/conf/McpServerConfig.txt`:

```
transport = socket
listenAddress = 127.0.0.1
listenPort = 8737
sharedSecretFile = D:\pericia\segredo-mcp.txt
```

`127.0.0.1` keeps the port on loopback, which is what you want when the harness is a VM on this same
host reaching it through a forwarded port. Widen it only if the harness is genuinely on another
machine.

**From WSL, whether loopback is enough depends on one setting.** With `networkingMode=mirrored` in
`%USERPROFILE%\.wslconfig`, the distribution reaches the host's loopback, so `127.0.0.1` on both sides
is all you need. With the default NAT networking it is not: the server has to listen on an interface
the distribution can reach, the client has to use the host's address rather than `127.0.0.1`, and
Windows Firewall has to allow the port. Prefer mirrored — it removes two moving parts, and remember
that removing it later breaks a configuration that was working.

If no secret resolves, **the endpoint is not established** and the startup diagnostic says why.
There is no configuration in which the server listens without authentication.

### In the isolated environment

Codex launches a process and speaks stdio to it; it cannot dial a socket. So something inside the
isolated environment has to turn stdio into the connection. Two implementations ship, speaking the
same protocol to the same server:

| | Needs | Where it is |
|---|---|---|
| `bridge/iped-mcp-bridge` | Python 3.6+ | `<IPED_ROOT>/bridge/` — copy both files in |
| `iped.mcp.McpRelayMain` | a JRE and four jars | `<IPED_ROOT>/lib/` |

Prefer the bridge unless the isolated environment already has a JVM: it is two files and about five
kilobytes, against installing a second runtime to keep patched inside the environment whose whole
value is being small enough to reason about.

```toml
[mcp_servers.iped]
command = "/opt/iped-mcp/iped-mcp-bridge"
args = []

[mcp_servers.iped.env]
IPED_MCP_HOST = "192.168.5.2"
IPED_MCP_PORT = "8737"
IPED_MCP_SECRET_FILE = "/home/analyst/.config/iped-mcp/secret"
IPED_MCP_OPERATOR = "perito.silva"
```

The secret itself stays out of `config.toml` — that file is the kind of thing that ends up in a
repository, so the configuration holds a path and not the credential. `IPED_MCP_SHARED_SECRET` in the
environment works too.

`IPED_MCP_OPERATOR` is recorded as an **unverified claim**: the secret proves the connection was
authorized, not who is at the keyboard. It appears in the trail marked as such.

**Run the wrapper by hand before wiring the harness to it.** It should print
`mcp-bridge: connected to ...` on stderr and then sit waiting. That is success, and it separates "the
server is unreachable" from "the harness configuration is wrong" in one step.

If the environment already has a JVM and you would rather use the relay:

```toml
[mcp_servers.iped]
command = "java"
args = [
  "-Dlog4j.configurationFile=/path/to/conf/Log4j2ConfigurationMcp.xml",
  "-Diped.mcp.relay.host=127.0.0.1",
  "-Diped.mcp.relay.port=8737",
  "-Diped.mcp.relay.operator=perito.silva",
  "-cp", "/path/to/lib/*",
  "iped.mcp.McpRelayMain"
]
```

**That `-Dlog4j.configurationFile` is not decoration.** The other two logging configurations in
`conf/`, and Log4j's own fallback, all write to stdout — which on this process is the protocol
channel. Without the flag a log line eventually lands in the middle of the JSON-RPC stream, and the
symptom looks like a protocol bug in the server. The same flag belongs on the server's own command
line.

### Verify the separation is real and not apparent

This is the step people skip, and skipping it buys the appearance of isolation with none of it.

1. **From inside the isolated environment, confirm the case folder cannot be reached.** Not "is not
   configured" — *cannot be reached*. If you can list it, the boundary does not exist and everything
   above is decoration.

   The usual way this fails is a VM that helpfully mounts the host for you. **WSL2 mounts the host's
   drives under `/mnt/c` by default**, writable, as the host user. A WSL2 sandbox therefore hands the
   agent the whole disk unless `automount.enabled=false` is set in `/etc/wsl.conf` — it is opt-out,
   so an untouched distro reproduces exactly the problem the isolation was meant to solve. Lima with
   QEMU is opt-in: nothing of the host is visible unless declared under `mounts:`. Check its default
   `/tmp/lima` share as well.

2. **Ask the server what it is doing.** `iped_session_info` reports the transport, the endpoint, the
   declared write roots and that the channel is unprotected. Compare it against the configuration
   file and against the machine's listening ports. Where they disagree, believe the ports.

3. **Know which filesystem the paths belong to.** Every path in the tool surface — the case that is
   opened, the destination an export is written to — is a path on the **server's** machine.
   `F:\cases\operation` is meaningful over there and meaningless in the isolated environment, and
   that is correct rather than a misconfiguration. Exports land under the declared `exportRoots` on
   the server; the answer says so, and the file is over there.

   Expect the agent to have to be told this once by the skill rather than discovering it: an agent
   that reads a Windows case path while running on Linux, concludes the case is missing and starts
   searching its own filesystem never calls `iped_open_case` at all, and produces no error to explain
   the silence. The skill carries that rule; if you replace it with your own prompt, carry it too.

## If something goes wrong

| Symptom | What it means |
|---|---|
| No `iped_*` tools | The config in step 1 is wrong. Check the TOML — on Windows, doubled backslashes. |
| "The IPED installation could not be located" | `-Diped.mcp.ipedRoot` points somewhere without a `conf/` folder. |
| `NOT_A_CASE` | Wrong folder. Use the IPED output folder, not the `iped` subfolder inside it. |
| `CASE_IN_PROCESSING` | Processing has not finished. |
| `VERSION_UNSUPPORTED` | The case is from an IPED outside the supported range. |
| "audit area is not writable" | Everything is refused until fixed. Set `auditArea` in `conf/McpServerConfig.txt`. |
| `WRITE_NOT_ENABLED` | Working as designed. See "Enabling writes". |
