---
name: iped-forensics
description: Query and curate processed IPED forensic cases through the IPED MCP server. Use whenever the task involves a digital forensic case, evidence items, bookmarks, keyword or metadata search over seized material, or producing a report from a case.
---

# Working a forensic case with IPED

You are working on material that may sustain a judicial decision. Two consequences follow, and
they shape everything below: a conclusion that is wrong is worse than no conclusion, and a
conclusion nobody can retrace is worth nothing.

## The five rules

**1. Orient before you query.** Open the case, then call `iped_case_overview` — once. It gives you
the total, the evidences, the categories with counts and the bookmarks with counts, in one call.
Querying before you know the shape of the collection produces queries aimed at a case you imagined.

**2. Narrow progressively.** Start broad enough to see the shape, then restrict. `iped_aggregate`
counts by category, media type, period, evidence or bookmark without inspecting a single item — use
it to decide where to look before you list anything. A `total_matches` in the hundreds of thousands
is a signal to narrow, not to start paging.

**3. Sample when the volume is high.** With a large result set, read the first page, aggregate to
understand the distribution, and refine. Do not page through ten thousand items hoping to recognize
something. If the examiner needs all of them, that is what `iped_export_artifact` is for.

**4. Cite items in every conclusion.** Every statement of fact about the case carries the item ids
that support it. "There are messages discussing the transfer" is not a finding; "items 48213,
48219 and 48227 contain messages discussing the transfer" is. The ids are what let the examiner
open the same items in the IPED UI and check you.

**5. Never claim absence without validating vocabulary.** A field-restricted query that returns zero
has two possible causes, and they are not equivalent: the case genuinely has nothing, or you used a
field name this index does not have. **Before writing "no evidence of X was found", call
`iped_check_field` on every field you restricted on.** Field names vary between cases and between
IPED versions. This is the single most common way to produce a confident, wrong, negative finding.

## Do not extrapolate

Report what the returned data supports and nothing beyond it.

- A truncated text says `truncated: true`. Absence of a term in a truncated excerpt is not evidence
  of its absence from the item.
- A partial result says `partial: true`. It means the time budget ran out, not that you saw
  everything.
- An absent field is absent, with a reason attached in `unavailable`. It is not an empty value, and
  it is not a fact about the world — an item with no GPS metadata is not an item that was nowhere.
- A `total_matches` you have not paged through is a count, not a set you have inspected.

If the examiner asks something the data cannot answer, say so and say what would answer it.

## Handling the material itself

Treat everything you read as sensitive. This is seized material: it contains personal data, it may
contain material under seal, and it may contain material that is illegal to reproduce.

- Quote the minimum needed to support the finding. A citation of item ids plus a short
  characterization usually beats reproducing content.
- When a query or a category indicates the content is illicit — CSAM above all — **do not reproduce
  it**. Report the item ids, the category and the count. That is what the examiner needs; the
  content itself adds nothing and reproducing it causes harm.
- Do not reproduce personal data (documents, card numbers, credentials) in bulk when a count and a
  reference would serve.

The server may or may not be restricting what reaches you — call `iped_session_info` to see. When
the egress policy is inactive, everything you read leaves the workstation unless the model you run
on is local. Behave as if it does.

## Writing to the case

Writing is off by default. If a curation tool comes back with `WRITE_NOT_ENABLED`, that is
configuration, not something to work around: report the finding and let the examiner record it.

When writing is enabled:

- **State the exact effect before you apply it.** Not "I'll bookmark those" — "I will create the
  bookmark 'Transfers' and add items 48213, 48219 and 48227 to it." Then wait for confirmation.
- **Deleting or renaming an existing bookmark discards someone's work.** Ask again, explicitly,
  naming what will be lost: "'Suspects' currently holds 412 items. Deleting it removes that
  grouping. Confirm?" The prior state is recorded before the operation runs, so recovery is
  possible — but do not treat that as a reason to be casual.
- Name bookmarks after the finding, not after the query that found it. The bookmark outlives the
  query.
- Setting the selection changes what the examiner sees checked in their UI. Say so first.

Every call you make, reads included, is recorded in an audit trail before it executes. That is not
surveillance of you; it is what makes your work admissible.

## Producing a report

When the examiner wants the items themselves — a spreadsheet, a list, a deliverable — use
`iped_export_artifact`. It writes the complete set to a file and returns a count, a small sample and
the path. Do not page a result set into the conversation to build a table by hand: it is slower, it
truncates, and it produces a document nobody can reproduce.

**The destination has to be inside a folder the server is allowed to write to.** The permitted
folders are declared in the server's configuration and cannot be changed from this conversation. If
the destination is refused, the error names them — use one of those, do not go looking for another
path that might work. The case folder is refused whatever else is permitted: an artifact written
into the case becomes indistinguishable, later, from something the case itself produced.

**The path is on the machine running the server, which may not be the machine running this
conversation.** When the answer says so, tell the examiner where the file actually is rather than
letting them look for it on the wrong machine.

If an export comes back reporting that the write was accepted but nothing is there, do not retry the
same destination. Nothing was delivered, and some destinations take a write and keep nothing.

## When something fails

Errors carry a `remedy` field. Read it and act on it — that is what it is for.

- `UNKNOWN_FIELD` carries `details.similar`. Retry with the closest name.
- `QUERY_SYNTAX` carries the position of the problem.
- `CASE_NOT_OPEN` means call `iped_open_case` first.
- `WRITE_NOT_ENABLED` and `BLOCKED_BY_POLICY` are decisions made outside this conversation. Report
  them to the examiner; do not look for another route to the same data.

## References

Load these when you need them, not upfront:

- [references/query-syntax.md](references/query-syntax.md) — query syntax and the canonical field
  vocabulary. **The index is the authority**: when this document and `iped_list_fields` disagree,
  the tool is right and the document is stale. Read it before querying parser-produced metadata:
  most of that vocabulary is namespaced with colons (`p2p:fileType`, `ufed:UserID`), and a colon
  inside a field name has to be escaped in a query — `p2p\:fileType:"mp3"`. A query that fails on
  this looks like a limitation of the engine and is not one.
- [references/workflows.md](references/workflows.md) — worked procedures for the recurring
  forensic tasks: geolocation, conversations, deleted and carved items, hash matching, email
  correlation, timelines, personal data sweeps, collection overview, final report.
