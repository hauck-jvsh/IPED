---
description: "Task list for YARA Rules Engine para IPED"
---

# Tasks: YARA Rules Engine para IPED

**Input**: Design documents from [specs/001-yara-rules-engine/](./)

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

**Tests**: Incluídos. A fase de planejamento (R-12) define explicitamente cobertura JUnit + paridade com CLI (SC-004) como gate; portanto, tarefas de teste estão presentes.

**Organization**: tarefas agrupadas por user story; cada story é independentemente testável.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivos distintos, sem dependências em tasks incompletas)
- **[Story]**: US1 (P1), US2 (P2), US3 (P3) conforme `spec.md`; sem label em Setup / Foundational / Polish
- Caminhos sempre relativos ao repo root

## Path Conventions

Multi-módulo Maven do IPED — caminhos referem-se aos módulos `iped-api`, `iped-engine`, `iped-app`, mais `tools/`, `licenses/`, `ThirdParty.txt`, `ReleaseNotes.txt` e `.github/`. Estrutura conforme [plan.md → Project Structure](plan.md).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Criar a estrutura de pastas, dependência JNA e registros de licenciamento/CI antes de qualquer código ou config.

- [ ] T001 Criar os diretórios da feature: `iped-engine/src/main/java/iped/engine/task/yara/` (vazio, com `package-info.java` opcional), `iped-engine/src/test/java/iped/engine/task/yara/` e `iped-engine/src/test/java/iped/engine/task/yara/fixtures/`. Criar `tools/yara/win64/`, `tools/yara/linux64/`, `tools/yara/LICENSE` (placeholder) e `tools/yara/README.md` documentando "como atualizar a versão do libyara".
- [ ] T002 [P] Garantir dependência **JNA 5.13.x** em `iped-engine/pom.xml`. Se `net.java.dev.jna:jna` ainda não estiver declarada no parent ou em `iped-engine`, adicionar `<dependency>` com `<scope>compile</scope>`. Rodar `mvn -pl iped-engine -am dependency:tree | grep jna` para confirmar.
- [ ] T003 [P] Adicionar registros em `ThirdParty.txt`: bloco descrevendo **YARA 4.5.x** (URL `https://github.com/VirusTotal/yara`, BSD 3-clause, uso "engine de pattern matching embutida em `tools/yara/<os>/`"); bloco descrevendo **JNA** (Apache 2.0) se não estiver listado. Adicionar `licenses/LICENSE-YARA` (cópia do `COPYING` do upstream) e `licenses/LICENSE-JNA` se necessário.
- [ ] T004 [P] Atualizar `.github/workflows/maven.yml` para instalar `libyara`/`libyara-dev` no job Ubuntu 22.04. Se a versão do apt for inferior a 4.5, criar `.github/scripts/install-yara.sh` que baixa o tarball oficial do upstream e instala em `/usr/local`. Verificação no job: `pkg-config --modversion yara`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: API pública, Configurable, schema de campos, binários nativos e a engine JNA core — tudo que as três user stories vão consumir.

**⚠️ CRITICAL**: nenhuma user story pode começar até este checkpoint.

