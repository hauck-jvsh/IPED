---
description: "Task list for IPED ↔ LLM integration (MCP server + agent skill)"
---

# Tasks: Integração IPED ↔ LLM (Servidor MCP + Skill de agente)

**Input**: Design documents from `/specs/001-iped-llm-integration/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/mcp-tools.md](./contracts/mcp-tools.md), [quickstart.md](./quickstart.md)

**Tests**: incluídos. O spec pede verificação explicitamente — cada user story traz um "Independent Test", o [quickstart.md](./quickstart.md) define 13 cenários executáveis, e os critérios SC-001 a SC-015 são mensuráveis por construção.

**Organization**: tarefas agrupadas por user story, para que cada uma seja implementável e testável de forma independente.

**Escopo**: a US5 (preparo de evidência) é **fase 2** por decisão D1 e **não** aparece aqui. Os FR-058 a FR-061 ficam fora desta entrega.

**Numeração**: T001 a T082 vieram da geração inicial; T083 a T087 foram acrescentados na revisão de durabilidade da auditoria (T007), T088 na reavaliação contra a Constituição v1.0.0, T089 a T093 na correção dos dois defeitos do primeiro teste de campo, T094 a T095 na do segundo e T096 na execução de T028 (2026-08-06). Todos aparecem junto das tarefas com que se relacionam, fora da ordem numérica. Os identificadores são referências estáveis — a posição é que indica a ordem de execução.

---

## Estado da implementação — 2026-08-06

O módulo `iped-mcp` está implementado, compila em Java 11, empacota no release e roda **151 testes
com 0 falhas**. Dos 151, **47 pulam** por falta do caso de referência de T006.

### Escala verificada — T028 fechada

Executada em 2026-08-06 sobre caso real de **15.061.999 itens**, índice de 68,6 GB, no JRE 11 do
release. Cinco medições, todas dentro do teto, com a decisiva sendo a última:

| Medição | Resultado | Teto |
|---|---|---|
| `iped_open_case` + `iped_case_overview` | 3.050 ms | 30.000 ms |
| Primeira página de `*:*` (50 itens) | 655 ms | 5.000 ms |
| Total exato devolvendo 1 item | 817 ms | 5.000 ms |
| `iped_aggregate` por categoria (41 valores) | 4.490 ms | 15.000 ms |
| 10 páginas seguidas, pior caso | 836 ms — página 1: 836 ms, **página 10: 479 ms** | 5.000 ms |

A página 10 sai mais rápida que a primeira: o custo acompanha a página, não a profundidade. É a
diferença entre `PagedSearcher` e uma implementação sobre `IPEDSearcher.searchAll()`, e é o que
R3 previu e SC-002 exige. **SC-002 e SC-015 verificados.**

Rodar exigiu antes corrigir o harness (T096), que nunca tinha aberto um caso real e não carregava a
configuração do engine. Vale registrar o que isso significa: **as 47 suítes que hoje pulam também
não abririam caso** quando T006 ficasse pronta.

### Segundo teste de campo — cobertura das 25 ferramentas

Teste de cobertura sobre caso real de 781.246 itens e 455 campos, exercitando as 25 ferramentas com
caminhos de erro e parâmetros opcionais, e devolvendo o caso ao estado inicial. **Um** defeito
funcional encontrado, corrigido em T094:

`iped_search` devolvia `next_cursor`, mas retomar dali repetia a mesma página com o mesmo cursor. A
posição de ordenação vinha de `ScoreDoc.score`, que o `TopFieldCollector` do Lucene 9 deixa `NaN`;
toda comparação *search-after* contra `NaN` é falsa, então a coleta reiniciava do topo. Um laço de
paginação nunca terminava e nunca passava da primeira página — **sem sinal de erro**, que é o que
torna o defeito grave apesar de contornável por `iped_export_artifact`.

O teste também registrou que `similar: []` em `iped_check_field` não era distinguível de "sugestão
não implementada" (T095).

**Verificado em campo após a correção**, em sessão nova sobre o mesmo caso — e por comparação de
conjuntos contra verdade-terreno, não por "a página 2 difere da página 1":

| Verificação | Resultado |
|---|---|
| `Regex\:BR_CPF:*` (95 itens) exportada por `iped_export_artifact` e depois percorrida por cursor em 3 páginas de 25/25/45 | 95 paginados / 95 distintos / 95 no export |
| Duplicados entre páginas · faltando · extras | nenhum · nenhum · nenhum |
| Ordem idêntica à do export | sim |
| `next_cursor` na última página | ausente — a travessia termina |
| Caminho de score variável (`radxa`, 8.862 hits) | três saltos disjuntos, scores monotônicos 1456,0 → 1011,0 → 395,0 |

A consulta foi escolhida pelo caso difícil: **todos os 95 itens têm score constante 1,0**, então é o
desempate por `docId` que decide sozinho a ordem — onde um *search-after* mal feito pula ou repete
itens. Os dois caminhos do *search-after* estão corretos.

### Primeiro teste de campo — dois defeitos corrigidos

O primeiro deploy usado por perito fora da bancada de desenvolvimento encontrou o que nenhuma suíte
tinha pego, porque as duas falhas dependiam de vocabulário namespaced que o caso sintético não tem.
Aos olhos de quem testava, as duas eram a mesma coisa: "o MCP não consulta campo de metadado".

| Defeito | Efeito observado | Correção |
|---|---|---|
| Mensagem JSON malformada derrubava o processo | O cliente via o servidor MCP morrer no meio da sessão. Gatilho: `\:` cru dentro de string JSON — escape inválido, e exatamente o que um agente escreve ao tentar escapar nome de campo | T089 |
| Vocabulário devolvia nome que não se pode colar numa consulta | O agente entrava em laço entre `QUERY_SYNTAX` e `UNKNOWN_FIELD` e relatava ao perito que o mecanismo de consulta era limitado — **conclusão falsa sobre a ferramenta, no meio de uma análise** | T090 a T093 |

O segundo é o mais grave dos dois pela natureza do erro que produz, e sua raiz estava na própria
auto-correção: o remedy de `UNKNOWN_FIELD` sugeria `p2p:fileType`, grafia que não parseia. A
verificação da correção contra o caso antes de sugeri-la (FR-076) é o que impede que isso volte.

Escopo deliberadamente deixado de fora: dimensão de agregação por campo arbitrário — ver a
clarificação de 2026-08-06 em [spec.md](./spec.md).

**Verificado em campo** no teste de cobertura seguinte, sobre um caso com **455 campos, 386 deles
exigindo escape**: `iped_list_fields` anuncia a contagem e a regra, `iped_check_field` e
`iped_item_fields` devolvem `query_form` pronto, e consultas como `Regex\:BR_CPF:*` executam. A
lacuna que restava era vocabulário sintético, e ela está fechada — os 47 testes de integração
continuam pulando por outro motivo, a ausência do caso de referência de T006.

### Encerramento — 2026-08-06

**A feature foi encerrada por decisão do perito**, com T006, T073 e T079 **dispensadas e não
executadas**. Elas permanecem `[ ]` de propósito: encerrar a spec não as converte em verificadas, e
marcá-las concluídas seria registrar como feito o que não foi feito.

| Tarefa dispensada | O que não foi verificado | O que isso significa na prática |
|---|---|---|
| **T006** | Caso de referência não construído | **47 dos 151 testes continuam pulando**, com 7 cenários do quickstart. As garantias que eles protegem foram exercitadas em campo sobre casos reais de 781 mil e 15 milhões de itens, mas **não estão sob regressão automatizada**: uma alteração futura que as quebre passa no `mvn test` |
| **T073** | Instalação nunca cronometrada em máquina limpa, nos 3 harnesses | SC-010 não verificado |
| **T079** | Nunca executada contra harness de modelo local | FR-065 não verificado e, com ele, a salvaguarda operacional de D3 — a política de egresso é inativa por padrão, então conteúdo de evidência trafega para o provedor do modelo em uso. O servidor declara isso na abertura de toda sessão (FR-043), mas declarar não é conter |

**T028 saiu desta lista em 2026-08-06**, executada: caso real de 15.061.999 itens, SC-002 e SC-015
verificados. Era a lacuna que o quickstart marcava como inegociável, e é a única das quatro que foi
fechada por medição em vez de por decisão.

Estado final da suíte, no JRE 11 do release, com o caso grande configurado:
**151 testes, 0 falhas, 47 pulados**.

Além disso, **SC-008 e SC-009 não são automatizáveis**: são propriedades do que o agente escreve,
não do que a ferramenta devolve. `InvestigationBatteryTest` cobre a metade de recuperação e diz
isso explicitamente.

Relatório completo da execução do quickstart, com as lacunas em ordem de gravidade, em
[validation-report.md](./validation-report.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependência pendente)
- **[Story]**: user story a que a tarefa pertence (US1, US2, US3, US4)
- Caminhos de arquivo exatos em toda descrição

## Path Conventions

Módulo novo `iped-mcp/` na raiz do repositório, conforme "Source Code" em [plan.md](./plan.md):

- Main: `iped-mcp/src/main/java/iped/mcp/`
- Recursos: `iped-mcp/src/main/resources/`
- Testes: `iped-mcp/src/test/java/iped/mcp/{contract,integration,unit}/`
- Arquivos existentes tocados, ambos de forma aditiva: `pom.xml` (raiz) e `iped-app/pom.xml`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: criar o módulo e garantir que ele compila dentro da árvore existente

- [X] T001 Criar `iped-mcp/pom.xml` com parent `iped`, Java 11 herdado, e dependências: `iped-engine`, `iped-api`, `lucene-highlighter`, `lucene-core`, `lucene-queryparser`, Jackson e Apache POI com versão alinhada à que o Tika 2.4.0 traz
- [X] T002 Registrar `<module>iped-mcp</module>` no `pom.xml` da raiz, após `iped-engine`
- [X] T003 [P] Criar árvore de pacotes em `iped-mcp/src/main/java/iped/mcp/{protocol,session,query,item,curation,audit,egress,export,tools}`
- [X] T004 [P] Criar árvore de testes em `iped-mcp/src/test/java/iped/mcp/{contract,integration,unit}` e `iped-mcp/src/test/resources/`
- [X] T005 Verificar que `mvn -pl iped-mcp -am install` conclui limpo sobre `iped-mcp/pom.xml` e o `pom.xml` da raiz, antes de qualquer código de domínio

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: protocolo, sessão, acesso ao caso e auditoria — nada de user story funciona sem isto

**⚠️ CRITICAL**: nenhuma user story pode começar antes desta fase terminar. Em particular, FR-035 exige que **nenhuma operação execute sem registro prévio em auditoria**, o que torna a trilha bloqueante até para leitura.

- [ ] T006 Construir o **caso de referência pequeno** e versionar sua receita reprodutível em `iped-mcp/src/test/resources/reference-case/README.md`, com conteúdo conhecido e não sensível cobrindo: documentos, imagens com GPS, e-mails, mensagens, itens deletados, itens recuperados por carving e hits de regex — **receita e scripts versionados; caso NÃO construído.** `README.md` mais `build-reference-case.{sh,ps1}` produzem o material determinístico; fotos com EXIF GPS e a imagem de sistema de arquivos com item apagado e item carveado exigem passo manual **DISPENSADA em 2026-08-06 por decisão do perito, no encerramento da feature — não executada.**
- [x] T007 Reabrir a decisão provisória de durabilidade da trilha de auditoria e registrar o desfecho em `specs/001-iped-llm-integration/research.md` (seção R7) antes de escrever `AuditTrail` — **concluída em 2026-08-04**: estação vira buffer write-ahead, pasta do caso vira o lar da trilha; SC-003 reescrito e FR-071 a FR-074 acrescentados
- [X] T008 [P] Implementar `iped-mcp/src/main/java/iped/mcp/protocol/JsonRpcCodec.java` (JSON-RPC 2.0 sobre Jackson: request, response, notification, erro), com **charset UTF-8 explícito** na leitura e escrita de stdio — sem herdar o padrão da plataforma (Princípio V)
- [X] T089 Fazer de mensagem malformada um evento não-fatal: `JsonRpcCodec.readMessage` converte a falha do Jackson em `McpError.MALFORMED_MESSAGE` e o laço de `McpServerMain.start` responde `-32700` e continua servindo (FR-078). Coberto por `contract/MalformedMessageTest` — **concluída em 2026-08-06**, defeito encontrado no primeiro teste de campo: a exceção escapava do laço de leitura e derrubava o processo, então uma consulta mal escrita custava a sessão e todos os casos abertos nela
- [X] T009 [P] Implementar `iped-mcp/src/main/java/iped/mcp/protocol/McpError.java` com o envelope comum `{code, message, remedy, details}` de [contracts/mcp-tools.md](./contracts/mcp-tools.md) — `remedy` é obrigatório, é o que sustenta FR-065
- [X] T010 [P] Implementar `iped-mcp/src/main/java/iped/mcp/protocol/ToolDescriptor.java` e o registro de ferramentas com esquema de entrada
- [X] T011 Implementar `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java` tratando `initialize`, `tools/list` e `tools/call`, declarando a versão de protocolo suportada (depende de T008, T009, T010)
- [X] T012 Implementar `iped-mcp/src/main/java/iped/mcp/McpServerMain.java` como entry point stdio, iniciável programaticamente por processo hospedeiro (FR-064)
- [X] T088 Implementar `iped-mcp/src/main/java/iped/mcp/config/McpServerConfig.java` como `Configurable<T>` de `iped-api`, carregado por `ConfigurationManager` a partir de `conf/`, cobrindo área de auditoria, modo de acesso, política de egresso e tetos de página e de conteúdo. Nenhum desses valores MUST viver em constante de código (Princípio IV)
- [X] T013 [P] Implementar `iped-mcp/src/main/java/iped/mcp/session/Session.java` com operador da estação, modo de acesso `READ_ONLY` por padrão e política de egresso vigente, lendo os valores de `McpServerConfig` (FR-025, depende de T088)
- [X] T014 [P] Implementar `iped-mcp/src/main/java/iped/mcp/session/CaseValidator.java` validando integridade do caso e faixa de versão 4.x, com diagnósticos `NOT_A_CASE`, `CASE_INCOMPLETE`, `CASE_IN_PROCESSING`, `VERSION_UNSUPPORTED` (FR-001, FR-002, FR-054)
- [X] T015 Implementar `iped-mcp/src/main/java/iped/mcp/session/CaseRegistry.java` com abertura idempotente, `caseId` estável derivado do caminho canônico + identidade do índice, e liberação sem trava pendente (FR-003, FR-004, FR-005)
- [X] T016 [P] Implementar `iped-mcp/src/main/java/iped/mcp/audit/AuditRecord.java` com `seq`, `operation`, `parameters`, `resultVolume`, `outcome`, `priorState`, `prevHash`, `hash`
- [X] T017 Implementar `iped-mcp/src/main/java/iped/mcp/audit/AuditTrail.java` em JSON Lines append-only encadeado por hash, com **charset UTF-8 explícito** (Princípio V), gravado na área de auditoria da estação com escrita e `fsync` a cada operação, recusando a operação quando não for possível registrar, e carregando vínculo forte com o caso (caminho canônico + identidade do índice) para reassociação (FR-032, FR-034, FR-035, FR-071, R7)
- [X] T083 Implementar a sincronização automática da trilha para a subpasta de auditoria dentro da pasta do caso, no encerramento e periodicamente durante a sessão, em `iped-mcp/src/main/java/iped/mcp/audit/AuditSync.java` — sem ação manual do perito (FR-072)
- [X] T084 Implementar a degradação para mídia não gravável em `iped-mcp/src/main/java/iped/mcp/audit/AuditSync.java`: cópia da estação torna-se autoritativa e a sessão adverte na abertura que a trilha não poderá ser co-localizada (FR-073)
- [X] T085 Implementar a detecção de trilha órfã na abertura do caso em `iped-mcp/src/main/java/iped/mcp/session/CaseRegistry.java`, reportando ao perito trilha anterior existente na estação sem correspondente na pasta do caso (FR-074)
- [X] T018 [P] Implementar `iped-mcp/src/main/java/iped/mcp/egress/EgressPolicy.java` inativa por padrão e consultável mesmo inativa (FR-038, FR-042)
- [X] T019 Ligar a auditoria ao despacho em `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java`, de modo que **toda** chamada seja registrada antes de executar, leitura inclusive, e recusada se o registro falhar (depende de T011, T017)
- [X] T020 Emitir na abertura da sessão a advertência sobre qual conteúdo de evidência poderá ser transmitido na configuração vigente, em `iped-mcp/src/main/java/iped/mcp/session/Session.java` (FR-043)
- [X] T021 Teste de contrato do handshake em `iped-mcp/src/test/java/iped/mcp/contract/HandshakeTest.java`: `initialize` responde com versão de protocolo, `tools/call` com ferramenta inexistente devolve erro JSON-RPC bem formado e não exceção (Cenário 1 do quickstart)

**Checkpoint**: protocolo falando, caso abrindo, auditoria gravando. User stories podem começar.

---

## Phase 3: User Story 1 - Interrogar um caso processado (Priority: P1) 🎯 MVP

**Goal**: o perito aponta para um caso e faz perguntas de investigação; o assistente se orienta sozinho, consulta com paginação e responde com conclusões ancoradas em itens citados.

**Independent Test**: contra o caso de referência, 20 perguntas típicas (palavra-chave, datas, GPS, hash, tipo, remetente, tema em conversas) devem citar exatamente os itens esperados, sem falso positivo e sem omissão, e os identificadores citados devem abrir os mesmos itens na UI do IPED.

### Tests for User Story 1

> Escrever antes da implementação e confirmar que falham.

- [X] T022 [P] [US1] Teste de contrato em `iped-mcp/src/test/java/iped/mcp/contract/ToolSchemaTest.java` verificando que `tools/list` expõe todas as ferramentas de leitura de [contracts/mcp-tools.md](./contracts/mcp-tools.md) com esquema válido
- [X] T023 [P] [US1] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/CaseOpenTest.java`: abertura idempotente, panorama em uma chamada, e recusa diagnosticada de pasta que não é caso e de caso em processamento (Cenário 2)
- [X] T024 [P] [US1] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/PaginationTest.java`: `total_matches` exato com `items` limitado, paginação completa sem repetição nem lacuna, e mesma primeira página na mesma ordem ao repetir (Cenário 3, FR-012, FR-013, FR-019)
- [X] T025 [P] [US1] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/VocabularyTest.java`: campo inexistente devolve `UNKNOWN_FIELD` com `details.similar` contendo o nome correto, e a consulta refeita com a sugestão retorna resultados (Cenário 4, SC-006)
- [X] T026 [P] [US1] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/AggregationTest.java`: soma dos buckets bate com o total do caso e é coerente com `total_matches` de uma consulta restritiva (Cenário 5)
- [X] T027 [P] [US1] Teste de indisponibilidade em `iped-mcp/src/test/java/iped/mcp/unit/AvailabilityTest.java`: item sem texto extraído, sem miniatura, cifrado e com evidência ausente declaram indisponibilidade com motivo, nunca vazio silencioso (FR-022)
- [X] T028 [US1] Teste de desempenho em `iped-mcp/src/test/java/iped/mcp/integration/ScalePerformanceTest.java` sobre caso de ~10 M itens: primeira página < 5 s, abertura + panorama < 30 s, agregação < 15 s (SC-002, SC-015). **Executar contra o caso grande é obrigatório** — a diferença entre paginar e materializar não aparece no caso pequeno. **Executado em 2026-08-06** sobre caso real de **15.061.999 itens**, índice de 68,6 GB, 5 testes verdes: abertura + panorama 3.050 ms, primeira página de `*:*` 655 ms, total exato 817 ms, agregação por categoria 4.490 ms, e 10 páginas seguidas com pior caso de 836 ms — **página 10 mais rápida que a página 1** (479 ms contra 836 ms), que é a medição que separa paginar de materializar. SC-002 e SC-015 **verificados**. A suíte passa a imprimir as medições ao final: pass/fail sozinho não mostra a corrida se aproximando do teto
- [X] T096 [US1] Tornar o harness de integração capaz de abrir caso real: `McpTestSupport.requireIpedConfiguration()` carrega a configuração do engine a partir de `-Diped.mcp.ipedRoot`, como o `McpServerMain.main` faz, e o harness recusa com instrução em JVM ≥ 16, onde o FST não consegue refletir e a falha viria de dentro de uma lib de serialização — **concluída em 2026-08-06**. Sem isso o `iped_open_case` falhava com `CASE_INACCESSIBLE` e `IndexTaskConfig` nulo, e **nenhuma** das suítes de integração conseguiria abrir caso quando T006 ficasse pronta

### Implementation for User Story 1

- [X] T029 [P] [US1] Implementar `iped-mcp/src/main/java/iped/mcp/item/ItemView.java` com propriedades essenciais enriquecidas e distinção explícita entre ausente e vazio (FR-014, FR-022)
- [X] T030 [P] [US1] Implementar `iped-mcp/src/main/java/iped/mcp/query/FieldVocabulary.java` sobre `LoadIndexFields.getFields(...)`, com verificação de existência e sugestão de campos próximos por distância de edição (FR-007, FR-008, R6)
- [X] T031 [US1] Implementar `iped-mcp/src/main/java/iped/mcp/query/PagedSearcher.java` usando `QueryBuilder.getQuery`/`rewriteQuery` para a semântica do IPED e `IndexSearcher.searchAfter` para colher só a página, com contagem exata por `IndexSearcher.count`, ordenação estável e limite de tempo. **Não usar `IPEDSearcher`** — `searchAll()` materializa todo o conjunto (R3, FR-011 a FR-013, FR-018, FR-019)
- [X] T090 [P] [US1] Implementar `iped-mcp/src/main/java/iped/mcp/query/FieldNames.java` com a grafia de consulta de nome de campo (`p2p:fileType` → `p2p\:fileType`) e o reparo de expressão limitado ao vocabulário do caso, fora de aspas e seguido de `:` (FR-075). Coberto por `unit/FieldNamesTest`, que exercita o parser Lucene real — asserção sobre a string sozinha passaria com uma forma que não parseia — **concluída em 2026-08-06**
- [X] T094 [US1] Extrair a continuação de página para `iped-mcp/src/main/java/iped/mcp/query/Cursor.java`, lendo a posição de ordenação de `FieldDoc.fields[0]` em vez de `ScoreDoc.score`, que o `TopFieldCollector` deixa `NaN`, e recusando cursor inutilizável tanto na emissão quanto na leitura (FR-013, FR-019, FR-079). Coberto por `unit/CursorPaginationTest` sobre índice Lucene em memória — **concluída e verificada em campo em 2026-08-06**: travessia completa de 95 itens em 3 páginas conferida contra o export do mesmo conjunto, sem duplicata, falta ou extra, na consulta de score constante onde só o desempate por `docId` ordena. Defeito encontrado no teste de cobertura das 25 ferramentas: `next_cursor` era devolvido mas não avançava, e um laço de paginação repetia a primeira página para sempre sem sinal de erro
- [X] T092 [US1] Quebrar o laço `UNKNOWN_FIELD` ⇄ `QUERY_SYNTAX` em `PagedSearcher`: `plan()` verifica a correção contra o caso antes de sugeri-la, `checkFields` reconhece nome namespaced por `FieldVocabulary.namesUnder` em vez de tratá-lo como erro de digitação, e o reparo automático opcional (`autoEscapeFieldNames`, desligado por padrão) declara `query_normalized` em busca, agregação e exportação (FR-076, FR-077) — **concluída em 2026-08-06**. Era o defeito de maior impacto: o remedy mandava o agente reescrever a grafia que acabara de falhar, e o modelo concluía que o mecanismo de consulta era limitado
- [X] T032 [P] [US1] Implementar `iped-mcp/src/main/java/iped/mcp/query/SnippetBuilder.java` sobre `lucene-highlighter`, devolvendo trecho ausente e declarado quando o item não tem conteúdo textual indexado (FR-015, R5)
- [X] T033 [US1] Implementar `iped-mcp/src/main/java/iped/mcp/query/Aggregator.java` sobre `SortedSetDocValues`, sem materializar itens, seguindo o padrão de `TimelineResults` (FR-016, R4)
- [X] T034 [US1] Implementar `iped-mcp/src/main/java/iped/mcp/item/ContentAccess.java` com tetos de volume, sinalização de truncamento e tamanho real para texto, miniatura e binário (FR-020, FR-021)
- [X] T035 [US1] Implementar navegação de hierarquia (contêiner pai e itens contidos) em `iped-mcp/src/main/java/iped/mcp/item/ContentAccess.java` (FR-023)
- [X] T036 [US1] Implementar as ferramentas de sessão e caso em `iped-mcp/src/main/java/iped/mcp/tools/SessionTools.java`: `iped_session_info`, `iped_open_case`, `iped_case_overview`, `iped_close_case` (FR-006)
- [X] T037 [P] [US1] Implementar as ferramentas de vocabulário em `iped-mcp/src/main/java/iped/mcp/tools/VocabularyTools.java`: `iped_list_fields`, `iped_check_field`, `iped_item_fields` (FR-009)
- [X] T095 [US1] Declarar em `iped_check_field` quantos nomes foram comparados quando `similar` volta vazio, para que "nenhum nome próximo" seja distinguível de "sugestão não implementada" — é sobre essa resposta que se apoia uma afirmação de ausência (FR-008) — **concluída em 2026-08-06**
- [X] T091 [US1] Devolver a grafia de consulta junto do nome cru nas ferramentas de vocabulário: `query_form` em `iped_check_field` e `iped_item_fields`, e nota com a regra e um exemplo do próprio caso em `iped_list_fields` (FR-075) — **concluída em 2026-08-06**
- [X] T038 [US1] Implementar as ferramentas de consulta em `iped-mcp/src/main/java/iped/mcp/tools/QueryTools.java`: `iped_search` e `iped_aggregate`, com erros `QUERY_SYNTAX` indicando a posição e `UNKNOWN_FIELD` trazendo sugestões (FR-017)
- [X] T039 [US1] Implementar as ferramentas de item em `iped-mcp/src/main/java/iped/mcp/tools/ItemTools.java`: `iped_get_items` com teto de lote, `iped_item_metadata`, `iped_item_text`, `iped_item_thumbnail`, `iped_item_content`, `iped_item_tree` (FR-024)
- [X] T040 [US1] Escrever a skill canônica em `iped-mcp/src/main/resources/skill/SKILL.md`: orientar-se antes de consultar, estreitar progressivamente, amostrar em volume alto, citar itens em toda conclusão, não afirmar ausência de evidência sem validar vocabulário, não extrapolar além dos dados retornados (FR-044 a FR-048)
- [X] T041 [P] [US1] Escrever `iped-mcp/src/main/resources/skill/references/query-syntax.md` com sintaxe de consulta e vocabulário canônico de campos, subordinado à descoberta em tempo de execução em caso de conflito (FR-050)
- [X] T093 [US1] Documentar em `query-syntax.md` e no `SKILL.md` a grafia de nome de campo com `:` dentro de expressão, incluindo que aspas não são alternativa e que o backslash é duplicado em JSON (FR-050) — **concluída em 2026-08-06**
- [X] T042 [P] [US1] Escrever `iped-mcp/src/main/resources/skill/references/workflows.md` com os fluxos periciais recorrentes: localização geográfica, análise de conversas, itens deletados e recuperados, correspondência por hash, correlação por e-mail, linha do tempo, levantamento de dados pessoais, panorama de acervo (FR-049)
- [X] T043 [US1] Construir a bateria de 30 perguntas com gabarito em `iped-mcp/src/test/resources/evaluation/questions.md` e o verificador em `iped-mcp/src/test/java/iped/mcp/integration/InvestigationBatteryTest.java`, aferindo ≥ 90% de acerto, zero falso positivo apresentado como conclusão e 100% de conclusões com itens citados (Cenário 12, SC-008, SC-009)

**Checkpoint**: US1 completa. O perito já consegue interrogar um caso e obter resposta fundamentada. **Este é o MVP entregável.**

---

## Phase 4: User Story 2 - Registrar achados com auditoria (Priority: P2)

**Goal**: preservar o achado dentro do caso via marcadores, com escrita desabilitada por padrão, confirmação antes de aplicar e trilha de auditoria completa.

**Independent Test**: com escrita habilitada, executar criar → associar → renomear → remover e verificar na UI do IPED que o marcador existe com exatamente os itens esperados; a trilha exportada reproduz a sequência integral.

### Tests for User Story 2

- [X] T044 [P] [US2] Teste de invariante somente-leitura em `iped-mcp/src/test/java/iped/mcp/integration/ReadOnlyInvariantTest.java`: hash recursivo da pasta do caso **excluindo a subpasta de auditoria por nome** idêntico após sessão completa, `WRITE_NOT_ENABLED` ao tentar criar marcador, e verificação de que **nenhuma escrita ocorreu fora** da subpasta excluída (Cenário 6, SC-003)
- [X] T045 [P] [US2] Teste de ciclo de escrita em `iped-mcp/src/test/java/iped/mcp/integration/BookmarkWriteTest.java`: criar, associar, renomear e remover, verificando persistência ao reabrir o caso (Cenário 7, FR-030)
- [X] T046 [P] [US2] Teste unitário de integridade da trilha em `iped-mcp/src/test/java/iped/mcp/unit/AuditChainTest.java`: `seq` monotônico sem lacunas, cadeia de hash íntegra, e adulteração de um registro detectada (Cenário 8, FR-034)
- [X] T047 [P] [US2] Teste de durabilidade em `iped-mcp/src/test/java/iped/mcp/integration/AuditDurabilityTest.java`: **matar o processo no meio da sessão** e confirmar que as operações concluídas até ali estão na trilha — é o teste que valida a decisão de R7 (Cenário 8, passo 5)
- [X] T048 [P] [US2] Teste de auditoria indisponível em `iped-mcp/src/test/java/iped/mcp/integration/AuditFailClosedTest.java`: área de auditoria não gravável faz a operação ser recusada **antes** de executar (FR-035)
- [X] T049 [P] [US2] Teste de concorrência em `iped-mcp/src/test/java/iped/mcp/integration/ConcurrentAccessTest.java`: com o caso aberto por outro processo local, escrita recusada com `CONCURRENT_ACCESS` e leitura preservada (FR-028)
- [X] T086 [P] [US2] Teste de co-localização em `iped-mcp/src/test/java/iped/mcp/integration/AuditSyncTest.java`: a trilha aparece na subpasta de auditoria do caso sem qualquer ação manual, e continua íntegra e encadeada após a sincronização (FR-072)
- [X] T087 [P] [US2] Teste de degradação e trilha órfã em `iped-mcp/src/test/java/iped/mcp/integration/AuditOrphanTest.java`: caso em mídia não gravável mantém a cópia da estação autoritativa e adverte na abertura; e uma trilha anterior na estação sem correspondente no caso é reportada ao abrir (FR-073, FR-074)

### Implementation for User Story 2

- [X] T050 [US2] Implementar `iped-mcp/src/main/java/iped/mcp/session/ConcurrencyGuard.java` detectando acesso concorrente ao caso por outro processo na mesma máquina, tipicamente a UI do IPED (FR-028, R8)
- [X] T051 [US2] Implementar `iped-mcp/src/main/java/iped/mcp/curation/BookmarkWriter.java` sobre `Bookmarks`/`saveState`, capturando o estado anterior em exclusão e renomeação de marcador preexistente (FR-026, FR-030, FR-033)
- [X] T052 [US2] Implementar o portão de modo de escrita em `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java`, recusando toda ferramenta de curadoria com `WRITE_NOT_ENABLED` sem tocar o caso quando `accessMode = READ_ONLY` (FR-025)
- [X] T053 [US2] Implementar as ferramentas de marcador em `iped-mcp/src/main/java/iped/mcp/tools/BookmarkTools.java`: `iped_list_bookmarks`, `iped_create_bookmark`, `iped_rename_bookmark`, `iped_delete_bookmark`, `iped_add_to_bookmark`, `iped_remove_from_bookmark`
- [X] T054 [P] [US2] Implementar as ferramentas de seleção em `iped-mcp/src/main/java/iped/mcp/tools/SelectionTools.java`: `iped_get_selection` e `iped_set_selection` (FR-027)
- [X] T055 [US2] Implementar `iped_export_audit` em `iped-mcp/src/main/java/iped/mcp/tools/AuditTools.java`, aceitando a pasta do caso como destino de exportação deliberada (FR-036)
- [X] T056 [US2] Acrescentar a `iped-mcp/src/main/resources/skill/SKILL.md` a disciplina de escrita: apresentar o efeito exato antes de aplicar, obter confirmação, e confirmação reforçada para exclusão e renomeação de marcador preexistente (FR-029)

**Checkpoint**: US1 e US2 funcionam de forma independente. O trabalho vira produto pericial aproveitável.

---

## Phase 5: User Story 3 - Produzir artefato de saída (Priority: P3)

**Goal**: gerar planilha, CSV ou JSON a partir de um marcador, consulta ou lista explícita, sem trafegar os itens pela conversa.

**Independent Test**: sobre um marcador com 5.000 itens, gerar o artefato nos três formatos e verificar que os 5.000 registros estão completos e corretos, e que a conversa recebeu apenas contagem, amostra e caminho.

### Tests for User Story 3

- [X] T057 [P] [US3] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/ArtifactExportTest.java`: marcador de 5.000 itens exportado em xlsx, CSV e JSON, com os 5.000 registros presentes e corretos em cada arquivo (Cenário 9, SC-012)
- [X] T058 [P] [US3] Teste de conjunto vazio e de destino recusado em `iped-mcp/src/test/java/iped/mcp/unit/ArtifactGuardTest.java`: conjunto vazio informa e não cria arquivo; destino dentro da pasta do caso é recusado por padrão (FR-068, FR-070)

