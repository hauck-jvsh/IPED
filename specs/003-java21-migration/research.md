# Research — Migração para Java 21 LTS

**Fase 0** do plano. Resolve as escolhas técnicas e a matriz de compatibilidade de dependências. Formato por item: **Decisão / Justificativa / Alternativas consideradas**.

> Base factual: levantamento direto do repositório (POMs, `Bootstrap.java`, `Util.java`, `RegexTask.java`, imports JavaFX/`javax.*`, ausência de `sun.misc.Unsafe`/`SecurityManager` no fonte). Datas/versões refletem o ecossistema em 2026-05.

---

## 1. Runtime e nível de linguagem

**Decisão**: `maven.compiler.release = 21` no parent POM. Runtime de execução e build: **BellSoft Liberica Full JDK 21** (LTS, com JavaFX e Flight Recorder). Manter `-XX:+IgnoreUnrecognizedVMOptions` no Bootstrap.

**Justificativa**: Liberica Full já é o JDK usado hoje (11) e empacota JavaFX — alinhado à [Clarification Q3]. `release` (em vez de `source`+`target`) impede uso acidental de APIs mais novas que a plataforma. `IgnoreUnrecognizedVMOptions` protege contra flags de GC/legados removidos entre 11 e 21 (ex.: opções de CMS, removido no 14).

**Alternativas**: (a) OpenJDK base + OpenJFX modular — rejeitado (module-path, skew de versão; Q3). (b) Temurin/Zulu — válidos, mas Liberica Full mantém o pipeline de empacotamento atual sem mudança de fornecedor.

---

## 2. Encapsulamento forte (a barreira real do 11→21)

**Decisão**: depender de `--add-opens`/`--add-exports` apenas onde bibliotecas exigirem, mantendo a lista mínima em `Bootstrap.getCustomJVMArgs()`. Eliminar a causa-raiz quando barato (ex.: remover FST).

**Justificativa**: a partir do Java 16 (JEP 396) o acesso reflexivo a internals é negado por default. O projeto **já** passa 10 `--add-opens` e **não** usa `sun.misc.Unsafe`/`jdk.internal`/`SecurityManager` diretamente no fonte — então a superfície é das *dependências*, não do código próprio. SecurityManager segue presente (apenas desabilitável) no 21 — sem impacto.

**add-opens prováveis a adicionar** (confirmar em runtime):
- **Neo4j 5 embarcado**: historicamente requer abrir `java.base/java.nio`, `java.base/sun.nio.ch` (já presentes) e pode exigir `java.base/java.lang.invoke`. Validar com a doc da versão 5.26.
- **Libs Swing/DockingFrames/JFreeChart**: podem exigir `java.desktop/...` (ex.: `java.desktop/sun.awt`, `java.desktop/javax.swing.plaf.basic`) — confirmar empiricamente.

**Alternativas**: `--illegal-access=permit` — **inexistente** no Java 21 (removido no 17). Rejeitado.

---

## 3. Neo4j (maior risco)

**Decisão**: **Neo4j 5.26.x LTS** embarcado. Não preservar grafos de casos antigos. Como casos são **autocontidos** (cada caso acompanha a JRE + libs do seu processamento — Clarifications 2026-06-01), o release novo nunca abre um graph store de outra versão; **não há guarda de store 4.x** a implementar (FR-007 retirado).

**Justificativa**: Neo4j 4.4 suporta apenas Java 11/17 — não inicia em Java 21. 5.26 é o LTS da linha 5, certificado para Java 17/21. A API embarcada (`DatabaseManagementServiceBuilder`) já existe na linha 4.4 (migração de chamadas é contida). Cypher dos templates `.cypher` precisa de revisão (sintaxe 5.x). Como não há requisito de abrir stores antigos, a mudança de formato é aceitável.

**Alternativas**: (a) Neo4j 2025.x (calver) — também Java 21, porém mais novo/instável para embarcado; 5.26 LTS é mais conservador. (b) Manter 4.4 e travar em Java 17 — contraria a feature. (c) Substituir Neo4j por outra engine de grafo — fora de escopo (mudança funcional).

**Itens a validar**: classe de inicialização embarcada, `--add-opens` exigidos, sintaxe Cypher dos templates de `links/`. (A verificação de "abrir `graph.db` 4.x sem derrubar a UI" saiu do escopo em 2026-06-01.)

---

## 4. FST (serialização)

