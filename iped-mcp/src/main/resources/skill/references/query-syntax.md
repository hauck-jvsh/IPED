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
