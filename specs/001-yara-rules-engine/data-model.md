# Phase 1 — Data Model

**Feature**: YARA Rules Engine para IPED
**Date**: 2026-05-19

Documento das entidades introduzidas pela feature, suas relações com o modelo existente do IPED, regras de validação e estado persistido. Sem código — apenas modelo. Detalhes de schema concreto (nomes de campos Lucene, chaves de `.properties`) estão em `contracts/`.

---

## Visão geral

```text
                        ┌──────────────────────────────┐
                        │       YaraConfig             │  (Configurable<UTF8Properties>)
                        │  - enabled                   │
                        │  - ruleDirectories           │
                        │  - maxFileSizeBytes          │
                        │  - perItemTimeoutMs          │
                        │  - scanAllItems              │
                        │  - fastMode                  │
                        │  - matchHexMaxBytes          │
                        └──────────────┬───────────────┘
                                       │ aponta para
                                       ▼
                        ┌──────────────────────────────┐
                        │        Ruleset               │ (1)
                        │  - sourceFiles[]             │
                        │  - compiledRulesPtr          │   (YRX_RULES* handle)
                        │  - failedRules[]             │
                        │  - engineVersion             │
                        └──────────────┬───────────────┘
                                       │ contém 0..N
                                       ▼
                        ┌──────────────────────────────┐
                        │        YaraRule              │
                        │  - namespace                 │
                        │  - name                      │
                        │  - tags[]                    │
                        │  - meta{}                    │
                        │  - identifier ⟵ namespace/name │
                        └──────────────┬───────────────┘
                                       │ casa com 0..N
                                       ▼
                        ┌──────────────────────────────┐
                        │        YaraMatch             │
                        │  - rule (YaraRule.identifier)│
                        │  - tags[]                    │
                        │  - strings[]                 │   (matched-string detail)
                        │     - id ($s1, $re1, ...)    │
                        │     - offset                 │
                        │     - hex                    │
                        │     - truncated              │
                        └──────────────┬───────────────┘
                                       │ atribuído a 1
                                       ▼
                        ┌──────────────────────────────┐
                        │     IItem (existente)        │
                        │  + yara:rule[]   (indexado)  │  ← Lucene multi-valor
                        │  + yara:tag[]    (indexado)  │  ← Lucene multi-valor
                        │  + yara:matches  (stored)    │  ← Lucene JSON serializado
                        └──────────────────────────────┘
```

---

## Entidades

### 1. `YaraConfig` (em memória, persistido em texto)

Configurable instanciado pelo `ConfigurationManager`. Representa o estado configurado da feature para um perfil/caso.

| Campo | Tipo | Validação | Default |
|---|---|---|---|
| `enabled` | boolean | — | `false` |
| `ruleDirectories` | List<Path> | cada path existe e é diretório; permitido vazio (feature efetivamente desligada) | `[]` |
| `maxFileSizeBytes` | long | > 0; aceita sufixos `K`/`M`/`G` na string | `262144000` (250 MiB) |
| `perItemTimeoutMs` | int | > 0; superior a 100 | `30000` |
| `scanAllItems` | boolean | — | `false` |
| `fastMode` | boolean | — | `true` |
| `matchHexMaxBytes` | int | > 0; ≤ 65536 | `256` |

**Lifecycle**: carregado no startup do `Manager`; congelado durante a execução de um caso. Não muda em runtime.

**Origem**: `iped-app/resources/config/conf/YaraConfig.txt` + override por perfil (`profiles/*/conf/YaraConfig.txt`). Schema textual em `contracts/YaraConfig.txt.contract.md`.

---

### 2. `Ruleset` (em memória — não persistido em disco do caso)

Container do estado compilado da engine YARA-X para uma execução.

| Campo | Tipo | Validação | Notas |
|---|---|---|---|
| `sourceFiles` | List<Path> | extensão `.yar` ou `.yara`; legíveis em UTF-8 | Adicionados ao compiler via `yrx_compiler_new_namespace` + `yrx_compiler_add_source_with_origin`. |
| `compiledRulesPtr` | handle nativo (`YRX_RULES*`) | não-nulo após init | Liberado em `finish()` via `yrx_rules_destroy`. |
| `failedRules` | List<FailedRule> | — | Cada entrada: `{file, line, reason}`. Extraída do JSON retornado por `yrx_compiler_errors_json` após cada `add_source_with_origin` falhar. Logado uma vez no init; exposto via métrica `yara.compile.failed`. |
| `engineVersion` | String | `^yara-x-\S+$` | Montada a partir da versão pinned em `tools/yara-x/README.md` (o C API do YARA-X não expõe versão programaticamente). Persistido em cada match (R-05) para auditoria. |

