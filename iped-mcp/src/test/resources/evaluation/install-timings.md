# Install timings — SC-010

**Criterion**: from a clean machine with only IPED installed, a forensic examiner with no prior
agent-integration experience reaches their first answer about the reference case in **under 15
minutes**, in each of the three harnesses (Scenario 10, FR-062).

## Status: NOT YET MEASURED

This cannot be measured from a development workstation. It needs three clean machines — or three
clean profiles — and a person who has not read the guides before, because the thing being measured
is how well the guide works for someone who does not already know the answer. Someone who wrote the
guide will always beat 15 minutes and learn nothing from doing so.

**SC-010 is unverified until this table is filled in.**

## What to run

For each harness, on a machine with only IPED installed:

1. Start a stopwatch.
2. Follow the guide, and only the guide:
   - Claude Code — `skills/claude-code/iped-forensics/install/claude-code.md`
   - Codex — `skills/codex/iped-forensics/install/codex.md`
   - OpenCode — `skills/opencode/iped-forensics/install/opencode.md`
3. Stop when the agent has answered a real question about the reference case, not when the tools
   merely appear in a list.
4. Record every point where the guide was ambiguous, wrong, or silently assumed something.

## Results

| Harness | OS | Operator experience | Time to first answer | Verdict | Notes |
|---|---|---|---|---|---|
| Claude Code | | | | | |
| Codex | | | | | |
| OpenCode (local model) | | | | | |

## Guide friction observed

Record every stumble, including ones that were recovered from quickly. A step that costs two minutes
of confusion is a defect in the guide even when the total still lands under fifteen — the next
person may not recover as fast.

| Harness | Step | What went wrong | Fix applied |
|---|---|---|---|

## Guidance parity check

Scenario 10, step 4: confirm the skill loaded in each harness is the same text.
`SkillParityTest` checks this automatically over the generated wrappers, but confirm by hand that
the harness actually loaded it — a wrapper on disk that the harness never reads is not guidance.

| Harness | Skill loaded | Identical to canonical |
|---|---|---|
| Claude Code | | |
| Codex | | |
| OpenCode | | |
