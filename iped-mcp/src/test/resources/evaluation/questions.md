# Investigation battery — 30 questions with an answer key

Scenario 12 of [quickstart.md](../../../../../specs/001-iped-llm-integration/quickstart.md), covering
SC-001, SC-008 and SC-009.

## What this battery does and does not verify

**It verifies the retrieval half**, automatically, in `InvestigationBatteryTest`: for each question,
the query the workflow prescribes returns exactly the items the answer key names. That is what
SC-001 rests on, and it is what a regression would break silently — a change in field handling or
query rewriting turns a correct answer into an empty one without any test failing anywhere else.

**It does not verify the reasoning half.** "Zero false positives presented as a conclusion" and
"100% of conclusions carry cited items" (SC-008, SC-009) are properties of what an agent *writes*,
not of what the tools *return*. Checking them means running a live agent against the battery and
reading its answers. That run is a manual step; record its outcome in
[local-model.md](local-model.md) and in the completion report. **A green
`InvestigationBatteryTest` is not the same as a passing SC-008.**

## Answer key convention

The expected results are given as **file names**, never as item ids. Ids are assigned at processing
time and change every time the reference case is rebuilt; names are fixed by the recipe in
[../reference-case/README.md](../reference-case/README.md).

- `expect_names` — the result set must be exactly these names.
- `expect_contains` — these names must be present; others are allowed.
- `expect_count` — the exact number of matching items.
- `expect_min` — at least this many.

## The battery

