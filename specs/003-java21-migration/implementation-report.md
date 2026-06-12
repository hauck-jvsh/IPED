# Implementation Report — Migração do IPED para Java 21 LTS

**Feature**: `003-java21-migration` · **Branch**: `003-java21-migration` · **Data**: 2026-05-31
**Spec**: [spec.md](spec.md) · **Plano**: [plan.md](plan.md) · **Tarefas**: [tasks.md](tasks.md) · **Research**: [research.md](research.md)

> Registro do que foi efetivamente implementado, **verificado** e o que permanece pendente. Complementa o `tasks.md` (que mantém os checkboxes por tarefa). Cada item referencia o commit correspondente.

## 1. Status executivo

A migração do IPED de **Java 11 → Java 21 LTS** está **provada em execução real**: o build compila inteiro no JDK 21, a suíte de testes do engine passa (136/136), e o produto **processou uma imagem forense E01 real** (`RockPi4.E01`, profile `forensic`) com Sleuthkit, pipeline completo, indexação e UI de análise — tudo no Java 21.

Descobriu-se que a migração de **código** é pequena (o reator compilou com a mera mudança de toolchain). O esforço real concentrou-se em (a) um punhado de dependências/APIs sensíveis ao encapsulamento forte e (b) **empacotamento/distribuição** (jar-plugin, JRE embarcado, launchers `.exe`) — onde estavam os bloqueios que impediam o produto de iniciar.

| Dimensão | Estado |
|---|---|
| Build (17 módulos) no JDK 21 | ✅ BUILD SUCCESS (`mvn clean package`) |
| Testes engine no JDK 21 | ✅ 136 testes, 0 falhas, 2 skips (YARA integration-gated) |
| Processamento real E01 (forensic) | ✅ rodou de ponta a ponta (Sleuthkit + pipeline + índice + UI) |
| Neo4j 4.4 → 5.26 | ✅ migrado **out-of-process via Bolt** e **validado end-to-end** (build verde, isolamento de classpath, import Neo4j 5, post-import Cypher 5 e **aba de grafo na UI renderizando** após reprocessamento) — ver §2.5 |
| JRE embarcada | ✅ artefato **`java:jre:21.0.11` publicado no `iped-maven`** e descompactado via `unpack-jre` (workaround `copy-jre`/pasta local removido em 2026-06-12) — ver §2.6 e §4.1 |
| Distribuição (Windows) | ✅ launchers `.exe` refeitos via launch4j (apontam p/ o `jre/` embarcado); `iped.bat` mantido como fallback |
| CI Java 21 (FR-013) | ✅ job único `build-java21` no workflow; ⏳ confirmação verde pendente de um push |
| Validação de paridade forense (SC-002) | ⏳ formal não executada (requer baseline Java 11); informal já excelente (6 itens de diff em 781k — §4.5) |

## 2. Implementado e verificado

### 2.1 Toolchain (commit `58ba4c5`)
- `pom.xml` (parent): `maven.compiler.source/target = 11` → **`maven.compiler.release = 21`**.
- `maven-compiler-plugin` → **3.13.0** (parent + carvers/viewers/geo/app); `maven-surefire-plugin` → **3.5.4**; `maven-dependency-plugin` → **3.8.1**.
- Removido o `findbugs-maven-plugin` (abandonado).
- **Verificação**: `mvn clean compile` → BUILD SUCCESS nos 16 módulos (`javac [release 21]`).

### 2.2 Correções de código (commits `58ba4c5`, `ce76df8`, `1d48ace`)
| Item | Mudança | Motivo |
|---|---|---|
| **FST removido** | `RegexTask`: cache de regex via serialização JDK (`ObjectOutput/InputStream`), com leitura resiliente (cache FST antigo → `StreamCorruptedException` capturada → rebuild) | FST 2.57 usa `Unsafe`/reflexão; quebra sob encapsulamento forte. **Confirmado em runtime**: o log mostrou o rebuild do cache antigo. |
| **Version check** | `Util.MIN/MAX_JAVA_VER` 11/14 → **21** | Reconhecer Java 21 como suportado (FR-012) |
| **APIs removidas** | `TelegramParser`: `DatatypeConverter.parseBase64Binary` → `java.util.Base64`; `CachePersistance`: `printHexBinary` → `java.util.HexFormat` | Reduzir dependência de JAXB; APIs JDK nativas |
| **SecurityManager** | `Bootstrap.getCustomJVMArgs()` + `iped.bat`: `-Djava.security.manager=allow` | `Configuration.loadConfigurables` instala um `SecurityManager` p/ **bloquear acesso à rede dos HTML viewers**; Java 18+ desabilita `System.setSecurityManager()` por padrão (lançava `UnsupportedOperationException` fatal). =allow preserva o comportamento (SM só é removido no Java 24+). **Verificado**: 0 erros de SecurityManager no run real. |
| **StartUpControl** | `getCurrentProcessSize()` via `ClassLoadingMXBean.getLoadedClassCount()` | Antes lia o campo privado `ClassLoader.classes` por reflexão (removido no 21 → `NoSuchFieldException` em loop, ~43× no startup). API pública equivalente. |