### Implementation for User Story 3

- [X] T059 [US3] Implementar `iped-mcp/src/main/java/iped/mcp/export/ArtifactWriter.java` com escrita em CSV e JSON do conjunto completo, sem paginação nem truncamento (FR-066, FR-067)
- [X] T060 [US3] Acrescentar a saída xlsx em `iped-mcp/src/main/java/iped/mcp/export/ArtifactWriter.java` usando Apache POI em modo de escrita incremental, para não carregar 5.000 itens em memória de uma vez
- [X] T061 [P] [US3] Implementar o agrupamento cronológico de mensagens por conversa, com remetente e destinatário identificados, em `iped-mcp/src/main/java/iped/mcp/export/ArtifactWriter.java` (FR-069)
- [X] T062 [US3] Implementar `iped_export_artifact` em `iped-mcp/src/main/java/iped/mcp/tools/ExportTools.java`, devolvendo apenas contagem, amostra e caminho, e registrando na trilha a definição do conjunto, a contagem e o destino (FR-067, FR-070)
- [X] T063 [P] [US3] Acrescentar a `iped-mcp/src/main/resources/skill/references/workflows.md` o fluxo de relatório final sobre marcador

**Checkpoint**: US1, US2 e US3 independentes. O ciclo de trabalho fecha.