```json
{
  "questions": [
    {
      "id": "Q01",
      "ask": "Which documents mention the word 'contrato'?",
      "query": "contrato",
      "expect_contains": ["contrato-servicos.pdf", "minuta-contrato.docx"],
      "note": "Bare term searches name and content. Both a name match and a content match must appear."
    },
    {
      "id": "Q02",
      "ask": "Which files are PDFs?",
      "query": "ext:pdf",
      "expect_contains": ["contrato-servicos.pdf", "relatorio-anual.pdf"],
      "note": "Extension restriction. Distinct from contentType: a mislabelled file has one and not the other."
    },
    {
      "id": "Q03",
      "ask": "Which items are recognized as JPEG images?",
      "query": "contentType:\"image/jpeg\"",
      "expect_min": 3,
      "note": "Detected media type, not extension. This is the field an agent most often gets wrong."
    },
    {
      "id": "Q04",
      "ask": "Which photographs carry GPS coordinates?",
      "query": "contentType:image* AND \"GPS Latitude\":*",
      "expect_contains": ["praia-geotag.jpg", "cidade-geotag.jpg"],
      "note": "Field name varies by parser version. The workflow checks the vocabulary first, on purpose."
    },
    {
      "id": "Q05",
      "ask": "Which files were modified during March 2024?",
      "query": "modified:[2024-03-01 TO 2024-03-31]",
      "expect_min": 2,
      "note": "Date range at second resolution."
    },
    {
      "id": "Q06",
      "ask": "Which items were deleted from the filesystem?",
      "query": "deleted:true",
      "expect_contains": ["apagado-recibo.txt"],
      "note": "Deleted entries still carry name, path and timestamps."
    },
    {
      "id": "Q07",
      "ask": "Which items were recovered by carving?",
      "query": "carved:true",
      "expect_min": 1,
      "note": "Carved items have no filesystem entry. Name and timestamps are synthesized, not recovered."
    },
    {
      "id": "Q08",
      "ask": "Which item has the hash of the known planted file?",
      "query": "hash:\"D41D8CD98F00B204E9800998ECF8427E\"",
      "expect_min": 0,
      "note": "Exact-content identification. Zero is acceptable only if the recipe's planted hash differs; the recipe fixes it."
    },
    {
      "id": "Q09",
      "ask": "Which emails were sent by ana.silva@exemplo.test?",
      "query": "\"ana.silva@exemplo.test\"",
      "expect_min": 2,
      "note": "Address as a phrase. The sender field name is discovered from an item, not assumed."
    },
    {
      "id": "Q10",
      "ask": "Which emails carry attachments?",
      "query": "category:\"E-mails\" AND hasChildren:true",
      "expect_min": 1,
      "note": "Attachments are subitems. The parent-child relation is what iped_item_tree walks."
    },
    {
      "id": "Q11",
      "ask": "Which items are attachments extracted from a container?",
      "query": "subitem:true",
      "expect_min": 3,
      "note": "Subitems come from mails and archives alike."
    },
    {
      "id": "Q12",
      "ask": "Which chat messages discuss a payment?",
      "query": "category:\"Chats\" AND pagamento",
      "expect_min": 1,
      "note": "Category plus term. Category matching expands to descendants."
    },
    {
      "id": "Q13",
      "ask": "How many items are in the case in total?",
      "query": "*:*",
      "expect_min": 40,
      "note": "The match-all form. Its total must equal the case total reported by the overview."
    },
    {
      "id": "Q14",
      "ask": "Which spreadsheets exist?",
      "query": "contentType:*spreadsheet* OR ext:xlsx OR ext:ods",
      "expect_min": 1,
      "note": "Alternation across a detected type and two extensions."
    },
    {
      "id": "Q15",
      "ask": "Which items are larger than 100 KB?",
      "query": "size:[102400 TO *]",
      "expect_min": 2,
      "note": "The field is 'size', not 'length'. Getting this wrong returns zero silently."
    },
    {
      "id": "Q16",
      "ask": "Which items have no hash computed?",
      "query": "NOT hash:*",
      "expect_min": 1,
      "note": "Negation combined with field presence. Directories typically match."
    },
    {
      "id": "Q17",
      "ask": "Which items are directories?",
      "query": "isDir:true",
      "expect_min": 2,
      "note": "Directories have no content of their own; the content tools must say so rather than return empty."
    },
    {
      "id": "Q18",
      "ask": "Which text files mention a CPF number?",
      "query": "ext:txt AND \"123.456.789-09\"",
      "expect_contains": ["dados-pessoais.txt"],
      "note": "Personal data. The report must give the count and the item, not the value."
    },
    {
      "id": "Q19",
      "ask": "Which items were created before 2023?",
      "query": "created:[* TO 2022-12-31]",
      "expect_min": 1,
      "note": "Open-ended range."
    },
    {
      "id": "Q20",
      "ask": "Which archive contains the planted document?",
      "query": "ext:zip",
      "expect_contains": ["documentos.zip"],
      "note": "The container itself; iped_item_tree lists what is inside it."
    },
    {
      "id": "Q21",
      "ask": "Which items mention both 'contrato' and 'pagamento'?",
      "query": "contrato AND pagamento",
      "expect_min": 1,
      "note": "Conjunction across content."
    },
    {
      "id": "Q22",
      "ask": "Which items mention 'contrato' but not 'rascunho'?",
      "query": "contrato NOT rascunho",
      "expect_min": 1,
      "note": "Exclusion. A query that is all-negative must still be answerable."
    },
    {
      "id": "Q23",
      "ask": "Which items match the phrase 'transferência bancária'?",
      "query": "\"transferência bancária\"",
      "expect_min": 1,
      "note": "Phrase with diacritics. Folding is a processing-time setting; both forms should match on a default case."
    },
    {
      "id": "Q24",
      "ask": "Which items begin with the word stem 'transfer'?",
      "query": "transfer*",
      "expect_min": 1,
      "note": "Prefix query."
    },
    {
      "id": "Q25",
      "ask": "Which items are in the 'Documentos' category?",
      "query": "category:\"Documentos\"",
      "expect_min": 3,
      "note": "Category expansion downward. The count must be at least the sum of its leaf categories."
    },
    {
      "id": "Q26",
      "ask": "Which items are root items of an evidence?",
      "query": "isRoot:true",
      "expect_min": 1,
      "note": "Roots have no parent; the tree tool must say so rather than return a null parent silently."
    },
    {
      "id": "Q27",
      "ask": "Which items came from the second evidence?",
      "query": "evidenceUUID:*",
      "expect_min": 1,
      "note": "Presence of the evidence field. Its values are what iped_aggregate on 'evidence' buckets."
    },
    {
      "id": "Q28",
      "ask": "Which images have no thumbnail?",
      "query": "contentType:image* AND NOT thumbnail:*",
      "expect_min": 0,
      "note": "Zero is a legitimate answer here. What must not happen is an error."
    },
    {
      "id": "Q29",
      "ask": "Which items timed out during parsing?",
      "query": "timeout:true",
      "expect_min": 0,
      "note": "A timed-out item has incomplete text. Any conclusion about its content has to say so."
    },
    {
      "id": "Q30",
      "ask": "Which items mention a bitcoin address?",
      "query": "\"1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa\"",
      "expect_min": 1,
      "note": "Crypto address planted by the recipe. Regex-tagged fields vary by version; the literal always works."
    }
  ]
}
```

## Reading the results

The verifier reports, per question, whether the expectation held. The bar from SC-001 is **≥ 90%**,
which over thirty questions means at most three may fail — and a failure has to be explained, not
absorbed. A question failing because the reference case was built differently is a recipe problem;
a question failing because a field stopped resolving is a regression.
