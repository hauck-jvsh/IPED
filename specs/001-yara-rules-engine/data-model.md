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
                        ┌────────────────────────────────────────┐
                        │     IItem (existente)                  │
                        │  + yara:tag[]              (indexado)  │  ← Lucene multi-valor (cross-rule)
                        │  + yara:match:<ns>/<name>  (indexado)  │  ← um campo Lucene multi-valor por regra casada
                        │     valores = matched strings decoded  │
                        │       (texto ASCII ou hex lowercase)   │
                        └────────────────────────────────────────┘
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
| `identifier` | String | computado: `namespace + "/" + name` | Sufixo do campo Lucene `yara:match:<identifier>`. |
| `isPrivate` | boolean | declaração `private rule X` | Se `true`, matches **não** são expostos na UI nem no índice. |
| `isGlobal` | boolean | declaração `global rule X` | Comportamento padrão YARA (afeta avaliação de outras regras); não exposto na UI separado. |

**Transições de estado**: nenhuma — regras são imutáveis durante a execução do caso.

---

### 4. `YaraMatch` (persistido por item no índice)

Resultado da aplicação de uma `YaraRule` a um `IItem`. Cada item pode ter zero ou mais matches.

| Campo | Tipo | Validação | Persistência |
|---|---|---|---|
| `rule` | String (`identifier`) | `namespace/name` válido | Sufixo do nome de campo Lucene `yara:match:<identifier>` (rev-5: o antigo campo agregado `yara:rule` foi removido) |
| `namespace` | String | derivado de `rule` | inferido pelo split em `/` do nome de campo `yara:match:<ns>/<name>` no read-time |
| `tags` | List<String> | herdadas da `YaraRule` | `yara:tag` Lucene (multi-valor, indexado, armazenado), **deduplicado** entre matches do mesmo item |
| `meta` | Map<String, String> | da `YaraRule` | **não persistido** (rev-5: removido junto com o JSON `yara:matches`) — disponível apenas em memória durante o scan, perdido depois |
| `strings` | List<MatchedString> | ordenadas por `(id asc, offset asc)` | colapsado por valor distinto em `yara:match:<id>`; offsets per-string e id não são persistidos (rev-5) |

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

A feature **adiciona** propriedades ao item; nenhuma propriedade existente é alterada. Histórico: a v1.0 incluía também `yara:rule` (lista agregada) e `yara:matches` (JSON de auditoria); ambos foram removidos na rev-5 por redundância com os campos per-rule `yara:match:<id>` da rev-4 (decisão registrada em [plan.md Complexity Tracking rev-5](plan.md) e [research.md §R-05](research.md)).

| Propriedade Lucene | Tipo | Fonte | Comportamento na UI/Report |
|---|---|---|---|
| `yara:tag` | multi-valor `String`, indexado | união das tags dos matches do item | Faceta cross-rule; permite filtrar "todos os itens com tag `apt`". |
| `yara:match:<namespace>/<name>` | multi-valor `String`, indexado | uma entrada por matched-string distinto da regra que casou (decodificado para ASCII imprimível ou hex lowercase via `YaraHighlightSupport.decodeHexForFacet`) | Faceta por regra no painel de metadados (FR-008/FR-008a); drill-down filtra a galeria; valores selecionados viram termos de highlight no viewer de texto via `MetadataPanel.getHighlightTerms()` — mirror exato de `Regex:<categoria>`. Um campo por regra que casou em pelo menos um item do caso. |

**Mutações suportadas**:
- **Insert**: durante `YaraScanTask.process(item)` no fluxo normal. Conteúdo escrito uma única vez por item, logo antes da `IndexTask`.
- **Replace**: no modo `--yara-only` (FR-011, rev-2). O pipeline normal é re-executado sobre o `-d` original; ao chegar no `IndexTask`, itens cujo `trackId` já está commitado são detectados (`SkipCommitedTask.isAlreadyCommited(...)`) e a escrita usa `IndexWriter.updateDocuments(new Term(IndexItem.TRACK_ID, trackId), iterable)` em vez de `addDocuments(...)`. Isso apaga atomicamente **todos** os docs com aquele `trackId` (parent + fragmentos de conteúdo) e adiciona o bloco novo — os campos `yara:*` (incluindo todos os `yara:match:*`) refletem o catálogo atual; demais campos são repopulados do mesmo IItem fresco que veio do `DataSourceReader` + Parsing. Regras que pararam de casar simplesmente não aparecem mais como campo `yara:match:<id>` no doc.