---

## Phase 6: User Story 4 - Instalar e conectar sem conhecimento prévio (Priority: P3)

**Goal**: um perito sem experiência com integração de agente instala a partir da distribuição do IPED, em qualquer dos três harnesses, e chega à primeira resposta em menos de 15 minutos.

**Independent Test**: máquina limpa com apenas o IPED instalado; seguir o guia e cronometrar até a primeira resposta contra o caso de referência, em cada harness.

### Tests for User Story 4

- [X] T064 [P] [US4] Teste de diagnóstico em `iped-mcp/src/test/java/iped/mcp/integration/DiagnosticsTest.java` cobrindo a matriz do Cenário 13: IPED não localizado, caso inacessível, caso fora da faixa 4.x, área de auditoria não gravável, caso portátil com evidência ausente — todos com diagnóstico acionável, nenhum com erro técnico opaco (SC-011)
- [X] T065 [P] [US4] Verificação de que a orientação carregada é idêntica entre harnesses, em `iped-mcp/src/test/java/iped/mcp/contract/SkillParityTest.java`, comparando os invólucros gerados contra a fonte canônica (FR-063)

### Implementation for User Story 4

- [X] T066 [US4] Implementar a verificação de diagnóstico em `iped-mcp/src/main/java/iped/mcp/Diagnostics.java`, validando todos os pré-requisitos e reportando o que falta e como corrigir (FR-053)
- [X] T067 [US4] Registrar o diagnóstico operacional do próprio servidor **via SLF4J**, em log separado da trilha pericial, em `iped-mcp/src/main/java/iped/mcp/Diagnostics.java`. `System.out` e `System.err` MUST NOT aparecer — em transporte stdio eles corromperiam o próprio protocolo (FR-056, Princípio V)
- [X] T068 [US4] Empacotar `iped-mcp` no release, alterando `iped-app/pom.xml` de forma aditiva (FR-054)
- [X] T069 [US4] Implementar a geração dos invólucros por harness a partir da fonte canônica, no build de `iped-mcp/pom.xml`, com saída em `iped-app/resources/skills/` — conteúdo canônico único, sem duplicação (FR-063)
- [X] T070 [P] [US4] Escrever o guia de instalação para Claude Code em `iped-mcp/src/main/resources/skill/install/claude-code.md`
- [X] T071 [P] [US4] Escrever o guia de instalação para Codex em `iped-mcp/src/main/resources/skill/install/codex.md`
- [X] T072 [P] [US4] Escrever o guia de instalação para OpenCode em `iped-mcp/src/main/resources/skill/install/opencode.md`, apresentando a operação com **modelo local como configuração recomendada** — é a salvaguarda que sustenta a decisão D3 (D4, FR-065)
- [ ] T073 [US4] Cronometrar SC-010 em máquina limpa nos três harnesses e registrar os resultados em `iped-mcp/src/test/resources/evaluation/install-timings.md` (Cenário 10) — **registro preparado; medição NÃO executada.** Exige três máquinas limpas e alguém que não escreveu os guias: quem escreveu bate os 15 minutos e não aprende nada com isso **DISPENSADA em 2026-08-06 por decisão do perito, no encerramento da feature — não executada.**