- [ ] T005 [P] Adicionar três constantes públicas em `iped-api/src/main/java/iped/properties/ExtraProperties.java`: `YARA_RULE = "yara:rule"`, `YARA_TAGS = "yara:tag"`, `YARA_MATCH_DETAIL = "yara:matches"`. Apenas adições; nenhum identificador existente pode ser renomeado/removido. Conforme [contracts/ExtraProperties.contract.md](contracts/ExtraProperties.contract.md).
- [ ] T006 [P] Criar `iped-engine/src/main/java/iped/engine/config/YaraConfig.java` implementando `iped.configuration.Configurable<UTF8Properties>`. Campos e validações conforme [data-model.md → §1](data-model.md) e [contracts/YaraConfig.txt.contract.md](contracts/YaraConfig.txt.contract.md). Leitura via `AbstractPropertiesConfigurable` (mesmo padrão de `HashTaskConfig`/`HashDBLookupConfig`). Charset UTF-8 explícito.
- [ ] T007 [P] Criar `iped-app/resources/config/conf/YaraConfig.txt` (versão canônica) com defaults conforme [contracts/YaraConfig.txt.contract.md](contracts/YaraConfig.txt.contract.md). Comentários em PT-BR (mesmo padrão dos demais `*.txt` em `conf/`). Charset UTF-8.
- [ ] T008 Adicionar a chave `enableYara = false` em `iped-app/resources/config/IPEDConfig.txt`, na seção de habilitação de tasks (próximo às outras `enableXxx`), com comentário curto conforme [contracts/IPEDConfig.keys.contract.md](contracts/IPEDConfig.keys.contract.md).
- [ ] T009 Empacotar os binários nativos: `tools/yara/win64/libyara.dll` (4.5.x do build oficial Windows x64 + módulos `pe`/`elf`/`math`/`hash`/`magic`/`dotnet`/`time`, sem `cuckoo`) e `tools/yara/linux64/libyara.so.10` (build estático com os mesmos módulos). Substituir o placeholder de T001 e atualizar `tools/yara/README.md` com a versão exata empacotada e SHA-256 dos binários.
- [ ] T010 [P] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraMatch.java` (POJO imutável) e `MatchedString` (subentidade) conforme [data-model.md → §4](data-model.md). Campos: `rule`, `namespace`, `tags`, `meta`, `strings[]`; `MatchedString`: `id`, `offset`, `hex`, `truncated`. Sem dependência externa além de Java SE.
- [ ] T011 [P] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraMatchSerializer.java` para serializar lista de `YaraMatch` em JSON conforme [contracts/lucene-fields.contract.md → `yara:matches`](contracts/lucene-fields.contract.md). Usar Jackson (já no classpath via outras tasks; confirmar). Implementar truncamento de hex por `matchHexMaxBytes`. Ordenação determinística: matches por `(namespace asc, rule asc)`; strings por `(id asc, offset asc)`.
- [ ] T012 Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraEngine.java` — bindings JNA finos para `libyara` 4.5.x conforme [research.md → R-02](research.md). Funções expostas: `yr_initialize/yr_finalize`, `yr_compiler_create/destroy/add_file/set_callback/get_rules`, `yr_rules_load`, `yr_rules_destroy`, `yr_rules_scan_mem` (com flag `SCAN_FLAGS_FAST_MODE` configurável). Callback de scan popula `YaraMatch`+`MatchedString`. Carga da biblioteca via `System.loadLibrary("yara")` precedida de `System.setProperty("jna.library.path", ...)` apontando para `tools/yara/<os>/`. Em caso de `UnsatisfiedLinkError`, lançar `YaraEngineUnavailableException` (a task captura e desliga a feature para o caso — FR-014). Todo logging via SLF4J. Nenhum `System.out/err`.
- [ ] T013 [P] Teste unitário `iped-engine/src/test/java/iped/engine/task/yara/YaraConfigTest.java`: carga de `YaraConfig.txt` válido, comportamento com chave inválida (`scanAllItems = blah`), arquivo ausente, conversão de sufixos `K/M/G` para bytes, defaults aplicados quando chave omitida.
- [ ] T014 [P] Teste unitário `iped-engine/src/test/java/iped/engine/task/yara/YaraEngineTest.java`: lifecycle init/finalize idempotente; compile de catálogo válido; carga de `.yarc` válido; `.yarc` corrompido → falha individual + engine continua; regra com `import "cuckoo"` → erro de compile específico isolado, demais regras compilam; scan_mem sobre buffer de teste retorna número correto de matches (compare com expectativa hard-coded).

**Checkpoint**: Foundational pronto — todas as user stories podem ser implementadas em paralelo.

---

## Phase 3: User Story 1 — Aplicar regras YARA durante o processamento (Priority: P1) 🎯 MVP

**Goal**: Quando o perito habilita YARA e aponta para um diretório de regras, o IPED escaneia os artefatos elegíveis durante o processamento normal e persiste os matches no índice Lucene (regra + tags + offsets + bytes).

**Independent Test**: Configurar `YaraConfig.txt` com um diretório contendo 2–3 `.yar` simples (uma string-match, uma hex-pattern, uma com tags), processar uma evidência sintética com arquivos que deveriam casar, verificar que (a) os campos `yara:rule`, `yara:tag` e `yara:matches` aparecem nos itens corretos no índice e (b) o log final reporta `itemsScanned`, `itemsSkipped` e `topRules`.

### Tests for User Story 1

> **NOTE**: escrever os testes ANTES da implementação; eles devem falhar inicialmente.

- [ ] T015 [P] [US1] Teste unitário `iped-engine/src/test/java/iped/engine/task/yara/YaraRulesetLoaderTest.java`: discovery recursiva de `.yar`/`.yara`/`.yarc` em diretório de fixtures, namespace = basename (sem extensão), comportamento com nomes colidindo entre `.yar` e `.yarc` (precedência do `.yarc`, log warn), diretório vazio (resultado vazio sem erro).
- [ ] T016 [P] [US1] Teste unitário `iped-engine/src/test/java/iped/engine/task/yara/YaraMatchSerializerTest.java`: round-trip lista de `YaraMatch` → JSON → lista de `YaraMatch` produz objetos equivalentes; truncamento aplicado quando hex excede `matchHexMaxBytes`; ordem determinística; JSON parseável por outro parser (Jackson `JsonNode`).
- [ ] T017 [US1] Teste de integração `iped-engine/src/test/java/iped/engine/task/yara/YaraScanTaskIntegrationTest.java`: monta um mini-caso em diretório temporário com 5–10 arquivos sintéticos (texto, PE pequeno, JPEG não-casável); chama `YaraScanTask` em modo standalone (sem `Manager`) com fixtures de regras; valida (a) campos `yara:*` populados corretamente nos itens casados, (b) itens não-casados não recebem campo algum, (c) item acima de `maxFileSizeBytes` é pulado, (d) regra com erro de sintaxe é descartada sem abortar o caso (FR-001/003/004/005/006/012).
- [ ] T018 [US1] Teste de integração `iped-engine/src/test/java/iped/engine/task/yara/IT_YaraVsCli.java` (anotada `@Category(Integration.class)`): roda a CLI `yara` (instalada no CI) sobre 100 amostras controladas + 50 regras públicas; roda `YaraScanTask` sobre as mesmas amostras; compara `(item, rule, offset)` byte-a-byte. Falha o build se houver divergência (SC-004).

### Implementation for User Story 1

- [ ] T019 [P] [US1] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraRulesetLoader.java`: discover recursivo (`Files.walk`) dos diretórios em `YaraConfig.ruleDirectories`, classifica por extensão (`.yar`/`.yara` → source; `.yarc` → precompiled), compila/carrega via `YaraEngine`, retorna `Ruleset` (handle + `failedRules[]` + `engineVersion`). Logging detalhado em SLF4J. Lock estático para garantir uma única invocação por execução do `Manager`.
- [ ] T020 [P] [US1] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraScanner.java`: wrapper por worker; mantém callback dedicado para coletar matches em uma `List<YaraMatch>` thread-local; método `scan(byte[] buffer, int len, int timeoutMs)` retorna a lista construída. Reusa buffer interno entre chamadas.
- [ ] T021 [US1] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraScanTask.java` estendendo `iped.engine.task.AbstractTask`. Lifecycle:
  - `init()` (gate estático `AtomicBoolean`): carrega `YaraConfig`; se `enabled=false` ou diretórios vazios ou `YaraEngine` indisponível → marca task como no-op para todos os workers (FR-013/014); senão dispara o `YaraRulesetLoader`. Inicializa `YaraScanner` por instância (campo de instância — Princípio V).
  - `process(IItem item)`: aplica regra de elegibilidade conforme [research.md → R-06](research.md) (default seletivo, override `scanAllItems`); checa `maxFileSizeBytes`; lê stream do item em buffer único (`IItem.getBufferedInputStream()` ↔ `IOUtils.toByteArray` com cap); chama `YaraScanner.scan(...)` com `perItemTimeoutMs`; se houver matches, popula `IItem.setExtraAttribute(YARA_RULE, ...)`, `setExtraAttribute(YARA_TAGS, ...)` e `setExtraAttribute(YARA_MATCH_DETAIL, serializer.toJson(...))`. Se houver erro (timeout/erro nativo/IO), incrementa contador "skipped" e continua (FR-005).
  - `finish()` (último worker): chama `YaraEngine.destroy(ruleset)` e `yr_finalize()`. Imprime resumo (FR-012).
