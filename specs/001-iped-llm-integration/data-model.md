# Phase 1 — Modelo de dados

**Feature**: 001-iped-llm-integration | **Data**: 2026-08-04

Entidades do domínio da integração, seus campos, invariantes e transições. Deriva de "Key Entities" do [spec.md](./spec.md), acrescentando o que a Phase 0 fixou.

Convenção: `MUST` reproduz obrigação do spec; "invariante" é condição que o código deve garantir sempre.

---

## Session

Contexto de trabalho do processo servidor. Uma sessão por processo.

| Campo | Tipo | Origem / regra |
|---|---|---|
| `sessionId` | UUID | Gerado na inicialização |
| `operator` | string | Identidade do operador da estação, obtida do ambiente (D2 — sem autenticação própria) |
| `startedAt` | instante UTC | |
| `accessMode` | `READ_ONLY` \| `READ_WRITE` | **Default `READ_ONLY`** (FR-025). Definido fora do alcance do agente |
| `egressPolicy` | referência | Inativa por padrão (FR-038) |
| `openCases` | mapa `caseId` → `OpenCase` | |
| `auditTrail` | referência | Uma trilha por sessão |

**Invariantes**
- `accessMode` MUST NOT ser alterável por qualquer ferramenta exposta ao agente.
- Nenhuma operação executa antes de estar registrada na trilha (FR-035). Registro precede ação, não o contrário.
- Na abertura, a sessão MUST advertir sobre o conteúdo que poderá ser transmitido na configuração vigente (FR-043).

---

## OpenCase

Caso aberto e validado.

| Campo | Tipo | Origem / regra |
|---|---|---|
| `caseId` | string | Derivado do caminho canônico + identidade do índice; estável se a pasta mudar de lugar |
| `casePath` | caminho absoluto | Pasta de saída do IPED (não a subpasta `iped/`) |
| `ipedVersion` | string | Lida do caso; faixa suportada declarada é 4.x (FR-054) |
| `source` | `IPEDSource` | Handle do engine |
| `totalItems` | inteiro | |
| `evidences` | lista de `Evidence` | |
| `fieldVocabulary` | `FieldVocabulary` | Carregado na abertura (FR-007) |
| `openedAt` | instante UTC | |

**Estados**: `VALIDATING → OPEN → CLOSED`. Falha na validação não produz `OpenCase` — produz erro diagnosticado (FR-002).

**Invariantes**
- Abrir um caso já aberto é idempotente e bem-sucedido (FR-004), devolvendo o mesmo `caseId`.
- Fechar libera recursos sem deixar trava pendente sobre a pasta (FR-005).
- Casos incompletos, em processamento ou de versão não interpretável MUST ser recusados com diagnóstico específico, nunca abertos parcialmente (FR-002, FR-054).

---

## Evidence

Fonte ingerida que compõe um caso. Somente leitura.

| Campo | Tipo |
|---|---|
| `evidenceUUID` | string |
| `name` | string |
| `itemCount` | inteiro |

**Invariante**: arquivos de evidência original MUST NOT ser modificados em nenhum modo (FR-031).

---

## ItemRef

Referência a um item. **É o identificador que circula em toda a superfície de ferramentas.**

| Campo | Tipo |
|---|---|
| `caseId` | string |
| `itemId` | inteiro (local ao caso) |

**Invariante crítica**: `itemId` é local ao caso; dois casos distintos têm IDs que colidem. A superfície de ferramentas MUST tornar impossível referenciar um item sem seu caso (FR-003). Nenhuma operação aceita `itemId` isolado.

---

## ItemView

Projeção enriquecida de um item, devolvida já nos resultados de consulta para evitar uma chamada por item (FR-014).

| Campo | Tipo | Observação |
|---|---|---|
| `ref` | `ItemRef` | |
| `name`, `path`, `ext`, `category`, `contentType` | string | |
| `size` | long | Campo Lucene é `size`, não `length` |
| `created`, `modified`, `accessed` | instante \| ausente | Campos `created`/`modified`/`accessed` |
| `hash` | string \| ausente | |
| `deleted`, `carved`, `subitem`, `isDir`, `hasChildren` | booleano | |
| `parentId` | inteiro \| ausente | |
| `bookmarks` | lista de string | |
| `selected` | booleano | |
| `snippet` | string \| **ausente** | Presente só em consulta textual sobre item com conteúdo indexado (FR-015) |

**Invariante**: ausência MUST ser distinguível de vazio (FR-022). Um campo ausente significa "não disponível para este item" e vem acompanhado do motivo quando pedido explicitamente; nunca string vazia silenciosa.

---

## FieldSelection

Projeção **escolhida pelo chamador** sobre um lote de itens (FR-080). Alternativa ao `ItemView` no `iped_get_items`, não substituta: sem campos nomeados, o que volta é o `ItemView`.

