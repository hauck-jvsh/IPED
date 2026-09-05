# Forensic workflows

Worked procedures for the tasks that recur. Each one is a sequence, not a template to fill in —
adapt it, but do not skip the vocabulary checks.

---

## Collection overview

*"What is in this case?"* — always the first thing you do.

1. `iped_open_case` with the folder path.
2. `iped_case_overview` with the returned `case_id`. Read the totals, the evidences, the categories
   and the bookmarks.
3. `iped_aggregate` on `contentType` to see the file-type distribution, and on `period` to see the
   time span the material covers.

Report the shape before the examiner asks a narrower question: total items, how many evidences,
which categories dominate, which period the material spans. It usually reframes their question.

---

## Keyword investigation

1. `iped_search` with the term. Read `total_matches` first, before the items.
2. Large total: `iped_aggregate` with the same query on `category` to see where the hits sit, then
   restrict — `contract AND category:"Documents"`.
3. Small total: read the snippets. They show why each item matched, which is usually enough to tell
   relevant from incidental without opening anything.
4. `iped_get_items` for a batch of the promising ones, `iped_item_text` for the few that matter. When
   what you need is one or two specific fields rather than the whole item, pass `fields` — it returns
   just those, for the whole batch.
5. Cite ids in the conclusion.

If a term produces zero, try it as a fragment (`transfer*`), check spelling variants, and consider
that the material may be in another language before concluding it is absent.

---

## Geolocation

1. `iped_check_field` on `GPS Latitude`, `geo:lat`, or whatever the case uses — **look first**, the
   names vary a lot by parser and version. `iped_list_fields` and grep the result mentally for
   anything with `lat`, `lon`, `gps` or `geo`.
2. Query for presence: `<latitude-field>:*` gives everything geotagged.
3. `iped_aggregate` on `category` over that query to see what kind of items carry coordinates —
   usually photos, sometimes app databases.
4. `iped_item_metadata` on candidates for the actual coordinates.
5. Combine with dates when the question is "where was this device on date X":
   `<latitude-field>:* AND created:[2024-03-01 TO 2024-03-31]`.

Report coordinates with the item that carries them. A coordinate without its source item is not
evidence.

---

## Conversations

1. Find the chats: `iped_aggregate` on `category` and look for chat/message categories, or query
   `category:"Chats"` — the exact name varies.
2. `iped_item_fields` on one message item. This is the fastest way to learn what this case calls
   conversation, sender and recipient.
3. Query by theme within the chats: `category:"Chats" AND "the term"`. Then `iped_get_items` with
   `fields` set to those sender and recipient names, over the ids the query returned: one call gives
   you who spoke to whom across the whole set, instead of one call per message.
4. `iped_item_tree` on a conversation item to see its messages, or on a message to see its
   conversation.
5. **To read a conversation, take the text of the container, not of each message.** A message record
   is produced by a decoder from a database and has no file behind it, so `iped_item_text` on one
   correctly reports that it has no text of its own and names the metadata fields that carry what it
   says. The container reads as a whole — in order, with who said what — which is what you need to
   report anyway. Use the message records for citation and for filtering by sender, date or content.
6. For the deliverable: `iped_export_artifact` with `group_by_conversation: true` — it writes the
   messages grouped by conversation, chronological, with sender and recipient identified.

Do not summarize a conversation from a handful of messages read out of order. Get the ordering
right first; a chat read out of sequence reverses who said what.

---

## Deleted and carved items

These are different things and the difference matters when you write it up.

- `deleted:true` — the filesystem entry was deleted; the entry still describes the file.
- `carved:true` — recovered by signature from unallocated space; **there is no filesystem entry**,
  so name, path and timestamps are absent or synthesized, not recovered.

1. `iped_aggregate` on `category` with `deleted:true`, then again with `carved:true`.
2. Query within them: `carved:true AND category:"Images"`.
3. When reporting a carved item, say it was carved. A carved file has no reliable timestamp and no
   reliable original path, and a report that presents one as if it did is wrong.

---

## Hash matching

1. `iped_search` with `hash:<value>`. Hashes are indexed as-is; case may matter, so try both if the
   first returns nothing.
2. For a list of hashes, query them in one expression: `hash:(<h1> OR <h2> OR <h3>)`.
3. `iped_check_field` on `hash` if it returns zero — some cases store hashes under algorithm-named
   fields instead.
4. A hash hit is an exact-content match. Report it as such; it is the strongest identification the
   case offers.

---

## Email correlation

1. `iped_item_fields` on one email item to learn the field names this case uses for sender and
   recipient.
2. Query by address: `<from-field>:"person@example.com"`, and the same for the recipient field.
3. `iped_aggregate` on `period` over that query to see the correspondence over time. For the
   correspondents themselves, `iped_get_items` with `fields` set to the sender and recipient names
   reads them for the whole result set in one call.
4. `iped_item_tree` on an email to see its attachments — attachments are subitems, and they are
   frequently the point.
5. Cite the email and its attachments together.

---

## Timeline

1. `iped_aggregate` on `period` to see the distribution across the whole case.
2. Narrow to the period of interest with a range query on `created` or `modified`.
3. Combine with a category or a term to answer "what happened around date X".
4. Watch the timestamp semantics: `created` on a copied file is the copy's creation, not the
   original's. Say which timestamp you are using.

Carved items have no reliable timestamps. Exclude them (`carved:false`) when the answer depends on
time, or flag them separately.

---

## Personal data sweep

IPED's regex task tags documents, cards, crypto addresses and phone numbers during processing.

1. `iped_list_fields` and look for regex or entity fields — the naming varies by version.
2. Query for presence on the relevant field.
3. `iped_aggregate` on `category` to see where the hits concentrate.
4. **Report counts and item ids, not the values.** A report that lists three thousand card numbers
   in the clear is itself a data breach. Give the count, the item ids and a characterization; the
   examiner can extract the values from the artifact if they need them.

---

## Final report from a bookmark

The end of a normal investigation cycle.

1. `iped_list_bookmarks` to confirm the bookmark and its count.
2. `iped_export_artifact` with `bookmark`, the format the examiner wants, and a destination
   **outside the case folder**.
3. Report back what came back: the count, the path, and the sample. Do not paste the rows.
4. If the count is not what the examiner expected, that is the finding to raise — before they open
   the file, not after.

For a chat report, add `group_by_conversation: true`.