**Checkpoint**: todas as user stories da entrega inicial estão independentes e verificáveis.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: o que atravessa as histórias — política de egresso ativável, verificação de portabilidade e fechamento

- [X] T074 Implementar o modo ativo da política em `iped-mcp/src/main/java/iped/mcp/egress/EgressPolicy.java`, restringindo classes de conteúdo e permitindo restrição por categoria ou classificação de sensibilidade atribuída no processamento (FR-039)
- [X] T075 Aplicar a política no servidor de modo que o agente não a contorne por escolha de ferramenta ou parâmetro, e registrar cada bloqueio na trilha com item e regra, em `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java` (FR-040, FR-041)
- [X] T076 [P] Teste de contorno da política em `iped-mcp/src/test/java/iped/mcp/integration/EgressPolicyTest.java`: com a política ativa, nenhum conteúdo bloqueado alcança o agente em nenhuma tentativa testada; com ela inativa, a advertência de abertura de sessão ocorre sempre (SC-014)
- [X] T077 [P] Acrescentar a `iped-mcp/src/main/resources/skill/SKILL.md` a orientação de tratar material de evidência como sensível ao apresentá-lo, evitando reprodução desnecessária de conteúdo que a própria consulta indica ser ilícito ou sob sigilo (FR-052)
- [X] T078 Verificar em `iped-mcp/src/test/java/iped/mcp/integration/NoNetworkExposureTest.java` que o servidor não abre porta de rede na configuração padrão (FR-057)
- [ ] T079 Executar a verificação funcional com harness de modelo local (Cenário 11) e registrar o resultado em `iped-mcp/src/test/resources/evaluation/local-model.md`, confirmando que os erros são autocorrigíveis pelo modelo sem depender de capacidade de modelo de fronteira (FR-065) — **registro preparado; execução NÃO realizada.** Exige OpenCode com runtime local **DISPENSADA em 2026-08-06 por decisão do perito, no encerramento da feature — não executada.**
- [X] T080 Verificar a faixa de compatibilidade 4.x em `iped-mcp/src/test/java/iped/mcp/integration/VersionRangeTest.java`, sobre ao menos um caso da versão mais antiga e um da mais recente da linha, com recusa diagnosticada fora dela (SC-013)
- [X] T081 Executar a validação completa de [quickstart.md](./quickstart.md) e registrar as lacunas encontradas
- [X] T082 [P] Criar `iped-mcp/CLAUDE.md` documentando o módulo no padrão dos demais, e acrescentar a linha correspondente na tabela de módulos do `CLAUDE.md` da raiz

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Fase 1)**: sem dependências
- **Foundational (Fase 2)**: depende da Fase 1 — **bloqueia todas as user stories**. A auditoria é bloqueante até para leitura, porque FR-035 impede execução sem registro
- **User Stories (Fases 3 a 6)**: dependem da Fase 2. Depois disso podem correr em paralelo, ou em ordem de prioridade P1 → P2 → P3
- **Polish (Fase 7)**: depende das user stories desejadas estarem completas