### 2.3 Dependências atualizadas (commits `9e9c803`, `d251bb6`)
| Dependência | De → Para | Nota |
|---|---|---|
| `net.java.dev.jna:jna` | 5.7.0 → **5.14.0** | engine + parsers-impl (alinhado); melhor carga nativa no 21 |
| Jersey (grizzly/hk2/json) | 2.30.1/2.28 → **2.41** | mantém namespace `javax`; HK2 mais amigável ao 21 |
| `com.github.luben:zstd-jni` | 1.3.3-3 → **1.5.6-9** | — |
| `org.neo4j:neo4j` (embarcado) | 4.4.4 → **5.26.0** | Compila limpo, **mas o runtime exigiu rearquitetar o grafo para out-of-process** (conflito de antlr) — ver **§2.5**. No processo principal ficaram só `neo4j-graphdb-api` + `neo4j-java-driver`. |

### 2.4 Empacotamento e distribuição (commits `62c7792`, `0a602e6`, `ea1c465`)
| Problema (sintoma) | Causa | Fix |
|---|---|---|
| `iped.exe`: "Unable to access jarfile iped.jar"; `lib/` vazio | `maven-jar-plugin` 3.4.0+ proíbe execuções multi-jar sem classifier (`create-jar`/`create-search-jar`/…) → build abortava na `create-jar` | **Pin `iped-app` jar-plugin em 2.6**. Verificado: release completo (iped.jar + search/webapi/hashdb + 510 jars em `lib/`) |
| `UnsupportedClassVersionError 65.0 vs 55.0` | JRE embarcado ainda era Liberica **11.0.13** | `unpack-jre` → **`java:jre:21.0.11`** (requer publicar o artefato; ver §4) |
| `iped.exe` roda Java 11 mesmo com `jre/`=21 e `JAVA_HOME`=21 | `iped.exe`/`IPED-SearchApp.exe` eram **binários pré-compilados** (launch4j) que pegavam um Java 11 do **registro do Windows** e ignoravam `jre/`/`JAVA_HOME` | **`.exe` refeitos via `launch4j-maven-plugin`** (perfil Windows em `iped-app/pom.xml`): apontam para o `jre/` embarcado (sem busca no registro) + `-Djava.security.manager=allow`. Verificado: `iped.exe` roda Bootstrap no JRE 21; `IPED-SearchApp.exe` sobe a UI completa (BootstrapUI→App child) no JRE 21. `iped.bat` mantido como fallback |

### 2.5 Grafo Neo4j 5 — migração para out-of-process via Bolt (sessão 2026-05-31)

O bump 4.4 → 5.26 compila, mas **quebra em runtime** por dois motivos distintos:

1. **CLI de import mudou** — `GraphImportRunner` montava o comando no estilo Neo4j 4.x (`neo4j-admin import --database=…`). No Neo4j 5 virou `database import full <db>` com flags `--opt=value`, `--high-io`→`--high-parallel-io=on`, e o nome do banco como argumento **posicional** (com separador `--` para não ser engolido pelas opções variádicas `--nodes/--relationships`). **Validado** rodando contra os CSVs reais do caso (10 nós importados).

2. **Conflito de antlr (bloqueador estrutural)** — o parser Cypher do Neo4j 5.26 exige `antlr4-runtime` **4.13.x**; o `io.github.fmpfeifer:libfqlite` (undelete de SQLite) fixa antlr **4.9.2**. A serialização do ATN é incompatível entre 4.9 e 4.10+ **nos dois sentidos**, e não há versão de libfqlite que resolva. Não dá para ter as duas no mesmo classpath plano.

**Decisão (do solicitante, mirando uma futura migração para OSGi): isolamento por processo.** O engine Neo4j embarcado roda em **JVM filha** com classpath isolado (`lib/neo4j/`, antlr 4.13.x); o processo principal mantém antlr 4.9.2 (libfqlite) e fala com o filho via **driver Bolt**. É o mesmo idioma out-of-process já usado pelo IPED (Sleuthkit/Parsing).

