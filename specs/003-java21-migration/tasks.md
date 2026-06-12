---

description: "Task list — Migração do IPED para Java 21 LTS"
---

# Tasks: Migração do IPED para Java 21 LTS

**Input**: Design documents from `specs/003-java21-migration/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: esta é uma migração **preservadora de comportamento** (FR-018). Não há TDD de novas features; as tarefas de "teste" são as de **validação/paridade** que a própria spec exige como gate (FR-002/FR-003/SC-001/SC-002) — incluídas dentro de cada story.

**Organization**: tarefas agrupadas por user story. Como é uma migração cross-cutting, a **Phase 2 (Foundational)** concentra o substrato comum que precisa compilar e carregar no Java 21 antes de qualquer story.

## Progresso da implementação — sessão 2026-05-29/30 (JDK 21 em `H:\java\LibericaJDK-21-Full`)

> 📋 **Registro consolidado, evidências de verificação e pendências em [implementation-report.md](implementation-report.md).** O resumo abaixo é o histórico por commit.

**Verificado por compilação (`mvn clean compile` → BUILD SUCCESS no JDK 21, 16 módulos):**
- T001 emenda da constituição (Java 11→21, v1.2.0).
- T002–T007 toolchain (`release=21`; compiler 3.13.0; surefire 3.5.4; jar 3.4.1; dependency 3.8.1; findbugs removido).
- T014–T015 FST totalmente removido: cache de regex via serialização JDK (T014) + dependência `de.ruedigermoeller:fst` dropada do `iped-engine/pom.xml` (T015, 2026-06-01, build verde + 136 testes).
- T030 version check (`Util.MIN/MAX_JAVA_VER = 21`).
- T009 `TelegramParser`: `parseBase64Binary` → `java.util.Base64` (o uso real era Base64, não Hex).
- T011 `CachePersistance`: `printHexBinary` → `java.util.HexFormat` (uppercase; preserva nomes de cache).
- T018/T020/T021 (deps): **JNA 5.14.0**, **Jersey 2.41**, **zstd-jni 1.5.6-9** (engine + parsers-impl). Engine: **136 testes verdes** no JDK 21 (`mvn -pl iped-engine test`).
- **Launchers `.exe` (T050) — FEITO (2026-06-02)**: os binários pré-compilados pegavam um **Java 11 do registro do Windows** → `UnsupportedClassVersionError (65.0 vs 55.0)`. Agora são **gerados no build** pelo `launch4j-maven-plugin` 2.5.2 num `<profile>` ativado no Windows (`iped-app/pom.xml`): `iped.exe` (console→`iped.jar`) e `bin/IPED-SearchApp.exe` (GUI→`lib/iped-search-app.jar`), ambos com `<jre><path>` **exe-relativo** para o `jre/` embarcado (`jre` / `..\jre`) e **sem `minVersion`** (ignora o registro) + `-Djava.security.manager=allow`. Binários Java-11 versionados **removidos**; ícone extraído p/ `resources/root/iped.ico`. `iped.bat` mantido como fallback. Validado no Windows: `iped.exe`→Bootstrap no JRE 21; `IPED-SearchApp.exe`→UI completa (2 java children do `jre/` embarcado) testado em pasta `iped`. Aprendizado launch4j: tanto `<path>` do JRE quanto `<jar>` (dontWrapJar) são resolvidos relativos ao **dir do exe** (não ao cwd); o root do search app exige pasta de deploy chamada `iped` (`Main.setConfigPath` sobe `iped/lib`→`iped`).
- **JRE embarcado (T050, parcial)**: `unpack-jre` bumpado `java:jre` 11.0.13 → **21.0.11** em `iped-app/pom.xml`. ⚠️ Runtime Java 11 embarcado causava `UnsupportedClassVersionError (class file 65.0 vs 55.0)` ao rodar `iped.exe`. **Requer o usuário publicar** o zip `java:jre:21.0.11` (top-level `jre/`, Liberica Full 21 c/ JavaFX) no maven do projeto antes do `mvn package` resolver.
- **Fix de empacotamento (regressão da própria migração)**: `maven-jar-plugin` do `iped-app` revertido **3.4.1 → 2.6**. O 3.4.0+ aborta a execução `create-jar` ("You have to use a classifier…") → release saía **sem `iped.jar` e com `lib/` incompleto** (sintoma: `iped.exe` → "Unable to access jarfile iped.jar"). Validado com `mvn package`: release completo (iped.jar + iped-search-app/webapi/hashdb + 510 jars em `lib/`).
- T022–T024/T026 **Neo4j 4.4.4 → 5.26.0**: reator compila limpo; runtime resolvido na sessão 2026-05-31 (ver abaixo).

**Pendente / follow-up (não iniciado ou parcial):**
- T008/T010 (`CertificateParser`/`GeofileParser`): uso real é `DatatypeConverter.parseDateTime` (datas) — **mantido via JAXB transitivo** para não arriscar o determinismo forense (Princípio IV); migrar p/ `java.time` exige validação dedicada.
- T012 (OFCParser `JAXBContext`) e T013 (jsr305): compilam/rodam via deps transitivas; tornar explícitas é higiene (FR-014).
- **T016 Lucene 9.12** e **T019 BC jdk18on** revertidos/adiados: Lucene 9.12 muda a API de `LeafReader`/`LeafMetaData` (quebra `SlowCompositeReaderWrapper` — Princípio I); BC `jdk18on` conflita (split-package `org.bouncycastle.*`) com o `jdk15on` transitivo do icepdf. Ambos **já rodam no Java 21** na versão atual — modernizá-los é independente da migração (follow-up dedicado).
- **T017 Tika 2.9** não iniciado (alto risco; toca ~200 parsers). · T027–T028 JEP (rebuild nativo) · T031–T058 testes/validação/distribuição/docs.

## Progresso da implementação — sessão 2026-05-31 (grafo Neo4j 5 out-of-process via Bolt)

> 📋 Detalhes, decisão arquitetural e evidências em [implementation-report.md](implementation-report.md) §2.5/§2.6.

**JRE local + PNG (commits `81d6fb6`, `e1c5e04`):**
- **JRE embarcada da pasta local** `iped-jre/` (gitignored) via `copy-jre` no `iped-app/pom.xml` — substitui o `unpack` do artefato `java:jre` (não publicado). Resolve o pendente de distribuição (T050 parcial) sem depender de publicar o JRE. **⤷ Superado em 2026-06-12**: `java:jre:21.0.11` foi publicado no `iped-maven`; o pom voltou à execution `unpack-jre` e o workaround `copy-jre` foi removido (ver T050).
- **T029 (parcial)** `--add-exports=java.desktop/{com.sun.imageio.plugins.common,sun.awt.image}=ALL-UNNAMED` em `Bootstrap.getCustomJVMArgs()` — o leitor `png-reader` (`com.sun.imageio.plugins.png2`) acessa internos do `java.desktop` (lista via `jdeps -jdkinternals`). Verificado em runtime.

**Grafo Neo4j 5 — reescrito out-of-process + Bolt (build verde; esta sessão):**
- **Conflito-raiz:** Cypher do Neo4j 5.26 exige antlr `4.13.x`; `libfqlite` (undelete SQLite) fixa antlr `4.9.2`; ATN incompatível nos 2 sentidos → não coexistem no mesmo classpath. Decisão (mira OSGi): **isolamento por processo**.
- **T024** (API embarcada do engine): **redesenhada** — não é mais migração in-process. Novo módulo **`iped-graph-server`** roda o Neo4j embarcado + Bolt em JVM filha (classpath `lib/neo4j/`, antlr 4.13). `GraphServiceImpl` reescrito como **cliente do driver Bolt** (spawn do server via `Neo4jChildLauncher`); adapters `Bolt{Node,Relationship,Path}` implementam `graphdb-api` → UI intocada. `GraphImportRunner` (CLI Neo4j 5: `database import full`) e `GraphGenerator`/`GraphPostImport` (post-import embedded no filho) usam o `lib/neo4j` isolado.
- **T025** Cypher 5: `size((n)--())`→`COUNT{...}`; `:A|:B`→`:A|B`; queries de rel retornam `startNode(r)/endNode(r)`. ✅
- **T026** consumidor UI: ✅ **sem mudança** nos 11 arquivos de `iped-app/.../graph/` (adapters preservam o tipo de fronteira `graphdb-api`); só `SearchLinksWorker` trocou `getGraphDb()` → `GraphService`.
- **Empacotamento:** `iped-engine` → `neo4j-graphdb-api` + `neo4j-java-driver`; `iped-app` desempacota o zip do `iped-graph-server` em `lib/neo4j/` com exclusão wildcard dos transitivos (evita vazar engine+antlr-4.13 p/ o `lib/` plano). Gotcha: `CacheTimePeriodEntry` usava `scala.Array.copy` (transitivo do neo4j) → `System.arraycopy`.
- **Verificado:** `mvn clean package` BUILD SUCCESS + separação de classpath confirmada (`lib/` sem cypher/scala/antlr-4.13; `lib/neo4j/` = 239 jars isolados). Import Neo4j 5 validado (graph.db gerado dos CSVs reais).
- **Aba de grafo na UI VALIDADA (2026-05-31):** reprocessou o caso RockPi4 com o build novo e abriu a aba Vínculos — nós renderizam. Caminho Bolt completo (GraphServer out-of-process + driver + adapters + Kharon) OK no Java 21. Dois fixes no caminho: (1) `GraphConfig.json` post-gen usava sintaxe de índice Cypher 4 → `CREATE INDEX IF NOT EXISTS FOR ...` + `GraphPostImport` endurecido (tx por statement); (2) `BoltNode.getDegree()` (stub que lançava) → carrega degree como snapshot do `COUNT { (n)--() }`, nunca lança. Commit `3153a89`.
- ~~**T043** guarda de store 4.x~~: **descartada (2026-06-01)** — casos são autocontidos (JRE + libs empacotadas com o caso), o release novo não abre graph store de outra versão; sem FR-007, não há guarda a implementar.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependências pendentes).
- **[Story]**: US1–US4 (mapeia para as user stories da spec).
- Caminhos de arquivo exatos nas descrições.

---

## Phase 1: Setup (governança + toolchain e nível de linguagem)

**Purpose**: legitimar a nova baseline e configurar o projeto para alvejar Java 21.

- [X] T001 **Emenda da constituição PRIMEIRO** (gate de governança V10): atualizar a seção "Restrições de Build" (Java 11 → 21, `release=21`) em `.specify/memory/constitution.md`, com Sync Impact Report e **bump MINOR**. Isto desbloqueia/legitima todas as mudanças de build abaixo (resolve o conflito CRITICAL antes de violá-lo).
- [X] T002 Instalar **BellSoft Liberica Full JDK 21** (com JavaFX) e apontar `JAVA_HOME` para ele; confirmar `java -version` = 21 (ver [quickstart.md](quickstart.md) §1).
- [X] T003 Em `pom.xml` (raiz), substituir `maven.compiler.source/target = 11` por `maven.compiler.release = 21`.
- [X] T004 Bump `maven-compiler-plugin` → 3.13.0 em `pom.xml` e nos POMs que o fixam: `iped-carvers/pom.xml`, `iped-viewers/pom.xml`, `iped-geo/pom.xml`, `iped-app/pom.xml`.
- [X] T005 Bump `maven-surefire-plugin` → 3.5.x em `iped-carvers/pom.xml`, `iped-viewers/pom.xml`, `iped-app/pom.xml` (e onde mais estiver fixado).
- [X] T006 [P] `maven-dependency-plugin` → 3.8.x (`iped-app`/`iped-utils`). ⚠️ **`maven-jar-plugin` do `iped-app` mantido em 2.6** — NÃO bumpar p/ 3.4.0+: a partir do 3.4.0 o plugin proíbe execuções multi-jar sem classifier (`create-jar`/`create-search-jar`/`create-webapi-jar`/`create-hashdb-jar`) e **quebra o release** (sem `iped.jar`/`lib`).
- [X] T007 [P] Remover `findbugs-maven-plugin` 3.0.0 de `pom.xml` (raiz).

---

## Phase 2: Foundational (substrato — BLOQUEIA todas as stories)

**Purpose**: fazer todo o código compilar e carregar no Java 21 (encapsulamento forte + APIs removidas + Neo4j 5).

**⚠️ CRITICAL**: nenhuma user story roda antes desta fase concluir e o `mvn clean package` + `mvn test` ficarem verdes.

### APIs Java EE removidas (paralelas — arquivos distintos)

- [ ] T008 [P] Substituir `javax.xml.bind.DatatypeConverter` por `java.util.HexFormat` em `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/security/CertificateParser.java`.
- [X] T009 [P] Substituir `javax.xml.bind.DatatypeConverter.parseBase64Binary` por `java.util.Base64` em `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/telegram/TelegramParser.java`.
- [ ] T010 [P] Substituir `javax.xml.bind.DatatypeConverter` por `java.util.HexFormat` em `iped-geo/src/main/java/iped/geo/parsers/GeofileParser.java`.
- [X] T011 [P] Substituir `javax.xml.bind.DatatypeConverter.printHexBinary` por `java.util.HexFormat` em `iped-app/src/main/java/iped/app/timelinegraph/cache/persistance/CachePersistance.java`.
- [ ] T012 [P] Adicionar dependências explícitas `jakarta.xml.bind:jakarta.xml.bind-api` + runtime `org.glassfish.jaxb:jaxb-runtime` em `iped-parsers/iped-parsers-impl/pom.xml` e ajustar imports JAXB em `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/misc/OFCParser.java`.
- [ ] T013 [P] Adicionar dependência explícita `com.google.code.findbugs:jsr305` (iped-engine) e confirmar import em `iped-engine/src/main/java/iped/engine/task/jumplist/PathToGuidConverter.java`.

### Remoção do FST

- [X] T014 Substituir o cache FST por serialização JDK (`ObjectOutputStream`/`ObjectInputStream`) em `iped-engine/src/main/java/iped/engine/task/regex/RegexTask.java` (remover `FSTConfiguration`/`asByteArray`/`asObject`), confirmando que `Regex` e `dk.brics.automaton.Automaton` são `Serializable`.
- [X] T015 **FEITO (2026-06-01)** — Removida a dependência `de.ruedigermoeller:fst:2.57` de `iped-engine/pom.xml` (bloco + exclusão de jackson-core). Era dependência pendurada: T014 já movera o `RegexTask` p/ serialização JDK e nenhum módulo importava `org.nustaq`/`de.ruedigermoeller`. Validado: `mvn -pl iped-engine -am clean install -DskipTests` = BUILD SUCCESS (compila sem o fst em toda a cadeia) + `mvn -pl iped-engine test` = **136 run, 0 Failures, 0 Errors, 2 Skipped**. FST 2.57 abusava de `sun.misc.Unsafe`/reflexão em internals — uma fonte de risco a menos no 21.

### Bumps de dependências compilação-bloqueantes

- [ ] T016 Bump `lucene.version` 9.2.0 → 9.12.x em `pom.xml` (raiz); manter `lucene-backward-codecs`; **não** tocar `AppAnalyzer`/chaves de campo.
- [ ] T017 Bump `tika.version`/`tika.core.version` → 2.9.2 em `pom.xml` (raiz); avaliar reverter o workaround `SyncMetadata` (commit `b673cf4`) e abandonar o fork `-p1` (TIKA-4126 corrigido upstream).
- [X] T018 [P] Alinhar `net.java.dev.jna:jna` → 5.14.0 em `iped-engine/pom.xml` e `iped-parsers/iped-parsers-impl/pom.xml`.
- [ ] T019 [P] Substituir `org.bouncycastle:bcpkix-jdk15on` 1.70 por `bcpkix-jdk18on` 1.78.1 (+ `bcprov-jdk18on`) em `iped-engine/pom.xml`.
- [X] T020 [P] Bump Jersey/HK2/Grizzly → 2.41 (mantendo namespace `javax`) em `iped-engine/pom.xml`.
- [X] T021 [P] Bump `com.github.luben:zstd-jni` → 1.5.x em `iped-engine/pom.xml`.
- [ ] T022 Verificar no Java 21 e bumpar **somente se necessário**: `opensearch-rest-high-level-client`, `minio`, `postgresql`, `sevenzipjbinding`, DockingFrames, em `iped-engine/pom.xml` e `iped-app/pom.xml` (registrar achados em [research.md](research.md) §13).

### Neo4j 4.4 → 5.26 (maior risco)

- [X] T023 Bump `org.neo4j:neo4j` 4.4.4 → **5.26.0** (depois **redesenhado**: `iped-engine` ficou só com `neo4j-graphdb-api` + `neo4j-java-driver`; o engine full foi para o módulo isolado `iped-graph-server`). Ver §2.5 do report.
- [X] T024 **Engine Neo4j 5 — redesenhado para out-of-process** (conflito antlr 4.13 vs libfqlite 4.9). Novo módulo `iped-graph-server` (`GraphServer` Bolt + `GraphPostImport`); `GraphServiceImpl` reescrito como cliente do driver Bolt + adapters `Bolt{Node,Relationship,Path}`; `GraphImportRunner`/`GraphGenerator` usam o `lib/neo4j` isolado via `Neo4jChildLauncher`. Build verde; import validado; **aba na UI pendente de reprocessamento**.
- [X] T025 Cypher 5 ajustado em `GraphServiceImpl` (`size(pattern)`→`COUNT{}`; `:A|:B`→`:A|B`; `startNode(r)/endNode(r)` nas queries de rel). Templates `links/*.cypher` retornam `path` (consumidos via `GraphService.searchPaths` over Bolt).
- [X] T026 Consumidor UI: **sem mudança** nos 11 arquivos de `iped-app/.../graph/` (adapters preservam o tipo de fronteira `graphdb-api`); só `SearchLinksWorker` passou de `getGraphDb()` → `GraphService`.

### JEP (Python embarcado)

**Diagnóstico (2026-06-01): JEP 4.0.3 validado no Java 21 — T027/T028 NÃO são bloqueadores da migração, ADIADAS.** O `ModuleNotFoundError: No module named 'numpy'` visto no run real **não é regressão do 21**: (1) o bundle `python-jep-dlib:3.9.12-4.0.3-19.23.1-2` é byte-a-byte igual ao master Java 11 (`git diff master` na `unpack-python` = vazio) e nunca trouxe numpy (só `dlib`/`jep`/`bs4`/`soupsieve`/`termcolor`/`docopt`); (2) `PythonParser` força o Python embarcado (`setPythonHome` + `IgnoreEnvironmentFlag` + `NoUserSiteDirectory`), então numpy do sistema não vaza — comportamento idêntico ao 11; (3) todas as tasks que importam numpy estão **off por padrão** (`enableFaceRecognition/AgeEstimation/YahooNSFWDetection/CSAMDetector/AudioTranscription = false`) e se auto-desabilitam; (4) o erro é `ModuleNotFoundError` **de dentro do Python** — zero `UnsatisfiedLinkError`/`UnsupportedClassVersion` no log — provando que a ponte nativa JEP→Python carrega e executa no 21 (era o real risco de migração; descartado). numpy + o stack de ML (torch/tensorflow/face_recognition/opencv) são instalados **por task pelo usuário** (User Manual), não bundlados.

- [ ] T027 **[ADIADA — não-bloqueador]** Bump `jep` 4.0.3 → 4.2.x em `iped-parsers/iped-parsers-impl/pom.xml`. Modernização independente do 21 (4.0.3 já roda no 21).
- [ ] T028 **[ADIADA — não-bloqueador]** Rebuildar/atualizar o bundle nativo `org.python:python-jep-dlib` (JEP 4.2 + Python) e bumpar a versão na execution `unpack-python` de `iped-app/pom.xml`. Bloqueada em produzir artefato nativo (Windows: python+jep+dlib) publicado no Maven; não automatizável e desnecessária para a migração.

### Inicialização e detecção de versão

- [X] T029 `--add-opens`/`--add-exports` em `getCustomJVMArgs()` de `Bootstrap.java`: `-Djava.security.manager=allow` + `--add-exports` p/ o leitor PNG (`java.desktop/{com.sun.imageio.plugins.common,sun.awt.image}`). Os `--add-opens` do Neo4j 5 ficam no `Neo4jChildLauncher` (JVM filha do grafo), não no processo principal. Validado em runtime.
- [X] T030 Atualizar `MIN_JAVA_VER`/`MAX_JAVA_VER` (11/14 → 21) em `iped-engine/src/main/java/iped/engine/util/Util.java` conforme [contracts/runtime-version-check.contract.md](contracts/runtime-version-check.contract.md); revisar textos `JavaVersion.*` em `iped-app/resources/localization/iped-engine-messages*.properties` se citarem "11"/"14".

### Checkpoint Foundational

- [X] T031 ✅ (2026-06-02) `mvn clean package` compila **todos os 17 módulos** no Java 21 — BUILD SUCCESS, validado em vários runs full-clean. `target/classes` sempre gerado pelo Maven (clean), sem "Unresolved compilation".
- [X] T032 ✅ (2026-06-03) **100% verde no Java 21, todos os módulos**: reator completo `mvn clean test -fae` no JDK 21 = **17/17 SUCCESS** (local) + **CI verde** (run `26886502532`). `iped-parsers-impl` 183/0/0 (1 skip = JEP/PythonParserTest local), `iped-engine` 146/0/0 (inclui `Yara*` com `YARA_X_LIB_PATH`). ⚠️ **Correção do registro anterior:** `OCRParserTest` PSD/SVG NÃO falhava "por ambiente local" — era **regressão real do Java 21** (png-reader/JEP 396 sem `--add-exports` na JVM de teste), corrigida em `d58cc50bf` (ver §2.7 do implementation-report + T048). Gate FR-002/SC-001.

**Checkpoint**: substrato pronto — as user stories podem começar.

---

## Phase 3: User Story 1 - Processar evidências no Java 21 sem regressão (Priority: P1) 🎯 MVP

**Goal**: o pipeline completo roda no Java 21 e produz resultado forensemente equivalente ao baseline Java 11.

**Independent Test**: processar o dataset de referência no release 21 e comparar os campos C1–C8 contra o caso-baseline Java 11 → zero divergências.

- [ ] T033 [US1] Definir e congelar o **conjunto de dados de referência** + gerar o **caso-baseline no release Java 11** (`-profile forensic`, `-tz` fixo) — ver [research.md](research.md) §16.
- [ ] T034 [US1] Processar o **mesmo** dataset no release Java 21 (mesmo profile/tz/flags) gerando o caso-candidato.
- [ ] T035 [P] [US1] **PARCIAL** — Nashorn carrega/roda os validadores JS de regex (`ScriptValidatorService`, ex. `Example_CRYPTO_POSSIBLE_SEED_PHRASE_*`) no run real → engine JS OK no 21. Falta exercitar explicitamente uma **task `.js`** dedicada (`<task script="...js">`).
- [ ] T036 [P] [US1] **PARCIAL** — Python/JEP **validado** no 21 (JEP 4.0.3 carrega/executa Python; ver §JEP). Falta exercitar **OCR** de fato (`enableOCR=false` no run de paridade) e uma task `.py` ativa.
- [X] T037 [P] [US1] ✅ GraphTask (Neo4j 5) constrói o grafo de **caso novo** no 21 — validado no run real (2026-06-01): post-import Cypher 5 OK (3× `CREATE INDEX IF NOT EXISTS FOR (n:EVIDENCIA)`, grouping contacts, "Generating graph database finished") e aba Vínculos popula. Ver §sessão 2026-05-31.
- [ ] T038 [US1] Implementar o procedimento/comparador de paridade (exportar C1–C8 via CSV/Web API/índice, casar por trackID, aplicar exclusões E1–E5 e normalização) conforme [contracts/parity-validation.contract.md](contracts/parity-validation.contract.md).
- [ ] T039 [US1] Executar a comparação de paridade baseline↔21 → **zero divergências** em C1–C8 (gate SC-002); triar e corrigir qualquer regressão.
- [ ] T040 [US1] Medir throughput (itens/s) baseline vs 21 no mesmo hardware/dataset → regressão ≤ 5% (gate SC-005).
- [X] T041 [US1] ✅ **Web API REST validada no Java 21** (2026-06-02, FR-010) — `iped.engine.webapi.Main` (`F:\test\iped\lib\iped-webapi.jar`) sobre o caso real `F:\test`, JRE 21 embarcado + `-Djava.security.manager=allow`, stack Jersey 2.41/Grizzly. Servidor sobe (`Started listener bound to 127.0.0.1:8089`) e respondem: `GET /sources` (`{"id":"case1","path":"F:\\test"}`), `GET /categories` (29), `GET /search?q=*&sourceID=case1` + queries (`category:"images"`=48726, `type:jpg`=228), `GET /sources/case1/docs/{id}` (props JSON), `/text` (7769 B), `/content` (18830 B), `/thumb` (JPEGs reais 663–1716 B; vazio só p/ itens sem thumb gravado), `GET /swagger.json` (Swagger 2.0). Source JSON: `[{"id":"<str>","path":"<dir-pai-do-iped/>"}]` (`IPEDSource` faz `casePath/iped`). Obs.: no boot houve 1 erro **não-fatal** de JEP (`loadLibrary` em `PythonTask.getConfigurables`) — capturado, não bloqueia (webapi é read-only, sem tasks Python); mesma classe do diagnóstico numpy/JEP.

**Checkpoint**: processamento + Web API no Java 21 validados com paridade — **MVP entregável**.

---

## Phase 4: ~~User Story 2 - Abrir e analisar casos pré-existentes~~ → **Validação de viewers/UI no Java 21 (FR-011)**

> **US2 removida por completo (2026-06-01).** Casos são distribuídos **autocontidos** (cada caso acompanha a JRE + libs do seu processamento em `<caso>/iped/jre` + `<caso>/iped/lib`) e são analisados com esse runtime/libs, **não** com um release de visualização posterior. Logo abrir casos antigos com o release novo deixou de ser requisito — **FR-004/005/006/007** retirados e **T042/T043/T044/T046 descartadas**. Resta apenas a validação de **render de viewers/UI** (FR-011), que se exercita sobre o **caso recém-processado** (aberto na UI no fluxo da US1).

**Goal**: viewers e visualizações renderizam corretamente no JavaFX/Swing do Java 21 (FR-011).

**Independent Test**: abrir um caso recém-processado no release 21 e exercitar todos os viewers + aba de grafo + timeline + mapa.

- ~~T042 [US2] Verificar abertura/busca de um caso antigo (índice Lucene Java 11)~~ — **descartada (2026-06-01)** com FR-004 (casos autocontidos).
- ~~T043 [US2] Guarda de degradação ao abrir graph store Neo4j 4.x~~ — **descartada (2026-06-01)** com FR-007.
- ~~T044 [P] [US2] Verificar abertura de um caso portátil gerado no build Java 11~~ — **descartada (2026-06-01)** com FR-005.
- [ ] T045 [US2] **PARCIAL** — Validar render dos viewers no JavaFX 21 (FR-011). ✅ **grafo** (aba Vínculos) validado; UI de análise navegando o caso + LibreOffice/viewers exercitados no run real (memória 2026-05-30). **Falta confirmar explicitamente**: aba **Mapa** (`iped-geo/.../impl/MapViewer.java` WebView), **Timeline** (`iped-app/.../timelinegraph/IpedChartsPanel.java`) e ausência de exceções de shutdown JavaFX (regressão #2874).
- ~~T046 [US2] Rodar o conjunto de validação de casos antigos (gate SC-003)~~ — **descartada (2026-06-01)** com SC-003.

**Checkpoint**: render de viewers/UI validado no Java 21 (FR-011).

---

## Phase 5: User Story 3 - Buildar e testar em toolchain suportada (Priority: P3)

**Goal**: build e CI sustentáveis no Java 21.

**Independent Test**: o pipeline de CI builda e roda os testes no Java 21 com sucesso.

- [X] T047 [US3] ✅ (commit `64ee8f0e3`) Substituídos `build-java11`/`build-java14` por **um job `build-java21`** em `.github/workflows/maven.yml` (`actions/setup-java@v4`, `distribution: liberica`, `java-version: 21`, `java-package: jdk+fx`, cache maven); `checkout@v1`→`@v4`; removido o tar do BellSoft 14; mantidos ferramentas nativas + verify `libyara_x_capi.so` + `YARA_X_LIB_PATH`. ⚠️ `jep` mantido em **4.0.3** (bump 4.2 = T027/T028 adiados), não 4.2.x.
- [X] T048 [US3] Confirmar `mvn -B package` + testes verdes no CI Java 21 (gate FR-013). **VERDE (2026-06-03, run `26886502532`, 7m50s)** após o fix `d58cc50bf` (png-reader/JEP 396 no surefire `argLine` + JaCoCo 0.8.12). O CI vinha vermelho desde 2026-05-30 por `OCRParserTest` PSD/SVG (regressão Java 21, não ambiente — ver §2.7 do implementation-report).
- [X] T049 [P] [US3] **PARCIAL→✅ no repo** — §5 de `CLAUDE.md` (raiz) atualizada p/ JDK 21 (`b13126ef6`). `README.md`/wiki de contribuição são docs upstream e não foram alterados neste fork.

**Checkpoint**: contribuições novas são validadas no Java 21.

---

## Phase 6: User Story 4 - Distribuir release com runtime Java 21 (Priority: P3)

**Goal**: release distribuível com runtime 21 (embarcado no Windows; do sistema no Linux).

**Independent Test**: instalar o release em máquina Windows sem Java e em Linux com Java 21 do sistema, e processar um caso de ponta a ponta.

- [X] T050 [US4] ✅ **REDESENHADO** (commit `81d6fb63e`) — em vez de publicar/`unpack-jre` o artefato `java:jre`, o `iped-app/pom.xml` agora **copia o JRE da pasta local `iped-jre/jre-21.0.11-full/`** (Liberica Full 21) via `copy-jre` (maven-resources-plugin, phase validate). Não exige mais publicar o zip no maven. Validado: release com `jre/bin/java.exe`=21.0.11. (O dev coloca a Liberica Full 21 em `iped-jre/`, que está no .gitignore.) **⤷ REVERTIDO ao desenho original (2026-06-12)**: `java:jre:21.0.11` (zip ~92 MB, `jre/` no topo, `jre/bin/java.exe` 21.0.11) foi **publicado** em `java/jre/21.0.11/` no `iped-maven`; o pom voltou à execution **`unpack-jre`** (maven-dependency-plugin, phase package, `outputDirectory=${release.dir}`) e o `copy-jre` foi removido. Resolução validada com `mvn dependency:get`. A pasta local `iped-jre/` ficou obsoleta (entrada no `.gitignore` mantida por ora).
- [X] T051 [US4] ✅ (2026-06-02) Release gerado e árvore `target/release/iped-4.4.0-SNAPSHOT/` validada no Windows: `jre/`=Liberica **21.0.11**, `iped.jar` + `lib/` (sem `fst.jar`), `lib/neo4j/` (238 jars, antlr 4.13.2), `python/`, `tools/`, launchers `.exe` gerados por launch4j.
- [X] T052 [P] [US4] ✅ Smoke Windows coberto pelo **processamento real do `RockPi4.E01` de ponta a ponta** (2026-05-30/06-01) usando o `jre/` embarcado (a máquina tem Java 11 no registro, mas o run usa o bundled jre via `iped.exe`/`iped.bat`); ferramentas nativas confirmadas no log: Sleuthkit out-of-process, ImageMagick, libesedb, MPlayer, LibreOffice. gate SC-004/FR-009.
- [X] T053 [P] [US4] Smoke **Linux com Java 21 do sistema** ✅ (2026-06-03, **WSL2 Ubuntu-26.04**, escopo mínimo foco-Java-21). Build Linux limpo (Liberica Full 21.0.11+11 do sistema, **release SEM `jre/`** = FR-015 ✓) + processamento `--nogui` de uma fonte lógica pequena (txt/html/png/pdf/tiff) **ponta a ponta → EXIT 0, "IPED finished."**, índice Lucene (3 segmentos) + grafo Neo4j 5 gerados. **Gates verificados no log:** FR-012 sem aviso de versão (só `[INFO] Java Version: 21.0.11`); **SC-006 zero** `UnsupportedClassVersion`/`IllegalAccessError`/`InaccessibleObject`/`does not export`; SecurityManager instalado e OK no 21 (warning de deprecation esperado, com `-Djava.security.manager=allow`); **png-reader add-exports ativos no runtime Linux** (`JVM Argument: --add-exports=java.desktop/com.sun.imageio.plugins.common=ALL-UNNAMED`, sem `IllegalAccessError` de png2). **3 achados (todos pré-existentes/ambiente, NÃO regressão do 21)** documentados no implementation-report §2.8: (1) `tskJarPath` precisa ser setado no `LocalConfig.txt` no Linux (jar `lib/sleuthkit-4.12.0.p1.jar` está no release, só comentado); (2) `GraphConfig.json` `phone-region="auto"` exige locale com país (WSL default C/POSIX não tem) → `LANG=*_*.UTF-8` ou região explícita; (3) **`Manager.prepareOutputFolder` copia `appRoot/jre` para o caso autocontido incondicionalmente (pré-existente no master)** → no release Linux sem `jre/` (FR-015) lança `IOException` — contornado no smoke com `jre/` placeholder; **decisão (2026-06-03): apenas documentar, sem mudança de código nesta branch** (o deploy Linux deve prover `jre/` no release; fix futuro sugerido = guardar a cópia com `if (jreDir.exists())`). **Exercício COMPLETO das tools nativas (Sleuthkit em E01/OCR/LibreOffice/RegRipper) NÃO feito** (escopo mínimo; sem apt) — todos os `[ERROR]` do run são "tool nativa ausente" não-fatais. Gate SC-004 (runtime Linux) ✅; FR-009 no Linux fica como exercício opcional "completo" (apt + E01).
- [ ] T054 [P] [US4] Adicionar teste unitário de `Util.getJavaVersionWarn()` (21/21.0.x → null; 17/25/11 → mensagem correta) e confirmar ausência de aviso na inicialização em 21 (gate FR-012/SC-008).

**Checkpoint**: release distribuível e validado nas duas plataformas.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: documentação e fechamento (a emenda da constituição foi adiantada para T001).

- [X] T055 ✅ (2026-06-02) `ThirdParty.txt` atualizado p/ as deps distribuídas que a migração mexeu: **Neo4j Community 5.26 + Neo4j Java Driver** (já em `b13126ef6`), **Jersey/Grizzly/HK2** (stack REST, EPL 2.0/GPL2+CPE), **ANTLR 4 Runtime** (BSD-3; 4.9.2 em `lib/` + 4.13.2 em `lib/neo4j/`), **launch4j** (BSD-3/MIT; stub embutido nos `.exe`), + versões em **JNA 5.14.0** e **ZSTD-jni 1.5.6-9**. Licenças: Apache 2.0 e GPL 3 já em `licenses/` (Neo4j); EPL 2.0/BSD-3/MIT citadas por nome+URL (padrão já usado no arquivo). FST removido não constava. *Nota: bundling estrito dos textos EPL-2.0/BSD-3/MIT em `licenses/` fica como follow-up opcional.*
- [X] T056 [P] ✅ Baselines/versões atualizadas: `CLAUDE.md` raiz (Java 21, Neo4j 5.26, Jersey 2.41, FST removido) `b13126ef6`+`934893ee4`+`30c542af9`; `iped-engine/CLAUDE.md` (Java 21+, neo4j-graphdb-api+driver, jersey/zstd, fst removido); `iped-parsers/CLAUDE.md` (jna 5.14); `iped-app/CLAUDE.md` (Java 21, copy-jre, launch4j).
- [X] T057 [P] ✅ (2026-06-02) Entrada `#spec/003-java21-migration` adicionada ao `ReleaseNotes.txt` sob `TBD: IPED-4.4.0` (release=21, SecurityManager/add-exports, FST removido, Neo4j 5 out-of-process Bolt, Cypher 5, bumps JNA/Jersey/zstd, JRE Liberica 21.0.11, launchers launch4j, CI Java 21; adiados Lucene/Tika/BC anotados).
- [ ] T058 **Sweep consolidado (2026-06-02)** — estado de cada gate registrado em [quickstart.md](quickstart.md) §7 (nova coluna "Estado"). **Nenhum gate reprovou.** ✅ fechados: Build (17 módulos), Runtime-limpo (sem incompat JDK), Governança/Docs; ✅ Distribuição no **Windows** (run real + launchers). Validação **informal** forte em Testes (engine 136/136), Paridade (6/781k) e Performance (+1,96%). **Fecho FORMAL pendente** (não reprovado, só não executado): Testes 100% todos os módulos = CI verde (**T048**, requer push); Paridade C1–C8 + Performance = baseline Java 11 (**T033/34/38/39/40**); smoke **Linux** (**T053**). §8 do quickstart atualizado p/ o estado atual (JRE local + launchers launch4j).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: começa imediatamente. **T001 (emenda) primeiro** — legitima T003+ (mudança de baseline de build).
- **Foundational (Phase 2)**: depende do Setup — **BLOQUEIA** todas as user stories. Concluída só com T031 (build) + T032 (testes verdes).
- **User Stories (Phase 3–6)**: dependem da Foundational.
  - US1 (P1) é o MVP. A Phase 4 (FR-011 viewers — ex-US2, T045) depende da Foundational e exercita o caso recém-processado da US1.
  - US3 (CI) e US4 (distribuição) podem rodar em paralelo a US1 após a Foundational, mas o smoke de distribuição (US4) só faz sentido após o build do release (T051).
- **Polish (Phase 7)**: após as stories desejadas.

### User Story Dependencies

- **US1 (P1)**: após Foundational. Independente das demais (T041 Web API usa o caso-candidato de T034).
- **Phase 4 — FR-011 viewers (ex-US2, P2)**: após Foundational. Só **T045** (valida JavaFX/Swing, incl. aba de grafo já validada) sobre o caso recém-processado. (US2 inteira + T042/T043/T044/T046 descartadas em 2026-06-01 — casos autocontidos, FR-004/005/006/007 retirados.)
- **US3 (P3)**: após Foundational. Independente.
- **US4 (P3)**: após Foundational; T052/T053 dependem de T050–T051.

### Parallel Opportunities

- Setup: T006, T007 em paralelo.
- Foundational: T008–T013 (APIs removidas, arquivos distintos) em paralelo; T018–T021 são bumps independentes (mas tocam `iped-engine/pom.xml` — coordenar para evitar conflito no mesmo arquivo); T025 paralelo às demais de Neo4j.
- US1: T035, T036, T037 em paralelo (validações independentes).
- Phase 4 (FR-011 viewers): só T045 (T042/T043/T044/T046 descartadas em 2026-06-01).
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
3. Phase 4 (ex-US2) → valida render de viewers/UI JavaFX (FR-011) sobre o caso recém-processado.
4. US3 → CI no 21.
5. US4 → distribuição validada.
6. Polish → docs + sweep final.

### Notes

- [P] = arquivos diferentes, sem dependências; **cuidado** com múltiplas edições no mesmo `pom.xml` (não são realmente paralelas).
- **T001 (emenda da constituição) é pré-requisito de tudo** — resolve o conflito de governança antes que as mudanças de build o violem.
- Commit após cada tarefa ou grupo lógico; parar em qualquer checkpoint para validar a story.
- Princípio IV (determinismo): a paridade (T038–T039) é o gate que protege a integridade forense — não pular.