**Lifecycle**: singleton por execução do `Manager`. Construído uma única vez no primeiro `init()` por worker (lock estático). Destruído no `finish()` quando o último worker termina.

**Regras de unicidade**:
- O **namespace** de cada arquivo é o seu basename sem extensão (ex.: `apt28.yar` → `apt28`), definido via `yrx_compiler_new_namespace(compiler, "apt28")` antes do `add_source`.
- Identificador exposto = `namespace/rule_name`. Duas regras com mesmo nome em arquivos diferentes coexistem.
- Duas regras com mesmo namespace+nome dentro de um único arquivo YARA — o compilador YARA-X rejeita com erro específico; a regra é descartada via fluxo de FR-005.

**Formatos não aceitos na v1**: `.yarc` (formato do YARA clássico) e o formato serializado próprio do YARA-X (`yrx_rules_serialize`). Decisão consciente — ver Clarifications Q3 revisada e research §R-01.

---

### 3. `YaraRule` (em memória — derivado do Ruleset)

Vista de uma regra individual dentro do ruleset compilado. Usada apenas para enriquecer o match com tags e metadata.

| Campo | Tipo | Origem | Notas |
|---|---|---|---|
| `namespace` | String | nome do arquivo (sem extensão) | Imutável após compile. |
| `name` | String | bloco `rule X { ... }` | Imutável. |
| `tags` | List<String> | declaração `: tag1 tag2` da regra | Lista ordenada, sem duplicatas. |
| `meta` | Map<String, String> | bloco `meta:` | Strings/inteiros/booleanos serializados como string. Limite de 32 entradas para evitar explosão. |
| `identifier` | String | computado: `namespace + "/" + name` | Chave em índice Lucene `yara:rule`. |
| `isPrivate` | boolean | declaração `private rule X` | Se `true`, matches **não** são expostos na UI nem no índice. |
| `isGlobal` | boolean | declaração `global rule X` | Comportamento padrão YARA (afeta avaliação de outras regras); não exposto na UI separado. |

**Transições de estado**: nenhuma — regras são imutáveis durante a execução do caso.

---

### 4. `YaraMatch` (persistido por item no índice)

Resultado da aplicação de uma `YaraRule` a um `IItem`. Cada item pode ter zero ou mais matches.

| Campo | Tipo | Validação | Persistência |
|---|---|---|---|
| `rule` | String (`identifier`) | `namespace/name` válido | `yara:rule` Lucene (multi-valor, indexado, armazenado) |
| `namespace` | String | derivado de `rule` | inferido no read-time |
| `tags` | List<String> | herdadas da `YaraRule` | `yara:tag` Lucene (multi-valor, indexado, armazenado), **deduplicado** entre matches do mesmo item |
| `meta` | Map<String, String> | da `YaraRule` | dentro do JSON `yara:matches` |
| `strings` | List<MatchedString> | ordenadas por `offset` ascending | dentro do JSON `yara:matches` |

#### 4.1 `MatchedString` (subentidade)

| Campo | Tipo | Validação | Notas |
|---|---|---|---|
| `id` | String | formato YARA: `$identifier` ou `$identifier_NN` | Ex.: `$mz_header`, `$re1_3`. |
| `offset` | long | ≥ 0, < `IItem.length` | Offset em bytes **relativo ao início do stream do item**. |
| `hex` | String hex (lowercase, sem espaços) | comprimento ≤ `2 * matchHexMaxBytes` | Bytes brutos do trecho casado. Quando `length > matchHexMaxBytes`, prefixado e marcado. |
| `truncated` | boolean | — | `true` se o match real era maior que `matchHexMaxBytes`. |

**Regra de ordenação determinística** (Princípio IV):
- Matches por item ordenados por: `(namespace asc, rule asc)`.
- `strings` dentro de cada match: `(id asc, offset asc)`.

---

### 5. `IItem` — extensões (entidade existente, sem renomeação)

A feature **adiciona** três propriedades ao item; nenhuma propriedade existente é alterada.

