# MCP bridge for the isolated environment

Two files that let a harness running in an isolated environment reach an IPED MCP
server that is running somewhere else, beside the evidence.

```
opencode ──stdio──▶ iped-mcp-bridge ──TCP──▶ McpServerMain
 (isolated env)      (isolated env)          (evidence workstation)
```

Copy both files into the isolated environment. They are the only IPED artifacts
that have to go in there — the bridge never opens a case, never reads evidence
and never writes an artifact. It moves bytes.

## Requirements

Python 3.6 or later, and nothing else. No third-party packages, no JRE.

There is an equivalent Java implementation, `iped.mcp.McpRelayMain`, in
`lib/iped-mcp-*.jar`. Use whichever matches what the isolated environment already
has. They speak the same protocol to the same server; the Java one needs a JRE
plus four jars in there, this one needs an interpreter that is already present on
every Linux image.

## Use

```sh
export IPED_MCP_HOST=host.lima.internal      # where the server listens
export IPED_MCP_PORT=8737
export IPED_MCP_SECRET_FILE=$HOME/.config/iped-mcp/secret   # mode 600
export IPED_MCP_OPERATOR=silva               # optional, recorded UNVERIFIED

./iped-mcp-bridge
```

It should print `mcp-bridge: connected to ...` on stderr and then wait. That is
success: it is now waiting for the harness to speak JSON-RPC on stdin.

Then point the harness at `iped-mcp-bridge` as the command it launches. The
server-side and harness-side configuration for each supported harness is in the
skill's install guides, under "Running the server on another machine".

## Two things that will bite

**The secret goes in the environment or in a file, never in the harness
configuration.** A harness config file is the kind of thing that ends up in a
repository. This is why the bridge reads `IPED_MCP_SECRET_FILE` — the config then
holds a path, not a credential.

**Nothing may print to stdout.** On this process stdout is the protocol channel
back to the harness. If you extend the script, diagnostics go to stderr; one
stray `print()` corrupts the session in a way that looks like a server bug.

The same trap applies to the server and the Java relay, where a logging
configuration that targets stdout has the same effect. That is what
`conf/Log4j2ConfigurationMcp.xml` exists to prevent.