| Campo | Tipo | Observação |
|---|---|---|
| `asked` | lista de string | Os nomes pedidos, deduplicados, na ordem dada — são as **chaves** do resultado |
| `indexFields` | mapa nome pedido → nome do índice | Só difere quando o pedido foi uma chave publicada pelo servidor (`content_type`), a grafia de consulta (`p2p\:fileType`) ou diferença de caixa |
| `computed` | conjunto | `bookmarks`, `selected`, `case_id` — vêm do estado do caso, não do documento |
| `storedFields` | conjunto | Exatamente o que é lido de cada documento; nada além |

**Invariantes**
- Resolução MUST acontecer antes de ler qualquer documento, e nome inexistente MUST recusar a operação inteira com os nomes próximos (FR-080, FR-047).
- Tipagem MUST coincidir com o `ItemView` campo a campo: tamanho número, timestamp instante, flag booleana. As duas formas descrevem o mesmo item e são comparadas entre si.
- Ausência de campo booleano que o índice grava só quando verdadeiro (`isRoot`) MUST ser lida como `false`, não como indeterminado.
- Campo binário (`thumbnail`, features) MUST ser declarado ausente com o motivo e a ferramenta que devolve bytes, nunca projetado como vazio.
- `content` MUST ser recusado com explicação própria: é indexado para busca e não armazenado como propriedade.

---

## Query

| Campo | Tipo | Regra |
|---|---|---|
| `caseId` | string | Consulta opera sobre **um** caso (suposição do spec) |
| `expression` | string | Sintaxe de consulta do IPED, interpretada por `QueryBuilder.getQuery` (FR-011) |
| `pageSize` | inteiro | Limitado por teto do servidor (FR-013) |
| `cursor` | opaco \| ausente | Continuação determinística |
| `timeoutMs` | inteiro | FR-018 |

**Invariantes**
- MUST NOT devolver o conjunto completo de uma consulta ampla (FR-013).
- Ordenação estável: score com desempate por ordem de documento, para que a mesma consulta produza a mesma página na mesma ordem (FR-019).
- Erro de sintaxe MUST indicar o ponto do problema (FR-017); campo inexistente MUST vir com campos próximos sugeridos (FR-008).

---

## QueryResult

| Campo | Tipo | Regra |
|---|---|---|
| `totalMatches` | long | Total do conjunto, **independente** de quantos itens são devolvidos (FR-012). Vem da própria coleta: a página custa **uma** avaliação da consulta (FR-082) |
| `totalMatchesExact` | booleano | `false` quando o orçamento de tempo interrompeu a varredura — aí `totalMatches` é **piso**, não exato |
| `items` | lista de `ItemView` | No máximo `pageSize` |
| `nextCursor` | opaco \| ausente | Ausente na última página **e em página parcial** (FR-079) |
| `nextCursorOmitted` | string \| ausente | O motivo, quando a página é parcial |
| `partial` | booleano | `true` quando houve esgotamento de tempo (FR-018) |

**Invariantes**
- Uma página MUST custar uma avaliação da consulta. Contagem exata obtida por passada separada roda fora do orçamento de tempo e faz o `timeoutMs` pedido proteger metade do trabalho (FR-082).
- Total interrompido MUST vir declarado como piso. Isso **não** se lê da relação de `TotalHits` do Lucene, que responde `EQUAL_TO` mesmo com a varredura cortada — ela descreve o teto de contagem, não a interrupção.
- Página parcial MUST NOT produzir cursor: ele retomaria depois de posição que a varredura não alcançou, e acerto ordenado antes dela sumiria desta página e de todas as seguintes, em silêncio (FR-079).

---

## Aggregation

Contagens por dimensão, sem materializar itens (FR-016).

| Campo | Tipo |
|---|---|
| `dimension` | `category` \| `contentType` \| `period` \| `evidence` \| `bookmark` |
| `buckets` | lista de `{ value, count }` |

**Invariante**: custo cresce com o acervo, não com o resultado — é a operação que SC-015 mede. Implementada sobre `SortedSetDocValues` (R4), pois não há `lucene-facet` e casos existentes não têm `FacetField`.

---

## FieldVocabulary

Nomes de campos efetivamente presentes no índice do caso. Base de toda consulta válida.

| Campo | Tipo |
|---|---|
| `fields` | conjunto de string |
| `loadedAt` | instante UTC |

**Operações**: listar; verificar existência; sugerir similares por distância de edição quando um nome não existe (FR-008).

**Invariante**: é a **fonte da verdade** sobre nomes de campo. Documentação da skill sobre vocabulário canônico é subordinada a isto em caso de conflito (FR-050).

---

## Bookmark / Selection

Estado curatorial persistido no caso. Escrita apenas com `accessMode = READ_WRITE`.