**Decisão**: **remover** `de.ruedigermoeller:fst`. Substituir o cache de regex em `RegexTask` (`fastSerializer.asByteArray`/`asObject` de `Regex`/`List<Regex>`) por **serialização JDK** (`ObjectOutputStream`/`ObjectInputStream`), confirmando que `iped.engine.task.regex.Regex` e `dk.brics.automaton.Automaton` são `Serializable`.

**Justificativa**: FST 2.57 usa `Unsafe`/reflexão sobre internals e é frágil sob encapsulamento forte; está sem manutenção ativa. O uso é **isolado** (um cache local em disco) — não é um contrato persistido entre versões, então trocar o formato do cache é inócuo (cache pode ser regenerado). `dk.brics.automaton.Automaton` implementa `Serializable`.

**Alternativas**: (a) `--add-opens` para FST — mantém dependência morta. (b) FST 3.x — risco de compatibilidade, mesmo problema de manutenção. (c) Kryo — nova dependência desnecessária para um cache simples.

---

## 5. Lucene

**Decisão**: **Lucene 9.12.x** (manter linha 9.x). Manter `lucene-backward-codecs`. Não tocar `AppAnalyzer`/`StandardASCIIAnalyzer` nem as chaves de campo.

**Justificativa**: dentro da linha 9.x o formato de índice é estável (Princípio I — evita churn; o caso é lido com suas próprias libs); 9.12 é testado em Java 21 e usa o Panama/MMap moderno. Subir para Lucene 10 mudaria o formato de índice e exigiria Java 21 mínimo — ganho desnecessário e arriscado para esta feature. (FR-004 retirado em 2026-06-01 — não há mais requisito de abrir índices antigos; manter 9.x continua valendo por estabilidade/Princípio I.)

**Alternativas**: Lucene 10.x — rejeitado (muda o formato de índice e os mínimos de JDK; churn desnecessário para uma migração preservadora). Manter 9.2.0 — funciona no 21 mas perde correções e otimizações de MMap/Panama.

---

## 6. Apache Tika (fork interno `-p1`)

**Decisão**: subir para **Tika 2.9.2** (upstream) e **avaliar a remoção do fork** `tika-core` `2.4.0-p1`. O comentário no parent POM indica que o patch `SyncMetadata` existe por causa de **TIKA-4126**, corrigido upstream a partir do Tika 2.5.0.

**Justificativa**: eliminar um fork interno reduz custo de manutenção e risco de skew. 2.9.x é a cauda estável da linha 2.x (mantém namespace e API de parser usados). Confirmar que o workaround do commit `b673cf4` pode ser revertido.

**Alternativas**: Tika 3.x — migra para `jakarta`/Java 11+ com mudanças de API maiores; fora de escopo. Manter 2.4.0-p1 — preserva o fork e o débito.

**A validar**: regressão dos parsers customizados (`StandardParser`, SQLite module, NLP module, langdetect) e se `tika-parser-sqlite3-module`/`tika-parsers-standard-package` sobem juntos sem conflito.

---

## 7. JEP (Python embarcado / OCR)

**Decisão**: **JEP 4.2.x** e **rebuild** do artefato bundle `org.python:python-jep-dlib` para JEP 4.2 + Python 3.x (manter 3.9/3.11 conforme disponibilidade do dlib). `JEPClassFinder` já cai no classlist "11" para qualquer versão ≥ 11 — sem mudança de código necessária ali.

**Justificativa**: JEP é nativo (JNI); 4.0.3 antecede a validação em Java 21. 4.2.x cobre JDKs novos. O bundle nativo precisa ser recompilado de qualquer forma para o ambiente alvo.

**Alternativas**: manter 4.0.3 — risco de carga nativa/JNI sob 21. Migrar OCR para fora do JEP — mudança funcional fora de escopo.

**A validar**: `OCRParser`, scripts Python de task (`FaceRecognitionTask.py`, `Wav2Vec2Process.py`, `WhisperProcess.py`, `CSAMDetectorTask.py`) carregam e executam.

---

## 8. Nashorn (scripting JS)

**Decisão**: manter `org.openjdk.nashorn:nashorn-core` (standalone), **validar com 15.4 e, se necessário, subir para 15.6**. Sem mudança em `ScriptTask` (usa `javax.script` / `ScriptEngineManager`).

**Justificativa**: o Nashorn do JDK foi removido no 15; o projeto já usa o standalone. 15.x usa `jdk.dynalink` (presente no 21). É baixo risco; só requer revalidar os scripts `.js` de task.

**Alternativas**: GraalJS — mudança maior (engine diferente, `polyglot`); fora de escopo. Remover scripting JS — quebra extensibilidade documentada.

---

