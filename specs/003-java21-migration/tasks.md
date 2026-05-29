---

description: "Task list — Migração do IPED para Java 21 LTS"
---

# Tasks: Migração do IPED para Java 21 LTS

**Input**: Design documents from `specs/003-java21-migration/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: esta é uma migração **preservadora de comportamento** (FR-018). Não há TDD de novas features; as tarefas de "teste" são as de **validação/paridade** que a própria spec exige como gate (FR-002/FR-003/SC-001/SC-002) — incluídas dentro de cada story.

**Organization**: tarefas agrupadas por user story. Como é uma migração cross-cutting, a **Phase 2 (Foundational)** concentra o substrato comum que precisa compilar e carregar no Java 21 antes de qualquer story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependências pendentes).
- **[Story]**: US1–US4 (mapeia para as user stories da spec).
- Caminhos de arquivo exatos nas descrições.

---

## Phase 1: Setup (governança + toolchain e nível de linguagem)

**Purpose**: legitimar a nova baseline e configurar o projeto para alvejar Java 21.

- [ ] T001 **Emenda da constituição PRIMEIRO** (gate de governança V10): atualizar a seção "Restrições de Build" (Java 11 → 21, `release=21`) em `.specify/memory/constitution.md`, com Sync Impact Report e **bump MINOR**. Isto desbloqueia/legitima todas as mudanças de build abaixo (resolve o conflito CRITICAL antes de violá-lo).
- [ ] T002 Instalar **BellSoft Liberica Full JDK 21** (com JavaFX) e apontar `JAVA_HOME` para ele; confirmar `java -version` = 21 (ver [quickstart.md](quickstart.md) §1).
- [ ] T003 Em `pom.xml` (raiz), substituir `maven.compiler.source/target = 11` por `maven.compiler.release = 21`.
- [ ] T004 Bump `maven-compiler-plugin` → 3.13.0 em `pom.xml` e nos POMs que o fixam: `iped-carvers/pom.xml`, `iped-viewers/pom.xml`, `iped-geo/pom.xml`, `iped-app/pom.xml`.
- [ ] T005 Bump `maven-surefire-plugin` → 3.5.x em `iped-carvers/pom.xml`, `iped-viewers/pom.xml`, `iped-app/pom.xml` (e onde mais estiver fixado).
- [ ] T006 [P] Bump `maven-jar-plugin` → 3.4.x e `maven-dependency-plugin` → 3.8.x em `iped-app/pom.xml` e `iped-utils/pom.xml`.
- [ ] T007 [P] Remover `findbugs-maven-plugin` 3.0.0 de `pom.xml` (raiz).

---

## Phase 2: Foundational (substrato — BLOQUEIA todas as stories)

**Purpose**: fazer todo o código compilar e carregar no Java 21 (encapsulamento forte + APIs removidas + Neo4j 5).

**⚠️ CRITICAL**: nenhuma user story roda antes desta fase concluir e o `mvn clean package` + `mvn test` ficarem verdes.

### APIs Java EE removidas (paralelas — arquivos distintos)

- [ ] T008 [P] Substituir `javax.xml.bind.DatatypeConverter` por `java.util.HexFormat` em `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/security/CertificateParser.java`.
- [ ] T009 [P] Substituir `javax.xml.bind.DatatypeConverter` por `java.util.HexFormat` em `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/telegram/TelegramParser.java`.
- [ ] T010 [P] Substituir `javax.xml.bind.DatatypeConverter` por `java.util.HexFormat` em `iped-geo/src/main/java/iped/geo/parsers/GeofileParser.java`.
- [ ] T011 [P] Substituir `javax.xml.bind.DatatypeConverter` por `java.util.HexFormat` em `iped-app/src/main/java/iped/app/timelinegraph/cache/persistance/CachePersistance.java`.
- [ ] T012 [P] Adicionar dependências explícitas `jakarta.xml.bind:jakarta.xml.bind-api` + runtime `org.glassfish.jaxb:jaxb-runtime` em `iped-parsers/iped-parsers-impl/pom.xml` e ajustar imports JAXB em `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/misc/OFCParser.java`.
- [ ] T013 [P] Adicionar dependência explícita `com.google.code.findbugs:jsr305` (iped-engine) e confirmar import em `iped-engine/src/main/java/iped/engine/task/jumplist/PathToGuidConverter.java`.

### Remoção do FST

- [ ] T014 Substituir o cache FST por serialização JDK (`ObjectOutputStream`/`ObjectInputStream`) em `iped-engine/src/main/java/iped/engine/task/regex/RegexTask.java` (remover `FSTConfiguration`/`asByteArray`/`asObject`), confirmando que `Regex` e `dk.brics.automaton.Automaton` são `Serializable`.
- [ ] T015 Remover a dependência `de.ruedigermoeller:fst` de `iped-engine/pom.xml`.

### Bumps de dependências compilação-bloqueantes

- [ ] T016 Bump `lucene.version` 9.2.0 → 9.12.x em `pom.xml` (raiz); manter `lucene-backward-codecs`; **não** tocar `AppAnalyzer`/chaves de campo.
- [ ] T017 Bump `tika.version`/`tika.core.version` → 2.9.2 em `pom.xml` (raiz); avaliar reverter o workaround `SyncMetadata` (commit `b673cf4`) e abandonar o fork `-p1` (TIKA-4126 corrigido upstream).
- [ ] T018 [P] Alinhar `net.java.dev.jna:jna` → 5.14.0 em `iped-engine/pom.xml` e `iped-parsers/iped-parsers-impl/pom.xml`.
- [ ] T019 [P] Substituir `org.bouncycastle:bcpkix-jdk15on` 1.70 por `bcpkix-jdk18on` 1.78.1 (+ `bcprov-jdk18on`) em `iped-engine/pom.xml`.
- [ ] T020 [P] Bump Jersey/HK2/Grizzly → 2.41 (mantendo namespace `javax`) em `iped-engine/pom.xml`.
- [ ] T021 [P] Bump `com.github.luben:zstd-jni` → 1.5.x em `iped-engine/pom.xml`.
- [ ] T022 Verificar no Java 21 e bumpar **somente se necessário**: `opensearch-rest-high-level-client`, `minio`, `postgresql`, `sevenzipjbinding`, DockingFrames, em `iped-engine/pom.xml` e `iped-app/pom.xml` (registrar achados em [research.md](research.md) §13).

### Neo4j 4.4 → 5.26 (maior risco)

- [ ] T023 Bump `org.neo4j:neo4j` 4.4.4 → 5.26.x em `iped-engine/pom.xml` (manter exclusões de slf4j-nop/jaxb/commons-logging).
- [ ] T024 Migrar a API embarcada do Neo4j (engine) em `iped-engine/src/main/java/iped/engine/graph/` (`GraphService.java`, `GraphTask.java` e classes relacionadas) para a API 5.x (`DatabaseManagementServiceBuilder`).
- [ ] T025 [P] Revisar/ajustar a sintaxe Cypher dos templates em `iped-engine/src/main/resources/iped/engine/graph/links/*.cypher` para Neo4j 5.x.
- [ ] T026 Migrar o consumidor Neo4j da UI em `iped-app/src/main/java/iped/app/graph/` (`AppGraphAnalytics.java`, `LoadGraphDatabaseWorker.java`) para a API 5.x.

### JEP (Python embarcado)

- [ ] T027 Bump `jep` 4.0.3 → 4.2.x em `iped-parsers/iped-parsers-impl/pom.xml`.
- [ ] T028 Rebuildar/atualizar o bundle nativo `org.python:python-jep-dlib` (JEP 4.2 + Python) e bumpar a versão na execution `unpack-python` de `iped-app/pom.xml`.

### Inicialização e detecção de versão

- [ ] T029 Adicionar os `--add-opens`/`--add-exports` necessários (Neo4j 5 e libs Swing) em `getCustomJVMArgs()` de `iped-app/src/main/java/iped/app/bootstrap/Bootstrap.java` (validar empiricamente; manter lista mínima).
- [ ] T030 Atualizar `MIN_JAVA_VER`/`MAX_JAVA_VER` (11/14 → 21) em `iped-engine/src/main/java/iped/engine/util/Util.java` conforme [contracts/runtime-version-check.contract.md](contracts/runtime-version-check.contract.md); revisar textos `JavaVersion.*` em `iped-app/resources/localization/iped-engine-messages*.properties` se citarem "11"/"14".

### Checkpoint Foundational

- [ ] T031 `mvn clean package` compila **todos** os módulos no Java 21 (detectar/limpar `target/classes` envenenado: procurar "Unresolved compilation").
- [ ] T032 `mvn test` passa 100% (incl. `Yara*` integration-gated com `YARA_X_LIB_PATH`) — gate FR-002/SC-001.

**Checkpoint**: substrato pronto — as user stories podem começar.

---

## Phase 3: User Story 1 - Processar evidências no Java 21 sem regressão (Priority: P1) 🎯 MVP

**Goal**: o pipeline completo roda no Java 21 e produz resultado forensemente equivalente ao baseline Java 11.

**Independent Test**: processar o dataset de referência no release 21 e comparar os campos C1–C8 contra o caso-baseline Java 11 → zero divergências.

- [ ] T033 [US1] Definir e congelar o **conjunto de dados de referência** + gerar o **caso-baseline no release Java 11** (`-profile forensic`, `-tz` fixo) — ver [research.md](research.md) §16.
- [ ] T034 [US1] Processar o **mesmo** dataset no release Java 21 (mesmo profile/tz/flags) gerando o caso-candidato.
- [ ] T035 [P] [US1] Validar execução de tarefas de **scripting JavaScript** (Nashorn) num caso com `<task script="...js">` configurado (`iped-app/resources/scripts/tasks/`).
- [ ] T036 [P] [US1] Validar execução de tarefas **Python/JEP** e **OCR** (`OCRParser`, `*.py` de task) no Java 21.
- [ ] T037 [P] [US1] Validar que a **GraphTask** (Neo4j 5) constrói o grafo de um caso **novo** processado no 21.
- [ ] T038 [US1] Implementar o procedimento/comparador de paridade (exportar C1–C8 via CSV/Web API/índice, casar por trackID, aplicar exclusões E1–E5 e normalização) conforme [contracts/parity-validation.contract.md](contracts/parity-validation.contract.md).
- [ ] T039 [US1] Executar a comparação de paridade baseline↔21 → **zero divergências** em C1–C8 (gate SC-002); triar e corrigir qualquer regressão.
- [ ] T040 [US1] Medir throughput (itens/s) baseline vs 21 no mesmo hardware/dataset → regressão ≤ 5% (gate SC-005).
- [ ] T041 [US1] **Validar a Web API REST** (FR-010) no Java 21: subir `iped.engine.webapi.Main` (`lib/iped-webapi.jar`) sobre o caso-candidato e confirmar `GET /sources`, `GET /search?q=`, `GET /content/{...}`, `GET /text/{...}`, `GET /thumbnail/{...}` e a Swagger UI respondendo corretamente (stack Jersey 2.41 de T020).

**Checkpoint**: processamento + Web API no Java 21 validados com paridade — **MVP entregável**.

---

## Phase 4: User Story 2 - Abrir e analisar casos pré-existentes sem regressão (Priority: P2)

**Goal**: casos processados por releases Java 11 abrem e são plenamente analisáveis no release 21.

**Independent Test**: abrir um conjunto de casos antigos (inclui portáteis e um com graph store 4.x) no release 21 e exercitar busca, navegação, filtros, bookmarks, **viewers** e relatório.

- [ ] T042 [US2] Verificar abertura/busca de um caso antigo (índice Lucene Java 11) na UI 21 — busca full-text, navegação, filtros, galeria (gate FR-004; Princípio I).
- [ ] T043 [US2] Implementar **guarda de degradação** ao abrir graph store Neo4j **4.x** (formato incompatível) no caminho de carregamento de grafo (`iped-engine/src/main/java/iped/engine/graph/GraphService.java` e/ou `iped-app/src/main/java/iped/app/graph/LoadGraphDatabaseWorker.java`): try/catch → aba de grafo indica "reprocessar" sem crashar o caso (gate FR-007).
- [ ] T044 [P] [US2] Verificar abertura de um **caso portátil** gerado no build Java 11 (gate FR-005).
- [ ] T045 [P] [US2] **Validar render dos viewers no JavaFX 21** (FR-011): exercitar `iped-viewers/iped-viewers-impl/.../HtmlViewer.java`, `AudioViewer.java`, `MetadataViewer.java` (WebView), a aba **Mapa** (`iped-geo/.../impl/MapViewer.java` + WebView) e a **Timeline** (`iped-app/.../timelinegraph/IpedChartsPanel.java`); confirmar render correto e ausência de exceções de shutdown JavaFX (regressão #2874).
- [ ] T046 [US2] Rodar o **conjunto de validação de casos antigos** (search/navigate/report) no release 21 (gate SC-003).

**Checkpoint**: continuidade operacional de casos antigos validada (US1 + US2 funcionam de forma independente).

---

## Phase 5: User Story 3 - Buildar e testar em toolchain suportada (Priority: P3)

**Goal**: build e CI sustentáveis no Java 21.

**Independent Test**: o pipeline de CI builda e roda os testes no Java 21 com sucesso.

- [ ] T047 [US3] Substituir os jobs `build-java11`/`build-java14` por **um job Java 21** em `.github/workflows/maven.yml` (`actions/setup-java@v4`, `distribution: liberica`, `java-version: 21`, com JavaFX); remover o download do BellSoft 14; manter instalação de ferramentas nativas + verificação do `tools/yara-x/linux64/libyara_x_capi.so` + `jep==4.2.x`.
- [ ] T048 [US3] Confirmar `mvn -B package` + testes verdes no CI Java 21 (gate FR-013).
- [ ] T049 [P] [US3] Atualizar instruções de build/run para Java 21 na seção §5 de `CLAUDE.md` (raiz) e em READMEs/wiki de contribuição.

**Checkpoint**: contribuições novas são validadas no Java 21.

---

## Phase 6: User Story 4 - Distribuir release com runtime Java 21 (Priority: P3)

**Goal**: release distribuível com runtime 21 (embarcado no Windows; do sistema no Linux).

**Independent Test**: instalar o release em máquina Windows sem Java e em Linux com Java 21 do sistema, e processar um caso de ponta a ponta.

- [ ] T050 [US4] Publicar o zip do **Liberica Full JDK 21** no maven do projeto e bumpar o artefato `java:jre` (11.0.13 → 21.0.x) na execution `unpack-jre` de `iped-app/pom.xml`.
- [ ] T051 [US4] Gerar o release e validar a árvore `target/release/iped-4.4.0/` com o runtime 21 embarcado (Windows).
- [ ] T052 [P] [US4] Smoke **Windows sem Java**: instalar o release e processar um caso pequeno de ponta a ponta, confirmando as ferramentas nativas (Sleuthkit out-of-process, ImageMagick, Tesseract/JEP) (gate SC-004/FR-009).
- [ ] T053 [P] [US4] Smoke **Linux com Java 21 do sistema**: iniciar e confirmar ferramentas nativas (Sleuthkit out-of-process, OCR/JEP, ImageMagick, LibreOffice, RegRipper) (gate SC-004/FR-009).
- [ ] T054 [P] [US4] Adicionar teste unitário de `Util.getJavaVersionWarn()` (21/21.0.x → null; 17/25/11 → mensagem correta) e confirmar ausência de aviso na inicialização em 21 (gate FR-012/SC-008).

**Checkpoint**: release distribuível e validado nas duas plataformas.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: documentação e fechamento (a emenda da constituição foi adiantada para T001).

- [ ] T055 Registrar todas as dependências novas/atualizadas em `ThirdParty.txt` e anexar licenças em `licenses/` (Princípio Build).
- [ ] T056 [P] Atualizar baselines/versões de dependências em `CLAUDE.md` (raiz §3), `iped-engine/CLAUDE.md` (§14), `iped-app/CLAUDE.md` (§1/§6/§12).
- [ ] T057 [P] Atualizar `ReleaseNotes.txt` com a entrada da migração para Java 21.
- [ ] T058 Sweep de regressão final por [quickstart.md](quickstart.md) §7 (todos os gates: build, testes, paridade, perf, casos antigos, Web API, viewers, distribuição, runtime limpo).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: começa imediatamente. **T001 (emenda) primeiro** — legitima T003+ (mudança de baseline de build).
- **Foundational (Phase 2)**: depende do Setup — **BLOQUEIA** todas as user stories. Concluída só com T031 (build) + T032 (testes verdes).
- **User Stories (Phase 3–6)**: dependem da Foundational.
  - US1 (P1) é o MVP. US2 depende da Foundational (e a guarda T043 depende de T023–T026).
  - US3 (CI) e US4 (distribuição) podem rodar em paralelo a US1/US2 após a Foundational, mas o smoke de distribuição (US4) só faz sentido após o build do release (T051).
- **Polish (Phase 7)**: após as stories desejadas.

### User Story Dependencies

- **US1 (P1)**: após Foundational. Independente das demais (T041 Web API usa o caso-candidato de T034).
- **US2 (P2)**: após Foundational. T043 usa o trabalho de Neo4j (T023–T026); T045 valida JavaFX. Testável de forma independente de US1.
- **US3 (P3)**: após Foundational. Independente.
- **US4 (P3)**: após Foundational; T052/T053 dependem de T050–T051.

### Parallel Opportunities

- Setup: T006, T007 em paralelo.
- Foundational: T008–T013 (APIs removidas, arquivos distintos) em paralelo; T018–T021 são bumps independentes (mas tocam `iped-engine/pom.xml` — coordenar para evitar conflito no mesmo arquivo); T025 paralelo às demais de Neo4j.
- US1: T035, T036, T037 em paralelo (validações independentes).
- US2: T044, T045 em paralelo.
- US4: T052, T053, T054 em paralelo.
- Polish: T056, T057 em paralelo.

---

## Parallel Example: Phase 2 (APIs removidas)

```text
# Correções de API removida em arquivos distintos — paralelas:
T008 CertificateParser.java  → HexFormat
T009 TelegramParser.java     → HexFormat
T010 GeofileParser.java      → HexFormat
T011 CachePersistance.java   → HexFormat
T012 OFCParser.java          → JAXB explícito
T013 PathToGuidConverter.java→ jsr305
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 (Setup, **emenda primeiro**) → 2. Phase 2 (Foundational, CRÍTICA) → 3. Phase 3 (US1) → **PARAR e VALIDAR** paridade + Web API → release de prova.

### Incremental Delivery

1. Setup + Foundational → substrato pronto (compila + testes verdes).
2. US1 → valida paridade de processamento + Web API → **MVP**.
3. US2 → valida casos antigos + viewers JavaFX.
4. US3 → CI no 21.
5. US4 → distribuição validada.
6. Polish → docs + sweep final.

### Notes

- [P] = arquivos diferentes, sem dependências; **cuidado** com múltiplas edições no mesmo `pom.xml` (não são realmente paralelas).
- **T001 (emenda da constituição) é pré-requisito de tudo** — resolve o conflito de governança antes que as mudanças de build o violem.
- Commit após cada tarefa ou grupo lógico; parar em qualquer checkpoint para validar a story.
- Princípio IV (determinismo): a paridade (T038–T039) é o gate que protege a integridade forense — não pular.
