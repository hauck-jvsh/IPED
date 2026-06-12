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

> **Removido (2026-06-01):** o passo de abrir casos antigos (V4/V5, casos portáteis, graph 4.x) saiu do escopo junto com FR-004/005/006/007 — casos são autocontidos (analisados com as libs empacotadas neles). A busca/navegação/render de um caso **recém-processado** é coberta pela validação do caso-candidato (acima) + render de viewers (FR-011).

## 6. Smoke de distribuição (SC-004)

- **Windows sem Java**: instalar o release e processar um caso pequeno de ponta a ponta (runtime embarcado).
- **Linux com Java 21 do sistema**: idem; confirmar ferramentas nativas (Sleuthkit out-of-process, OCR/JEP, ImageMagick, LibreOffice, RegRipper).
- Confirmar que o aviso de versão **não** dispara em 21 (FR-012).

## 7. Definition of Done (resumo dos gates)

| Gate | Critério | Estado (sweep T058, 2026-06-02) |
|---|---|---|
| Build | `mvn clean package` em Java 21, todos os módulos | ✅ **17 módulos BUILD SUCCESS** (vários runs full-clean) |
| Testes | 100% passam (SC-001) | ✅ **CI verde no Java 21** (T048, run `26886502532`, 2026-06-03) + reator completo verde local (`mvn clean test -fae`, 17/17 módulos): `iped-parsers-impl` 183/0/0 (1 skip = JEP local), `iped-engine` 146/0/0 (com YARA). As falhas de `OCRParserTest` PSD/SVG eram regressão **real** do Java 21 (png-reader/JEP 396), corrigidas no surefire `argLine` (§2.7 do report) + JaCoCo 0.8.12. |
| Paridade | zero divergências em C1–C8 (SC-002) | ⏳ **formal pendente** (precisa baseline Java 11). **Informal excelente**: 6 itens de diff em 781.246 (0,0008%); carving/Active Items/Parsing-Exceptions idênticos (§4.5 do report) |
| Performance | regressão ≤ 5% (SC-005) | ⏳ benchmark formal pendente. **Informal dentro do gate**: mesmo caso 4692s (J11) → 4784s (J21) = **+1,96%** (não é benchmark controlado) |
| ~~Casos antigos~~ | **Fora de escopo (2026-06-01)** — casos autocontidos; release novo não abre casos antigos (FR-004/005/006/007 retirados) | — |
| Distribuição | Win (embarcado) + Linux (sistema) iniciam e processam (SC-004) | ✅ **Windows** (run real E01 ponta a ponta + launchers `.exe` validados); ✅ **Linux** (T053, WSL2 Ubuntu-26.04 + Liberica 21 do sistema): build sem `jre/` + processamento `--nogui` ponta a ponta → EXIT 0, índice Lucene + grafo Neo4j 5, FR-012/SC-006 limpos (§2.8 do report). 3 achados pré-existentes/ambiente (tskJarPath, locale phone-region, `prepareOutputFolder` copia `jre/`); exercício completo de tools nativas (FR-009/E01) não feito (escopo mínimo) |
| Runtime limpo | sem erro por incompat JDK (SC-006) | ✅ run real + Web API + logs de paridade: **sem** `UnsupportedClassVersion`/`IllegalAccess`/reflexão em internals. Não-fatais residuais (numpy/JEP, thumb SVG/ImageMagick, "graph not found" benigno) **não** são incompat de JDK |
| Governança/Docs | constituição emendada; `ThirdParty.txt`/`licenses/`/CI/CLAUDE.md atualizados | ✅ constituição v1.2.0; CI Java 21 (T047); CLAUDE.md (T049/T056); ThirdParty (T055); ReleaseNotes (T057) |

**Resumo do sweep:** dos gates em escopo, **Build, Runtime-limpo e Governança/Docs estão ✅ fechados**; **Distribuição ✅ no Windows** (Linux pendente); **Testes/Paridade/Performance têm validação informal forte** e aguardam o fechamento **formal** (CI verde + baseline Java 11 do usuário). Nenhum gate **reprovou**.

## 8. Rodar o release no Java 21 (Windows)

1. **Build**: `mvn clean package` com `JAVA_HOME` = Liberica Full 21. Gera o release completo (`iped.jar` + `lib/` + `lib/neo4j/` + `jre/` + launchers `.exe`).
   - O JRE embarcado vem do artefato **`java:jre:21.0.11`** (zip Liberica Full 21 com JavaFX, `jre/` no topo), publicado no repo `iped-maven` e descompactado pela execution `unpack-jre` — sem passos manuais. (O workaround anterior, `copy-jre` da pasta local `iped-jre/`, foi removido em 2026-06-12 após a publicação do artefato.)
   - No Windows, o perfil `windows-launchers` gera `iped.exe` (console) e `bin/IPED-SearchApp.exe` (GUI) via launch4j, já apontando para o `jre/` embarcado.
2. **Deploy**: copiar `target/release/iped-4.4.0-SNAPSHOT/` para a instalação (o usuário é dono do deploy). Para a UI de análise (`IPED-SearchApp.exe`) localizar o config, a pasta do caso deve se chamar `iped` (convenção `<caso>/iped/`; `Main.setConfigPath` sobe de `iped/lib`→`iped`). O `iped.exe` de processamento não tem essa exigência.
3. **Executar**:
   ```powershell
   cd <release>
   .\iped.exe -profile forensic -d <fonte> -o <saida>
   ```
   `iped.exe` usa o `jre/` embarcado (sem buscar Java no registro) e passa `-Djava.security.manager=allow`. `iped.bat` permanece como fallback equivalente.

## 9. Validação realizada (2026-05-30)

- **Build**: `mvn clean package` no JDK 21 → BUILD SUCCESS (16 módulos; release completo).
- **Testes**: `mvn -pl iped-engine test` → 136 testes, 0 falhas, 2 skips (YARA integration-gated).
- **Processamento real**: `iped.bat -profile forensic -d E:\hds\RockPi4\RockPi4.E01 -o F:\test`
  - Sleuthkit decodificou o E01; pipeline completo; índice; UI de análise navegável; 0 erros de SecurityManager.
  - Cache de regex reconstruído a partir do formato FST antigo (esperado).
  - Não-fatais: `numpy` ausente (Python/JEP, não-regressão); timeout de thumb de SVG (ImageMagick externo, não-regressão — ver §5 do report).
- **Atualização (até 2026-06-02)**: grafo Neo4j 5 em runtime ✅; JRE embarcado da pasta local + launchers `.exe` via launch4j ✅; Web API ✅; CI Java 21 ✅; docs/ThirdParty/ReleaseNotes ✅. **Ainda pendente**: paridade/perf formais (baseline Java 11), CI verde (push), smoke Linux. Ver §7 (sweep T058).
