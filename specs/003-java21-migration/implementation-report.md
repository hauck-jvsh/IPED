# Implementation Report — Migração do IPED para Java 21 LTS

**Feature**: `003-java21-migration` · **Branch**: `003-java21-migration` · **Data**: 2026-05-30
**Spec**: [spec.md](spec.md) · **Plano**: [plan.md](plan.md) · **Tarefas**: [tasks.md](tasks.md) · **Research**: [research.md](research.md)

> Registro do que foi efetivamente implementado, **verificado** e o que permanece pendente. Complementa o `tasks.md` (que mantém os checkboxes por tarefa). Cada item referencia o commit correspondente.

## 1. Status executivo

A migração do IPED de **Java 11 → Java 21 LTS** está **provada em execução real**: o build compila inteiro no JDK 21, a suíte de testes do engine passa (136/136), e o produto **processou uma imagem forense E01 real** (`RockPi4.E01`, profile `forensic`) com Sleuthkit, pipeline completo, indexação e UI de análise — tudo no Java 21.

Descobriu-se que a migração de **código** é pequena (o reator compilou com a mera mudança de toolchain). O esforço real concentrou-se em (a) um punhado de dependências/APIs sensíveis ao encapsulamento forte e (b) **empacotamento/distribuição** (jar-plugin, JRE embarcado, launchers `.exe`) — onde estavam os bloqueios que impediam o produto de iniciar.

| Dimensão | Estado |
|---|---|
| Build (16 módulos) no JDK 21 | ✅ BUILD SUCCESS (`mvn clean package`) |
| Testes engine no JDK 21 | ✅ 136 testes, 0 falhas, 2 skips (YARA integration-gated) |
| Processamento real E01 (forensic) | ✅ rodou de ponta a ponta (Sleuthkit + pipeline + índice + UI) |
| Neo4j 4.4 → 5.26 | ✅ compila (runtime de grafo ainda não exercido com grafo real) |
| Distribuição (Windows) | ⚠️ funciona via workaround (`iped.bat` + JRE 21 manual); itens "bonitos" pendentes |
| Validação de paridade forense (SC-002) | ⏳ não executada (requer caso-baseline Java 11) |

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
| `org.neo4j:neo4j` (embarcado) | 4.4.4 → **5.26.0** | **compila limpo** — a API `DatabaseManagementServiceBuilder` já era usada (4.0+); só warnings de `getId()` deprecated (mantidos: migrar p/ `getElementId()` mudaria `long`→`String` = mudança de comportamento, contra FR-018) |

### 2.4 Empacotamento e distribuição (commits `62c7792`, `0a602e6`, `ea1c465`)
| Problema (sintoma) | Causa | Fix |
|---|---|---|
| `iped.exe`: "Unable to access jarfile iped.jar"; `lib/` vazio | `maven-jar-plugin` 3.4.0+ proíbe execuções multi-jar sem classifier (`create-jar`/`create-search-jar`/…) → build abortava na `create-jar` | **Pin `iped-app` jar-plugin em 2.6**. Verificado: release completo (iped.jar + search/webapi/hashdb + 510 jars em `lib/`) |
| `UnsupportedClassVersionError 65.0 vs 55.0` | JRE embarcado ainda era Liberica **11.0.13** | `unpack-jre` → **`java:jre:21.0.11`** (requer publicar o artefato; ver §4) |
| `iped.exe` roda Java 11 mesmo com `jre/`=21 e `JAVA_HOME`=21 | `iped.exe`/`IPED-SearchApp.exe` são **binários pré-compilados** (launch4j) que pegam um Java 11 do **registro do Windows** e ignoram `jre/`/`JAVA_HOME` | **`iped.bat`** interino (usa o `jre/` embarcado). Verificado: `iped.jar` roda no Java 21 |

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
- **Publicar `java:jre:21.0.11`** (zip com `jre/` no topo, Liberica Full 21 c/ JavaFX) em `java/jre/21.0.11/` no maven do projeto (`iped-maven`). O pom já aponta para essa versão (`0a602e6`). Até publicar, `mvn package` falha na `unpack-jre`; usar swap manual do `jre/`.
- **Rebuildar os launchers `.exe`** (`iped.exe`, `IPED-SearchApp.exe`) para Java 21 (launch4j — config fora do repo). Interino: `iped.bat` (falta análogo `IPED-SearchApp.bat`).
- **Embutir os fixes no jar**: novo `mvn clean package` embute `Bootstrap` (SecurityManager) e `StartUpControl` no `iped.jar` — aí o flag manual no `.bat` e o spam deixam de existir.

