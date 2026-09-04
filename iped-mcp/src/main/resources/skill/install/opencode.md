# Installing the IPED MCP server in OpenCode — with a local model

**This is the recommended configuration for real casework**, and the reason is not preference.

By default the server does not restrict what evidence content reaches the model. That is a
deliberate scope decision: restricting content by default would cripple the tool for the ordinary
case. The consequence is equally deliberate — with a hosted model, item text, thumbnails and raw
bytes leave the workstation and reach a third party. For seized material that can include personal
data, material under seal, and material that is illegal to transmit.

Running against a **local model** removes the problem at its root: the content never leaves the
machine. The server is built to work under that constraint — every error carries what is needed to
correct it, so a smaller local model can drive the tools without frontier-model reasoning.

If you are working real seized material, use this guide.

## Before you start

1. **An installed IPED** — the folder containing `iped.jar`, `conf/` and `lib/`. Referred to below
   as `<IPED_ROOT>`.
2. **A processed case** — the IPED output folder containing an `iped` subfolder. Referred to below
   as `<CASE_PATH>`.
3. **A local model runtime** — Ollama or LM Studio, with a model pulled. A mid-size
   instruction-tuned model with solid tool-calling is enough; you do not need the largest one that
   fits.

Java comes with the IPED release. Nothing else to install.

## Step 1 — Point OpenCode at your local model

In `~/.config/opencode/opencode.json` (on Windows,
`%USERPROFILE%\.config\opencode\opencode.json`):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    "ollama": {
      "npm": "@ai-sdk/openai-compatible",
      "options": { "baseURL": "http://localhost:11434/v1" },
      "models": { "qwen2.5-coder:14b": { "name": "Qwen 2.5 Coder 14B" } }
    }
  },
  "model": "ollama/qwen2.5-coder:14b"
}
```

Use whatever model you actually pulled. The important part is that `baseURL` points at a service on
`localhost`.

Confirm before continuing: `ollama list` should show your model, and
`curl http://localhost:11434/v1/models` should answer.

## Step 2 — Register the server

In the same `opencode.json`, add the `mcp` block:

**Windows**

```json
{
  "mcp": {
    "iped": {
      "type": "local",
      "command": [
        "C:\\path\\to\\IPED\\jre\\bin\\java.exe",
        "-Diped.mcp.ipedRoot=C:\\path\\to\\IPED",
        "-cp", "C:\\path\\to\\IPED\\lib\\*",
        "iped.mcp.McpServerMain"
      ],
      "enabled": true
    }
  }
}
```

**Linux**

```json
{
  "mcp": {
    "iped": {
      "type": "local",
      "command": [
        "/path/to/IPED/jre/bin/java",
        "-Diped.mcp.ipedRoot=/path/to/IPED",
        "-cp", "/path/to/IPED/lib/*",
        "iped.mcp.McpServerMain"
      ],
      "enabled": true
    }
  }
}
```

## Step 3 — Install the guidance

**Windows**

```
xcopy /E /I "<IPED_ROOT>\skills\opencode\iped-forensics" "%USERPROFILE%\.config\opencode\iped-forensics"
```

**Linux**

```
cp -r "<IPED_ROOT>/skills/opencode/iped-forensics" ~/.config/opencode/
```

Then reference it from `~/.config/opencode/AGENTS.md`:

```markdown
When working with IPED forensic cases, read ~/.config/opencode/iped-forensics/SKILL.md before
anything else. The files it links under references/ are relative to that folder.
```

Name the folder, not just the file: without it the two references — the query syntax and the worked
workflows — are never opened, and those carry the field-name escaping rule that makes metadata
queries work.

**The copy is not required.** The pointer takes any absolute path, so it can name the installation you
already keep updated — `<IPED_ROOT>/skills/opencode/iped-forensics/SKILL.md` — and then there is one
copy on the machine instead of two drifting apart.

The text is identical to what the other harnesses load. Divergent guidance would produce divergent
analyses of the same evidence, which is why there is one canonical source and thin wrappers.

## Step 4 — Check it

Start OpenCode and ask it to list its tools. Tools named `iped_*` should be there.

Verify the model is genuinely local: stop your Ollama service and ask a question. It should fail. If
it answers, OpenCode is falling back to a hosted provider and your content is leaving the machine —
fix that before opening a real case.

## Step 5 — Ask something

```
Open the case at <CASE_PATH> and tell me what is in it.
```

Then:

```
Find documents mentioning "contract" that were modified in 2024.
```

## Working with a smaller model

The tools are built for this, but a few habits help:

- **One question at a time.** A local model handles a focused request far better than a compound
  one.
- **Let it self-correct.** When it asks for a field this case does not have, the error comes back
  with the near names attached and the model usually retries correctly on its own. Give it the
  chance before intervening.
- **Point it at a workflow when it wanders.** "Follow the geolocation workflow in
  references/workflows.md" is a cheap, effective correction.
- **Keep sessions short.** Close and reopen between lines of inquiry rather than accumulating a long
  context.

## Enabling writes

1. Open `<IPED_ROOT>/conf/McpServerConfig.txt`.
2. Set `accessMode = READ_WRITE`.
3. Restart OpenCode.

## Tightening egress even further

With a local model, content already stays on the workstation. If you want the server to enforce it
rather than relying on configuration staying correct, edit `conf/McpServerConfig.txt`:

