# Implementation Plan: Migração do IPED para Java 21 LTS

**Branch**: `003-java21-migration` | **Date**: 2026-05-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-java21-migration/spec.md`

## Summary

Elevar a plataforma de build e execução do IPED de **Java 11 para Java 21 LTS**, de forma **preservadora de comportamento** (paridade forense, sem novas funcionalidades). É um **cut-over total** (Java 11 deixa de ser suportado). A abordagem técnica é: (1) atualizar a toolchain Maven e o nível de linguagem para 21; (2) atualizar/substituir as dependências que não rodam sob o encapsulamento forte do Java 16+ ou que não suportam Java 21 — com destaque para **Neo4j 4.4 → 5.26 LTS** (embarcado) e a **remoção do FST**; (3) empacotar/validar o runtime **Liberica Full JDK 21** (embarcado no Windows; do sistema no Linux); (4) validar paridade forense contra um baseline Java 11 e garantir que casos antigos continuem abrindo. A migração é incremental e gated por uma **suíte de paridade** que compara campos forenses definidos.

## Technical Context

**Language/Version**: Java 21 LTS (atual: Java 11). `maven.compiler.source/target` → `maven.compiler.release = 21` no parent POM. Runtime: **BellSoft Liberica Full JDK 21** (com JavaFX embutido), conforme [Clarifications Q3](spec.md).

**Primary Dependencies** (as que mudam — ver [research.md](research.md) para a matriz completa):
- **Neo4j** 4.4.4 → **5.26.x LTS** (embarcado out-of-process via Bolt; troca de formato de store aceita — casos autocontidos, sem abrir grafos antigos; Clarifications 2026-06-01).
- **FST** 2.57 → **removido** (substituído por serialização JDK no cache de `RegexTask`).
- **Lucene** 9.2.0 → **9.12.x** (compatibilidade de índice mantida dentro da linha 9.x — Princípio I).
- **Tika** 2.4.0-p1 (fork interno) → **2.9.2** upstream (avaliar drop do fork — TIKA-4126 já corrigido upstream).
- **JEP** 4.0.3 → **4.2.x** (rebuild do bundle nativo `python-jep-dlib`).
- **Nashorn** `nashorn-core` 15.4 → 15.4/15.6 (validar scripts JS).
- **JNA** 5.7.0 → 5.14.0 (alinhado entre `iped-engine` e `iped-parsers`).
- **BouncyCastle** `bcpkix-jdk15on` 1.70 → `bcpkix-jdk18on` 1.78.1 (+ `bcprov-jdk18on`).
- **Jersey/Grizzly** 2.30.1 → 2.41 (mantém namespace `javax.*`; **sem** migração jakarta — Out of Scope).
- Toolchain Maven: `maven-compiler-plugin` 3.2/3.7/3.10.1 → 3.13.0; `maven-surefire-plugin` 2.18.1/2.20.1 → 3.5.x; `maven-jar-plugin` 2.5/3.1.0 → 3.4.x; `maven-dependency-plugin` 2.10 → 3.8.x; `findbugs-maven-plugin` 3.0.0 → removido.
- Runtime embarcado: artefato `java:jre` 11.0.13 → Liberica Full **21.0.x** (publicar no maven do projeto).

**Storage** (formato congelado por Princípio I — evitar churn; FR-004 retirado em 2026-06-01, então não é mais gate de retrocompatibilidade do release novo):
- Índice **Lucene** do caso → formato inalterado (linha 9.x + `lucene-backward-codecs`).
- **SQLite** (`storage-*.db`, hashdb) via xerial-sqlite-jdbc → sem mudança de formato.
- **H2** (cache) 2.3.232 → sem mudança.
- **Neo4j** graph store → formato muda (5.x); grafos antigos **não** precisam abrir. Casos são autocontidos (JRE + libs do processamento empacotadas com o caso — Clarifications 2026-06-01), então o release novo nunca abre um store de outra versão; sem guarda de store 4.x (FR-007 retirado).

**Testing**: JUnit 4.13.2 + Hamcrest 3.0 + Mockito 3.8.0 via `maven-surefire-plugin` (atualizado p/ 3.5.x). Testes YARA integration-gated (`assumeTrue` em `YARA_X_LIB_PATH`). **Novo**: harness de **paridade forense** (compara campos definidos entre baseline Java 11 e Java 21 — ver [contracts/parity-validation.contract.md](contracts/parity-validation.contract.md)).

**Target Platform**: Windows x64 (runtime Java 21 embarcado) e Linux x64 (runtime Java 21 do sistema). Sem novas plataformas (Out of Scope).

**Project Type**: Aplicação forense desktop (Swing + JavaFX) + CLI de processamento + Web API REST. Maven multi-módulo (8 módulos, 16 POMs, ~1.302 arquivos Java, ~193K LOC).

**Performance Goals**: Throughput igual ou melhor que o baseline Java 11; **regressão máxima de 5%** no mesmo dataset/hardware (SC-005). Ordem de grandeza preservada (centenas de milhões de itens/caso; ~400 GB/h).

**Constraints**:
- **Preservador de comportamento** (FR-018): sem novos recursos de linguagem, sem mudança funcional.
- **Determinismo forense** (Princípio IV): saída idêntica nos campos definidos (SC-002).
- **Estabilidade de formato de índice** (Princípio I): `BasicProps`/`IndexItem`/`AppAnalyzer` congelados (evita churn; o caso é lido com suas próprias libs). Abrir casos antigos com o release novo não é mais requisito (FR-004 retirado em 2026-06-01).
- **Modelo de concorrência** (Princípio V): workers, per-worker task instances e out-of-process preservados; **sem virtual threads**.
- **Sem migração jakarta** e **sem preservar/abrir grafos antigos** (casos autocontidos — Out of Scope / Clarifications 2026-06-01).

**Scale/Scope**: 8 módulos, 16 POMs, ~193K LOC. ~30 dependências de terceiros a verificar/atualizar. CI a reconfigurar. Toque cross-cutting (não há "feature tree" isolada).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

> ⚠️ **Gate de Governança — emenda obrigatória.** A seção *"Restrições de Build, Ferramentas e Distribuição"* da constituição (v1.1.0) fixa **"Java 11 com JavaFX … `maven.compiler.source/target = 11`. Não introduzir APIs de Java posteriores"**. Esta feature **redefine essa restrição**. A migração **exige uma emenda** à constituição (atualizar a baseline para Java 21), via o processo do §Governance, com **bump MINOR** (expansão/atualização material de orientação de build; não remove princípio I–V → não é MAJOR). Esta emenda é pré-requisito de merge e está listada como tarefa no plano. **Não é violação a justificar — é o propósito explícito da feature**, mas precisa do PR de emenda.

| Princípio | Avaliação | Status |
|---|---|---|
| **I. Estabilidade da API Pública** | `iped-api`, `BasicProps`/`IndexItem` (chaves Lucene) e `AppAnalyzer` **não** podem mudar. Lucene sobe dentro da linha 9.x com `backward-codecs` → formato de índice inalterado (evita churn; o caso é lido com suas próprias libs). Bump de versão do projeto permanece `4.4.0-SNAPSHOT` (ou conforme release). | ✅ PASS (FR-004 retirado 2026-06-01 — não há mais gate de abertura de casos antigos) |
| **II. Extensão Modular vs Modificação** | Migração é inerentemente cross-cutting. Núcleo (`Manager`/`Worker`/`ProcessingQueues`/`IndexWriter`) **não** sofre mudança de lógica. Toques pontuais inevitáveis: `Bootstrap.getCustomJVMArgs()` (add-opens), `Util` (versão), `RegexTask` (remoção FST), módulo `graph/` (Neo4j 5 API, reescrito out-of-process via Bolt). Documentados em Complexity Tracking. | ⚠️ PASS c/ justificativa |
| **III. Configuração antes de Código** | Sem novo comportamento hardcoded. Mensagens de versão permanecem em `localization/` (PT-BR+EN). Constantes `MIN/MAX_JAVA_VER` em código já são o padrão atual (apenas atualizadas). | ✅ PASS |
| **IV. Integridade Forense e Determinismo** | Gate central. Charset/log/`java.time`/`SleuthkitClient` inalterados. Risco: bumps de libs podem introduzir não-determinismo (ordenação, encoders de imagem). Mitigado pela **suíte de paridade** (SC-002) e por revisar libs que afetam saída forense. | ✅ PASS (gate: paridade verde) |
| **V. Concorrência e Isolamento de Processo** | Modelo de workers, instância-por-worker e out-of-process (`SleuthkitClient`, `ParsingProcess`, `Bootstrap`→JVM filha) preservados. **Proibido** adotar virtual threads (FR-018). JavaFX em `Platform.runLater`, Swing na EDT — inalterados. | ✅ PASS |

**Restrições de Build/Distribuição**: novas/atualizadas dependências **DEVEM** ser registradas em `ThirdParty.txt` + `licenses/`; o `.github/workflows/maven.yml` **DEVE** ser atualizado no mesmo conjunto de mudanças (gate de CI).

**Resultado do gate**: PASS condicionado a (a) PR de emenda da constituição, (b) suíte de paridade verde, (c) `ThirdParty.txt`/CI atualizados. Sem violações que exijam abandono de princípio. (A validação de abertura de casos antigos saiu do escopo em 2026-06-01 — FR-004 retirado.)

## Project Structure

### Documentation (this feature)

```text
specs/003-java21-migration/
├── plan.md                                  # Este arquivo (/speckit-plan)
├── research.md                              # Fase 0: decisões de versão/abordagem + matriz de deps
├── data-model.md                            # Fase 1: artefatos, runtime e matriz de upgrade
├── quickstart.md                            # Fase 1: setup dev Java 21, build, validação de paridade
├── contracts/
│   ├── runtime-version-check.contract.md    # Contrato: detecção/aviso de versão de Java
│   └── parity-validation.contract.md        # Contrato: o que e como comparar (baseline vs 21)
├── checklists/
│   └── requirements.md                      # (de /speckit-specify)
└── tasks.md                                 # Fase 2 (/speckit-tasks — NÃO criado aqui)
```

### Source Code (repository root)

Migração cross-cutting — não há árvore de feature isolada. Mapa dos pontos de toque por módulo:

```text
pom.xml                                       # parent: maven.compiler.release=21; bump plugins; remover findbugs
iped-api/ … iped-geo/ (poms)                  # plugins de build (compiler/surefire/jar) → versões Java 21
iped-engine/pom.xml                           # Neo4j 5.26, Lucene 9.12, Tika 2.9.2, JNA 5.14, BC jdk18on,
                                              #   Jersey 2.41, remover FST, zstd-jni bump; ThirdParty.txt
