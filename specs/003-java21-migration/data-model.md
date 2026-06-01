# Data Model / Artefatos — Migração Java 21

**Fase 1.** Uma migração de plataforma não introduz entidades de domínio novas; este documento modela os **artefatos manipulados**, seus **invariantes de compatibilidade** (regras de validação derivadas dos requisitos) e as **transições de estado** relevantes (carregamento de caso e fases da migração).

## Entidades / Artefatos

### 1. Caso processado (`Processed Case`)
Saída do IPED em `{caseDir}/iped/`.
- **Campos**: índice Lucene (`index/`), storage SQLite (`storage-*.db`), bookmarks, thumbnails, eventual graph store Neo4j, `CaseData` serializado.
- **Origem**: gerado por um release (Java 11 = "antigo"; Java 21 = "novo").
- **Invariantes**:
  - ~~I1 (FR-004, Princípio I): índice Lucene de caso antigo **DEVE** abrir e ser pesquisável no release 21.~~ — **Removido (2026-06-01)** com FR-004 (casos autocontidos; release novo não abre casos antigos). O formato de índice (Lucene 9.x, `backward-codecs`, chaves/`AppAnalyzer`) segue **congelado** por Princípio I — para evitar churn e manter o caso legível com **suas próprias libs** —, mas não como garantia de retrocompatibilidade do release novo.
  - ~~I2 (FR-005): caso portátil antigo **DEVE** abrir.~~ — **Removido (2026-06-01)** junto com FR-005 (casos autocontidos; release novo não abre casos antigos).
  - ~~I3 (FR-007): graph store Neo4j 4.x **NÃO** precisa abrir; tentar abri-lo **NÃO PODE** crashar o carregamento do caso.~~ — **Removido (2026-06-01)** junto com FR-007.
  - I4 (Princípio I): chaves de campo Lucene (`BasicProps`/`IndexItem`) e `AppAnalyzer` **imutáveis**.

### 2. Conjunto de dados de referência (`Reference Baseline Dataset`)
Amostra de fontes para validar paridade.
- **Campos**: ≥1 imagem forense (E01/DD), ≥1 UFDR, ≥1 pasta lógica; cobertura de parsers de alto uso, carving, YARA, OCR, timeline.
- **Estado**: definido pelos mantenedores (Deferred — ver research.md §16).
- **Invariante**: produz um **caso-baseline Java 11** congelado, usado como verdade-base na comparação.

### 3. Runtime embarcado (`Bundled Runtime`)
- **Campos**: Liberica Full JDK 21 (com JavaFX), por plataforma.
- **Invariantes**:
  - R1 (FR-015, Q1): **Windows** embarca o runtime; **Linux** usa runtime do sistema (não embarca).
  - R2 (Q3): é um **Full JDK com JavaFX embutido** (não OpenJDK base + OpenJFX separado).
  - R3 (FR-012): reconhecido como "suportado" pela verificação de versão (sem aviso falso).

### 4. Pacote de release (`Release Package`)
Árvore `target/release/iped-<version>/`.
- **Campos**: `iped.jar`, `lib/`, `tools/`, `conf/`, `profiles/`, `jre/` (só Windows), `models/`, `plugins/`, `localization/`.
- **Invariantes**:
  - P1 (FR-016): configs/profiles/localização inalterados — sem ação de migração do usuário.
  - P2 (Princípio Build): novas/atualizadas deps registradas em `ThirdParty.txt` + `licenses/`.

### 5. Dependência (`Dependency`)
- **Campos**: groupId/artifactId, versão atual, versão-alvo, ação (manter/bump/substituir/remover), risco.
- **Invariante** (FR-014): nenhuma dep pode falhar em runtime por encapsulamento forte ou API removida.
- **Dados**: ver **Matriz de Upgrade** abaixo.

### 6. Constituição (`Constitution`)
- **Campos**: seção "Restrições de Build" (baseline Java).
- **Invariante**: precisa de **emenda** (Java 11 → 21, bump MINOR) antes do merge (gate de governança).

## Matriz de Upgrade (estado-alvo das dependências)