### User Story Dependencies

- **US1 (P1)**: começa após a Fase 2. Sem dependência de outras histórias
- **US2 (P2)**: começa após a Fase 2. Usa a busca de US1 na prática, mas é testável de forma independente sobre marcadores preexistentes
- **US3 (P3)**: começa após a Fase 2. Testável sobre marcadores preexistentes, sem depender de US2
- **US4 (P3)**: começa após a Fase 2. Depende do conteúdo de skill de US1 para gerar invólucros completos — T069 pressupõe T040

### Dependências internas notáveis

- T011 depende de T008, T009, T010
- T019 depende de T011 e T017
- T017 não deve começar antes de T007 (decisão de durabilidade reaberta)
- T031 é pré-requisito de T038; T033 é pré-requisito de T038
- T060 depende de T059 (mesmo arquivo)
- T069 depende de T040
- Toda a Fase 3 em diante depende de T006 (caso de referência), sem o qual nenhum critério é verificável de forma repetível

### Parallel Opportunities

- Fase 1: T003 e T004 em paralelo
- Fase 2: T008, T009, T010 em paralelo; T013, T014, T016, T018 em paralelo
- Fase 3: todos os testes T022 a T027 em paralelo; T029 e T030 em paralelo; T041 e T042 em paralelo
- Fase 4: todos os testes T044 a T049 em paralelo
- Fase 6: T070, T071, T072 em paralelo
- Entre histórias: com equipe, US1 a US4 podem correr em paralelo após a Fase 2

