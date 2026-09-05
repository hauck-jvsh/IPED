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
it to decide where to look before you list anything. To inspect one curated set, pass its exact name
as `bookmark` to `iped_search`; it is intersected with the query, and **on its own, with no query at
all, it lists the whole bookmark** — that is the cheap way to open a bookmark. A `total_matches` in
the hundreds of thousands is a signal to narrow, not to start paging.

When you do need every item, ask for it as `*:*`. A bare `*` means the same thing to you and
something else to the parser — a wildcard over the name and text of every item, expanded term by
term — and the server will answer it, tell you it rewrote it, and the rewrite is what saved the time.
Never invent a query just to satisfy a parameter: if what you want is a bookmark, pass the bookmark
alone.

**3. Sample when the volume is high.** With a large result set, read the first page, aggregate to
understand the distribution, and refine. Do not page through ten thousand items hoping to recognize
something. If the examiner needs all of them, that is what `iped_export_artifact` is for. When the
question turns on one or two specific fields rather than on whole items, pass `fields` to
`iped_get_items`: it returns exactly the fields you name for the whole batch of ids, which is how you
read a sender, a coordinate or an EXIF tag across a result set without one call per item.

**4. Cite items in every conclusion.** Every statement of fact about the case carries the item ids
that support it. "There are messages discussing the transfer" is not a finding; "items 48213,
48219 and 48227 contain messages discussing the transfer" is. The ids are what let the examiner
open the same items in the IPED UI and check you.

**5. Never claim absence without validating vocabulary.** A field-restricted query that returns zero
has two possible causes, and they are not equivalent: the case genuinely has nothing, or you used a
field name this index does not have. **Before writing "no evidence of X was found", call
`iped_check_field` on every field you restricted on.** Field names vary between cases and between
IPED versions. This is the single most common way to produce a confident, wrong, negative finding.

## Paths belong to the server

Every path these tools take — the case you open, the destination you export to — is a path on the
**machine running the server**, which is frequently not the machine running this conversation. The
server may be on Windows while you are on Linux, with no filesystem shared between them.

A case path is therefore not something you can check. `F:\cases\operation` does not exist for you
and never will, and that is not evidence that the case is missing. **The only way to learn whether a
case path is good is to pass it to `iped_open_case` and read the answer.** Do not go looking for it
first with a directory listing, a file search or a glob: those answer a question about the wrong
machine, and a negative from them tells you nothing about the case.

This is the failure mode to watch for in yourself. You read a Windows path, notice you are on Linux,
conclude the case has moved, and start searching your own filesystem for it. Every step of that
reasoning is wrong, and none of it produces an error message — you simply never call the tool that
would have worked. When a recorded path looks foreign to your environment, that is expected: open
it.

`iped_open_case` says precisely what is wrong with a path it rejects. A local search says only that
your machine does not have it, which was already true before you asked.

**The same holds for evidence.** If this installation creates cases, `iped_process_evidence` takes a
source path and a destination path, and both are the server's, exactly like a case path. An
`E:\hds\...` that means nothing on your filesystem is not a missing disk — it is a disk on the other
machine. Pass it and read the answer. The refusal, if there is one, names where reading is permitted;
your own directory listing names nothing useful.

## Creating a case takes hours, and you do not wait for it

If `iped_process_evidence` is available at all, this installation has been configured to allow it.
Three things about it are not like the other tools:

- **It returns immediately with a `job_id` and nothing has happened yet.** Follow it with
  `iped_job_status`. Do not treat the accept as a result.
- **Progress has two parts and they answer different questions.** Counters say how far along it is;
  `phase` says what it is doing. During index commit and optimization there are no counters for
  minutes at a time — that is normal, and `measurable: false` is the tool saying so rather than
  progress having stopped. `stalled: true` is the one that means something is wrong, and even that
  has to be read together with the phase.
- **The case does not exist until the job completes.** A destination from an unfinished job is
  refused by `iped_open_case`, and that refusal is protecting you: the folder can look structurally
  complete while holding only what was processed before the run stopped, with nothing to say which
  part of the evidence is missing.

If a job fails, `iped_job_status` carries the engine's own last lines. Report the cause from those
rather than guessing from the fact of failure.

## Do not extrapolate

Report what the returned data supports and nothing beyond it.

- A truncated text says `truncated: true`. Absence of a term in a truncated excerpt is not evidence
  of its absence from the item.
- A partial result says `partial: true`. It means the time budget ran out, not that you saw
  everything. Two things follow, and the answer states both: `total_matches` is a **floor** — at
  least that many items match, possibly many more, and `total_matches_exact` is `false` — and no
  cursor is issued, because paging on from a partial page skips hits silently. A partial page is a
  sample the clock chose, not the top of the result set. Narrow the query and ask again rather than
  reporting from it.
- An absent field is absent, with a reason attached in `unavailable`. It is not an empty value, and
  it is not a fact about the world — an item with no GPS metadata is not an item that was nowhere.
- **A message with no text of its own is not an empty message.** A chat record is built by a decoder
  from a database; it has no file behind it, so there is nothing to re-extract, and what it says is
  carried in its metadata. `iped_item_text` says exactly that and names the fields — follow it to
  `iped_item_metadata` rather than reporting the conversation as empty. To read a conversation as a
  conversation, use `iped_item_tree` to get the container and take its text: it reads in order, with
  who said what.
- **A failure of the server is not a fact about the evidence.** When an answer says the server could
  not read an item, that is the server's fault and not the item's: report it as a gap in what you
  could examine, never as an item with nothing in it, and do not reach for another content tool to
  work around it — they read through the same machinery. `iped_item_metadata` answers from the index
  and is unaffected.
- A projection carries only what you asked for. When you pass `fields`, the answer lists what was
  read in `projection`; a field outside that list was never looked at and says nothing about the
  items. A field inside it that an item does not have is declared in that item's `unavailable`, with
  the reason. And a field name this case does not have does not come back quietly empty — the call is
  refused with the near names, precisely so a typo cannot become a finding of absence.
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

When the deliverable is **one item rather than a list** — the photograph, the document, the
transcription of a conversation — use `iped_export_item`. It writes the item into the server's
export folder and gives you back the path. Two things about it are worth knowing:

- **You do not choose the path or the name.** The name comes from the evidence, and the server
  decides how to write it safely. Report the path it returns; do not try to steer it.
- **`text_only` decides what the file holds.** Left alone, it exports the item's own bytes and
  checks them against the hash the case recorded — read `hash_verified` and say so, because a file
  that does not verify is not the item until someone explains why. With `text_only: true` it writes
  the extracted text instead, and nothing is truncated: this is how to obtain the whole of a text
  whose reading in this conversation stopped at the ceiling.

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