```
egressPolicyActive = true
egressAllowedClasses = metadata, text
```

That blocks thumbnails and raw bytes entirely, and every block is recorded in the audit trail with
the item and the rule. The restriction is applied at the server boundary, so the agent cannot get
around it by choosing a different tool.

You can also restrict by category — useful for material that should never be rendered at all:

```
egressRestrictedCategories = Child Pornography
```

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

OpenCode cannot dial a socket. It knows two kinds of MCP server: `local`, which it launches and
speaks stdio to, and `remote`, which is an HTTP URL. So something inside the isolated environment has
to turn stdio into the connection. Two implementations ship, speaking the same protocol to the same
server:

| | Needs | Where it is |
|---|---|---|
| `bridge/iped-mcp-bridge` | Python 3.6+ | `<IPED_ROOT>/bridge/` — copy both files in |
| `iped.mcp.McpRelayMain` | a JRE and four jars | `<IPED_ROOT>/lib/` |

Prefer the bridge unless the isolated environment already has a JVM: it is two files and about five
kilobytes, against installing a second runtime to keep patched inside the environment whose whole
value is being small enough to reason about.

Copy `bridge/mcp-bridge.py` and `bridge/iped-mcp-bridge` in, put the secret in a file only the
harness account can read, and point OpenCode at the wrapper:

```json
{
  "mcp": {
    "iped": {
      "type": "local",
      "command": ["/opt/iped-mcp/iped-mcp-bridge"],
      "enabled": true,
      "timeout": 180000
    }
  }
}
```

with the environment carrying the rest:

```sh
IPED_MCP_HOST=host.lima.internal
IPED_MCP_PORT=8737
IPED_MCP_SECRET_FILE=/home/analyst/.config/iped-mcp/secret   # chmod 600
IPED_MCP_OPERATOR=perito.silva                               # optional
```

The secret stays out of `opencode.json` on purpose: that file is the kind of thing that ends up in a
repository. The configuration holds a path; the credential does not.

`IPED_MCP_OPERATOR` is recorded as an **unverified claim** — the secret proves the connection was
authorized, not who is at the keyboard. It appears in the trail marked as such.

**Run the wrapper by hand before wiring the harness to it.** It should print
`mcp-bridge: connected to ...` on stderr and then sit waiting. That is success, and it separates "the
server is unreachable" from "the harness configuration is wrong" in one step instead of leaving you
to guess which.

If the environment already has a JVM and you would rather use the relay:

```json
"command": [
  "java",
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
symptom looks like a protocol bug in the server rather than a logging setting. The same flag belongs
on the server's own command line, for the same reason.

### A worked example: Lima with QEMU

A concrete recipe, because "put the harness in a VM" hides the few decisions that determine whether
the isolation is real.

```yaml
# iped-agent.yaml
#
# Deliberately NOT based on template://ubuntu-24.04: that template pulls in
# _default/mounts, which mounts the host home directory into the guest.
base:
- template:_images/ubuntu-24.04

vmType: qemu
cpus: 4
memory: "8GiB"

# The isolation boundary. Nothing of the host filesystem is visible in here.
# Do not add a mount for convenience: a mount is a second path to the evidence,
# and a second path is one the audit trail does not record.
mounts: []

ssh:
  # Do not copy the operator's personal keys into the guest.
  loadDotSSHPubKeys: false
```

```sh
limactl start --name=iped-agent iped-agent.yaml
```

The guest reaches the host through the user-mode network gateway, which Lima publishes as
`host.lima.internal`. QEMU maps that gateway to the **host's loopback**, so the server can stay on
`listenAddress = 127.0.0.1` and still be reachable from the VM — nothing is exposed to the network at
all. That is the configuration to aim for.

Two consequences to know before relying on it:

- The gateway reaches **every** service listening on the host's loopback, not only the MCP port. If
  the workstation runs other loopback services, the isolated environment can reach those too.
  Restrict outbound traffic from the guest if that matters to you.
- The VM has ordinary outbound network access. It isolates the *filesystem*, not egress. Where the
  evidence goes after the agent reads it is still decided by where the model runs.

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
| No `iped_*` tools | The `mcp` block is wrong. Run the command by hand and read the error. |
| The agent says the case folder does not exist | It is checking the isolated environment's filesystem. Case paths belong to the server. |
| Log lines appear inside the JSON-RPC stream | `-Dlog4j.configurationFile=<install>/conf/Log4j2ConfigurationMcp.xml` is missing. |
| `opencode run` produces nothing and never ends | It is waiting on stdin. Non-interactively, redirect it: `opencode run "..." < /dev/null`. |
| Bridge prints nothing at all, not even an error | It connected but the handshake was never answered — something else is on that port. |
| Answers still work with Ollama stopped | OpenCode is using a hosted provider. Fix before opening real cases. |
| "The IPED installation could not be located" | `-Diped.mcp.ipedRoot` points somewhere without a `conf/` folder. |
| `NOT_A_CASE` | Wrong folder. Use the IPED output folder, not the `iped` subfolder inside it. |
| `CASE_IN_PROCESSING` | Processing has not finished. |
| `VERSION_UNSUPPORTED` | The case is from an IPED outside the supported range. |
| "audit area is not writable" | Everything is refused until fixed. Set `auditArea` in `conf/McpServerConfig.txt`. |
| Model loops on the same failing query | Paste it the `remedy` from the error, or name the workflow to follow. |