| Componente | O que é |
|---|---|
| **Módulo novo `iped-graph-server`** | Depende do Neo4j full **sem** libfqlite → antlr 4.13.x resolve naturalmente. Contém `GraphServer` (embedded + conector Bolt em porta livre, protocolo stdio `BOLT_PORT=`/`GRAPH_SERVER_READY`/`STOP`) e `GraphPostImport` (statements pós-geração + agrupamento de contatos, movidos do `GraphGenerator`, via API embarcada). Empacotado como zip e desempacotado em `lib/neo4j/`. |
| **Adapters `BoltEntity`/`BoltNode`/`BoltRelationship`/`BoltPath`** (iped-engine) | Implementam as interfaces `org.neo4j.graphdb.*` sobre os registros do driver → **os 11 arquivos de grafo da UI ficam intocados** (o tipo de fronteira continua sendo `graphdb-api`, que é só interfaces — sem antlr). |
| **`GraphServiceImpl` reescrito** | `start()` faz spawn do `GraphServer` (via `Neo4jChildLauncher`, que acha `lib/neo4j/` e monta a base do comando), lê a porta e abre um `Driver`. Cada query roda numa `Session` Bolt e embrulha os resultados nos adapters. As queries Cypher seguem quase iguais, com fixes **Cypher 5**: `size((n)--())`→`COUNT { (n)--() }`; alternância de tipos `:A|:B`→`:A|B`; queries de relação retornam `startNode(r)/endNode(r)` para os adapters servirem `getStartNode()/getEndNode()`. |
| **`GraphService` + SPI** | Removido `getGraphDb()` (vazava o DB embarcado); adicionado `searchPaths()`. A SPI `SearchLinksQuery`/`AbstractSearchLinksQuery` e o `SearchLinksWorker` (UI) passam a usar `GraphService` em vez de `GraphDatabaseService`. |
| **Import/post-import isolados** | `GraphImportRunner` (neo4j-admin) e `GraphGenerator` (post-import) spawnam children no `lib/neo4j/`. As escritas do post-import (`createNode`/`createRelationshipTo`) rodam embedded **dentro** do filho isolado (`GraphPostImport`), não no processo principal. |
| **Empacotamento** | `iped-engine` agora só pede `neo4j-graphdb-api` + `neo4j-java-driver`. `iped-app` desempacota o zip do `iped-graph-server` em `lib/neo4j/` e **exclui os transitivos** dessa dep (wildcard) para o engine embarcado + antlr 4.13 **não** vazarem para o `lib/` plano. |

**Verificação:**
- ✅ **Arquitetura provada end-to-end** antes da integração: o `GraphServer` isolado (antlr 4.13.2) sobe, abre o `graph.db` importado (via `initial_default_database`) e o driver Bolt consulta dados reais (10 nós/10 relações).
- ✅ `mvn clean package` (JDK 21) = **BUILD SUCCESS**, com **separação de classpath verificada**: `lib/` principal **sem** cypher/scala/antlr-4.13 (tem antlr **4.9.2** + graphdb-api + driver); `lib/neo4j/` = 239 jars isolados (antlr **4.13.2** + cypher + `iped-graph-server`).
- ✅ Import Neo4j 5 fresh → `graph.db` gerado em `F:\test` a partir dos CSVs reais, usando o `lib/neo4j/` buildado.
- ⏳ **Aba de grafo na UI** (GraphServer + driver + adapters em uso real) pendente de validação por reprocessamento — o caso empacota a própria `iped/lib` (com o código antigo), então exige a `lib` nova (ou reprocessar).
- Gotcha resolvido de quebra: `CacheTimePeriodEntry` (timeline) usava `scala.Array.copy` que vinha **transitivo do Neo4j full**; com a exclusão wildcard o scala sumiu → trocado por `System.arraycopy` (equivalente).

### 2.6 JRE local e leitor de PNG (commits `81d6fb6`, `e1c5e042`)
- **JRE embarcada da pasta local**: o `iped-app/pom.xml` substituiu o `unpack` do artefato `java:jre:21.0.11` (não publicado) por um `copy-jre` que copia uma Liberica Full 21 de `iped-jre/` (gitignored) → `release/jre`. Resolve o item de distribuição "publicar o artefato JRE". **⤷ Superado (2026-06-12)**: artefato publicado no `iped-maven` e pom revertido para `unpack-jre` — ver §4.1.
- **`--add-exports` para o leitor de PNG**: o `png-reader-jdk11+28-p1.jar` (pacote `com.sun.imageio.plugins.png2`) acessa internos do `java.desktop` (`com.sun.imageio.plugins.common.*` e `sun.awt.image.ByteInterleavedRaster`). No Java 11 passava sob `--illegal-access=permit`; no 17+ vira `IllegalAccessError` (JEP 396) e quebra ícones do ProgressFrame + decodificação de PNG. Fix: dois `--add-exports` em `Bootstrap.getCustomJVMArgs()` (lista obtida via `jdeps -jdkinternals`). **Verificado em runtime** (log limpo).

