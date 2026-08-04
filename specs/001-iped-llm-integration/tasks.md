---
description: "Task list for IPED ↔ LLM integration (MCP server + agent skill)"
---

# Tasks: Integração IPED ↔ LLM (Servidor MCP + Skill de agente)

**Input**: Design documents from `/specs/001-iped-llm-integration/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/mcp-tools.md](./contracts/mcp-tools.md), [quickstart.md](./quickstart.md)

**Tests**: incluídos. O spec pede verificação explicitamente — cada user story traz um "Independent Test", o [quickstart.md](./quickstart.md) define 13 cenários executáveis, e os critérios SC-001 a SC-015 são mensuráveis por construção.

**Organization**: tarefas agrupadas por user story, para que cada uma seja implementável e testável de forma independente.

**Escopo**: a US5 (preparo de evidência) é **fase 2** por decisão D1 e **não** aparece aqui. Os FR-058 a FR-061 ficam fora desta entrega.

**Numeração**: T001 a T082 vieram da geração inicial; T083 a T087 foram acrescentados na revisão de durabilidade da auditoria (T007) e aparecem junto das tarefas com que se relacionam, fora da ordem numérica. Os identificadores são referências estáveis — a posição é que indica a ordem de execução.

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

- [ ] T001 Criar `iped-mcp/pom.xml` com parent `iped`, Java 11 herdado, e dependências: `iped-engine`, `iped-api`, `lucene-highlighter`, `lucene-core`, `lucene-queryparser`, Jackson e Apache POI com versão alinhada à que o Tika 2.4.0 traz
- [ ] T002 Registrar `<module>iped-mcp</module>` no `pom.xml` da raiz, após `iped-engine`
- [ ] T003 [P] Criar árvore de pacotes em `iped-mcp/src/main/java/iped/mcp/{protocol,session,query,item,curation,audit,egress,export,tools}`
- [ ] T004 [P] Criar árvore de testes em `iped-mcp/src/test/java/iped/mcp/{contract,integration,unit}` e `iped-mcp/src/test/resources/`
- [ ] T005 Verificar que `mvn -pl iped-mcp -am install` conclui limpo sobre `iped-mcp/pom.xml` e o `pom.xml` da raiz, antes de qualquer código de domínio

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: protocolo, sessão, acesso ao caso e auditoria — nada de user story funciona sem isto

**⚠️ CRITICAL**: nenhuma user story pode começar antes desta fase terminar. Em particular, FR-035 exige que **nenhuma operação execute sem registro prévio em auditoria**, o que torna a trilha bloqueante até para leitura.

- [ ] T006 Construir o **caso de referência pequeno** e versionar sua receita reprodutível em `iped-mcp/src/test/resources/reference-case/README.md`, com conteúdo conhecido e não sensível cobrindo: documentos, imagens com GPS, e-mails, mensagens, itens deletados, itens recuperados por carving e hits de regex
- [x] T007 Reabrir a decisão provisória de durabilidade da trilha de auditoria e registrar o desfecho em `specs/001-iped-llm-integration/research.md` (seção R7) antes de escrever `AuditTrail` — **concluída em 2026-08-04**: estação vira buffer write-ahead, pasta do caso vira o lar da trilha; SC-003 reescrito e FR-071 a FR-074 acrescentados
- [ ] T008 [P] Implementar `iped-mcp/src/main/java/iped/mcp/protocol/JsonRpcCodec.java` (JSON-RPC 2.0 sobre Jackson: request, response, notification, erro)
- [ ] T009 [P] Implementar `iped-mcp/src/main/java/iped/mcp/protocol/McpError.java` com o envelope comum `{code, message, remedy, details}` de [contracts/mcp-tools.md](./contracts/mcp-tools.md) — `remedy` é obrigatório, é o que sustenta FR-065
- [ ] T010 [P] Implementar `iped-mcp/src/main/java/iped/mcp/protocol/ToolDescriptor.java` e o registro de ferramentas com esquema de entrada
- [ ] T011 Implementar `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java` tratando `initialize`, `tools/list` e `tools/call`, declarando a versão de protocolo suportada (depende de T008, T009, T010)
- [ ] T012 Implementar `iped-mcp/src/main/java/iped/mcp/McpServerMain.java` como entry point stdio, iniciável programaticamente por processo hospedeiro (FR-064)
- [ ] T013 [P] Implementar `iped-mcp/src/main/java/iped/mcp/session/Session.java` com operador da estação, modo de acesso `READ_ONLY` por padrão e política de egresso vigente (FR-025)
- [ ] T014 [P] Implementar `iped-mcp/src/main/java/iped/mcp/session/CaseValidator.java` validando integridade do caso e faixa de versão 4.x, com diagnósticos `NOT_A_CASE`, `CASE_INCOMPLETE`, `CASE_IN_PROCESSING`, `VERSION_UNSUPPORTED` (FR-001, FR-002, FR-054)
- [ ] T015 Implementar `iped-mcp/src/main/java/iped/mcp/session/CaseRegistry.java` com abertura idempotente, `caseId` estável derivado do caminho canônico + identidade do índice, e liberação sem trava pendente (FR-003, FR-004, FR-005)
- [ ] T016 [P] Implementar `iped-mcp/src/main/java/iped/mcp/audit/AuditRecord.java` com `seq`, `operation`, `parameters`, `resultVolume`, `outcome`, `priorState`, `prevHash`, `hash`
- [ ] T017 Implementar `iped-mcp/src/main/java/iped/mcp/audit/AuditTrail.java` em JSON Lines append-only encadeado por hash, gravado na área de auditoria da estação com escrita e `fsync` a cada operação, recusando a operação quando não for possível registrar, e carregando vínculo forte com o caso (caminho canônico + identidade do índice) para reassociação (FR-032, FR-034, FR-035, FR-071, R7)
- [ ] T083 Implementar a sincronização automática da trilha para a subpasta de auditoria dentro da pasta do caso, no encerramento e periodicamente durante a sessão, em `iped-mcp/src/main/java/iped/mcp/audit/AuditSync.java` — sem ação manual do perito (FR-072)
- [ ] T084 Implementar a degradação para mídia não gravável em `iped-mcp/src/main/java/iped/mcp/audit/AuditSync.java`: cópia da estação torna-se autoritativa e a sessão adverte na abertura que a trilha não poderá ser co-localizada (FR-073)
- [ ] T085 Implementar a detecção de trilha órfã na abertura do caso em `iped-mcp/src/main/java/iped/mcp/session/CaseRegistry.java`, reportando ao perito trilha anterior existente na estação sem correspondente na pasta do caso (FR-074)
- [ ] T018 [P] Implementar `iped-mcp/src/main/java/iped/mcp/egress/EgressPolicy.java` inativa por padrão e consultável mesmo inativa (FR-038, FR-042)
- [ ] T019 Ligar a auditoria ao despacho em `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java`, de modo que **toda** chamada seja registrada antes de executar, leitura inclusive, e recusada se o registro falhar (depende de T011, T017)
- [ ] T020 Emitir na abertura da sessão a advertência sobre qual conteúdo de evidência poderá ser transmitido na configuração vigente, em `iped-mcp/src/main/java/iped/mcp/session/Session.java` (FR-043)
- [ ] T021 Teste de contrato do handshake em `iped-mcp/src/test/java/iped/mcp/contract/HandshakeTest.java`: `initialize` responde com versão de protocolo, `tools/call` com ferramenta inexistente devolve erro JSON-RPC bem formado e não exceção (Cenário 1 do quickstart)

**Checkpoint**: protocolo falando, caso abrindo, auditoria gravando. User stories podem começar.

---

## Phase 3: User Story 1 - Interrogar um caso processado (Priority: P1) 🎯 MVP

**Goal**: o perito aponta para um caso e faz perguntas de investigação; o assistente se orienta sozinho, consulta com paginação e responde com conclusões ancoradas em itens citados.

**Independent Test**: contra o caso de referência, 20 perguntas típicas (palavra-chave, datas, GPS, hash, tipo, remetente, tema em conversas) devem citar exatamente os itens esperados, sem falso positivo e sem omissão, e os identificadores citados devem abrir os mesmos itens na UI do IPED.

### Tests for User Story 1

> Escrever antes da implementação e confirmar que falham.

- [ ] T022 [P] [US1] Teste de contrato em `iped-mcp/src/test/java/iped/mcp/contract/ToolSchemaTest.java` verificando que `tools/list` expõe todas as ferramentas de leitura de [contracts/mcp-tools.md](./contracts/mcp-tools.md) com esquema válido
- [ ] T023 [P] [US1] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/CaseOpenTest.java`: abertura idempotente, panorama em uma chamada, e recusa diagnosticada de pasta que não é caso e de caso em processamento (Cenário 2)
- [ ] T024 [P] [US1] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/PaginationTest.java`: `total_matches` exato com `items` limitado, paginação completa sem repetição nem lacuna, e mesma primeira página na mesma ordem ao repetir (Cenário 3, FR-012, FR-013, FR-019)
- [ ] T025 [P] [US1] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/VocabularyTest.java`: campo inexistente devolve `UNKNOWN_FIELD` com `details.similar` contendo o nome correto, e a consulta refeita com a sugestão retorna resultados (Cenário 4, SC-006)
- [ ] T026 [P] [US1] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/AggregationTest.java`: soma dos buckets bate com o total do caso e é coerente com `total_matches` de uma consulta restritiva (Cenário 5)
- [ ] T027 [P] [US1] Teste de indisponibilidade em `iped-mcp/src/test/java/iped/mcp/unit/AvailabilityTest.java`: item sem texto extraído, sem miniatura, cifrado e com evidência ausente declaram indisponibilidade com motivo, nunca vazio silencioso (FR-022)
- [ ] T028 [US1] Teste de desempenho em `iped-mcp/src/test/java/iped/mcp/integration/ScalePerformanceTest.java` sobre caso de ~10 M itens: primeira página < 5 s, abertura + panorama < 30 s, agregação < 15 s (SC-002, SC-015). **Executar contra o caso grande é obrigatório** — a diferença entre paginar e materializar não aparece no caso pequeno

### Implementation for User Story 1

- [ ] T029 [P] [US1] Implementar `iped-mcp/src/main/java/iped/mcp/item/ItemView.java` com propriedades essenciais enriquecidas e distinção explícita entre ausente e vazio (FR-014, FR-022)
- [ ] T030 [P] [US1] Implementar `iped-mcp/src/main/java/iped/mcp/query/FieldVocabulary.java` sobre `LoadIndexFields.getFields(...)`, com verificação de existência e sugestão de campos próximos por distância de edição (FR-007, FR-008, R6)
- [ ] T031 [US1] Implementar `iped-mcp/src/main/java/iped/mcp/query/PagedSearcher.java` usando `QueryBuilder.getQuery`/`rewriteQuery` para a semântica do IPED e `IndexSearcher.searchAfter` para colher só a página, com contagem exata por `IndexSearcher.count`, ordenação estável e limite de tempo. **Não usar `IPEDSearcher`** — `searchAll()` materializa todo o conjunto (R3, FR-011 a FR-013, FR-018, FR-019)
- [ ] T032 [P] [US1] Implementar `iped-mcp/src/main/java/iped/mcp/query/SnippetBuilder.java` sobre `lucene-highlighter`, devolvendo trecho ausente e declarado quando o item não tem conteúdo textual indexado (FR-015, R5)
- [ ] T033 [US1] Implementar `iped-mcp/src/main/java/iped/mcp/query/Aggregator.java` sobre `SortedSetDocValues`, sem materializar itens, seguindo o padrão de `TimelineResults` (FR-016, R4)
- [ ] T034 [US1] Implementar `iped-mcp/src/main/java/iped/mcp/item/ContentAccess.java` com tetos de volume, sinalização de truncamento e tamanho real para texto, miniatura e binário (FR-020, FR-021)
- [ ] T035 [US1] Implementar navegação de hierarquia (contêiner pai e itens contidos) em `iped-mcp/src/main/java/iped/mcp/item/ContentAccess.java` (FR-023)
- [ ] T036 [US1] Implementar as ferramentas de sessão e caso em `iped-mcp/src/main/java/iped/mcp/tools/SessionTools.java`: `iped_session_info`, `iped_open_case`, `iped_case_overview`, `iped_close_case` (FR-006)
- [ ] T037 [P] [US1] Implementar as ferramentas de vocabulário em `iped-mcp/src/main/java/iped/mcp/tools/VocabularyTools.java`: `iped_list_fields`, `iped_check_field`, `iped_item_fields` (FR-009)
- [ ] T038 [US1] Implementar as ferramentas de consulta em `iped-mcp/src/main/java/iped/mcp/tools/QueryTools.java`: `iped_search` e `iped_aggregate`, com erros `QUERY_SYNTAX` indicando a posição e `UNKNOWN_FIELD` trazendo sugestões (FR-017)
- [ ] T039 [US1] Implementar as ferramentas de item em `iped-mcp/src/main/java/iped/mcp/tools/ItemTools.java`: `iped_get_items` com teto de lote, `iped_item_metadata`, `iped_item_text`, `iped_item_thumbnail`, `iped_item_content`, `iped_item_tree` (FR-024)
- [ ] T040 [US1] Escrever a skill canônica em `iped-mcp/src/main/resources/skill/SKILL.md`: orientar-se antes de consultar, estreitar progressivamente, amostrar em volume alto, citar itens em toda conclusão, não afirmar ausência de evidência sem validar vocabulário, não extrapolar além dos dados retornados (FR-044 a FR-048)
- [ ] T041 [P] [US1] Escrever `iped-mcp/src/main/resources/skill/references/query-syntax.md` com sintaxe de consulta e vocabulário canônico de campos, subordinado à descoberta em tempo de execução em caso de conflito (FR-050)
- [ ] T042 [P] [US1] Escrever `iped-mcp/src/main/resources/skill/references/workflows.md` com os fluxos periciais recorrentes: localização geográfica, análise de conversas, itens deletados e recuperados, correspondência por hash, correlação por e-mail, linha do tempo, levantamento de dados pessoais, panorama de acervo (FR-049)
- [ ] T043 [US1] Construir a bateria de 30 perguntas com gabarito em `iped-mcp/src/test/resources/evaluation/questions.md` e o verificador em `iped-mcp/src/test/java/iped/mcp/integration/InvestigationBatteryTest.java`, aferindo ≥ 90% de acerto, zero falso positivo apresentado como conclusão e 100% de conclusões com itens citados (Cenário 12, SC-008, SC-009)

**Checkpoint**: US1 completa. O perito já consegue interrogar um caso e obter resposta fundamentada. **Este é o MVP entregável.**

---

## Phase 4: User Story 2 - Registrar achados com auditoria (Priority: P2)

**Goal**: preservar o achado dentro do caso via marcadores, com escrita desabilitada por padrão, confirmação antes de aplicar e trilha de auditoria completa.

**Independent Test**: com escrita habilitada, executar criar → associar → renomear → remover e verificar na UI do IPED que o marcador existe com exatamente os itens esperados; a trilha exportada reproduz a sequência integral.

### Tests for User Story 2

- [ ] T044 [P] [US2] Teste de invariante somente-leitura em `iped-mcp/src/test/java/iped/mcp/integration/ReadOnlyInvariantTest.java`: hash recursivo da pasta do caso **excluindo a subpasta de auditoria por nome** idêntico após sessão completa, `WRITE_NOT_ENABLED` ao tentar criar marcador, e verificação de que **nenhuma escrita ocorreu fora** da subpasta excluída (Cenário 6, SC-003)
- [ ] T045 [P] [US2] Teste de ciclo de escrita em `iped-mcp/src/test/java/iped/mcp/integration/BookmarkWriteTest.java`: criar, associar, renomear e remover, verificando persistência ao reabrir o caso (Cenário 7, FR-030)
- [ ] T046 [P] [US2] Teste unitário de integridade da trilha em `iped-mcp/src/test/java/iped/mcp/unit/AuditChainTest.java`: `seq` monotônico sem lacunas, cadeia de hash íntegra, e adulteração de um registro detectada (Cenário 8, FR-034)
- [ ] T047 [P] [US2] Teste de durabilidade em `iped-mcp/src/test/java/iped/mcp/integration/AuditDurabilityTest.java`: **matar o processo no meio da sessão** e confirmar que as operações concluídas até ali estão na trilha — é o teste que valida a decisão de R7 (Cenário 8, passo 5)
- [ ] T048 [P] [US2] Teste de auditoria indisponível em `iped-mcp/src/test/java/iped/mcp/integration/AuditFailClosedTest.java`: área de auditoria não gravável faz a operação ser recusada **antes** de executar (FR-035)
- [ ] T049 [P] [US2] Teste de concorrência em `iped-mcp/src/test/java/iped/mcp/integration/ConcurrentAccessTest.java`: com o caso aberto por outro processo local, escrita recusada com `CONCURRENT_ACCESS` e leitura preservada (FR-028)
- [ ] T086 [P] [US2] Teste de co-localização em `iped-mcp/src/test/java/iped/mcp/integration/AuditSyncTest.java`: a trilha aparece na subpasta de auditoria do caso sem qualquer ação manual, e continua íntegra e encadeada após a sincronização (FR-072)
- [ ] T087 [P] [US2] Teste de degradação e trilha órfã em `iped-mcp/src/test/java/iped/mcp/integration/AuditOrphanTest.java`: caso em mídia não gravável mantém a cópia da estação autoritativa e adverte na abertura; e uma trilha anterior na estação sem correspondente no caso é reportada ao abrir (FR-073, FR-074)

### Implementation for User Story 2

- [ ] T050 [US2] Implementar `iped-mcp/src/main/java/iped/mcp/session/ConcurrencyGuard.java` detectando acesso concorrente ao caso por outro processo na mesma máquina, tipicamente a UI do IPED (FR-028, R8)
- [ ] T051 [US2] Implementar `iped-mcp/src/main/java/iped/mcp/curation/BookmarkWriter.java` sobre `Bookmarks`/`saveState`, capturando o estado anterior em exclusão e renomeação de marcador preexistente (FR-026, FR-030, FR-033)
- [ ] T052 [US2] Implementar o portão de modo de escrita em `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java`, recusando toda ferramenta de curadoria com `WRITE_NOT_ENABLED` sem tocar o caso quando `accessMode = READ_ONLY` (FR-025)
- [ ] T053 [US2] Implementar as ferramentas de marcador em `iped-mcp/src/main/java/iped/mcp/tools/BookmarkTools.java`: `iped_list_bookmarks`, `iped_create_bookmark`, `iped_rename_bookmark`, `iped_delete_bookmark`, `iped_add_to_bookmark`, `iped_remove_from_bookmark`
- [ ] T054 [P] [US2] Implementar as ferramentas de seleção em `iped-mcp/src/main/java/iped/mcp/tools/SelectionTools.java`: `iped_get_selection` e `iped_set_selection` (FR-027)
- [ ] T055 [US2] Implementar `iped_export_audit` em `iped-mcp/src/main/java/iped/mcp/tools/AuditTools.java`, aceitando a pasta do caso como destino de exportação deliberada (FR-036)
- [ ] T056 [US2] Acrescentar a `iped-mcp/src/main/resources/skill/SKILL.md` a disciplina de escrita: apresentar o efeito exato antes de aplicar, obter confirmação, e confirmação reforçada para exclusão e renomeação de marcador preexistente (FR-029)

**Checkpoint**: US1 e US2 funcionam de forma independente. O trabalho vira produto pericial aproveitável.

---

## Phase 5: User Story 3 - Produzir artefato de saída (Priority: P3)

**Goal**: gerar planilha, CSV ou JSON a partir de um marcador, consulta ou lista explícita, sem trafegar os itens pela conversa.

**Independent Test**: sobre um marcador com 5.000 itens, gerar o artefato nos três formatos e verificar que os 5.000 registros estão completos e corretos, e que a conversa recebeu apenas contagem, amostra e caminho.

### Tests for User Story 3

- [ ] T057 [P] [US3] Teste de integração em `iped-mcp/src/test/java/iped/mcp/integration/ArtifactExportTest.java`: marcador de 5.000 itens exportado em xlsx, CSV e JSON, com os 5.000 registros presentes e corretos em cada arquivo (Cenário 9, SC-012)
- [ ] T058 [P] [US3] Teste de conjunto vazio e de destino recusado em `iped-mcp/src/test/java/iped/mcp/unit/ArtifactGuardTest.java`: conjunto vazio informa e não cria arquivo; destino dentro da pasta do caso é recusado por padrão (FR-068, FR-070)

### Implementation for User Story 3

- [ ] T059 [US3] Implementar `iped-mcp/src/main/java/iped/mcp/export/ArtifactWriter.java` com escrita em CSV e JSON do conjunto completo, sem paginação nem truncamento (FR-066, FR-067)
- [ ] T060 [US3] Acrescentar a saída xlsx em `iped-mcp/src/main/java/iped/mcp/export/ArtifactWriter.java` usando Apache POI em modo de escrita incremental, para não carregar 5.000 itens em memória de uma vez
- [ ] T061 [P] [US3] Implementar o agrupamento cronológico de mensagens por conversa, com remetente e destinatário identificados, em `iped-mcp/src/main/java/iped/mcp/export/ArtifactWriter.java` (FR-069)
- [ ] T062 [US3] Implementar `iped_export_artifact` em `iped-mcp/src/main/java/iped/mcp/tools/ExportTools.java`, devolvendo apenas contagem, amostra e caminho, e registrando na trilha a definição do conjunto, a contagem e o destino (FR-067, FR-070)
- [ ] T063 [P] [US3] Acrescentar a `iped-mcp/src/main/resources/skill/references/workflows.md` o fluxo de relatório final sobre marcador

**Checkpoint**: US1, US2 e US3 independentes. O ciclo de trabalho fecha.

---

## Phase 6: User Story 4 - Instalar e conectar sem conhecimento prévio (Priority: P3)

**Goal**: um perito sem experiência com integração de agente instala a partir da distribuição do IPED, em qualquer dos três harnesses, e chega à primeira resposta em menos de 15 minutos.

**Independent Test**: máquina limpa com apenas o IPED instalado; seguir o guia e cronometrar até a primeira resposta contra o caso de referência, em cada harness.

### Tests for User Story 4

- [ ] T064 [P] [US4] Teste de diagnóstico em `iped-mcp/src/test/java/iped/mcp/integration/DiagnosticsTest.java` cobrindo a matriz do Cenário 13: IPED não localizado, caso inacessível, caso fora da faixa 4.x, área de auditoria não gravável, caso portátil com evidência ausente — todos com diagnóstico acionável, nenhum com erro técnico opaco (SC-011)
- [ ] T065 [P] [US4] Verificação de que a orientação carregada é idêntica entre harnesses, em `iped-mcp/src/test/java/iped/mcp/contract/SkillParityTest.java`, comparando os invólucros gerados contra a fonte canônica (FR-063)

### Implementation for User Story 4

- [ ] T066 [US4] Implementar a verificação de diagnóstico em `iped-mcp/src/main/java/iped/mcp/Diagnostics.java`, validando todos os pré-requisitos e reportando o que falta e como corrigir (FR-053)
- [ ] T067 [US4] Registrar o diagnóstico operacional do próprio servidor em log separado da trilha pericial, em `iped-mcp/src/main/java/iped/mcp/Diagnostics.java` (FR-056)
- [ ] T068 [US4] Empacotar `iped-mcp` no release, alterando `iped-app/pom.xml` de forma aditiva (FR-054)
- [ ] T069 [US4] Implementar a geração dos invólucros por harness a partir da fonte canônica, no build de `iped-mcp/pom.xml`, com saída em `iped-app/resources/skills/` — conteúdo canônico único, sem duplicação (FR-063)
- [ ] T070 [P] [US4] Escrever o guia de instalação para Claude Code em `iped-mcp/src/main/resources/skill/install/claude-code.md`
- [ ] T071 [P] [US4] Escrever o guia de instalação para Codex em `iped-mcp/src/main/resources/skill/install/codex.md`
- [ ] T072 [P] [US4] Escrever o guia de instalação para OpenCode em `iped-mcp/src/main/resources/skill/install/opencode.md`, apresentando a operação com **modelo local como configuração recomendada** — é a salvaguarda que sustenta a decisão D3 (D4, FR-065)
- [ ] T073 [US4] Cronometrar SC-010 em máquina limpa nos três harnesses e registrar os resultados em `iped-mcp/src/test/resources/evaluation/install-timings.md` (Cenário 10)

**Checkpoint**: todas as user stories da entrega inicial estão independentes e verificáveis.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: o que atravessa as histórias — política de egresso ativável, verificação de portabilidade e fechamento

- [ ] T074 Implementar o modo ativo da política em `iped-mcp/src/main/java/iped/mcp/egress/EgressPolicy.java`, restringindo classes de conteúdo e permitindo restrição por categoria ou classificação de sensibilidade atribuída no processamento (FR-039)
- [ ] T075 Aplicar a política no servidor de modo que o agente não a contorne por escolha de ferramenta ou parâmetro, e registrar cada bloqueio na trilha com item e regra, em `iped-mcp/src/main/java/iped/mcp/protocol/McpDispatcher.java` (FR-040, FR-041)
- [ ] T076 [P] Teste de contorno da política em `iped-mcp/src/test/java/iped/mcp/integration/EgressPolicyTest.java`: com a política ativa, nenhum conteúdo bloqueado alcança o agente em nenhuma tentativa testada; com ela inativa, a advertência de abertura de sessão ocorre sempre (SC-014)
- [ ] T077 [P] Acrescentar a `iped-mcp/src/main/resources/skill/SKILL.md` a orientação de tratar material de evidência como sensível ao apresentá-lo, evitando reprodução desnecessária de conteúdo que a própria consulta indica ser ilícito ou sob sigilo (FR-052)
- [ ] T078 Verificar em `iped-mcp/src/test/java/iped/mcp/integration/NoNetworkExposureTest.java` que o servidor não abre porta de rede na configuração padrão (FR-057)
- [ ] T079 Executar a verificação funcional com harness de modelo local (Cenário 11) e registrar o resultado em `iped-mcp/src/test/resources/evaluation/local-model.md`, confirmando que os erros são autocorrigíveis pelo modelo sem depender de capacidade de modelo de fronteira (FR-065)
- [ ] T080 Verificar a faixa de compatibilidade 4.x em `iped-mcp/src/test/java/iped/mcp/integration/VersionRangeTest.java`, sobre ao menos um caso da versão mais antiga e um da mais recente da linha, com recusa diagnosticada fora dela (SC-013)
- [ ] T081 Executar a validação completa de [quickstart.md](./quickstart.md) e registrar as lacunas encontradas
- [ ] T082 [P] Criar `iped-mcp/CLAUDE.md` documentando o módulo no padrão dos demais, e acrescentar a linha correspondente na tabela de módulos do `CLAUDE.md` da raiz

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
- **T028 contra o caso grande é inegociável.** A diferença entre `PagedSearcher` e `IPEDSearcher` não aparece em caso pequeno — passa nos testes e falha na bancada.
- `[P]` = arquivos diferentes, sem dependência pendente
- Commitar após cada tarefa ou grupo lógico
- Parar em qualquer checkpoint para validar a história de forma independente
