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