### 2.7 CI Java 21: mesmo `--add-exports` no surefire + bump do JaCoCo (T048)
- **Sintoma**: primeiro push do branch com o workflow Java 21 falhou em `iped-parsers-impl` — `OCRParserTest.testOCRParser{PSD,SVG}` (`AssertionError`, OCR vazio). Passavam no Java 11/14 (run inicial do CI com a matriz antiga).
- **Causa-raiz (mesma do §2.6, lado de teste)**: PSD/SVG vão por `parseNonStandard` → ImageMagick converte para PNG → `ImageIO.read()`. O `png2.PNGImageReader` está registrado **à frente** do leitor do JDK; sob Java 17+ ele lança `IllegalAccessError` em `com.sun.imageio.plugins.common.SubImageInputStream` (JEP 396) e `ImageIO.read` retorna `null` → OCR vazio. O **runtime** (Bootstrap) já concedia os exports, mas a **JVM forkada do maven-surefire** não — por isso só os testes quebravam. Isolado empiricamente: com o classpath completo do parsers-impl, `ImageIO.read` do PNG do ImageMagick lança `IllegalAccessError`; sem o png-reader, lê normal; o leitor do JDK lê os mesmos PNGs.
- **Fix** (`iped-parsers/iped-parsers-impl/pom.xml`): `maven-surefire-plugin` com `<argLine>@{argLine} --add-exports=java.desktop/com.sun.imageio.plugins.common=ALL-UNNAMED --add-exports=java.desktop/sun.awt.image=ALL-UNNAMED</argLine>` (espelha os flags de runtime; `@{argLine}` preserva o agente JaCoCo).
- **JaCoCo 0.8.8 → 0.8.12**: a 0.8.8 lançava `IllegalClassFormatException: Unsupported class file major version 65` ao instrumentar classes Java 21 (cobertura quebrada, ruído de log); a 0.8.12 entende bytecode 21.
- **Verificação local**: `mvn clean test -fae` (JDK 21, reator completo) → **17/17 módulos SUCCESS**, zero `IllegalAccessError`/major-version, zero falhas. `iped-parsers-impl` 183/0/0 (1 skip = PythonParserTest, JEP local); `iped-engine` 146/0/0 (inclui integração YARA com `YARA_X_LIB_PATH`).
- **CI verde (T048, 2026-06-03)**: push do fix `d58cc50bf` → GitHub Actions `Java CI` run `26886502532` **SUCCESS** (7m50s). O CI vinha vermelho desde 2026-05-30; este é o primeiro `mvn -B package` (build + testes) verde no Java 21. Fecha o gate **FR-013/SC-001**.

### 2.8 Smoke Linux com Java 21 do sistema (T053, 2026-06-03)
Validado em **WSL2 Ubuntu-26.04** com **Liberica Full 21.0.11+11** do sistema (instalada via tarball no `$HOME`, sem sudo), escopo **mínimo foco-Java-21**. Build Linux limpo (`mvn -B package -DskipTests`, reaproveitando o `.m2` do Windows) → release **sem `jre/`** (FR-015 ✓, o `copy-jre` pula a pasta local ausente) e **sem `iped.exe`** (perfil `windows-launchers` inativo no Linux). Processamento `java -jar iped.jar --nogui` de uma fonte lógica pequena (txt/html/png/pdf/tiff) rodou **ponta a ponta → EXIT 0**, com **índice Lucene** (3 segmentos) e **grafo Neo4j 5** gerados.
- **Gates confirmados no log de processamento**: **FR-012** sem aviso de versão (apenas `[INFO] Java Version: 21.0.11`); **SC-006** zero `UnsupportedClassVersionError`/`IllegalAccessError`/`InaccessibleObjectException`/"module does not export"; **SecurityManager** instalado e funcional no 21 (`System::setSecurityManager` deprecation warning + `-Djava.security.manager=allow`); **png-reader add-exports ativos no runtime Linux** (`JVM Argument: --add-exports=java.desktop/com.sun.imageio.plugins.common=ALL-UNNAMED`, sem `IllegalAccessError` de `png2`) — o fix do §2.7 é platform-independent.
- **3 achados (todos pré-existentes/ambiente, NÃO regressão do Java 21):**
  1. **`tskJarPath`** precisa ser setado no `LocalConfig.txt` no Linux. O jar `lib/sleuthkit-4.12.0.p1.jar` **está** no release, mas a linha vem comentada (`#tskJarPath = ...`); `PluginConfig.processProperties` lança `IPEDException` se vazio. Setei `tskJarPath = <release>/lib/sleuthkit-4.12.0.p1.jar`. (Fonte lógica não carrega o nativo do Sleuthkit; só o caminho do jar.)
  2. **`GraphConfig.json` `phone-region="auto"`**: `GraphConfiguration.decodePhoneRegion` deriva o país do locale padrão; o WSL default é C/POSIX (sem país) → lança `IllegalArgumentException`. Resolvido exportando `LANG=en_US.UTF-8` (a JVM deriva `user.country=US`), como num Linux com locale configurado. Alternativa: setar uma região 2-letras explícita no JSON.
  3. **`Manager.prepareOutputFolder` copia `appRoot/jre` para `<caso>/iped/jre`** (caso autocontido) **incondicionalmente** — código **idêntico ao master** (`IOUtil.copyDirectory` lança "Source is not a directory" se ausente). No release Linux sem `jre/` (FR-015) isso aborta o processamento. Contornado no smoke com um `jre/` placeholder. **É o único achado acionável p/ a migração**: como o build Linux (FR-015) não embarca JRE, ou (a) guardar a cópia quando `jre/` não existe (`if (jreDir.exists())`), ou (b) bundlar uma JRE Linux no release. É pré-existente (afeta qualquer Linux sem `jre/`), mas a migração o expõe ao adotar runtime do sistema. **Decisão (2026-06-03): apenas documentar — sem mudança de código nesta branch** (comportamento idêntico ao master; o deploy Linux deve prover um `jre/` no release, ou o processamento aborta na criação do caso). Fix futuro sugerido: guardar a cópia com `if (jreDir.exists())`.