## 9. JNA

**Decisão**: **JNA 5.14.0** alinhado em `iped-engine` (YARA-X) e `iped-parsers` (libesedb) e compatível com `oshi-core-java11` 6.2.2.

**Justificativa**: 5.7.0 é antigo; 5.14 melhora carga nativa em JDKs recentes e mantém compat com Java 11+ caso necessário. Alinhar a versão evita skew de `Native.load`.

**Alternativas**: manter 5.7.0 — risco de carga nativa. JNA 5.15+ — válido; 5.14 é conservador e amplamente usado.

---

## 10. BouncyCastle

**Decisão**: `bcpkix-jdk15on` 1.70 → **`bcpkix-jdk18on` 1.78.1** (+ `bcprov-jdk18on` 1.78.1).

**Justificativa**: a linha `jdk15on` está descontinuada; `jdk18on` é a linha mantida (JDK 1.8+), recomendada para JDKs modernos. Usado por `iped-parsers` (certificados) e cripto.

**Alternativas**: manter `jdk15on` — funciona mas legado e sem novas correções.

---

## 11. APIs Java EE removidas usadas no fonte

**Decisão**:
- `javax.xml.bind.DatatypeConverter` (em `CertificateParser`, `TelegramParser`, `GeofileParser`, `CachePersistance`) → substituir por **`java.util.HexFormat`** (Java 17+) ou `java.util.Base64`, eliminando a dependência de JAXB nesses pontos.
- `javax.xml.bind.JAXBContext`/`Unmarshaller`/anotações em **`OFCParser`** → adicionar dependências explícitas **`jakarta.xml.bind:jakarta.xml.bind-api`** + runtime **`org.glassfish.jaxb:jaxb-runtime`** (ou manter `javax.xml.bind` via `com.sun.xml.bind:jaxb-impl`), pois o parser faz binding real.
- `javax.annotation.Nonnull` (em `PathToGuidConverter`) → adicionar **`com.google.code.findbugs:jsr305`** explícito.

**Justificativa**: JAXB e `javax.annotation` foram removidos do JDK no Java 11 — hoje resolvem por dependência transitiva (frágil). Tornar explícito (ou substituir por API JDK) garante compilação determinística no 21. `HexFormat` elimina a dependência onde só se faz hex.

**Alternativas**: confiar na transitividade — frágil e pode sumir num bump. Migrar tudo para `jakarta` — só onde necessário (OFCParser); evita esforço amplo fora de escopo.

---

## 12. Web API (Jersey / Grizzly / Swagger)

**Decisão**: **Jersey 2.41** (linha 2.x, namespace `javax.*`) + HK2 correspondente + Grizzly. Manter `swagger-jersey2-jaxrs` 1.6.x e `jersey-media-json-jackson`. **Sem** migração para Jakarta/Jersey 3.x.

**Justificativa**: 2.41 é a cauda da linha `javax` (compatível com Java 21) — evita a reescrita de namespace (`javax`→`jakarta`) que é Out of Scope. Resolve a fragilidade do HK2 (reflexão) com versões mais novas.

**Alternativas**: Jersey 3.x (jakarta) — mudança ampla de imports na `webapi/`; fora de escopo. Manter 2.30.1 — pode rodar no 21 mas é antigo e arriscado com HK2.

**A validar**: endpoints REST + Swagger UI sobem; possíveis `--add-opens` para injeção HK2.

---

## 13. Demais dependências — matriz de verificação

