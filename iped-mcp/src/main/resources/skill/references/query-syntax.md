# Query syntax and field vocabulary

> **The index is the authority.** Everything in this document is a starting point. When a field name
> here disagrees with what `iped_list_fields` returns for the case in front of you, the tool is
> right. Cases processed years ago carry the vocabulary of the version that processed them, and
> nobody is going to reprocess them to match this page.

## Syntax

IPED uses Lucene query syntax, with IPED's own semantics layered on top (category expansion,
diacritic folding, mapping of content matches onto their parent item).

| Want | Write |
|---|---|
| A word anywhere in name or text | `contract` |
| An exact phrase | `"wire transfer"` |
| Both terms | `contract AND payment` |
| Either term | `contract OR invoice` |
| Excluding a term | `payment NOT test` |
| Grouping | `(contract OR invoice) AND 2024` |
| A field restriction | `ext:pdf` |
| A phrase in a field | `name:"annual report"` |
| Prefix | `trans*` |
| Single character | `te?t` |
| Fuzzy | `transfer~` |
| Numeric or date range | `size:[1000000 TO 5000000]` |
| Open-ended range | `created:[2024-01-01 TO *]` |
| Field has any value | `hash:*` |

Escape these with a backslash when you mean them literally:
`+ - && || ! ( ) { } [ ] ^ " ~ * ? : \`

A bare term with no field prefix searches **name** and **content**. Name matches are boosted, so a
file called `contract.pdf` ranks above a file that merely mentions the word.

## Field names that contain a colon

**Read this before writing any query against parser-produced metadata.** Most of a case's vocabulary
beyond the basic properties is namespaced with a colon: `p2p:fileType`, `ufed:UserID`, `image:Width`,
`dc:title`, `hashDb:status`, and script-produced names like `ai:csamDetector:status` carrying two.

The parser reads a colon as the separator between field and value, so a name has to be written with
its own colons escaped:

| Write | Not |
|---|---|
| `p2p\:fileType:"mp3"` | `p2p:fileType:"mp3"` — syntax error |
| `ufed\:UserID:12345` | `ufed:UserID:12345` — syntax error |
| `ai\:csamDetector\:status:done` | `ai:csamDetector:status:done` — syntax error |
| `p2p\:shared:true` | `p2p:shared:true` — syntax error |

Three things that trip agents here, in the order they cause trouble:

- **Quoting the name does not work.** `"p2p:fileType":"mp3"` is a syntax error, not an alternative.
  The backslash is the only form the parser accepts.
- **In JSON the backslash is itself escaped.** The tool argument has to carry `p2p\\:fileType` so the
  server receives `p2p\:fileType`. Emitting a bare `\:` inside a JSON string is invalid JSON and the
  call is rejected before it reaches the case.
- **The escape belongs only inside a query expression.** `iped_check_field`, the keys returned by
  `iped_item_fields` and the `dimension` argument of `iped_aggregate` all take the plain name.

`iped_list_fields` and `iped_item_fields` return the plain names and, when any of them need it, a
`query_form` with the spelling to paste into a query. `iped_check_field` returns `query_form` for a
single name. Use it rather than escaping by hand.

If a query fails, read the error: `QUERY_SYNTAX` and `UNKNOWN_FIELD` both carry the corrected
expression when the server can verify one against this case. **Retry with what the error gives you.**
An `UNKNOWN_FIELD` naming a field like `p2p` on a case that has `p2p:fileType` means exactly this —
the colon was eaten as the separator — and the error's `details.query_form` has the fix.

Diacritics are folded during indexing on most cases, so `Jose` matches `José`. Do not rely on it
without checking — it is a processing-time setting.

## Field vocabulary

These are the basic properties present on essentially every 4.x case. Anything beyond them —
parser-produced metadata, EXIF, message fields — varies by case and must come from
`iped_list_fields` or `iped_item_fields`.

### Identity and structure

| Field | Meaning |
|---|---|
| `id` | Item identifier, **local to the case** |
| `parentId` | Container's id |
| `parentIds` | Every ancestor id |
| `evidenceUUID` | Which evidence the item came from |
| `name` | File or item name |
| `path` | Full path within the evidence |
| `ext` | Extension |
| `type` | Normalized type |
| `contentType` | Detected media type — **not** `mediaType`, and **not** `mimeType` |
| `category` | IPED category; matching a parent category includes its descendants |
| `size` | Length in bytes — **the field is `size`, not `length`** |

### Dates

| Field | Meaning |
|---|---|
| `created` | Creation timestamp |
| `modified` | Last modification |
| `accessed` | Last access |
| `changed` | Metadata change (POSIX ctime) |
| `timeStamp` | Every timestamp the item carries, for timeline work |
| `timeEvent` | What each timestamp means |

Dates are indexed to second resolution. `created:[2024-03-01 TO 2024-03-31]` works.

### Content and integrity

| Field | Meaning |
|---|---|
| `content` | Full extracted text. Searchable, but **not listed** by `iped_list_fields` — it is the text itself, not a property |
| `hash` | Item hash |

### Flags

| Field | Meaning |
|---|---|
| `deleted` | Recovered from a deleted filesystem entry |
| `carved` | Recovered by carving, with no filesystem entry |
| `subitem` | Extracted from a container |
| `isDir` | Directory |
| `isRoot` | Root of an evidence |
| `hasChildren` | Contains other items |
| `timeout` | Parsing timed out — **its text may be incomplete** |

Flags are indexed as `true` / `false`: write `deleted:true`.

## Names that trip people up

These are the mistakes that produce a confident, wrong, negative finding. Every one of them returns
zero rather than an error.

| You may want to write | This index calls it |
|---|---|
| `mediaType`, `mimeType` | `contentType` |
| `length`, `filesize` | `size` |
| `filename` | `name` |
| `md5`, `sha1` | `hash` — the algorithm is a processing setting |
| `date`, `timestamp` | `created` / `modified` / `accessed`, or `timeStamp` |
| `isDeleted` | `deleted` |
| `label`, `tag` | bookmarks are not a query field; use `iped_list_bookmarks` |
| `p2p:fileType:"mp3"` | `p2p\:fileType:"mp3"` — see the colon section above |

**Whenever a field-restricted query returns zero, call `iped_check_field` before drawing any
conclusion from that zero.** It returns `similar` with the names this case actually has.

## Practical notes

- **Item ids are local to a case.** Id 4821 in one case and id 4821 in another are unrelated items.
  Every tool takes `case_id` alongside the id for exactly this reason.
- **Category queries expand downward.** `category:"Documents"` includes its subcategories. That is
  usually what you want; when it is not, restrict on the leaf.
- **Tree nodes are excluded automatically.** You will not see index scaffolding in results.
- **A content match maps to its item.** IPED splits long texts into fragments internally; the
  server maps a fragment hit back onto the item, so you always get items, never fragments.