- **Exercício completo das ferramentas nativas (FR-009: Sleuthkit em E01, OCR/JEP, ImageMagick, LibreOffice, RegRipper) NÃO realizado** (escopo mínimo; sem `apt`). Todos os `[ERROR]` do run são "tool nativa ausente" não-fatais (libesedb/msiecf/evt*export/sccainfo/rifiuti/agdbinfo/RegRipper-perl/ImageMagick/MPlayer/JEP), idênticos ao comportamento esperado num Linux sem essas ferramentas. Gate **SC-004 (runtime Linux)** ✅; **FR-009 no Linux** fica como exercício opcional "completo" (apt + E01).

## 3. Evidências de verificação

1. **Compilação**: `mvn clean package` (JDK 21) → BUILD SUCCESS, 16 módulos, release completo gerado.
2. **Testes**: `mvn -pl iped-engine test` → `Tests run: 136, Failures: 0, Errors: 0, Skipped: 2`.
3. **Processamento real** (2026-05-30): `iped.bat -profile forensic -d E:\hds\RockPi4\RockPi4.E01 -o F:\test`
   - `SleuthkitServer 0/1/2/7 started`; `Decoding image E:\hds\RockPi4\RockPi4.E01`; `sqlite-jdbc 3.41.2.2 native mode`.
   - Pipeline: Hash, Signature, Parsing, **libesedb** (Edge cache), **MPlayer** (vídeo), Regex, QRCode, HashDB (NSRL 177), IndexTask.
   - Cache de regex: `Could not load regex cache (StreamCorruptedException…); it will be rebuilt` (FST→JDK, comportamento esperado).
   - UI de análise (App): `LibreOffice frame ok`, `ColumnsManager`, `UICaseDataLoader: Listing all items`, busca/filtro, abertura de itens.
   - **0 erros de SecurityManager**; encerrado manualmente pelo operador (não foi crash).

## 4. Pendências

### 4.1 Distribuição (Windows) — para o produto sair "redondo"
- ~~**Publicar `java:jre:21.0.11`**~~ **FEITO (2026-06-12)**: zip (com `jre/` no topo, Liberica Full 21 c/ JavaFX, ~92 MB) publicado em `java/jre/21.0.11/` no maven do projeto (`iped-maven`). O `iped-app/pom.xml` voltou à execution `unpack-jre` (artefato `java:jre:21.0.11`) e o workaround `copy-jre`/pasta local `iped-jre/` (§2.6, `81d6fb6`) foi removido. Resolução validada via `mvn dependency:get`.
- ~~**Rebuildar os launchers `.exe`**~~ **FEITO (2026-06-02)**: `launch4j-maven-plugin` 2.5.2 num `<profile>` ativado no Windows (`iped-app/pom.xml`) gera `iped.exe` (console, wrappa `iped.jar`) e `bin/IPED-SearchApp.exe` (GUI, wrappa `lib/iped-search-app.jar`) a cada build, apontando para o `jre/` embarcado (`<path>` exe-relativo, sem `minVersion` → ignora o registro) + `-Djava.security.manager=allow`. Os binários Java-11 versionados foram removidos; ícone extraído p/ `resources/root/iped.ico`. Validado no Windows: `iped.exe`→Bootstrap no JRE 21; `IPED-SearchApp.exe`→UI completa no JRE 21 (testado em pasta `iped`).
- **Embutir os fixes no jar**: novo `mvn clean package` embute `Bootstrap` (SecurityManager) e `StartUpControl` no `iped.jar` — aí o flag manual no `.bat` e o spam deixam de existir.

