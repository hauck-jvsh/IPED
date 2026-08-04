# Small reference case — reproducible recipe

Almost every success criterion of this feature depends on a case whose contents are **known** and
**not sensitive**. Without it, SC-001, SC-005, SC-006, SC-008 and SC-009 are not repeatably
verifiable, and the integration suites in this module skip rather than pass.

The case itself is not committed — a forensic case is large and, if it were meaningful, would not be
committable. What is versioned is this recipe plus [build-reference-case.sh](build-reference-case.sh)
and [build-reference-case.ps1](build-reference-case.ps1), so anyone can rebuild a case that the
answer key in [../evaluation/questions.md](../evaluation/questions.md) still describes.

> **Status: recipe versioned, case not yet built.** Building it requires a working IPED release with
> its native toolchain. Until someone runs the script and points the suites at the result, every
> integration suite here skips, and a skip is not a pass.

## What the case has to contain

Each line exists because a requirement needs it. Dropping one silently disables the questions that
depend on it.

| Content | Why it is there |
|---|---|
| Text and office documents with known words (`contrato`, `pagamento`, `transferência bancária`) | Keyword search, phrase search, prefix search (Q01, Q21–Q24) |
| PDFs | Extension vs. detected type, and text extraction from a non-plain format (Q02, Q03) |
| Photographs **with EXIF GPS** | Geolocation workflow; the field name varies by parser version, which is the point (Q04) |
| Photographs without GPS | So a GPS query has something to exclude |
| Files with controlled timestamps spanning several years | Date ranges, open-ended ranges, timeline (Q05, Q19) |
| At least one deleted file | `deleted:true` still carries name, path and timestamps (Q06) |
| At least one file recoverable only by carving | `carved:true` has no filesystem entry — a different claim, and reports get it wrong (Q07) |
| A file with a fixed, documented hash | Exact-content identification (Q08) |
| Emails, at least one with an attachment | Correlation by address, parent/child hierarchy (Q09–Q11) |
| A chat/message database | Conversation grouping, chronological export (Q12) |
| A ZIP archive with documents inside | Container expansion into subitems (Q11, Q20) |
| A spreadsheet | Type alternation (Q14) |
| At least two files above 100 KB | Size ranges and content truncation (Q15) |
| Nested directories | Directories have no content of their own (Q17, Q26) |
| A text file with a **synthetic** CPF and a **well-known public** bitcoin address | Regex/personal-data sweep. Both values are fabricated or public; neither identifies anyone (Q18, Q30) |
| Two separate evidence sources | Evidence aggregation, and `evidenceUUID` having more than one value (Q27) |

**Nothing in the case may be real personal data, and nothing may be illicit.** The case is meant to
be committed to a shared drive, copied between workstations and attached to bug reports. Anything
sensitive in it becomes a leak the first time someone shares it.

## Building it

1. Build IPED: `mvn clean install` at the repository root, producing
   `target/release/iped-4.3.1/`.
2. Run the generator script from this folder to lay out the source material:

   ```bash
   ./build-reference-case.sh /path/to/workdir
   ```

   ```powershell
   .\build-reference-case.ps1 -WorkDir C:\path\to\workdir
   ```

   It creates `<workdir>/source/` with the files above, deterministic content and fixed timestamps.

3. Process it with IPED:

   ```bash
   java -jar /path/to/iped-4.3.1/iped.jar -d <workdir>/source -o <workdir>/case -profile forensic
   ```

4. Point the suites at the result:

   ```bash
   mvn -pl iped-mcp test -Diped.mcp.test.referenceCase=<workdir>/case
   ```

## Keeping the answer key honest

The answer key refers to items by **file name**, never by id — ids are assigned during processing and
change on every rebuild. When you change the recipe, change
[../evaluation/questions.md](../evaluation/questions.md) in the same commit. A battery that no longer
describes the case it runs against fails for the wrong reason, and the next person spends an
afternoon on it.

## The large case, for scale

SC-002 and SC-015 need roughly 10 M items and it may be synthetic — the point is volume, not
content. Generate a deep tree of small files, process it the same way, and point at it with
`-Diped.mcp.test.largeCase=<path>`.

**Running the scale suite against the small case proves nothing.** An implementation that
materializes the whole result set passes every other suite in this module and only falls over on a
real collection. That is exactly the defect this feature was built to remove, so
`ScalePerformanceTest` skips rather than pretending, and a skip means SC-002 and SC-015 are
unverified.