| GroupId:ArtifactId | Atual | Alvo | Ação | Risco |
|---|---|---|---|---|
| (parent) maven.compiler | source/target 11 | release 21 | alterar | — |
| org.neo4j:neo4j | 4.4.4 | 5.26.x | bump (API+Cypher) | 🔴 alto |
| de.ruedigermoeller:fst | 2.57 | — | **remover** | 🟠 médio |
| org.apache.lucene:* | 9.2.0 | 9.12.x | bump | 🟡 baixo |
| org.apache.tika:* | 2.4.0(-p1) | 2.9.2 | bump + avaliar drop do fork | 🟠 médio |
| (jep / python-jep-dlib) | 4.0.3 | 4.2.x | bump + rebuild nativo | 🟠 médio |
| org.openjdk.nashorn:nashorn-core | 15.4 | 15.4/15.6 | manter/validar | 🟡 baixo |
| net.java.dev.jna:jna | 5.7.0 | 5.14.0 | bump | 🟡 baixo |
| org.bouncycastle:bcpkix-jdk15on | 1.70 | bcpkix-jdk18on 1.78.1 | substituir | 🟡 baixo |
| org.glassfish.jersey.*:* | 2.30.1 | 2.41 | bump (mantém javax) | 🟠 médio |
| javax.xml.bind.* (uso no fonte) | transitivo | HexFormat / JAXB explícito | substituir/explicitar | 🟡 baixo |
| javax.annotation.Nonnull | transitivo | jsr305 explícito | explicitar | 🟢 trivial |
| com.github.luben:zstd-jni | 1.3.3-3 | 1.5.x | bump | 🟡 baixo |
| postgresql:postgresql | 9.1-901 | 42.x | bump (se usado) | 🟡 baixo |
| maven-compiler-plugin | 3.2/3.7/3.10.1 | 3.13.0 | bump | 🟢 |
| maven-surefire-plugin | 2.18.1/2.20.1 | 3.5.x | bump | 🟢 |
| maven-jar-plugin | 2.5/3.1.0 | 3.4.x | bump | 🟢 |
| maven-dependency-plugin | 2.10 | 3.8.x | bump | 🟢 |
| findbugs-maven-plugin | 3.0.0 | — | remover | 🟢 |
| java:jre (artefato embarcado) | 11.0.13 | Liberica Full 21.0.x | publicar+bump | 🟠 médio |
| (demais — opensearch, minio, pdfbox, h2, sqlite, hikari, dockingframes, sevenzipjbinding) | atuais | verificar; bump só se falhar | matriz §13 research | 🟡 baixo |

## Transições de estado

### Carregamento de caso no release 21
```
abrir caso → abrir índice Lucene (OK) → abrir storage SQLite (OK)
   → abrir graph store Neo4j 5 (gerado pelo mesmo release autocontido) → aba de grafo normal
```
> Removida (2026-06-01) a ramificação de store 4.x incompatível: casos são autocontidos
> (acompanham JRE + libs do seu processamento), então o release novo nunca abre um graph
> store de outra versão. Sem FR-007, não há guarda de degradação a modelar.

### Fases da migração (cut-over, sem janela dupla)
```
[Java 11 baseline] 
   → toolchain 21 + release=21 (compila)
   → substituir/atualizar deps (FST out, Neo4j 5, Lucene/Tika/JEP/JNA/BC/Jersey)
   → add-opens + version check + Bootstrap
   → release Windows (embarcado) / Linux (sistema)
   → validação de paridade (caso recém-processado no 21 vs baseline 11)
   → emenda da constituição + CI 21
[Java 21 único runtime suportado]
```

## Regras de validação (gates derivados dos requisitos)

| Regra | Fonte | Verificação |
|---|---|---|
| V1: build compila no `release=21`, todos os módulos | FR-001 | `mvn clean package` |
| V2: 100% dos testes passam no 21 | FR-002/SC-001 | `mvn test` |
| V3: paridade forense por campo definido | FR-003/SC-002 | suíte de paridade (contrato) |
| ~~V4: casos antigos abrem/buscam~~ | ~~FR-004/SC-003~~ | **Removido (2026-06-01)** — casos autocontidos; release novo não abre casos antigos. Busca/navegação de caso **recém-processado** coberta por V3/V7 + render de viewers (FR-011) |
| ~~V5: graph store antigo não crasha~~ | ~~FR-007~~ | **Removido (2026-06-01)** — casos autocontidos; release novo não abre graph store antigo |
| V6: release inicia (Win sem Java / Linux c/ Java 21) | FR-015/SC-004 | smoke test em máquina limpa |
| V7: sem erro de runtime por incompat JDK | FR-014/SC-006 | logs limpos nas execuções de validação |
| V8: version check reconhece 21 | FR-012/SC-008 | inspecionar aviso na inicialização |
| V9: ThirdParty.txt + CI atualizados | Princípio Build | revisão de PR |
| V10: constituição emendada | Governança | PR de emenda |