### 4.2 Ambiente Python (não-fatal — **não é regressão do 21**; diagnosticado 2026-06-01)
- `ModuleNotFoundError: No module named 'numpy'` no startup é **ruído inofensivo de tasks de IA opcionais desabilitadas**, idêntico ao master Java 11 — **não é regressão da migração**. Evidências: (1) o bundle `python-jep-dlib:3.9.12-4.0.3-19.23.1-2` é o mesmo do master (`git diff` na `unpack-python` = vazio) e nunca trouxe numpy (`site-packages` = `dlib`/`jep`/`bs4`/`soupsieve`/`termcolor`/`docopt`); (2) `PythonParser` força o Python embarcado (`setPythonHome`+`IgnoreEnvironmentFlag`+`NoUserSiteDirectory`) → numpy do sistema não vaza; (3) `enableFaceRecognition/AgeEstimation/YahooNSFWDetection/CSAMDetector/AudioTranscription = false` por padrão (as únicas consumidoras de numpy) e se auto-desabilitam graciosamente; o processamento completa.
- **JEP 4.0.3 validado no Java 21**: o erro é `ModuleNotFoundError` *de dentro do Python* — **zero** `UnsatisfiedLinkError`/`JEP_NOT_FOUND`/`UnsupportedClassVersion` no log → a ponte nativa JEP→Python carrega e executa no 21 (era o real risco de migração; **descartado**). numpy + ML (torch/tensorflow/face_recognition/opencv) são instalados **por task pelo usuário** (User Manual), não bundlados. T027 (bump 4.2) / T028 (rebuild do bundle) são modernização adiada, não bloqueadores — ver tasks.md §JEP.

### 4.3 Bumps de dependências (revisado 2026-06-01)

**FEITOS nesta branch (build-verde + run real no 21):**
- **JNA 5.7.0 → 5.14.0** (`iped-engine` + `iped-parsers-impl`; versões alinhadas para evitar skew, comentário no pom). Usada por libesedb e pela `YaraEngine`.
- **Jersey 2.30.1 → 2.41** (`jersey-container-grizzly2-servlet`/`jersey-hk2`/`jersey-media-json-jackson` no `iped-engine`). Web API.
- **zstd-jni 1.3.3-3 → 1.5.6-9** (`iped-engine`). Compressão.

**ADIADOS — bloqueio/risco confirmado no código (independentes do Java 21; já rodam no 21):**
- **Lucene 9.2 → 9.12**: revertido — muda a API de `LeafReader`/`LeafMetaData` e quebra o custom `iped.engine.lucene.SlowCompositeReaderWrapper` (classe que o IPED mantém porque o Lucene a removeu; usada em `IPEDSource`/`IPEDMultiSource`/`DuplicateTask`/`SkipCommitedTask`/`ExportIndexedTerms`). Infra de leitura de índice forense — **Princípio I** (formato de índice congelado). Bump exige reescrever o wrapper + revalidação de paridade.
- **BouncyCastle `jdk15on` → `jdk18on`**: revertido — `icepdf-core` 7.0.0 traz BC `jdk15on` transitivo (já há `<exclusion>` de `bcprov-ext-jdk15on`) e o engine depende direto de `bcpkix-jdk15on:1.70`; mover p/ `jdk18on` causa split-package `org.bouncycastle.*`. Resolver depende do icepdf migrar p/ jdk18on (upstream) ou exclusão + validação do PDF/crypto. `jdk15on:1.70` **roda no 21** — bump é só higiene de naming.
- **Tika 2.4 → 2.9**: não iniciado (alto risco; toca ~200 parsers; o fork custom `tika.core.version=2.4.0-p1` precisaria ser rebuildado no 2.9; o `-p1` poderia ser abandonado quando o upstream incluir TIKA-4126, revertendo a mudança de `SyncMetadata` do commit b673cf4).
- **JEP 4.0.3 → 4.2** + rebuild do bundle nativo — **4.0.3 já roda no 21** (validado, §4.2); modernização opcional, não migração.

> **Recomendação:** os 3 adiados (Lucene/Tika/BC) são follow-ups pós-migração, cada um com validação dedicada (Princípio IV — determinismo forense). Mantê-los fora da branch de migração preservadora de comportamento. Dependência pendurada `de.ruedigermoeller:fst:2.57` **removida** do `iped-engine/pom.xml` (2026-06-01): T014 já movera o `RegexTask` p/ serialização JDK e nenhum módulo a importava. Validado por `mvn -pl iped-engine -am clean install -DskipTests` (BUILD SUCCESS) + `mvn -pl iped-engine test` (136 run, 0 fail, 2 skip). Uma dep a menos abusando de `sun.misc.Unsafe` no 21.