iped-engine/.../util/Util.java                # MIN/MAX_JAVA_VER (11/14 → 21); buggedVersions
iped-engine/.../task/regex/RegexTask.java     # remover FSTConfiguration → serialização JDK do cache
iped-engine/.../graph/*  +  iped-app/.../graph/* # API Neo4j 4.4 → 5.x (DatabaseManagementService, Cypher)
iped-engine/.../task/ScriptTask.java          # validar Nashorn 15.x (sem mudança de API esperada)
iped-engine/.../config/Configuration.java     # (sem mudança funcional; revisar se toca classpath/JDK)
iped-parsers/.../misc/OFCParser.java          # javax.xml.bind (JAXBContext) → dep explícita jakarta/glassfish
iped-parsers/.../security/CertificateParser.java, telegram/TelegramParser.java # DatatypeConverter → HexFormat
iped-geo/.../parsers/GeofileParser.java       # DatatypeConverter → HexFormat
iped-app/.../timelinegraph/cache/.../CachePersistance.java # DatatypeConverter → HexFormat
iped-engine/.../task/jumplist/PathToGuidConverter.java     # javax.annotation.Nonnull → jsr305 explícito
iped-app/src/main/java/iped/app/bootstrap/Bootstrap.java   # getCustomJVMArgs(): add-opens p/ Neo4j 5/Swing libs
iped-app/pom.xml                              # artefato java:jre → Liberica Full 21; bump plugins; 25+ executions
iped-app/resources/localization/iped-engine-messages*.properties # mensagens de versão (se necessário)
.github/workflows/maven.yml                   # matriz Java 11/14 → Java 21 (Liberica Full + JavaFX)
ThirdParty.txt + licenses/                    # registrar deps novas/atualizadas
CLAUDE.md (raiz e módulos afetados)           # atualizar baseline Java + dependências
.specify/memory/constitution.md               # EMENDA: baseline Java 11 → 21 (bump MINOR)
```

**Test/validation (novo)**:
```text
specs/003-java21-migration/quickstart.md      # procedimento de validação de paridade
(baseline dataset + caso-baseline Java 11)    # definido pelos mantenedores (ver research.md / Deferred)
```

**Structure Decision**: mantém-se a estrutura Maven multi-módulo existente (8 módulos). Nenhum módulo novo é criado. As mudanças são edições in-place de POMs, de um conjunto pequeno e bem delimitado de classes Java, da configuração de CI e da distribuição. A validação adiciona um procedimento (não um módulo) de comparação baseline↔21.

## Complexity Tracking

> Toques em componentes sensíveis (Princípios I/II) e desvio da baseline de build (governança), todos justificados.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Emenda à constituição (baseline Java 11 → 21) | A constituição fixa Java 11; o propósito da feature é mudar isso | Não há alternativa: manter a baseline contradiz a feature; o processo de emenda existe exatamente para isso (bump MINOR) |
| Edição de `Bootstrap.getCustomJVMArgs()` (núcleo de inicialização) | Neo4j 5 embarcado e libs Swing exigem `--add-opens` adicionais sob encapsulamento forte | Não dá para adicionar via "novo módulo"; os args da JVM filha vivem no Bootstrap; `-XX:+IgnoreUnrecognizedVMOptions` já mitiga flags removidas |
| Migração da API do Neo4j em `graph/` (engine + app) → **resolvida como out-of-process** | Neo4j 4.4 não roda em Java 21; o Cypher do 5.26 exige antlr 4.13, incompatível com o antlr 4.9 que o `libfqlite` fixa (ATN incompatível). Não coexistem no mesmo classpath. | Manter 4.4 impede Java 21; forçar um antlr quebra o outro lado; shading do libfqlite foi preterido. Solução (mira OSGi): engine embarcado em **JVM filha isolada** (módulo `iped-graph-server`, `lib/neo4j/`), UI fala **Bolt**. Adapters `graphdb-api` mantêm os consumidores da UI intocados. Ver [implementation-report.md](implementation-report.md) §2.5. |
| Remoção do FST em `RegexTask` (toca task existente) | FST 2.57 usa `Unsafe`/reflexão e quebra sob encapsulamento forte | Adicionar `--add-opens` para FST mantém dependência morta e frágil; o uso é isolado (cache local) e trivialmente substituível por serialização JDK |
| ~~Guarda na abertura de graph store antigo (carregamento de caso)~~ | ~~Abrir caso com store Neo4j 4.x não pode crashar (FR-007)~~ | **Removido (2026-06-01)**: casos são autocontidos (JRE + libs empacotadas com o caso); o release novo não abre graph store de outra versão, então não há guarda a justificar. |

## Phases

- **Phase 0 — Research** → [research.md](research.md): resolve versões-alvo, matriz de compatibilidade de dependências, estratégia Neo4j 5, substituição do FST, add-opens necessários, e se o fork Tika pode ser abandonado.
- **Phase 1 — Design & Contracts** → [data-model.md](data-model.md) (artefatos + matriz de upgrade), [contracts/](contracts/) (contrato de detecção de versão + contrato de validação de paridade), [quickstart.md](quickstart.md) (setup/build/validação).
- **Phase 2 — Tasks** → gerado por `/speckit-tasks` (não aqui).
- **Implementação & validação** → ver [implementation-report.md](implementation-report.md) (o que foi feito, verificado por compilação/testes/run real, e o que permanece pendente). Destaque: o runtime do **Neo4j 5 foi rearquitetado para out-of-process via Bolt** (§2.5), e a **JRE passou a ser copiada da pasta local `iped-jre/`** (§2.6).