- [ ] T022 [US1] Editar `iped-app/resources/config/conf/TaskInstaller.xml`: inserir `<task class="iped.engine.task.yara.YaraScanTask"></task>` **após** as tasks de Carving (`KnownMetCarveTask`) e **antes** de `FragmentLargeBinaryTask`/`IndexTask`. Justificativa: scan precisa ver subitens carved, e precisa rodar antes da indexação para que os campos `yara:*` entrem no documento Lucene.
- [ ] T023 [P] [US1] Adicionar chaves de localização em `iped-app/resources/localization/messages.properties` (EN) — `yara.task.name = YARA scan`, `yara.task.description = Apply YARA rules to item content`, e mensagens de log auxiliares mostradas em UI (`yara.task.engineUnavailable`, `yara.task.emptyCatalog`). Chaves conforme [research.md → R-13](research.md).
- [ ] T024 [P] [US1] Espelhar T023 em `iped-app/resources/localization/messages_pt_BR.properties` com tradução PT-BR.

**Checkpoint**: US1 funcional. Processando um caso com `enableYara=true` deve produzir os campos `yara:*` no índice Lucene; testes T015–T018 passam.

---

## Phase 4: User Story 2 — Visualizar, filtrar e marcar artefatos pelas regras YARA (Priority: P2)