### 4.4 Neo4j 5 — runtime (FEITA e VALIDADA; ver §2.5)
- **Implementado, build-verde e validado end-to-end**: import Neo4j 5, grafo out-of-process via Bolt (server isolado + driver + adapters), post-import isolado, fixes Cypher 5, isolamento de classpath. Import validado contra dados reais; **aba de grafo na UI renderizando** após reprocessamento (commit `3153a89`).
- Dois fixes no caminho de validação: (1) `GraphConfig.json` post-gen usava sintaxe de índice Cypher 4 → `CREATE INDEX IF NOT EXISTS FOR ...` + `GraphPostImport` endurecido (tx por statement, catch/continue); (2) `BoltNode.getDegree()` (stub que lançava, quebrando o sizing em `GraphModel.convert`) → carrega o degree como snapshot do `COUNT { (n)--() }`, nunca lança.
- ~~**Guarda de store antigo (FR-007/T043)**~~: **descartada (2026-06-01)** — casos são autocontidos (JRE + libs empacotadas com o caso); o release novo não abre graph store de outra versão, então não há cenário de store 4.x a guardar.

### 4.5 Validação de paridade (SC-002)
- **Formal (pendente)**: gerar caso-baseline no build Java 11 e comparar os campos C1–C8 ([contracts/parity-validation.contract.md](contracts/parity-validation.contract.md)). Ainda não executado (requer baseline + dataset de referência).
- **Informal (FEITA, 2026-06-01) — excelente**: o usuário processou o mesmo caso (RockPi4.E01, forensic) nos releases 4.3.1/Java 11 e 4.4.0/Java 21. Diferença total **6 itens em 781.246 (0,0008%)**. IDÊNTICOS: Total Carved 4387, Active Items 267.186, Parsing Exceptions 2219, I/O errors 0, volume processado, ERRORs `SleuthkitReader` 331. Diff: Subitems/processed −6 (1 OLE carveado expandido no J11 e não no J21 + micro-variação na classificação de fragmentos carveados — dominada por não-determinismo `Processing Queue Random Order`+carving). NENHUM erro de classe novo no J21 (só 1 `LoadGraphDatabaseWorker` benigno). Não substitui a paridade formal C1–C8, mas é forte indício de equivalência.

### 4.6 Documentação
- ✅ Baselines "Java 11 → 21" atualizados nos `CLAUDE.md` (raiz §3/§5, `iped-engine` §14, `iped-app` §1/§6/§12) + Jersey/JNA/zstd/FST — T049/T056 (commits `b13126ef6`/`934893ee4`/`30c542af9`/`7c6fdb56b`).
- ✅ `ThirdParty.txt` (T055): Neo4j 5 (engine + driver), Jersey/Grizzly/HK2 (REST), ANTLR 4 Runtime (4.9.2 + 4.13.2), launch4j, + versões JNA/zstd. Apache 2.0/GPL 3 bundled em `licenses/`; EPL 2.0/BSD-3/MIT citadas por nome+URL (bundling estrito dos textos = follow-up opcional).
- ✅ `ReleaseNotes.txt`: entrada `#spec/003-java21-migration` sob `TBD: IPED-4.4.0` — T057.

## 5. Caveats e riscos conhecidos

- **SecurityManager é removido no Java 24+** (JEP 486). O `-Djava.security.manager=allow` funciona no 21, mas uma futura migração para 24+ exigirá outro mecanismo para bloquear rede dos HTML viewers. Registrado como dívida.
- **Thumbs de SVG dão timeout** (`ImageThumbTask` → `ExternalImageConverter` → **ImageMagick** externo, `imgConvTimeout=20s`+2s/MB): o ImageMagick trava na rasterização de SVG — até SVGs de poucos bytes atingem o timeout. **Não é regressão do 21** — idêntico no J11 (9.865 vs 9.854 timeouts de SVG). Não-fatal (só não gera a thumb daquele item). Mitigação possível (delegate rsvg, renderizador Batik, ou excluir `image/svg+xml` da conversão externa) = follow-up. *(Obs.: uma nota anterior atribuía isto a `ExternalImageConverter.getDimension` retornando null — **não verificado** nesses logs; `getDimension` é outro caminho, usado pelo "Faces Similares" da UI. Investigação adiada p/ outro branch.)*
- **Launchers `.exe`** agora são gerados no build pelo `launch4j-maven-plugin` (perfil Windows); o `.bat` segue como fallback. A detecção de root do search app exige que a pasta de deploy se chame `iped` (`Main.setConfigPath` sobe de `iped/lib`→`iped`) — convenção de deploy já existente, não específica do launcher.

