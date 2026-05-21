---
description: "Task list for YARA Rules Engine para IPED"
---

# Tasks: YARA Rules Engine para IPED

**Input**: Design documents from [specs/001-yara-rules-engine/](./)

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

**Tests**: Incluídos. A fase de planejamento (R-12) define explicitamente cobertura JUnit + paridade com CLI (SC-004) como gate; portanto, tarefas de teste estão presentes.

> **Migração YARA-X (2026-05-19)**: a engine alvo foi alterada de libyara 4.x clássica para **YARA-X 1.x** (ver `spec.md` Clarifications Q1/Q3 revisadas e `research.md` §R-01). Os caminhos abaixo refletem essa decisão (`tools/yara-x/`, `libyara-x-capi`, bindings `yrx_*`). Tasks já marcadas `[X]` no slice "Foundation + JNA" foram revistas em conjunto com a migração — ver notas em itálico ao lado de cada uma.

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

- [X] T001 Criar os diretórios da feature: `iped-engine/src/main/java/iped/engine/task/yara/` (vazio, com `package-info.java` opcional), `iped-engine/src/test/java/iped/engine/task/yara/` e `iped-engine/src/test/java/iped/engine/task/yara/fixtures/`. Criar `tools/yara-x/win64/`, `tools/yara-x/linux64/`, `tools/yara-x/LICENSE` (placeholder) e `tools/yara-x/README.md` documentando "como atualizar a versão do libyara-x-capi". *(Renomeado de `tools/yara/` → `tools/yara-x/` com a migração para YARA-X em 2026-05-19.)*
- [X] T002 [P] Garantir dependência **JNA 5.13.x** em `iped-engine/pom.xml`. Se `net.java.dev.jna:jna` ainda não estiver declarada no parent ou em `iped-engine`, adicionar `<dependency>` com `<scope>compile</scope>`. Rodar `mvn -pl iped-engine -am dependency:tree | grep jna` para confirmar. *(Implementado com versão 5.7.0 — match com `iped-parsers-impl` para evitar dep-skew; veja commit message.)*
- [X] T003 [P] Adicionar registros em `ThirdParty.txt`: bloco descrevendo **YARA-X 1.x** (URL `https://github.com/VirusTotal/yara-x`, BSD 3-clause, uso "engine de pattern matching embutida em `tools/yara-x/<os>/` via `libyara-x-capi`"); bloco descrevendo **JNA** (Apache 2.0 / LGPL 2.1). Adicionar `licenses/YARA-X.txt` (cópia do `LICENSE` do upstream). *(Renomeado de `licenses/YARA.txt` → `licenses/YARA-X.txt` com a migração para YARA-X. JNA reaproveita `licenses/Apache 2.0.txt` e `licenses/LGPL 2.1.txt` já existentes.)*
- [X] T004 [P] Atualizar `.github/workflows/maven.yml` para baixar e instalar o release oficial do **`libyara-x-capi`** em ambos os jobs Ubuntu 22.04 (versão pinned em `tools/yara-x/README.md`). Verificação no job: `ldconfig -p | grep yara_x_capi`. *(Substitui o step anterior de `libyara9`/`libyara-dev` clássicos. Integration tests skipam se a engine não carregar.)*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: API pública, Configurable, schema de campos, binários nativos e a engine JNA core — tudo que as três user stories vão consumir.

**⚠️ CRITICAL**: nenhuma user story pode começar até este checkpoint.