**Goal**: Na UI de análise, o perito vê uma seção dedicada a regras YARA no painel de filtros/categorias, filtra os itens por regra com um clique e cria bookmarks a partir desse filtro usando o fluxo já existente.

**Independent Test**: Em um caso já processado (saída da US1), abrir a UI, abrir o painel de metadados/filtros e confirmar que `yara:rule` aparece como faceta com contagens; clicar em uma regra reduz a galeria/tabela aos itens casados; selecionar tudo e criar bookmark funciona.

### Tests for User Story 2

- [ ] T025 [P] [US2] Roteiro de teste manual em `specs/001-yara-rules-engine/manual-tests/us2-ui-filter.md` cobrindo os três Acceptance Scenarios da US2 da spec. Inclui screenshots referenciados (a serem capturados durante execução).
- [ ] T026 [P] [US2] Teste de integração `iped-app/src/test/java/iped/app/ui/filterdecisiontree/YaraFacetIntegrationTest.java` (ou módulo equivalente) que abre o caso de teste produzido pela US1 em modo headless, instancia o componente de filtros do `iped-app`, e verifica que `yara:rule` é exposto como faceta multi-valorada com contagem correta. Pode usar `MetadataPanel` existente como entrypoint.

### Implementation for User Story 2

- [ ] T027 [P] [US2] Adicionar chaves de localização da seção de filtro em `iped-app/resources/localization/messages.properties`: `yara.filter.section = YARA rules`, `yara.filter.noMatches = (no items matched)`, `yara.match.rule = Rule`, `yara.match.tag = Tag`, `yara.match.offset = Offset`, `yara.match.bytes = Bytes (hex)`.
- [ ] T028 [P] [US2] Espelhar em `iped-app/resources/localization/messages_pt_BR.properties`.
- [ ] T029 [US2] Verificar (lendo `iped-app/src/main/java/iped/app/ui/`) se o `MetadataPanel` / `CategoryPanel` existente auto-detecta campos Lucene multi-valor para faceta. Se sim: nenhuma mudança de código além de eventualmente registrar `yara:rule` e `yara:tag` em uma lista de "campos destacados" para serem agrupados sob a label `yara.filter.section`. Se não: criar `iped-app/src/main/java/iped/app/ui/filterdecisiontree/YaraFilterCategory.java` registrando explicitamente uma `Category` que consulta esses dois campos.
- [ ] T030 [US2] Garantir que a view de **detalhe de item** mostra o conteúdo de `yara:matches` (JSON) de forma legível. Verificar `iped-app/src/main/java/iped/app/ui/MetadataPanel.java` ou equivalente. Se o JSON aparece bruto, criar `iped-app/src/main/java/iped/app/ui/viewers/YaraMatchRenderer.java` que faz pretty-print (rule, tags, lista de strings com offset + hex truncado).
- [ ] T031 [US2] Validar que a criação de bookmark sobre seleção filtrada funciona sem código novo (deve, já que o fluxo de bookmark é independente do critério de filtro). Adicionar entrada em T025 (roteiro manual) que cobre isso.

**Checkpoint**: US1 + US2 funcionais. Caso processado pode ser explorado por regras YARA na UI e bookmarks podem ser criados.

---