## 6. Commits da branch

| Commit | Descrição |
|---|---|
| `f06c7a8` | [Spec Kit] spec/plan/tasks/research/contracts/quickstart |
| `58ba4c5` | Toolchain → Java 21; FST removido; version check; Base64/HexFormat; **emenda da constituição (v1.2.0)** |
| `9e9c803` | Bumps JNA 5.14 / Jersey 2.41 / zstd 1.5.6 |
| `d251bb6` | Neo4j embarcado 4.4.4 → 5.26.0 |
| `62c7792` | Fix empacotamento: pin `iped-app` jar-plugin em 2.6 |
| `0a602e6` | JRE embarcado: `unpack-jre` → `java:jre:21.0.11` |
| `ea1c465` | Launcher `iped.bat` (usa o `jre/` embarcado) |
| `ce76df8` | SecurityManager: `-Djava.security.manager=allow` |
| `1d48ace` | StartUpControl via `ClassLoadingMXBean` |
| `8c8e339` | [Spec Kit] implementation-report + quickstart §8/§9 |
| `81d6fb6` | JRE embarcada da pasta local `iped-jre/` (substitui `java:jre`) — §2.6 |
| `e1c5e04` | `--add-exports` p/ o leitor de PNG embarcado (PNG2) — §2.6 |
| `9196ad8` | **Grafo Neo4j 5 out-of-process via Bolt** (módulo `iped-graph-server` + adapters + `GraphServiceImpl` driver + isolamento `lib/neo4j`) — §2.5 |
| `3153a89` | Fix aba de grafo Neo4j 5: degree do nó via Bolt + post-import Cypher 5 |
| `64ee8f0` | CI: matriz Java 11/14 → job único Java 21 (FR-013) |
| `f3a17dd` | Doc: numpy/JEP como não-regressão; T027/T028 adiadas |
| `b13126e` | Doc: baselines CLAUDE.md → Java 21 + Neo4j 5; ThirdParty Neo4j |
| `934893e` | Doc: status dos bumps; flag da dep FST pendurada |
| `30c542a` | Remove a dependência FST pendurada (T015) |
| `7c6fdb5` | Refaz launchers `.exe` Windows p/ Java 21 via launch4j (T050) |

## 7. Cobertura de requisitos (resumo)

| Requisito | Estado |
|---|---|
| FR-001 build/run no 21 | ✅ |
| FR-002 testes passam | ✅ (engine 136/136) |
| FR-003/SC-002 paridade forense | ⏳ pendente (baseline) |
| ~~FR-004~~ casos antigos abrem | ❌ **retirado (2026-06-01)** — casos autocontidos; release novo não abre casos antigos |
| ~~FR-005~~ portáteis antigos | ❌ **retirado (2026-06-01)** — casos autocontidos |
| ~~FR-006~~ grafo (caso novo) | ❌ **retirado (2026-06-01)** — garantia de render do grafo absorvida por FR-011; grafo ✅ validado na aba Vínculos (§2.5, commit `3153a89`) |
| ~~FR-007~~ guarda store 4.x | ❌ **retirado (2026-06-01)** — release novo não abre graph store de outra versão |
| FR-008 scripts JS/Python | ✅ JS/Python rodam no 21 (JEP 4.0.3 validado); numpy/ML instalados por-task pelo usuário — não-regressão (§4.2) |
| FR-009 tools nativas | ✅ (Sleuthkit/MPlayer/libesedb/ImageMagick/LibreOffice/sqlite no run real) |
| FR-010 Web API REST | ✅ validada no 21 sobre `F:\test` (sources/categories/search/docs/text/content/thumb/swagger; Jersey 2.41/Grizzly) — T041 |
| FR-011 viewers (incl. grafo) | ⚠️ **grafo (aba Vínculos) ✅** + LibreOffice/UI exercitados; Mapa/Timeline a confirmar (T045). Thumbs de SVG dão timeout (`ImageThumbTask`+ImageMagick) — pré-existente, não-regressão (idêntico no J11) |
| FR-012 version check | ✅ |
| FR-013 CI Java 21 | ✅ job único `build-java21` (commit `64ee8f0e3`); confirmação verde pendente de um push (T048) |
| FR-014 deps compatíveis | ✅ (no escopo migrado) |
| FR-015 runtime embarcado (Win) | ✅ JRE 21 da pasta local `iped-jre/` (§2.6) + **launchers `.exe` refeitos via launch4j** (§4.1, commit `7c6fdb56b`) |
| FR-017 Java 11 dropado | ✅ (release=21, MIN_JAVA_VER=21) |
| FR-018 preservar comportamento | ✅ (nenhuma feature/recurso de linguagem novo) |
| Governança (emenda constituição) | ✅ (v1.2.0) |