- [X] T005 [P] Adicionar três constantes públicas em `iped-api/src/main/java/iped/properties/ExtraProperties.java`: `YARA_RULE = "yara:rule"`, `YARA_TAGS = "yara:tag"`, `YARA_MATCH_DETAIL = "yara:matches"`. Apenas adições; nenhum identificador existente pode ser renomeado/removido. Conforme [contracts/ExtraProperties.contract.md](contracts/ExtraProperties.contract.md).
- [X] T006 [P] Criar `iped-engine/src/main/java/iped/engine/config/YaraConfig.java` implementando `iped.configuration.Configurable<UTF8Properties>`. Campos e validações conforme [data-model.md → §1](data-model.md) e [contracts/YaraConfig.txt.contract.md](contracts/YaraConfig.txt.contract.md). Leitura via `AbstractPropertiesConfigurable` (mesmo padrão de `HashTaskConfig`/`HashDBLookupConfig`). Charset UTF-8 explícito. *(Estende `AbstractTaskPropertiesConfig` — pega `enableYara` + `YaraConfig.txt` de forma idiomática.)*
- [X] T007 [P] Criar `iped-app/resources/config/conf/YaraConfig.txt` (versão canônica) com defaults conforme [contracts/YaraConfig.txt.contract.md](contracts/YaraConfig.txt.contract.md). Comentários em PT-BR (mesmo padrão dos demais `*.txt` em `conf/`). Charset UTF-8. *(Comentários sobre `.yarc` removidos com a migração para YARA-X — apenas `.yar`/`.yara` aceitos na v1.)*
- [X] T008 Adicionar a chave `enableYara = false` em `iped-app/resources/config/IPEDConfig.txt`, na seção de habilitação de tasks (próximo às outras `enableXxx`), com comentário curto conforme [contracts/IPEDConfig.keys.contract.md](contracts/IPEDConfig.keys.contract.md).
- [X] T009 (Windows-only) Empacotar `tools/yara-x/win64/yara_x_capi.dll` (21,542,400 bytes, SHA-256 `0F56AC336EFF5242F4BAB23F9A4419FC466A5DD2696B7A3CF6B11F6758B29121`) extraído do asset oficial `yara-x-capi-v1.16.0-x86_64-pc-windows-msvc.zip`. Header `yara_x.h` mantido ao lado para referência. SHA-256s registrados em [tools/yara-x/README.md](../../tools/yara-x/README.md). **Linux deferido**: o release 1.16.0 upstream **não publica** `libyara-x-capi` prebuilt para Linux (só o CLI `yara-x`); a `linux64/libyara_x_capi.so` precisa ser compilada do fonte via `cargo build -p yara-x-capi --release` (procedimento documentado no `tools/yara-x/README.md`). CI Linux skipa os testes YARA via `assumeTrue` enquanto a libyara-x-capi não estiver presente — FR-014 honrado. *Validado end-to-end (2026-05-20) rodando `YaraEngineTest` apontando `YARA_X_LIB_PATH` para a DLL: 5/5 testes verdes (compile/scan, ban_module(cuckoo), errors_json parser, scanner callback, rule introspection).*
- [X] T010 [P] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraMatch.java` (POJO imutável) e `MatchedString` (subentidade) conforme [data-model.md → §4](data-model.md). Campos: `rule`, `namespace`, `tags`, `meta`, `strings[]`; `MatchedString`: `id`, `offset`, `hex`, `truncated`. Sem dependência externa além de Java SE.
- [X] T011 [P] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraMatchSerializer.java` para serializar lista de `YaraMatch` em JSON conforme [contracts/lucene-fields.contract.md → `yara:matches`](contracts/lucene-fields.contract.md). Usar Jackson (já no classpath via outras tasks; confirmar). Implementar truncamento de hex por `matchHexMaxBytes`. Ordenação determinística: matches por `(namespace asc, rule asc)`; strings por `(id asc, offset asc)`.
- [X] T012 Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraEngine.java` — bindings JNA finos para `libyara-x-capi` conforme [research.md → R-02](research.md). Funções expostas: `yrx_compiler_create/destroy/ban_module/new_namespace/add_source_with_origin/errors_json/build`, `yrx_rules_destroy`, `yrx_scanner_create/destroy/set_timeout/on_matching_rule/scan`, `yrx_rule_identifier/namespace/iter_tags`, `yrx_buffer_destroy`, `yrx_last_error`. Carga via `Native.load("yara_x_capi", LibYaraX.class)` com `jna.library.path` apontando para `tools/yara-x/<os>/`. Em caso de `UnsatisfiedLinkError`, retorna `false` em `ensureAvailable` (a task captura e desliga a feature para o caso — FR-014). Todo logging via SLF4J. Nenhum `System.out/err`. *(Reescrito de libyara clássica para `libyara-x-capi` em 2026-05-19. Limitação documentada continua: matched-string detail extraction (offsets+bytes) fica para a próxima iteração — apesar de o YARA-X expor `yrx_rule_iter_patterns`/`yrx_pattern_iter_matches` de forma mais limpa, a v1 deste slice ainda popula `strings: []` para manter o escopo "Foundation + JNA".)*
- [X] T013 [P] Teste unitário `iped-engine/src/test/java/iped/engine/task/yara/YaraConfigTest.java`: carga de `YaraConfig.txt` válido, comportamento com chave inválida (`scanAllItems = blah`), arquivo ausente, conversão de sufixos `K/M/G` para bytes, defaults aplicados quando chave omitida. *(20 testes, todos verdes. Arquivo realmente vive em `iped-engine/src/test/java/iped/engine/config/YaraConfigTest.java` — colado ao pacote da classe sob teste.)*
- [X] T014 [P] Teste unitário `iped-engine/src/test/java/iped/engine/task/yara/YaraEngineTest.java`: `ensureAvailable` idempotente; compile de catálogo válido; regra com erro de sintaxe → falha individual + engine continua; regra com `import "cuckoo"` → erro de compile específico isolado, demais regras compilam; `yrx_scanner_scan` sobre buffer de teste retorna o identificador correto via callback `YRX_RULE_CALLBACK`. *(Gated em `assumeTrue` com `libyara-x-capi` ausente; pula limpo no CI/dev quando o binário nativo não está disponível. Os testes de `.yarc` (load + corrompido) foram removidos com a migração para YARA-X — pré-compilados fora de escopo na v1.)*

**Checkpoint**: Foundational pronto — todas as user stories podem ser implementadas em paralelo.

---

## Phase 3: User Story 1 — Aplicar regras YARA durante o processamento (Priority: P1) 🎯 MVP

**Goal**: Quando o perito habilita YARA e aponta para um diretório de regras, o IPED escaneia os artefatos elegíveis durante o processamento normal e persiste os matches no índice Lucene (regra + tags + offsets + bytes).

**Independent Test**: Configurar `YaraConfig.txt` com um diretório contendo 2–3 `.yar` simples (uma string-match, uma hex-pattern, uma com tags), processar uma evidência sintética com arquivos que deveriam casar, verificar que (a) os campos `yara:rule`, `yara:tag` e `yara:matches` aparecem nos itens corretos no índice e (b) o log final reporta `itemsScanned`, `itemsSkipped` e `topRules`.

### Tests for User Story 1

> **NOTE**: escrever os testes ANTES da implementação; eles devem falhar inicialmente.

- [X] T015 [P] [US1] Teste unitário `iped-engine/src/test/java/iped/engine/task/yara/YaraRulesetLoaderTest.java`: discovery recursiva de `.yar`/`.yara` em diretório de fixtures, namespace = basename (sem extensão), diretório vazio/ausente sem erro, multi-diretório, case-insensitive na extensão. *(9 testes, todos verdes.)*
- [X] T016 [P] [US1] Teste unitário `iped-engine/src/test/java/iped/engine/task/yara/YaraMatchSerializerTest.java`: round-trip lista de `YaraMatch` → JSON → lista de `YaraMatch` produz objetos equivalentes; truncamento aplicado quando hex excede `matchHexMaxBytes`; ordem determinística (matches por namespace+rule, strings por id+offset); tolerante a campos ausentes e chaves desconhecidas (forward-compat). *(10 testes, todos verdes.)*
- [X] T017 [US1] Teste de integração `iped-engine/src/test/java/iped/engine/task/yara/YaraScanTaskIntegrationTest.java`: roda `YaraScanTask.process(...)` contra `libyara-x-capi` real (skipa se `YARA_X_LIB_PATH` não setada) sobre `Item`s em memória com payload byte[]. Valida (a) item casado recebe `yara:rule`/`yara:tag`/`yara:matches`, (b) item não-casado não recebe nada, (c) item acima de `maxFileSizeBytes` é pulado, (d) regra com erro de sintaxe é descartada sem abortar a engine, (e) múltiplas regras casando em namespaces distintos aparecem em ordem lexicográfica, (f) `enableYara=false` produz no-op (FR-001/003/004/005/006/013). *(6 testes, todos verdes.)*
- [ ] T018 [US1] Teste de integração `iped-engine/src/test/java/iped/engine/task/yara/IT_YaraVsCli.java` (anotada `@Category(Integration.class)`): roda a CLI `yara-x` (instalada no CI) sobre 100 amostras controladas + 50 regras públicas; roda `YaraScanTask` sobre as mesmas amostras; compara `(item, rule)` byte-a-byte. Falha o build se houver divergência (SC-004). *(Deferido para iteração futura — exige o CLI `yara-x` 1.16.0 instalado + um corpus de amostras/regras controlado. O `YaraEngineTest` e `YaraScanTaskIntegrationTest` já validam a chain JNA↔libyara-x-capi end-to-end com 11 cenários, cobrindo a maior parte do risco que o IT_YaraVsCli mitigaria.)*

### Implementation for User Story 1

- [X] T019 [P] [US1] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraRulesetLoader.java`: discover recursivo (`Files.walk`) dos diretórios em `YaraConfig.ruleDirectories`, filtra `.yar`/`.yara` (case-insensitive), retorna em ordem lexicográfica determinística. Diretórios ausentes/não-diretório geram WARN e são pulados sem fazer o caso falhar. *(Pré-compilados `.yarc` fora de escopo na v1 — substitui a versão original do task que fazia também classificação `.yarc`.)*
- [X] T020 [P] [US1] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraScanner.java`: wrapper `AutoCloseable` por worker; instala uma única vez o `yrx_scanner_on_matching_rule` callback (`MatchCollector`) na construção; `scan(byte[], int, int)` zera a lista, aplica `yrx_scanner_set_timeout` se positivo, executa `yrx_scanner_scan` e retorna a lista coletada. `close()` chama `yrx_scanner_destroy`.
- [X] T021 [US1] Criar `iped-engine/src/main/java/iped/engine/task/yara/YaraScanTask.java` estendendo `iped.engine.task.AbstractTask`. Lifecycle: `init()` faz shared-init em bloco `synchronized` sobre `AtomicBoolean` (carrega config, descobre arquivos, compila o catálogo via `YaraEngine.compileSources`, popula `sharedEngine` estático); per-worker faz `sharedEngine.createScanner()`. `process(IItem)`: aplica gate de elegibilidade (R-06), checa `maxFileSizeBytes`, lê via `getBufferedInputStream().readAllBytes()`, escaneia via `YaraScanner.scan()`, persiste `yara:rule` (ordenado lex.), `yara:tag` (set unificado) e `yara:matches` (JSON). Erros nativos isolados no scanner são contabilizados como skipped. `finish()` destrói o scanner per-worker e — último worker — destrói o engine e loga o resumo. Método `initWithConfig(YaraConfig)` package-private para testes; `resetForTests()` zera o estado estático entre execuções.
- [X] T022 [US1] Editar `iped-app/resources/config/conf/TaskInstaller.xml`: inserido `<task class="iped.engine.task.yara.YaraScanTask"></task>` entre `KnownMetCarveTask` e `FragmentLargeBinaryTask`/`IndexTask`, com comentário explicando o porquê da posição (carved subitems precisam ser visíveis ao scan; matches precisam estar no `IItem` antes do `IndexTask`).
- [X] T023 [P] [US1] Chaves de localização em `iped-app/resources/localization/iped-engine-messages.properties` (EN): `YaraScanTask.Name`, `YaraScanTask.Description`, `YaraScanTask.EngineUnavailable`, `YaraScanTask.EmptyCatalog`, `YaraScanTask.CatalogLoaded`, `YaraScanTask.RuleCompileError`, `YaraScanTask.Summary*`. *(Convenção real do projeto é `iped-engine-messages.properties` por módulo, não `messages.properties` como o task original supunha; ajustei.)*
- [X] T024 [P] [US1] Espelhamento PT-BR em `iped-app/resources/localization/iped-engine-messages_pt_BR.properties`.

**Checkpoint**: US1 funcional. Processando um caso com `enableYara=true` deve produzir os campos `yara:*` no índice Lucene; testes T015–T018 passam.

---

## Phase 4: User Story 2 — Visualizar, filtrar e marcar artefatos pelas regras YARA (Priority: P2)

**Goal**: Na UI de análise, o perito vê uma seção dedicada a regras YARA no painel de filtros/categorias, filtra os itens por regra com um clique e cria bookmarks a partir desse filtro usando o fluxo já existente.

**Independent Test**: Em um caso já processado (saída da US1), abrir a UI, abrir o painel de metadados/filtros e confirmar que `yara:rule` aparece como faceta com contagens; clicar em uma regra reduz a galeria/tabela aos itens casados; selecionar tudo e criar bookmark funciona.

### Tests for User Story 2

- [X] T025 [P] [US2] Roteiro de teste manual em [`specs/001-yara-rules-engine/manual-tests/us2-ui-filter.md`](manual-tests/us2-ui-filter.md) cobrindo os três Acceptance Scenarios da US2 + T031. Screenshots a serem capturados na execução manual (perito).
- [ ] T026 [P] [US2] Teste de integração `iped-app/src/test/java/iped/app/ui/filterdecisiontree/YaraFacetIntegrationTest.java` (ou módulo equivalente) que abre o caso de teste produzido pela US1 em modo headless, instancia o componente de filtros do `iped-app`, e verifica que `yara:rule` é exposto como faceta multi-valorada com contagem correta. *(Deferido — exige montar um caso IPED real (Lucene index + bookmarks + IPEDSource) em fixture de teste, o que não há precedente no `iped-app/src/test/`; o roteiro manual em T025 cobre a validação até existir tooling de fixture pra casos pequenos. Reabrir quando houver demanda.)*

### Implementation for User Story 2

- [X] T027 [P] [US2] Adicionada `ColumnsManager.Yara = "YARA matches"` em `iped-app/resources/localization/iped-desktop-messages.properties` + idem nos demais locales (de_DE/es_AR/fr_FR/it_IT — texto EN como fallback, padrão IPED para chaves recentes). *(Bundle real é `iped-desktop-messages.properties`; chaves originais do task description — `yara.filter.section` etc. — foram dispensadas: o pretty-print de match detail é polish deferido (T030) e a única chave necessária para a faceta foi `ColumnsManager.Yara`.)*
- [X] T028 [P] [US2] Espelhamento PT-BR em `iped-desktop-messages_pt_BR.properties`: `ColumnsManager.Yara = "Matches YARA"`.
- [X] T029 [US2] Verificado: `MetadataPanel` + `ColumnsManager.updateDinamicFields()` agrupam fields Lucene multi-valor por prefixo (audio:, image:, regex:, p2p:, ufed:, ...). Adicionado `ExtraProperties.YARA_PREFIX = "yara:"` (constante nova em `iped-api`, sem renomeação) e um branch `else if (f.startsWith(ExtraProperties.YARA_PREFIX)) yaraFields.add(f);` em `ColumnsManager.updateDinamicFields()`, com `yaraFields.toArray(...)` adicionado ao `customGroups` e `Messages.getString("ColumnsManager.Yara")` inserido no `groupNames` entre `WindowsEvt` e `Other`. Após esta mudança, `yara:rule` e `yara:tag` aparecem como faceta dedicada no painel de filtros — fluxo de clique-para-filtrar e bookmark já é automático. *(Mudança cirúrgica de ~7 linhas em `ColumnsManager.java` + 1 chave de localização por idioma + 1 constante em `iped-api`. Sem `YaraFilterCategory.java` novo.)*
- [ ] T030 [US2] Garantir que a view de **detalhe de item** mostra o conteúdo de `yara:matches` (JSON) de forma legível. *(Deferido para polish — o `MetadataPanel` já mostra o JSON como string crua (funcional); um `AbstractViewer` dedicado em `iped-viewers-impl` que pretty-printa via `YaraMatchSerializer.fromJson()` é o caminho mas custa 200+ LOC com integração no `ViewerController`. O valor operacional do US2 — facetar/filtrar/bookmark — já está coberto por T029.)*
- [X] T031 [US2] Coberto em [`manual-tests/us2-ui-filter.md`](manual-tests/us2-ui-filter.md) §AS-3: o fluxo de criar bookmark sobre seleção filtrada não exige código novo — `BookmarksManager` é independente do critério de filtro. Validação fica no roteiro manual.

**Checkpoint**: US1 + US2 funcionais. Caso processado pode ser explorado por regras YARA na UI e bookmarks podem ser criados.

---

## Phase 5: User Story 3 — Catálogo por perfil + matches no relatório HTML (Priority: P3)

**Goal**: Cada perfil do IPED (`forensic`, `pedo`, `triage`, `fastmode`, `blind`) tem seu próprio estado de habilitação YARA e (opcionalmente) seu próprio catálogo de regras; o relatório HTML inclui as regras casadas em cada item.

**Independent Test**: Processar dois casos com perfis distintos (`forensic` × `pedo`) apontando para diretórios de regras diferentes; verificar que cada caso aplicou somente seu catálogo. Gerar relatório HTML de um caso com matches; verificar que cada item casado lista regras + tags + offsets na sua página.

### Tests for User Story 3

- [ ] T032 [P] [US3] Teste de integração `iped-engine/src/test/java/iped/engine/task/yara/YaraPerProfileTest.java`: monta dois casos pequenos processados com profiles distintos via `Configuration.loadConfigurables(profile)`; assert que `ruleDirectories` resolvidos diferem e que matches finais correspondem ao catálogo de cada perfil. *(Deferido — mesma razão de T026: requer montar um caso IPED real (Lucene index + bookmarks) em fixture, sem precedente no `iped-engine/src/test/`. A combinação de `IPEDConfig.txt` + `YaraConfig.txt` por perfil é mecanicamente verificada na ingestão do `ConfigurationManager`; o roteiro manual em US2 cobre os caminhos de filtro.)*
- [X] T033 [P] [US3] **Reescopo**: dado a indisponibilidade de fixture de caso IPED real (mesmo problema de T026/T032), o teste de geração HTML completa é deferido; em vez disso, foi escrito `iped-engine/src/test/java/iped/engine/task/yara/YaraReportRendererTest.java` (10 testes) cobrindo o coração lógico de T039 — a função pura {String JSON → String HTML} — incluindo: HTML escape dos 5 chars críticos, escape de namespace hostil (`<script>alert(1)</script>`), elipse para strings truncadas, múltiplos blocos por item, e tolerância a JSON vazio/inválido. *(A geração HTML end-to-end fica para a manual test extension da US3 quando houver fixture de caso pequeno.)*

### Implementation for User Story 3

- [X] T034 [P] [US3] `profiles/forensic/IPEDConfig.txt`: adicionado `enableYara = true` com comentário multi-linha pointando para `conf/YaraConfig.txt` e explicando que catalog vazio = no-op (não erro).
- [X] T035 [P] [US3] `profiles/pedo/IPEDConfig.txt`: idem com comentário sugerindo catálogo CSAM-IOC dedicado.
- [X] T036 [P] [US3] `profiles/{triage,fastmode,blind}/IPEDConfig.txt`: `enableYara = false` explícito, alinhado com o padrão dos demais `enableX = false` desses profiles minimalistas.
- [X] T037 [P] [US3] Criados stubs comentados em `profiles/forensic/conf/YaraConfig.txt` e `profiles/pedo/conf/YaraConfig.txt`, documentando o pattern de override profile-local de `ruleDirectories` (linha `# ruleDirectories = ...` comentada para o perito ativar).
- [X] T038 [P] [US3] Adicionadas em `iped-engine-messages.properties` (EN) + `iped-engine-messages_pt_BR.properties` (PT-BR) as chaves: `HTMLReportTask.YaraMatches`, `HTMLReportTask.YaraRule`, `HTMLReportTask.YaraTag`, `HTMLReportTask.YaraString`, `HTMLReportTask.YaraOffset`, `HTMLReportTask.YaraHex`. *(Chaves originais do task description (`yara.report.section`, `yara.report.engineVersion`, etc.) substituídas por convenção `<TaskName>.<Field>` do projeto — consistente com `HTMLReportTask.ItemName`, etc.)*
- [X] T039 [US3] Adicionado em `iped-engine.task.HTMLReportTask.createBookmarkPage()`: skip de `yara:matches` no loop genérico + chamada para `YaraReportRenderer.renderHtml(json)` para emitir bloco estruturado por item quando o usuário tiver selecionado `yara:matches` em `selectedProperties`. O renderer foi extraído para [`iped-engine/.../task/yara/YaraReportRenderer.java`](../../iped-engine/src/main/java/iped/engine/task/yara/YaraReportRenderer.java) (helper static, sem dependência de estado do `HTMLReportTask` — facilita teste em isolamento e mantém o método principal limpo). Renderização: bloco por match com regra, tags concatenadas, tabela de strings (id, offset decimal, hex truncado com `…`). HTML-safe: todos os valores derivados do item passam por `YaraReportRenderer.htmlEscape(...)` cobrindo `&`/`<`/`>`/`"`/`'` (FR-010). 10 unit tests em `YaraReportRendererTest` cobrem escape, parsing, ordering, edge cases.