### 4.2 Ambiente Python (não-fatal)
- `ModuleNotFoundError: No module named 'numpy'` — task Python/JEP. Bundle Python embarcado é da era Java 11. Fix: `pip install numpy` no `python/` embarcado **ou** rebuild do bundle JEP 4.2 (T027/T028).

### 4.3 Modernizações adiadas (independentes do Java 21 — já rodam no 21)
- **Lucene 9.2 → 9.12**: revertido — muda a API de `LeafReader`/`LeafMetaData` e quebra o custom `SlowCompositeReaderWrapper` (infra de leitura de índice, Princípio I).
- **BouncyCastle `jdk15on` → `jdk18on`**: revertido — split-package `org.bouncycastle.*` com o `jdk15on` transitivo do icepdf; exige alinhamento project-wide.
- **Tika 2.4 → 2.9**: não iniciado (alto risco; toca ~200 parsers; o fork `-p1` poderia ser abandonado — TIKA-4126).
- **JEP 4.0.3 → 4.2** + rebuild do bundle nativo.

### 4.4 Neo4j 5 — runtime
- Compila; falta exercer o **grafo de verdade** (GraphTask + abertura no UI): startup embarcado Neo4j 5, Cypher 5 (templates `links/*.cypher`) e a guarda de degradação para graph store 4.x (FR-007/T043).

### 4.5 Validação formal de paridade (SC-002)
- Gerar caso-baseline no build Java 11 e comparar os campos C1–C8 ([contracts/parity-validation.contract.md](contracts/parity-validation.contract.md)). Ainda não executado.

### 4.6 Documentação
- Atualizar baselines "Java 11 → 21" nos `CLAUDE.md` (raiz §3/§5, `iped-engine` §14, `iped-app` §1/§6/§12) — T049/T054/T056.
- `ThirdParty.txt`/`licenses/` para deps novas/atualizadas — T052/T055.

## 5. Caveats e riscos conhecidos

- **SecurityManager é removido no Java 24+** (JEP 486). O `-Djava.security.manager=allow` funciona no 21, mas uma futura migração para 24+ exigirá outro mecanismo para bloquear rede dos HTML viewers. Registrado como dívida.
- **NPE pré-existente** em `ExternalImageConverter.getDimension` ao abrir SVG (ImageMagick retorna dimensão nula) — **não é regressão do 21**; não-fatal (quebra só o preview daquele arquivo).
- **Launchers `.exe`** dependem de rebuild externo (launch4j); enquanto isso, o `.bat` é o caminho suportado no Java 21.

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

## 7. Cobertura de requisitos (resumo)

| Requisito | Estado |
|---|---|
| FR-001 build/run no 21 | ✅ |
| FR-002 testes passam | ✅ (engine 136/136) |
| FR-003/SC-002 paridade forense | ⏳ pendente (baseline) |
| FR-004 casos antigos abrem | ⏳ não testado (sem caso Java 11) |
| FR-006 grafo (caso novo) | ⏳ runtime de grafo não exercido |
| FR-008 scripts JS/Python | ⚠️ Python parcial (numpy ausente) |
| FR-009 tools nativas | ✅ (Sleuthkit/MPlayer/libesedb/ImageMagick/LibreOffice/sqlite no run real) |
| FR-011 viewers | ⚠️ LibreOffice/UI OK; NPE pré-existente em SVG |
| FR-012 version check | ✅ |
| FR-014 deps compatíveis | ✅ (no escopo migrado) |
| FR-015 runtime embarcado (Win) | ⚠️ via workaround; publicação do artefato pendente |
| FR-017 Java 11 dropado | ✅ (release=21, MIN_JAVA_VER=21) |
| FR-018 preservar comportamento | ✅ (nenhuma feature/recurso de linguagem novo) |
| Governança (emenda constituição) | ✅ (v1.2.0) |
