# Installing the IPED MCP server in Claude Code

Target: from an installed IPED to your first answer about a real case, in under 15 minutes. No
prior experience with agent integrations assumed.

## Before you start

You need two things, and nothing else:

1. **An installed IPED.** The folder you unpacked, the one containing `iped.jar`, `conf/` and
   `lib/`. Write that path down — it is referred to below as `<IPED_ROOT>`.
2. **A processed case.** The output folder from a completed IPED run: the one that contains an
   `iped` subfolder. Referred to below as `<CASE_PATH>`.

You do **not** need to install Java separately. The IPED release ships its own runtime under
`<IPED_ROOT>/jre`.

## Step 1 — Register the server

In a terminal, from any folder:

**Windows**

```
claude mcp add iped -- "<IPED_ROOT>\jre\bin\java.exe" -Diped.mcp.ipedRoot="<IPED_ROOT>" -cp "<IPED_ROOT>\lib\*" iped.mcp.McpServerMain
```

**Linux**

```
claude mcp add iped -- "<IPED_ROOT>/jre/bin/java" -Diped.mcp.ipedRoot="<IPED_ROOT>" -cp "<IPED_ROOT>/lib/*" iped.mcp.McpServerMain
```

Substitute your real path for `<IPED_ROOT>` in both places. Keep the quotes: forensic installations
are frequently under a path with spaces in it.

## Step 2 — Install the skill

Copy the skill folder into your Claude Code skills directory:

**Windows**

```
xcopy /E /I "<IPED_ROOT>\skills\claude-code\iped-forensics" "%USERPROFILE%\.claude\skills\iped-forensics"
```

**Linux**

```
cp -r "<IPED_ROOT>/skills/claude-code/iped-forensics" ~/.claude/skills/
```

`~/.claude/skills/` makes it available everywhere. A `.claude/skills/` inside a project scopes it to
that project instead — useful when the case work lives in one folder.

**If you would rather not duplicate it**, link to the installation you already keep updated, and
edits show up without a second install:

```
ln -s "<IPED_ROOT>/skills/claude-code/iped-forensics" ~/.claude/skills/iped-forensics
```

```
mklink /J "%USERPROFILE%\.claude\skills\iped-forensics" "<IPED_ROOT>\skills\claude-code\iped-forensics"
```

`/J` is a directory junction and needs no administrator; `/D` does. Confirm it took by running
`/skills` — if a linked skill does not appear, copy it instead.

The skill is what teaches the agent to work the case with forensic discipline — cite items, validate
field names before claiming absence, confirm before writing. Without it the tools still work, and
the answers are worse.

## Step 3 — Check it

Start Claude Code and run `/mcp`. You should see `iped` listed as connected.

If it is not, run the command from step 1 by hand in a terminal. The server logs its startup
diagnostics; each failure says what to fix.

## Step 4 — Ask something

```
Open the case at <CASE_PATH> and tell me what is in it.
```

The agent will open the case, call the overview, and describe the collection: totals, evidences,
dominant categories, time span. That is your first answer.

Then try a real question:

```
Find documents mentioning "contract" that were modified in 2024.
```

## What to expect on the first run

- **A warning at session open about what may be transmitted.** Read it. By default the server does
  not restrict evidence content, which means item text, thumbnails and raw bytes go to Anthropic's
  API. If that is not acceptable for your material, see
  [opencode.md](opencode.md) — running against a local model keeps everything on the workstation,
  and it is the recommended configuration for sensitive cases.
- **Read-only by default.** The agent cannot create bookmarks or change the selection until an
  examiner enables it. See below.
- **An audit trail.** Every call, reads included, is recorded before it runs. The trail is written
  to your workstation and copied into the case folder automatically.

## Enabling writes

Editing bookmarks and the selection is off by default and is deliberately outside the agent's
reach. To turn it on:

1. Open `<IPED_ROOT>/conf/McpServerConfig.txt`.
2. Set `accessMode = READ_WRITE`.
3. Restart Claude Code.

With writes on, the agent states the exact effect and waits for your confirmation before applying
anything, and destructive operations record their prior state before they run.

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

Claude Code launches a process and speaks stdio to it; it cannot dial a socket. So something inside
the isolated environment has to turn stdio into the connection. Two implementations ship, speaking
the same protocol to the same server:

| | Needs | Where it is |
|---|---|---|
| `bridge/iped-mcp-bridge` | Python 3.6+ | `<IPED_ROOT>/bridge/` — copy both files in |
| `iped.mcp.McpRelayMain` | a JRE and four jars | `<IPED_ROOT>/lib/` |

Prefer the bridge unless the isolated environment already has a JVM: it is two files and about five
kilobytes, against installing a second runtime to keep patched inside the environment whose whole
value is being small enough to reason about.

```
export IPED_MCP_HOST=192.168.5.2
export IPED_MCP_PORT=8737
export IPED_MCP_SECRET_FILE=$HOME/.config/iped-mcp/secret    # chmod 600
export IPED_MCP_OPERATOR=perito.silva                        # optional

claude mcp add iped -- /opt/iped-mcp/iped-mcp-bridge
```

The secret goes in a file or in the environment, never on the command line — shell history and
project configuration both outlive the session.

`IPED_MCP_OPERATOR` is recorded as an **unverified claim**: the secret proves the connection was
authorized, not who is at the keyboard. It appears in the trail marked as such.

**Run the wrapper by hand before wiring the harness to it.** It should print
`mcp-bridge: connected to ...` on stderr and then sit waiting. That is success, and it separates "the
server is unreachable" from "the harness configuration is wrong" in one step.

If the environment already has a JVM and you would rather use the relay:

```
claude mcp add iped -- java -Dlog4j.configurationFile=/path/to/conf/Log4j2ConfigurationMcp.xml -Diped.mcp.relay.host=127.0.0.1 -Diped.mcp.relay.port=8737 -Diped.mcp.relay.operator=perito.silva -cp "/path/to/lib/*" iped.mcp.McpRelayMain
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
| `iped` not listed in `/mcp` | The command in step 1 is wrong. Run it by hand and read the error. |
| "The IPED installation could not be located" | `-Diped.mcp.ipedRoot` points somewhere without a `conf/` folder. |
| `NOT_A_CASE` | You pointed at the wrong folder. Use the IPED output folder, the one containing `iped`, not the `iped` subfolder itself. |
| `CASE_IN_PROCESSING` | Processing has not finished. Wait for it. |
| `VERSION_UNSUPPORTED` | The case was produced by an IPED outside the supported range. |
| "audit area is not writable" | Every operation is refused until this is fixed. Set `auditArea` in `conf/McpServerConfig.txt` to a folder you can write to. |
| `WRITE_NOT_ENABLED` | Working as designed. See "Enabling writes". |