---

## Parallel Example: User Story 1

```bash
# Testes de US1 juntos:
Task: "Teste de contrato de esquema em iped-mcp/src/test/java/iped/mcp/contract/ToolSchemaTest.java"
Task: "Teste de abertura em iped-mcp/src/test/java/iped/mcp/integration/CaseOpenTest.java"
Task: "Teste de paginação em iped-mcp/src/test/java/iped/mcp/integration/PaginationTest.java"
Task: "Teste de vocabulário em iped-mcp/src/test/java/iped/mcp/integration/VocabularyTest.java"
Task: "Teste de agregação em iped-mcp/src/test/java/iped/mcp/integration/AggregationTest.java"
Task: "Teste de indisponibilidade em iped-mcp/src/test/java/iped/mcp/unit/AvailabilityTest.java"

# Componentes independentes de US1 juntos:
Task: "ItemView em iped-mcp/src/main/java/iped/mcp/item/ItemView.java"
Task: "FieldVocabulary em iped-mcp/src/main/java/iped/mcp/query/FieldVocabulary.java"

# Referências da skill juntas:
Task: "query-syntax.md em iped-mcp/src/main/resources/skill/references/"
Task: "workflows.md em iped-mcp/src/main/resources/skill/references/"
```

---

## Implementation Strategy

