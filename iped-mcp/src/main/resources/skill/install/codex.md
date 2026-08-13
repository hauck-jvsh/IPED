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

Codex reads project and user instructions from `AGENTS.md`. Copy the skill's content in:

**Windows**

```
xcopy /E /I "<IPED_ROOT>\skills\codex\iped-forensics" "%USERPROFILE%\.codex\iped-forensics"
```

**Linux**

```
cp -r "<IPED_ROOT>/skills/codex/iped-forensics" ~/.codex/
```

Then add a pointer at the top of the `AGENTS.md` of the project you work cases in — or to
`~/.codex/AGENTS.md` for all of them:

```markdown
When working with IPED forensic cases, follow ~/.codex/iped-forensics/SKILL.md.
```

The guidance is the same text used by every harness. That is deliberate: divergent guidance would
produce divergent analyses of the same evidence.

## Step 3 — Check it

Start Codex and ask it to list its available tools. Tools named `iped_*` should be there.

If they are not, run the command from step 1 by hand in a terminal. The server logs its startup
diagnostics; each failure says what to fix.

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