**Invariantes**:
- Se a feature está desabilitada (`enabled=false`), nenhum campo `yara:*` é gravado (FR-013).
- Se o item foi pulado (`scanAllItems=false` e o item não é elegível, ou ultrapassa limites), nenhum campo `yara:*` é gravado.
- Se o item foi escaneado mas sem matches, **também** nenhum campo `yara:*` é gravado (não criar entrada vazia — economia de índice e UI mais limpa).
- Se uma regra casou mas não capturou matched-strings (regra puramente baseada em `condition`), o campo `yara:match:<id>` não é gravado para aquela regra; o item aparece na faceta `yara:tag` apenas se a regra tem tags.

---

## Relações com o modelo existente

| Entidade existente | Como é tocada |
|---|---|
| `iped.data.IItem` | Recebe três novos campos via `setExtraAttribute(...)` — não há mudança de interface, só uso adicional. |
| `iped.properties.ExtraProperties` | Constantes novas (`YARA_PREFIX`, `YARA_TAGS`, `YARA_MATCH_PREFIX`) — adições puras. |
| `iped.engine.task.AbstractTask` | Subclasse nova `YaraScanTask` — sem mudança no contrato. |
| `iped.engine.config.Configurable` | Implementação nova `YaraConfig` — sem mudança no contrato. |
| `iped.engine.config.ConfigurationManager` | Recebe `YaraConfig` via mecanismo já existente de discovery. |
| `iped.engine.task.index.IndexTask` | **Não é modificada**. O documento é atualizado via `IItem.getExtraAttributes()` que a `IndexTask` já lê. |
| `iped.engine.task.HTMLReportTask` | Continua incluindo cada propriedade `yara:tag`/`yara:match:*` selecionada como linha de metadata padrão. O bloco estruturado dedicado (que parseava o JSON `yara:matches` v1) foi removido na rev-5 junto com o próprio campo — relatório lista os valores por regra como qualquer outro campo multi-valor. |
| `BasicProps`, `IndexItem`, `AppAnalyzer` | **Não são tocados** (Princípio I). |

---

## Decisões de modelagem não-óbvias

1. **Por que `yara:tag` separado dos `yara:match:*`?** Permite ao perito perguntar "todos os itens com regras com tag `apt`" sem precisar enumerar regras. Tag em YARA é uma faceta semântica padronizada (e ortogonal ao identificador `namespace/name`) — vale expor cross-rule.
2. **Por que `meta` (metadata YARA por regra) não vira campo indexado?** Metadata YARA é livre (cada autor inventa chaves). Indexar gera explosão de campos no Lucene. Na rev-5 a removemos junto com o JSON `yara:matches` — quem precisa de `meta.severity=high` etc. pode escrever a info na própria tag (`rule X : malware severity_high { ... }`) ou consultar via o arquivo fonte original da regra.
3. **Por que `private rules` ficam invisíveis na UI?** A semântica YARA: regras `private` são auxiliares de regras "públicas" — expor todas polui a UI com matches sem valor analítico.
4. **Por que removemos o `engineVersion` por item (que estava no JSON `yara:matches`)?** A informação ainda é fundamental para auditoria forense, mas em vez de carimbar por item, ela vai pro log do `Manager` no início de cada run (`YaraScanTask: catalog compiled in N ms from M source files (engine yara-x-X.Y.Z)`). Um caso processado num run carrega a mesma engine version para todos os itens — o per-item duplicava sem ganho de granularidade.
5. **Por que ordenar valores deterministicamente?** Hashes futuros do índice e diffs entre rodadas dependem de ordem estável — Princípio IV. Implementação: dedup via `LinkedHashSet` no `YaraScanTask`, sort lexicográfico antes de gravar.