| Propriedade Lucene | Tipo | Fonte | Comportamento na UI/Report |
|---|---|---|---|
| `yara:rule` | multi-valor `String`, indexado | `YaraMatch.rule` por match | Faceta no painel de metadados (FR-008); contagem por valor. |
| `yara:tag` | multi-valor `String`, indexado | união das tags dos matches do item | Faceta auxiliar; permite filtrar "todos os itens com tag `apt`". |
| `yara:matches` | `String` (JSON), stored, não indexado | serialização do conjunto completo de `YaraMatch` do item | Renderizado no card de detalhe e no HTML report (FR-010). |

**Mutações suportadas**:
- **Insert**: durante `YaraScanTask.process(item)` no fluxo normal. Conteúdo escrito uma única vez por item, logo antes da `IndexTask`.
- **Replace**: no modo `--yara-only` (FR-011, rev-2). O pipeline normal é re-executado sobre o `-d` original; ao chegar no `IndexTask`, itens cujo `trackId` já está commitado são detectados (`SkipCommitedTask.isAlreadyCommited(...)`) e a escrita usa `IndexWriter.updateDocuments(new Term(IndexItem.TRACK_ID, trackId), iterable)` em vez de `addDocuments(...)`. Isso apaga atomicamente **todos** os docs com aquele `trackId` (parent + fragmentos de conteúdo) e adiciona o bloco novo — os campos `yara:*` refletem o catálogo atual; demais campos são repopulados do mesmo IItem fresco que veio do `DataSourceReader` + Parsing, garantindo schema-consistency com a primeira ingestão. Detalhe arquitetural: [research.md §R-08](research.md) (a v1 standalone via `YaraRerunRunner` reconstruía `IItem` a partir do índice e foi rejeitada por incompatibilidade de schema).

**Invariantes**:
- Se a feature está desabilitada (`enabled=false`), nenhum dos três campos é gravado (FR-013).
- Se o item foi pulado (`scanAllItems=false` e o item não é elegível, ou ultrapassa limites), nenhum dos três campos é gravado.
- Se o item foi escaneado mas sem matches, **também** nenhum dos três campos é gravado (não criar entrada vazia — economia de índice e UI mais limpa).

---

## Relações com o modelo existente

| Entidade existente | Como é tocada |
|---|---|
| `iped.data.IItem` | Recebe três novos campos via `setExtraAttribute(...)` — não há mudança de interface, só uso adicional. |
| `iped.properties.ExtraProperties` | Constantes novas (`YARA_RULE`, `YARA_TAGS`, `YARA_MATCH_DETAIL`) — adições puras. |
| `iped.engine.task.AbstractTask` | Subclasse nova `YaraScanTask` — sem mudança no contrato. |
| `iped.engine.config.Configurable` | Implementação nova `YaraConfig` — sem mudança no contrato. |
| `iped.engine.config.ConfigurationManager` | Recebe `YaraConfig` via mecanismo já existente de discovery. |
| `iped.engine.task.index.IndexTask` | **Não é modificada**. O documento é atualizado via `IItem.getExtraAttributes()` que a `IndexTask` já lê. |
| `iped.engine.task.HTMLReportTask` | Recebe um novo template fragment opcional para listar matches; carregado se `yara:matches` presente no item. |
| `BasicProps`, `IndexItem`, `AppAnalyzer` | **Não são tocados** (Princípio I). |

---

## Decisões de modelagem não-óbvias

1. **Por que `yara:tag` separado de `yara:rule`?** Permite ao perito perguntar "todos os itens com regras com tag `apt`" sem precisar enumerar regras. Tag em YARA é uma faceta semântica padronizada — vale expor.
2. **Por que `meta` só dentro do JSON, não como campo indexado?** Metadata YARA é livre (cada autor inventa chaves). Indexar gera explosão de campos no Lucene. Quem quiser filtrar por `meta.severity=high` pode fazê-lo via busca textual no JSON (suficiente para forense; a query pode ser feita via `yara:matches:*high*` num campo separado se a necessidade aparecer — não na v1).
3. **Por que `private rules` ficam invisíveis na UI?** A semântica YARA: regras `private` são auxiliares de regras "públicas" — expor todas polui a UI com matches sem valor analítico.
4. **Por que armazenar `engineVersion` por item?** Auditoria forense: dois casos rodados com versões diferentes de YARA-X podem divergir em matches sutis (ex.: correções em módulos `pe` ou no engine de regex). Saber qual versão produziu o match é exigência prática.
5. **Por que ordenar matches deterministicamente?** Hashes futuros do índice e diffs entre rodadas dependem de ordem estável — Princípio IV.
