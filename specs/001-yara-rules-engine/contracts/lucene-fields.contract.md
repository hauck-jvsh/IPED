# Contract — Campos Lucene introduzidos

**Scope**: campos novos escritos no índice por `YaraScanTask` (via `IItem.setExtraAttribute(...)` lidos pela `IndexTask` já existente).

**Princípio I**: estes nomes de campo são **imutáveis** após o primeiro release que os introduz; qualquer mudança futura exige ciclo de deprecação em `ReleaseNotes.txt`.

---

## Campos

### `yara:rule`

| Atributo | Valor |
|---|---|
| Tipo Lucene | `StringField` (não tokenizado) |
| Multi-valor | **Sim** |
| Indexed | Sim |
| Stored | Sim |
| Term Vector | Não |
| Analyzer | nenhum (`StringField` bypassa o `AppAnalyzer` — Princípio I preservado) |
| Cardinalidade típica | 1–20 valores por item casado |
| Exemplo | `apt28/apt28_loader_dropper`, `formbook/formbook_packer_v3` |

**Read path**: usado pelo painel de filtros do `iped-app` (categorias/metadados) para faceta e filtro por igualdade. Suporta filtro `yara:rule:apt28/apt28_loader_dropper`.

---

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

**Read path**: idem `yara:rule`, mas a granularidade é por tag (faceta secundária).

---

### `yara:matches`

| Atributo | Valor |
|---|---|
| Tipo Lucene | `StoredField` (apenas armazenado) |
| Multi-valor | **Não** (um único JSON por item) |
| Indexed | **Não** |
| Stored | Sim |
| Term Vector | Não |
| Analyzer | n/a |
| Tamanho típico | 1–10 KB; up bound proporcional a `matchHexMaxBytes × Σ strings` |
| Encoding | UTF-8 (JSON) |

**Read path**: lido sob demanda pela view de detalhe do item e pelo `HTMLReportTask`. Estrutura formal:

```json
{
  "engineVersion": "yara-4.5.0",
  "scannedBytes": 32768,
  "items": [
    {
      "rule": "apt28_loader_dropper",
      "namespace": "apt28",
      "tags": ["apt", "windows"],
      "meta": {
        "author": "Florian Roth",
        "severity": "high"
      },
      "strings": [
        { "id": "$s1", "offset": 4096, "hex": "4d5a900003000000", "truncated": false },
        { "id": "$re1", "offset": 8192, "hex": "554e495f4944", "truncated": true }
      ]
    }
  ]
}
```

**Mandatory fields**:
- `engineVersion` (string, `^yara-\d+\.\d+\.\d+$`)
- `scannedBytes` (integer, ≥ 0)
- `items` (array, ≥ 1; ausente/vazio NÃO ocorre — items sem matches não recebem este campo)
- Cada `items[i]`:
  - `rule` (string, sem `/`)
  - `namespace` (string, sem `/`)
  - `tags` (array de strings; possivelmente vazio)
  - `meta` (objeto string→string; possivelmente vazio)
  - `strings` (array; possivelmente vazio se a regra casou via condition sem capturar strings)
    - Cada `strings[j]`:
      - `id` (string, começa com `$`)
      - `offset` (integer, ≥ 0)
      - `hex` (string hex lowercase sem espaços)
      - `truncated` (boolean)

**Optional fields** (apenas adições futuras compatíveis):
- `items[i].engine` (sub-objeto livre)
- `items[i].score` (float)
- (qualquer outro futuro — leitores devem ser tolerantes a chaves desconhecidas)

---

## Write contract

- A `YaraScanTask` grava os três campos via `IItem.setExtraAttribute(<chave>, <valor ou lista>)`. A `IndexTask` (já existente) converte para o `Document` Lucene.
- Itens sem matches: **nenhum** dos três campos é gravado (data-model §5 invariantes).
- Modo `--yara-only`: ao reaplicar o catálogo, os três campos são **substituídos integralmente** (FR-011). Implementação: `IndexWriter.updateDocument(term, doc)` onde `term = <id-único-do-item>`.

## Read contract

- Consumidores **DEVEM** tolerar a ausência total dos três campos (item nunca escaneado, sem matches, ou feature desabilitada).
- Consumidores **DEVEM** tolerar campos novos opcionais no JSON `yara:matches` (forward-compat).
- Consumidores **NÃO PODEM** assumir ordem específica dos elementos em `yara:rule`/`yara:tag` (multi-valor Lucene não preserva ordem de inserção em geral — embora a v1 ordene determinísticamente, leitores não dependem dessa garantia).