### MVP primeiro (apenas US1)

1. Fase 1: Setup
2. Fase 2: Foundational — **crítica, bloqueia tudo**
3. Fase 3: US1
4. **PARAR e VALIDAR**: rodar os Cenários 1 a 5 e 12 do quickstart contra o caso de referência, e o Cenário 3 contra o caso grande
5. Demonstrar a um perito real antes de seguir

### Entrega incremental

1. Setup + Foundational → base pronta
2. US1 → validar → **MVP**, já substitui horas de navegação manual
3. US2 → validar → o trabalho passa a ser aproveitável como produto pericial
4. US3 → validar → o ciclo fecha com artefato entregável
5. US4 → validar → sai da bancada de quem construiu
6. Polish → política de egresso ativável e verificações transversais

### Equipe em paralelo

Depois da Fase 2: um desenvolvedor em US1 (o caminho crítico e o mais pesado), outro em US2, um terceiro pode adiantar US4 no que não depende do conteúdo de skill de US1.

---

## Notes

- **T006 e T007 primeiro.** O caso de referência é pré-requisito de toda verificação, e a decisão de durabilidade da auditoria precisa ser reaberta antes de virar código. Ambas são fáceis de adiar e caras de corrigir depois.
- **T028 contra o caso grande é inegociável.** A diferença entre `PagedSearcher` e `IPEDSearcher` não aparece em caso pequeno — passa nos testes e falha na bancada. ✅ Executada em 2026-08-06 sobre 15 M itens.
- **Suíte de integração precisa de `-Diped.mcp.ipedRoot` e do JRE 11 do release em `-Djvm`.** Ver a seção Testes do [CLAUDE.md do módulo](../../iped-mcp/CLAUDE.md); sem os dois, nenhum caso real abre.
- `[P]` = arquivos diferentes, sem dependência pendente
- Commitar após cada tarefa ou grupo lógico
- Parar em qualquer checkpoint para validar a história de forma independente