| Campo | Tipo |
|---|---|
| `bookmarkId` | inteiro |
| `name` | string |
| `itemCount` | inteiro |

**Transições**: `criar → renomear → associar/desassociar itens → excluir`.

**Invariantes**
- Qualquer escrita com `accessMode = READ_ONLY` MUST ser recusada sem tocar o caso (FR-025).
- Escrita MUST ser recusada quando há acesso concorrente ao caso na mesma máquina (FR-028).
- Alterações MUST ser visíveis ao reabrir o caso na UI do IPED (FR-030).
- Exclusão e renomeação de marcador preexistente MUST registrar o estado anterior antes de aplicar (FR-033).

---

## AuditRecord

Unidade da trilha. **Append-only, encadeada por hash.**

| Campo | Tipo | Regra |
|---|---|---|
| `seq` | long | Monotônico, sem lacunas |
| `timestamp` | instante UTC | |
| `operator` | string | |
| `caseId` | string \| ausente | |
| `operation` | string | Nome da ferramenta |
| `parameters` | objeto | |
| `resultVolume` | inteiro | Contagem devolvida |
| `outcome` | `OK` \| `DENIED` \| `ERROR` | |
| `priorState` | objeto \| ausente | **Obrigatório** em operações de escrita (FR-033) |
| `blockedByPolicy` | objeto \| ausente | Item e regra, quando a política bloqueia conteúdo (FR-041) |
| `prevHash` | string | Hash do registro anterior |
| `hash` | string | Hash deste registro, incluindo `prevHash` |

**Invariantes**
- Somente-acréscimo; MUST NOT ser alterável por ferramenta exposta ao agente (FR-034).
- Gravada e sincronizada em disco **a cada operação**, não acumulada em memória (FR-071, R7) — é o que elimina a perda em encerramento anormal.
- Se não for possível registrar, a operação MUST ser recusada antes de executar (FR-035).
- Adulteração detectável: alterar ou remover um registro quebra a cadeia a partir dali.
- Carrega vínculo forte com o caso — caminho canônico mais identidade do índice — para reassociação posterior (FR-032).

---

## AuditTrail

| Campo | Tipo |
|---|---|
| `sessionId` | UUID |
| `caseBinding` | identificação forte do caso (caminho canônico + identidade do índice) |
| `stagingLocation` | caminho na área de auditoria da estação — **buffer write-ahead** |
| `homeLocation` | subpasta de auditoria dentro da pasta do caso — **lar durável** |
| `coLocated` | booleano; `false` quando o caso está em mídia não gravável |
| `records` | sequência de `AuditRecord` |

**Estados de sincronização**: `STAGED` (só na estação) → `SYNCED` (co-localizada no caso). Um caso em mídia somente-leitura permanece em `STAGED` com `coLocated = false`.

**Invariantes**
- MUST conter informação suficiente para um segundo examinador reproduzir a sequência de consultas e chegar ao mesmo conjunto de itens (FR-037). Formato JSON Lines (FR-036).
- MUST ser sincronizada automaticamente para `homeLocation` no encerramento e periodicamente durante a sessão, sem ação manual do perito (FR-072).
- Caso não gravável: `stagingLocation` é autoritativa e o perito MUST ser advertido na abertura (FR-073).
- Na abertura, uma trilha anterior em `stagingLocation` sem correspondente em `homeLocation` MUST ser reportada ao perito (FR-074).

---

## EgressPolicy

Opcional, **inativa por padrão** (D3).

| Campo | Tipo |
|---|---|
| `active` | booleano (default `false`) |
| `allowedClasses` | subconjunto de `{metadata, text, thumbnail, binary}` |
| `restrictedCategories` | lista de string |

**Invariantes**
- Inativa: metadados, texto, miniatura e binário disponíveis, sujeitos apenas aos limites de volume (FR-038, FR-021).
- Ativa: aplicada pelo servidor, de modo que o agente não a contorne por escolha de ferramenta ou parâmetro (FR-040).
- Consultável pelo perito inclusive quando inativa (FR-042).

---

## OutputArtifact

Produto de saída da US3.

| Campo | Tipo | Regra |
|---|---|---|
| `format` | `xlsx` \| `csv` \| `json` | FR-066 |
| `sourceSet` | marcador \| consulta \| lista explícita | |
| `itemCount` | inteiro | |
| `destination` | caminho | Escolhido pelo perito; **MUST NOT** ser a pasta do caso por padrão (FR-068) |

**Invariantes**
- Conjunto completo, sem paginação nem truncamento; a conversa recebe apenas contagem, amostra e caminho (FR-067).
- Conjunto vazio: informa e **não** gera artefato (FR-070).
- Toda geração registrada na trilha com definição do conjunto, contagem e destino, de modo que o artefato seja reproduzível (FR-070).