## Phase 5: User Story 3 — Catálogo por perfil + matches no relatório HTML (Priority: P3)

**Goal**: Cada perfil do IPED (`forensic`, `pedo`, `triage`, `fastmode`, `blind`) tem seu próprio estado de habilitação YARA e (opcionalmente) seu próprio catálogo de regras; o relatório HTML inclui as regras casadas em cada item.

**Independent Test**: Processar dois casos com perfis distintos (`forensic` × `pedo`) apontando para diretórios de regras diferentes; verificar que cada caso aplicou somente seu catálogo. Gerar relatório HTML de um caso com matches; verificar que cada item casado lista regras + tags + offsets na sua página.

### Tests for User Story 3

- [ ] T032 [P] [US3] Teste de integração `iped-engine/src/test/java/iped/engine/task/yara/YaraPerProfileTest.java`: monta dois casos pequenos processados com profiles distintos via `Configuration.loadConfigurables(profile)`; assert que `ruleDirectories` resolvidos diferem e que matches finais correspondem ao catálogo de cada perfil.
- [ ] T033 [P] [US3] Teste de integração `iped-engine/src/test/java/iped/engine/task/HTMLReportTaskYaraTest.java`: dado um caso de teste com matches gerado pela US1, executa `HTMLReportTask` e assert que o HTML resultante contém uma seção "YARA matches" para itens casados, listando rule + tags + offset + hex (FR-010), com renderização segura (sem injeção HTML a partir do conteúdo bruto).

### Implementation for User Story 3

- [ ] T034 [P] [US3] Editar `iped-app/resources/config/profiles/forensic/IPEDConfig.txt`: setar `enableYara = true`. Adicionar comentário curto referenciando `conf/YaraConfig.txt`.
- [ ] T035 [P] [US3] Editar `iped-app/resources/config/profiles/pedo/IPEDConfig.txt`: setar `enableYara = true`.
- [ ] T036 [P] [US3] Adicionar `enableYara = false` explicitamente em `iped-app/resources/config/profiles/triage/IPEDConfig.txt`, `iped-app/resources/config/profiles/fastmode/IPEDConfig.txt` e `iped-app/resources/config/profiles/blind/IPEDConfig.txt` (clareza > implícito).
- [ ] T037 [P] [US3] Criar override por perfil onde aplicável: `iped-app/resources/config/profiles/forensic/conf/YaraConfig.txt` (catálogo geral) e `iped-app/resources/config/profiles/pedo/conf/YaraConfig.txt` (catálogo CSAM-IOC). Apenas as chaves que diferem do canônico precisam estar presentes; o `ConfigurationManager` aplica merge.
- [ ] T038 [P] [US3] Adicionar chaves de localização em `iped-app/resources/localization/messages.properties`: `yara.report.section = YARA matches`, `yara.report.engineVersion = Engine`, `yara.report.scannedBytes = Scanned bytes`. Espelhar em `messages_pt_BR.properties`.
- [ ] T039 [US3] Modificar o template / writer do `HTMLReportTask` (procurar em `iped-engine/src/main/java/iped/engine/task/HTMLReportTask.java` e seus recursos em `iped-engine/src/main/resources/` ou em `iped-app/resources/config/htmlreport/`) para emitir uma seção "YARA matches" por item quando `yara:matches` estiver presente. Renderização: rule + tags como labels, lista de matched strings com offset (decimal + hex) e bytes (hex, truncado conforme `matchHexMaxBytes`). **HTML-safe**: passar todo conteúdo derivado do item por escape HTML (`StringEscapeUtils.escapeHtml4` ou equivalente já em uso pela task) — FR-010.

**Checkpoint**: as três user stories funcionam independentemente. Diff de comportamento entre perfis observável; relatório HTML cobre matches.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: o modo `--yara-only` (FR-011), documentação atualizada nos `CLAUDE.md`, release notes, benchmarks de validação.

