# Contract — Campos Lucene introduzidos

**Scope**: campos novos escritos no índice por `YaraScanTask` (via `IItem.setExtraAttribute(...)` lidos pela `IndexTask` já existente).

**Princípio I**: estes nomes de campo são **imutáveis** após o primeiro release que os introduz; qualquer mudança futura exige ciclo de deprecação em `ReleaseNotes.txt`.

**Histórico**: a v1.0 (rev-1..rev-3) escrevia também os campos `yara:rule` (lista agregada de identificadores `namespace/name` casados) e `yara:matches` (JSON de auditoria com offsets/meta/hex completo). Ambos foram **removidos na rev-5 (2026-05-27)** por serem redundantes com os campos `yara:match:<namespace>/<name>` per-rule introduzidos na rev-4. Consumidores que dependiam do JSON `yara:matches` (notadamente o bloco estruturado YARA no HTML report do `HTMLReportTask`) foram simplificados para usar os campos per-rule + `yara:tag`. O custo aceito é perder a informação fina de offset/meta/hex no relatório HTML; o ganho é um modelo de dados consistente com o `RegexTask` (`Regex:<categoria>` por categoria).

---

## Campos

### `yara:tag`

| Atributo | Valor |
|---|---|
| Tipo Lucene | `StringField` (não tokenizado) |
| Multi-valor | **Sim** |
| Indexed | Sim |
| Stored | Sim |
| Term Vector | Não |
| Analyzer | nenhum |
| Cardinalidade típica | 1–10 valores por item casado |
| Exemplo | `apt`, `malware`, `windows`, `ransomware` |

**Read path**: faceta cross-regra no painel de metadados do `iped-app` — agrupa itens por tag (`apt`, `malware`, etc.) independente de qual regra casou. Suporta filtro `yara:tag:apt`. Único campo que sobrevive como agregado depois da rev-5; `yara:rule` foi removido porque a lista de regras casadas pode ser inferida pelo conjunto de campos `yara:match:*` presentes no doc.

---

### `yara:match:<namespace>/<name>`

| Atributo | Valor |
|---|---|
| Tipo Lucene | `StringField` (não tokenizado) |
| Multi-valor | **Sim** |
| Indexed | Sim |
| Stored | Sim |
| Term Vector | Não |
| Analyzer | nenhum (`StringField` bypassa o `AppAnalyzer` — Princípio I preservado) |
| Cardinalidade típica | 1–50 valores únicos por regra por item; um campo por regra casada |
| Exemplo de nome de campo | `yara:match:apt28/apt28_loader_dropper`, `yara:match:suspicious_strings/VMWare_Detection` |
| Exemplo de valores | `[hello, world, MZ\x90\x00...]` (textos decodificados misturados com hex pra binário) |

**Naming**: o sufixo após o prefixo `yara:match:` é o **identificador completo da regra**, no formato `<namespace>/<name>` — mesmo formato que era usado nos valores do antigo `yara:rule`. Caracteres permitidos: o que YARA-X aceita em identificadores de regra/namespace (letras ASCII, dígitos, `_`, `/`).

**Valores**: cada valor é um matched-string distinto, codificado por `YaraHighlightSupport.decodeHexForFacet`:
- Texto ASCII imprimível trimmed quando todo o `hex` (do `MatchedString`) decodifica em ASCII printable (`0x20..0x7E` + `\t`/`\n`/`\r`).
- Lowercase hex como fallback caso contrário (binário puro).

Deduplicação por valor dentro de uma regra (mesma string casando em offsets distintos vira UM valor). Cap implícito pelo `YaraConfig.matchHexMaxBytes` aplicado no scanner (matches grandes vêm com hex truncado).

**Read path**: usado pelo painel de metadados como faceta dedicada por regra — mesmo UX do `Regex:CPF`/`Regex:EMAIL`. Selecionar um valor (a) filtra a galeria, e (b) torna o valor selecionado um termo de highlight no viewer de texto via o branch literal de `MetadataPanel.getHighlightTerms()` (junto com `Regex:*` e `NamedEntityTask.NER_PREFIX`).

---

## Write contract

- A `YaraScanTask` grava os campos via `IItem.setExtraAttribute(<chave>, <lista>)`. A `IndexTask` (já existente) converte cada elemento da lista em um valor multi-valorado do campo Lucene de mesmo nome.
- Itens sem matches: **nenhum** campo `yara:*` é gravado.
- Itens com matches mas sem tags (regras sem `: tag1 tag2`): `yara:tag` **não** é gravado; os campos `yara:match:*` continuam.
- Itens com matches mas sem matched-strings (regras puramente baseadas em `condition` que não capturam strings): nenhum campo `yara:match:<id>` é gravado (lista vazia → skip).
- Modo `--yara-only`: ao reaplicar o catálogo, todos os campos `yara:*` do doc são **substituídos integralmente** via `IndexWriter.updateDocuments(term, docs)` — implementação no `IndexTask` que detecta `isAlreadyCommited && cmdArgs.isYaraOnly()` (FR-011).

## Read contract

- Consumidores **DEVEM** tolerar a ausência total dos campos (item nunca escaneado, sem matches, ou feature desabilitada).
- Consumidores **NÃO PODEM** assumir ordem específica dos elementos em `yara:tag` ou em `yara:match:<id>` (Lucene multi-valor não preserva ordem de inserção; v1 ordena lexicograficamente mas leitores não dependem dessa garantia).
- Consumidores **PODEM** descobrir quais regras casaram um item enumerando os field names do doc que começam com `yara:match:` — esse é o caminho substituto para o removido `yara:rule`.