| Dependência | Atual | Decisão | Risco/Nota |
|---|---|---|---|
| zstd-jni | 1.3.3-3 | Subir p/ 1.5.x | Nativo antigo; bump recomendado |
| postgresql | 9.1-901-1.jdbc4 | Subir p/ driver moderno (42.x) | Driver ancião; verificar uso real |
| opensearch-rest-high-level-client | 2.1.0 | Verificar no 21; bump se falhar | Reflexão; provavelmente OK |
| minio | 8.3.8 | Verificar no 21 | OkHttp transitive; provavelmente OK |
| HikariCP | 7.0.2 | Manter | OK no 21 |
| h2 | 2.3.232 | Manter | OK no 21 |
| sqlite-jdbc (xerial) | 3.41.2.2 | Manter (ou bump menor) | OK |
| pdfbox / xmpbox / jbig2 | 2.0.27 / 3.0.4 | Manter | OK no 21 |
| icepdf | 7.0.0 | Manter; revalidar viewer | Swing/render |
| commons-compress | 1.27.1 | Manter | OK (ver issue #1068 antes de bump) |
| DockingFrames | 1.1.2 | Manter; possível `--add-opens java.desktop` | Swing reflexivo |
| kharon / jfreechartextensions | atual | Manter; revalidar grafo/timeline | Swing/JavaFX render |
| sevenzipjbinding | 16.02-2.01 | Verificar carga nativa no 21 | Nativo |
| sleuthkit | 4.12.0.p1 | Manter (out-of-process isola) | JNI isolado por `SleuthkitClient` |
| vosk / google-speech / ms-speech | atuais | Verificar; speech é `provided`/opcional | Baixa prioridade |

**Decisão geral**: bump apenas o necessário para rodar no 21; **congelar** o que já é compatível para minimizar superfície de regressão (Princípio IV).

---

## 14. Toolchain Maven e CI

**Decisão**:
- `maven-compiler-plugin` → **3.13.0**; usar `<release>21</release>`.
- `maven-surefire-plugin` → **3.5.x** (2.18/2.20 não forka testes corretamente em JDK novo).
- `maven-jar-plugin` → **3.4.x**; `maven-dependency-plugin` → **3.8.x**.
- Remover `findbugs-maven-plugin` 3.0.0 (abandonado); SpotBugs é opcional e fora de escopo.
- `.github/workflows/maven.yml`: substituir os jobs `build-java11`/`build-java14` por **um job Java 21** (`setup-java@v4`, `distribution: liberica`, `java-version: 21`, com JavaFX). Manter a instalação das ferramentas nativas e a verificação do `libyara_x_capi.so`.

**Justificativa**: as versões atuais de surefire/compiler não suportam Java 21. Um único job 21 reflete o cut-over (FR-013/FR-017).

**Alternativas**: manter matriz 11/14 — contraria o cut-over. Adicionar job 25 — Out of Scope.

---

## 15. Detecção de versão no app

**Decisão**: `Util.MIN_JAVA_VER`/`MAX_JAVA_VER` (hoje 11/14) → **21/21**; revisar `buggedVersions` (bug de WebView JDK-8196011 é do Java 8 — pode permanecer inócuo). Mensagens em `localization/` revisadas se necessário (FR-012).

**Justificativa**: hoje qualquer versão > 14 dispara aviso falso de "não testada". Atualizar reconhece o 21 como suportado.

**Alternativas**: deixar `MAX_JAVA_VER` folgado (ex.: 21) e `MIN`=21 — escolhido. `MIN`=17 permitiria 17 — contraria cut-over para 21.

---

## 16. Item Deferred — conjunto de dados de referência (baseline)

**Decisão**: o **dataset de paridade** e o **caso-baseline Java 11** serão definidos pelos mantenedores (amostra representativa: ≥1 imagem forense E01/DD, ≥1 UFDR, ≥1 pasta lógica, cobrindo parsers de alto uso, carving, YARA, OCR e timeline). Procedimento de comparação em [contracts/parity-validation.contract.md](contracts/parity-validation.contract.md).

**Justificativa**: o *conteúdo* do dataset é decisão operacional/de dados, não de arquitetura — apropriado para a fase de execução. O **critério** (campos comparados, exclusões) já está fixado por SC-002/FR-003.

**Alternativas**: especificar imagens fixas no plano — engessaria a validação; melhor deixar o conjunto a cargo de quem detém as evidências de teste.

---

## Resumo das decisões (índice rápido)

| Área | Decisão |
|---|---|
| Runtime | Liberica Full JDK 21; `release=21`; `IgnoreUnrecognizedVMOptions` |
| Neo4j | 5.26 LTS embarcado; sem preservar store antigo (guarda contra crash) |
| FST | Removido; cache de regex → serialização JDK |
| Lucene | 9.12.x (compat de índice; `backward-codecs`) |
| Tika | 2.9.2 upstream; avaliar drop do fork `-p1` (TIKA-4126) |
| JEP | 4.2.x + rebuild do bundle nativo |
| Nashorn | manter standalone (15.4/15.6); validar scripts |
| JNA | 5.14.0 alinhado |
| BouncyCastle | `jdk18on` 1.78.1 |
| javax.* removidos | `HexFormat`/`Base64`; JAXB explícito no OFCParser; jsr305 |
| Web API | Jersey 2.41 (javax); sem jakarta |
| Toolchain | compiler 3.13, surefire 3.5, jar 3.4, dependency 3.8; remove findbugs; CI Java 21 |
| Version check | MIN/MAX_JAVA_VER → 21 |
| Governança | Emenda da constituição (Java 11→21, bump MINOR) |