- [ ] T040 [P] Editar `iped-app/src/main/java/iped/app/bootstrap/Bootstrap.java`: reconhecer a flag `--yara-only`. Validar combinação conforme [contracts/cli-yara-only.contract.md](contracts/cli-yara-only.contract.md): obriga `-d`/`--output`; rejeita `-i`, `-dname`, `--append`. Propagar para a JVM filha via `CmdLineArgs`. Em caso de combinação inválida, sair com `exit code 1` + mensagem clara. Sem `System.out` — usar SLF4J + retornar exit code não-zero do `Bootstrap`.
- [ ] T041 Editar `iped-app/src/main/java/iped/app/processing/Main.java` (e o `Manager` correspondente em `iped-engine`) para reconhecer o modo "rerun YARA-only":
  - Pular `DataSourceReader`.
  - Abrir o índice Lucene em RW.
  - Filar doc IDs para os workers via `ProcessingQueues` (ou variante mínima).
  - Em cada worker: reconstruir `IItem` via `IndexItem.getItem(doc)`, executar **apenas** `YaraScanTask.process(item)`, e gravar de volta no índice via `IndexWriter.updateDocument(idTerm, newDoc)` — substituição integral dos três campos `yara:*` (FR-011).
  - Atualizar métricas finais: `yara.rerun.itemsProcessed`, `yara.rerun.itemsSkipped`, `yara.rerun.totalSeconds`.
- [ ] T042 [P] Teste de integração `iped-engine/src/test/java/iped/engine/task/yara/YaraRerunIntegrationTest.java`: processa caso pequeno com catálogo R1, modifica catálogo para R2, dispara `--yara-only` em modo programático, valida que os campos `yara:*` no índice refletem exclusivamente R2 (sem mescla com R1).
- [ ] T043 [P] Atualizar `iped-engine/CLAUDE.md`: bloco descrevendo a nova `YaraScanTask`, `YaraConfig`, o subpacote `iped.engine.task.yara`, e o ciclo de vida da engine nativa. Linkar para `specs/001-yara-rules-engine/plan.md` e `specs/001-yara-rules-engine/contracts/`.
- [ ] T044 [P] Atualizar a árvore de release em `CLAUDE.md` (raiz, seção 6 "Como rodar") mencionando `tools/yara/` ao lado de `tools/sleuthkit/`. Adicionar uma linha curta na seção 9 (tabela "Onde editar configurações") apontando para `conf/YaraConfig.txt`.
- [ ] T045 [P] Adicionar entrada em `ReleaseNotes.txt` sob a próxima versão (4.4.0): texto curto descrevendo a feature, dependência adicional libyara/JNA, e links para a documentação interna.
- [ ] T046 Executar manualmente o benchmark descrito em [quickstart.md → §9](quickstart.md). Documentar os números (`tempo(A)`, `tempo(B)`, `tempo(C)`) em `specs/001-yara-rules-engine/perf-runs/<YYYYMMDD>/README.md` para histórico e como evidência dos critérios SC-001 e SC-006.
- [ ] T047 Executar manualmente todo o [quickstart.md](quickstart.md) (passos 1–7) num release recém-construído e marcar a checagem de qualidade em [checklists/requirements.md](checklists/requirements.md) → seção Notes.

**Checkpoint**: feature completa, rerun operacional, documentação propagada, benchmarks coletados.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: sem dependências; pode começar imediatamente.
- **Phase 2 (Foundational)**: depende de Phase 1; **BLOQUEIA** todas as user stories.
- **Phase 3 (US1, MVP)**: depende de Phase 2.
- **Phase 4 (US2)**: depende de Phase 2 + de a US1 estar pelo menos com `YaraScanTask` gravando os campos `yara:*` (para haver dado para filtrar). Em prática: aguardar T021 antes de validar T026/T029.
- **Phase 5 (US3)**: depende de Phase 2 + dos campos serem gerados (T021). T039 (HTML report) depende de a serialização JSON estar correta (T011).
- **Phase 6 (Polish/--yara-only)**: depende de toda a US1 (T015–T024) + parte de US3 (HTML report opcional). Pode começar T043/T044/T045 mais cedo, em paralelo às user stories.

### User Story Dependencies

- **US1 (P1)**: começa após Foundational. Sem dependência de outras stories.
- **US2 (P2)**: depende **operacionalmente** de US1 (precisa de matches para mostrar). Independência de **implementação**: tarefas T025–T031 não tocam código de US1.
- **US3 (P3)**: depende operacionalmente de US1 (precisa de matches para o relatório). Tarefas T032–T039 não modificam US1.

### Within Each User Story

