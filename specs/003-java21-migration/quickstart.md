# Quickstart — Build e validação no Java 21

Procedimento para desenvolver, buildar e **validar** a migração do IPED para Java 21 LTS. Pré-condição: branch `003-java21-migration`.

## 1. Pré-requisitos

- **BellSoft Liberica Full JDK 21** (com JavaFX) — equivalente ao Liberica Full 11 usado hoje.
- **Maven 3.6+**.
- `JAVA_HOME` apontando para o Liberica Full 21.
- Linux: ferramentas nativas via `apt` (ver `.github/workflows/maven.yml`) + `jep==4.2.x`.
- YARA-X: `tools/yara-x/<os>/` presente (e `YARA_X_LIB_PATH` para os testes integration-gated).

> Memória do projeto: usar o Liberica Full FX. O alvo desta feature é a versão **21** desse JDK (hoje os builds usam o 11 em `H:\java\LibericaJDK-11-Full`).

```powershell
# Windows (PowerShell)
$env:JAVA_HOME = "H:\java\LibericaJDK-21-Full"   # ajuste ao caminho do JDK 21
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version    # deve reportar 21 (Liberica, com JavaFX)
```

## 2. Build

```powershell
mvn clean package        # SEMPRE clean (m2e/ECJ pode envenenar target/classes)
```

- Build incremental de um módulo: `mvn -pl iped-engine -am install`.
- Detectar classes envenenadas: procurar "Unresolved compilation" em `iped-*/target/classes`.
- O release sai em `target/release/iped-4.4.0/`. **Não** copiar para instalações de teste — reportar caminhos dos JARs (o deploy é do usuário).

## 3. Ordem recomendada de execução (cut-over incremental)

1. **Toolchain**: parent POM `maven.compiler.release=21`; bump compiler/surefire/jar/dependency; remover findbugs. `mvn clean package` deve compilar.
2. **APIs removidas**: `HexFormat`/JAXB explícito/jsr305 (compila sem JAXB transitivo).
3. **FST out**: remover dep; cache de regex via serialização JDK; rodar `RegexTask` tests.
4. **Bumps de runtime**: Lucene 9.12, Tika 2.9.2 (avaliar drop do fork), JNA 5.14, BC jdk18on, Jersey 2.41, zstd-jni.
5. **Neo4j 5.26**: migrar API em `graph/`, revisar Cypher, guardar abertura de store antigo.
6. **JEP 4.2**: rebuild do bundle nativo; revalidar OCR + scripts Python.
7. **Bootstrap**: `getCustomJVMArgs()` — add-opens necessários (Neo4j/Swing); `Util` MIN/MAX_JAVA_VER → 21.
8. **Distribuição**: artefato Liberica Full 21 (Windows embarcado); `ThirdParty.txt`/`licenses/`; CI Java 21.
9. **Governança**: PR de emenda da constituição (Java 11 → 21).

## 4. Testes

```powershell
mvn test                                   # suíte completa (surefire 3.5.x)
mvn -pl iped-engine test                   # só engine
mvn -pl iped-parsers/iped-parsers-impl test
# YARA integration-gated:
$env:YARA_X_LIB_PATH = "$PWD\tools\yara-x\win64\yara_x_capi.dll"
mvn -pl iped-engine -Dtest='Yara*' test
```

Gate: **100% verde** (SC-001).

## 5. Validação de paridade forense (gate central — SC-002)

Conforme [contracts/parity-validation.contract.md](contracts/parity-validation.contract.md):

```text
# 1) Baseline (release atual, Java 11) — congelar
iped -d <DATASET_REF> -o <CASE_BASELINE> -profile forensic -tz <TZ>

# 2) Candidato (release Java 21) — mesmo dataset/profile/tz
iped -d <DATASET_REF> -o <CASE_J21> -profile forensic -tz <TZ>

# 3) Comparar campos C1–C8 (hashes, MIME, contagens, categorias,
#    texto normalizado, carved, YARA, timeline), casando por trackID,
#    aplicando exclusões E1–E5. Esperado: zero divergências.
```

- **Throughput** (SC-005): medir itens/s nos dois e exigir candidato ≥ baseline − 5%.
- **Casos antigos** (V4/V5): abrir conjunto de casos pré-existentes (inclui portáteis) na UI 21 — busca/navegação/relatório OK; caso com `graph.db` 4.x **não** crasha (aba de grafo degrada).

## 6. Smoke de distribuição (SC-004)

- **Windows sem Java**: instalar o release e processar um caso pequeno de ponta a ponta (runtime embarcado).
- **Linux com Java 21 do sistema**: idem; confirmar ferramentas nativas (Sleuthkit out-of-process, OCR/JEP, ImageMagick, LibreOffice, RegRipper).
- Confirmar que o aviso de versão **não** dispara em 21 (FR-012).

## 7. Definition of Done (resumo dos gates)

| Gate | Critério |
|---|---|
| Build | `mvn clean package` em Java 21, todos os módulos |
| Testes | 100% passam (SC-001) |
| Paridade | zero divergências em C1–C8 (SC-002) |
| Performance | regressão ≤ 5% (SC-005) |
| Casos antigos | abrem e buscam; graph 4.x não crasha (SC-003, FR-007) |
| Distribuição | Win (embarcado) + Linux (sistema) iniciam e processam (SC-004) |
| Runtime limpo | sem erro por incompat JDK (SC-006) |
| Governança/Docs | constituição emendada; `ThirdParty.txt`/`licenses/`/CI/CLAUDE.md atualizados |