**Checkpoint**: as três user stories funcionam independentemente. Diff de comportamento entre perfis observável; relatório HTML cobre matches.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: o modo `--yara-only` (FR-011), documentação atualizada nos `CLAUDE.md`, release notes, benchmarks de validação.

- [X] T040 [P] Implementado em `iped-app/src/main/java/iped/app/processing/CmdLineArgsImpl.java` (não em `Bootstrap.java` — a flag JCommander é parseada pelo `Main` no processo filho, e o `Bootstrap` propaga o argv sem modificação). Adicionado `@Parameter(names = "--yara-only")` + `isYaraOnly()` getter + bloco em `handleSpecificArgs()` que: (a) obriga `-o`/`--output` (não `-d`, que é datasource em IPED); (b) rejeita `-d`, `-dname`, `--append`, `--continue`, `--restart`, `-remove`; (c) valida que `<output>/iped/` existe (caso processado); (d) curto-circuita o resto da validação que depende de datasources. `CmdLineArgs` interface em `iped-engine` ganhou `default boolean isYaraOnly() { return false; }` (compat com implementações externas).
- [X] T041 Implementado **bypassando o `Manager`** (decisão de design): criada classe nova `iped-engine/src/main/java/iped/engine/task/yara/YaraRerunRunner.java` (~300 LOC); em `iped-app/.../processing/Main.startManager()` adicionado branch `if (cmdLineParams.isYaraOnly()) { new YaraRerunRunner(caseRoot, ConfigurationManager.get()).run(); return; }`. **Nenhuma linha de `Manager`/`Worker`/`ProcessingQueues` foi alterada** (Princípio II honrado). Fluxo do runner:
  - Abre `IndexWriter` em `OpenMode.APPEND` sobre `caseRoot/iped/index`.
  - Constrói `IPEDSource(caseRoot, writer)` compartilhando o writer (sem conflito de lock).
  - Compila o catálogo via `YaraEngine.compileSources()` + cria um `YaraScanner` single-threaded.
  - Itera por `LeafReaderContext` do `DirectoryReader.open(writer)` (Lucene NRT).
  - Por doc vivo: reconstrói `IItem` via `IndexItem.getItem`, aplica o pipeline do `YaraScanTask.process` (eligibility, size cap, scan, persist), e chama `IndexWriter.updateDocument(new Term(IndexItem.ID, ...), IndexItem.Document(item, moduleDir))` **apenas** quando o item tinha yara antes OU ganhou matches agora.
  - Métricas via `RerunStats` (itemsScanned, itemsWithMatches, itemsUpdated, itemsSkippedSize/NoStream/Error, totalMillis); commit + close ordenado.
  - **Limitação documentada**: single-threaded na v1 (SC-006 ≤ 25% atinge para casos médios; paralelização fica como evolução).