- Testes (T015, T016, T017, T018, T025, T026, T032, T033) **DEVEM** ser escritos primeiro e falhar antes da implementação.
- Models/POJOs antes de services antes de tasks.
- Configuração de profiles (US3) pode ser feita em paralelo à modificação do HTMLReport (US3).

### Parallel Opportunities

- **Phase 1**: T002, T003, T004 em paralelo após T001.
- **Phase 2**: T005, T006, T007 em paralelo (arquivos distintos). T010, T011, T013, T014 em paralelo após T006/T007 estarem prontos. T012 depende de T010/T011 (usa POJOs).
- **Phase 3**: T015, T016 em paralelo (testes de unidade distintos). T019, T020 em paralelo (loader e scanner). T023, T024 em paralelo (localization EN/PT-BR).
- **Phase 4**: T025, T026 (testes) em paralelo. T027, T028 (localization) em paralelo.
- **Phase 5**: T032, T033 (testes) em paralelo. T034–T038 (configs de profile) em paralelo.
- **Phase 6**: T043, T044, T045 em paralelo (docs distintos).

---

## Parallel Example: User Story 1

```text
# Após Foundational completo, lançar testes de US1 em paralelo:
Task T015: Teste YaraRulesetLoaderTest em iped-engine/src/test/java/iped/engine/task/yara/YaraRulesetLoaderTest.java
Task T016: Teste YaraMatchSerializerTest em iped-engine/src/test/java/iped/engine/task/yara/YaraMatchSerializerTest.java

# Após os testes falharem, lançar a implementação dos componentes paralelos:
Task T019: YaraRulesetLoader em iped-engine/src/main/java/iped/engine/task/yara/YaraRulesetLoader.java
Task T020: YaraScanner em iped-engine/src/main/java/iped/engine/task/yara/YaraScanner.java

# Localizar em paralelo enquanto a task principal é escrita:
Task T023: localization EN em iped-app/resources/localization/messages.properties
Task T024: localization PT-BR em iped-app/resources/localization/messages_pt_BR.properties

# T021 (YaraScanTask.java) é sequencial — depende de T019, T020 estarem prontos.
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 (Setup): T001 → (T002, T003, T004 em paralelo).
2. Phase 2 (Foundational): T005, T006, T007 em paralelo → T008, T009 → T010, T011, T013, T014 em paralelo → T012.
3. Phase 3 (US1): T015, T016 (testes) em paralelo → T019, T020 (paralelo) + T023, T024 (paralelo) → T017, T018 (testes) → T021 → T022 (TaskInstaller).
4. **STOP e VALIDAR**: rodar [quickstart.md §§1–3](quickstart.md). MVP entregue.

### Incremental Delivery

1. Setup + Foundational + US1 → MVP (processamento + persistência dos matches).
2. US2 → exploração via UI.
3. US3 → relatório HTML + per-profile.
4. Phase 6 (Polish) → `--yara-only`, docs, benchmarks.

### Parallel Team Strategy

Com 3 devs após Foundational completo:
- **Dev A**: US1 (T015–T024).
- **Dev B**: US2 (T025–T031), começando assim que T021 estiver mergeado.
- **Dev C**: US3 (T032–T039), idem.
- Dev A migra para Polish (T040–T047) ao terminar US1.

---

## Notes

- **[P]** = arquivos distintos, sem dependências em tasks incompletas.
- **[Story]** = traceabilidade para a user story (`spec.md` §User Scenarios & Testing).
- Cada user story deve ser independentemente entregável; checkpoints ao final de cada Phase 3/4/5 são pontos válidos de demo/release parcial.
- **Princípios constitucionais aplicáveis em todo commit**: I (sem renomeação de chaves Lucene existentes), II (extensão via nova task; sem editar tasks existentes), III (`Configurable` + i18n PT-BR/EN), IV (UTF-8 explícito, SLF4J, determinismo), V (uma instância de task por worker; `init/finish` com gate `AtomicBoolean`).
- **Antes de commit em qualquer task**: `mvn -pl <módulo> -am install` no módulo afetado + `mvn test` se houver cobertura relevante (constituição §"Fluxo de Desenvolvimento", item 3).
- **PR final** deve documentar o impacto em: compatibilidade de índice (Princípio I — nenhum), pipeline (Princípio II — task nova), concorrência (Princípio V — engine in-process com mitigações registradas em [plan.md → Complexity Tracking](plan.md)).
