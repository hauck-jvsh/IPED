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
When working with IPED forensic cases, follow ~/.config/opencode/iped-forensics/SKILL.md.
```

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

## If something goes wrong

| Symptom | What it means |
|---|---|
| No `iped_*` tools | The `mcp` block is wrong. Run the command by hand and read the error. |
| Answers still work with Ollama stopped | OpenCode is using a hosted provider. Fix before opening real cases. |
| "The IPED installation could not be located" | `-Diped.mcp.ipedRoot` points somewhere without a `conf/` folder. |
| `NOT_A_CASE` | Wrong folder. Use the IPED output folder, not the `iped` subfolder inside it. |
| `CASE_IN_PROCESSING` | Processing has not finished. |
| `VERSION_UNSUPPORTED` | The case is from an IPED outside the supported range. |
| "audit area is not writable" | Everything is refused until fixed. Set `auditArea` in `conf/McpServerConfig.txt`. |
| Model loops on the same failing query | Paste it the `remedy` from the error, or name the workflow to follow. |