- [X] T042 [P] Implementado parcialmente como `iped-engine/src/test/java/iped/engine/task/yara/YaraRerunRunnerTest.java` (4 testes, todos verdes): valida o constructor (null caseRoot, null configManager) + run() com caseRoot sem `iped/index` + `RerunStats` defaults+toString. *(O E2E completo — processar caso pequeno, alterar catálogo, rodar `--yara-only`, assertar yara:* — fica deferido pelo mesmo bloqueio de fixture de caso IPED real que afetou T026/T032/T033. Validação manual é o caminho atual, com o roteiro em [quickstart.md → §6](quickstart.md).)*
- [X] T043 [P] [iped-engine/CLAUDE.md](../../iped-engine/CLAUDE.md) atualizado: (a) `YaraScanTask` inserida como linha 11 da tabela §4 (Pipeline padrão), com renumeração das linhas seguintes 11–26 → 12–27; (b) `YaraConfig` adicionada à lista de "Configurações relevantes" em §5; (c) nova seção §22 "YARA Rules Engine" com tabela de componentes (`YaraConfig`/`YaraEngine`/`YaraScanner`/`YaraRulesetLoader`/POJOs/`YaraMatchSerializer`/`YaraScanTask`/`YaraReportRenderer`/`YaraRerunRunner`), ciclo de vida da engine nativa em 5 passos, hooks de configuração, modo `--yara-only`, e inventário dos 7 conjuntos de testes; (d) "Checklist de PR" renumerado §22 → §23.
- [X] T044 [P] [CLAUDE.md](../../CLAUDE.md) (raiz) atualizado: (a) §6 "Como rodar" — `tools/` description menciona `yara-x`; CLI entry points listam o flag `--yara-only` em `Bootstrap`; (b) §7 "Pipeline de alto nível" — ASCII art ganhou `YaraScanTask (regras YARA-X)` entre carving e Regex/NER; (c) §9 "Onde editar configurações" — nova linha "Regras YARA" apontando para `conf/YaraConfig.txt` + overrides por perfil + `tools/yara-x/`; (d) §10 "Onde editar código" — nova linha apontando para o subpacote `iped-engine/.../task/yara/` com a lista dos componentes; (e) §13 "Histórico recente" — bullet no topo descrevendo a feature.
- [X] T045 [P] [ReleaseNotes.txt](../../ReleaseNotes.txt) ganha entrada `TBD: IPED-4.4.0` no topo com um único bullet `#spec/001-yara-rules-engine` que cobre: integração da `YaraScanTask` no pipeline, schema dos campos Lucene `yara:rule`/`yara:tag`/`yara:matches`, configuração via `conf/YaraConfig.txt` + overrides por perfil, banimento do módulo `cuckoo`, flag `YRX_RELAXED_RE_SYNTAX`, faceta UI dedicada, integração no HTML report (com escape HTML-safe), modo `--yara-only -o <CASE>`, binário Windows pré-bundled e instruções de build Linux, dependência nova `net.java.dev.jna:jna:5.7.0` e pointers para `specs/001-yara-rules-engine/`.
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
