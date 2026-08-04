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
