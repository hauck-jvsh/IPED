# Implementation Plan: YARA Rules Engine para IPED

**Branch**: `001-yara-rules-engine` | **Date**: 2026-05-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from [specs/001-yara-rules-engine/spec.md](spec.md)

## Summary

Adicionar uma engine de regras YARA integrada ao pipeline do IPED, permitindo que peritos apliquem catálogos de regras (`.yar`/`.yara`, **YARA-X 1.x**) aos artefatos de um caso e usem os matches resultantes como facetas de busca, bookmark e relatório. A solução é entregue como **uma nova task do engine** (`YaraScanTask`, padrão `AbstractTask`) com seu próprio `Configurable` (`YaraConfig`), opcionalmente desligada por default e ativável via `IPEDConfig.txt` por perfil — sem alterar tasks existentes, propriedades indexadas existentes nem o modelo de concorrência do `Worker`. A engine **YARA-X** é embarcada como biblioteca nativa em `tools/yara-x/<os>/` (mesmo padrão de Sleuthkit/Tesseract) e acessada via JNA contra o `libyara-x-capi`, sem chamadas a `System.out` e sem alterar a estabilidade dos campos Lucene já existentes. Re-execução "YARA-only" sobre casos prontos é entregue como modo de processamento adicional (CLI `--yara-only`) reutilizando o `Manager` existente.

> **Nota de governança**: a decisão de migrar do YARA clássico (libyara 4.x) para o **YARA-X** foi tomada em 2026-05-19, refletida em `spec.md` (Clarifications Q1/Q3 revisadas) e `research.md` (R-01..R-03, R-09 reescritas). Ver `research.md` §R-01 "Por que mudou" para o racional completo.

## Technical Context

**Language/Version**: Java 11 (Liberica/BellSoft Full JDK), `maven.compiler.source/target = 11`, UTF-8 fonte.

**Primary Dependencies (novas)**:
- **YARA-X 1.x** — engine de regras nativa (Rust), distribuída via `libyara-x-capi` em `tools/yara-x/{win64,linux64}/`. Módulos `pe`, `elf`, `math`, `hash`, `magic`, `dotnet` e `time` vêm habilitados no release oficial; `cuckoo` é banido em runtime via `yrx_compiler_ban_module`. Releases pré-compilados do upstream em `https://github.com/VirusTotal/yara-x/releases`.
- **JNA** 5.7.0 — bridge Java↔C, declarada em `iped-engine/pom.xml` (versão alinhada com `iped-parsers-impl` para evitar dep-skew).
- Wrapper interno `iped.engine.task.yara.YaraEngine` — bindings JNA finos contra a C API `yrx_*`, escritos neste repositório.

**Primary Dependencies (reutilizadas, sem alteração)**:
- Apache Lucene 9.2.0 (`IndexTask`) — recebe propriedade multi-valorada `yara:rule`.
- SLF4J + Log4j 2 — todo logging passa por aqui.
- `SleuthkitClient` — *não* é tocado; o YARA-X consome o stream do `IItem` (que já encapsula leitura via Sleuthkit out-of-process).
- `iped-api` / `ExtraProperties` — recebe **constantes novas** (`YARA_RULE`, `YARA_TAGS`, `YARA_MATCH_DETAIL`) sem renomear nem remover existentes.
- `iped-app` HTML report template — recebe um `<tr>` adicional opcional por item.

**Storage**:
- **Índice Lucene**: campos novos `yara:rule` (multi-valorado, indexado) e `yara:tag` (multi-valorado, indexado).
- **Match detail por item**: persistido como propriedade JSON serializada em campo Lucene `yara:matches` (stored, não indexado) — mesma técnica que `MakePreview`/parsers já usam para metadados estruturados. Estrutura: lista de `{rule, namespace, tags[], strings:[{id, offset, hex}]}`.
- **Sem novo banco** — Princípio I (estabilidade) preservado; nenhuma chave Lucene existente é renomeada.

**Testing**:
- JUnit 4 (padrão do projeto — `iped-engine/src/test/java/`).
- Suites novas:
  - `YaraConfigTest` — leitura/validação do `YaraConfig.txt`.
  - `YaraEngineTest` — compilação de catálogos sintéticos, carga de `.yarc`, casos negativos (rule inválida, `.yarc` corrompido, módulo `cuckoo` rejeitado).
  - `YaraScanTaskTest` — pipeline em modo dry-run sobre fixtures conhecidas; comparação ground-truth com saída da CLI `yara` (SC-004).
  - Integração: rerun-only sobre um caso teste pequeno.

**Target Platform**: Windows (x64) e Linux (x64) — mesmas plataformas oficialmente suportadas pelo IPED. macOS fica fora; em SO sem `libyara-x-capi` disponível, a feature degrada silenciosamente (FR-014).

**Project Type**: Aplicação Java desktop multi-módulo (Maven). A feature adiciona uma task no engine, um Configurable, um arquivo de configuração e um patch de empacotamento — não introduz módulo novo.

**Performance Goals (do spec)**:
- SC-001: ≤ 15% de overhead em caso de 1M itens / 500 regras.
- SC-006: rerun YARA-only em ≤ 25% do tempo de processamento original.
- Implicações de design: scan em-processo (JNA, sem fork/exec por item), reuso de `YaraScanner` por worker, leitura do stream do `IItem` em chunks (sem materializar 100% em memória), respeitar tamanho máx (default 250 MB) e timeout (default 30 s).

**Constraints**:
- Sem `System.out`/`System.err` (Princípio IV).
- Charset sempre explícito UTF-8 (Princípio IV).
- `Configurable` para tudo que o perito ajusta (Princípio III).
- Threading: uma instância de task por worker, estado global em `caseData.objectMap`, cleanup em `finish()` (Princípio V).
- Não tocar `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`, `AppAnalyzer` (Princípio I/II). O modo `--yara-only` toca **apenas** o CLI parser (`CmdLineArgsImpl`) + uma branch mínima em `SkipCommitedTask.process` (não chamar `setToIgnore` quando yaraOnly) + uma branch mínima em `IndexTask.process` (usar `updateDocuments` em vez de `addDocuments` quando o item é commitado em yaraOnly). Ver Complexity Tracking.
- Toda string visível ao usuário em `iped-app/resources/localization/` PT-BR + EN (Princípio III).
- Native libs em `tools/yara-x/<os>/` — não no `PATH` do sistema (constituição: "Restrições de Build").

**Scale/Scope**:
- Casos: até centenas de milhões de itens (limite operacional do IPED).
- Catálogo: até ~5.000 regras compiladas simultaneamente (Neo23x0 + YARA-Forge combined).
- Item médio com stream binário: 1–10 MB; cauda longa até 250 MB (acima → skip).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Cada princípio da constituição (`.specify/memory/constitution.md` v1.0.0) é avaliado como **gate**:

| # | Princípio | Status | Evidência no design |
|---|---|---|---|
| I | Estabilidade da API Pública (NÃO-NEGOCIÁVEL) | **PASS** | `iped-api` recebe apenas **adições** em `ExtraProperties` (constantes novas `YARA_RULE`, `YARA_TAGS`, `YARA_MATCH_DETAIL`). Nenhuma chave Lucene existente é renomeada. `BasicProps`, `IndexItem`, `AppAnalyzer`, `iped-ahocorasick` **não são tocados**. |
| II | Extensão Modular em vez de Modificação | **PASS (com 2 desvios)** | Feature entregue como **nova `AbstractTask`** (`YaraScanTask`) + entrada em `TaskInstaller.xml`. Nenhuma task existente é alterada **no fluxo normal**. Não há ramificação condicional em `Manager`/`Worker`. As únicas exceções: (a) `CmdLineArgsImpl` reconhece a nova flag CLI `--yara-only`; (b) `SkipCommitedTask` ganha uma branch que pula `setToIgnore` quando `yaraOnly`; (c) `IndexTask` ganha uma branch que troca `addDocuments` por `updateDocuments` quando o item é commitado em modo `yaraOnly`. Ambas as branches (b)/(c) foram introduzidas no rev-2 do `--yara-only` (2026-05-22) após a v1 standalone falhar — ver Complexity Tracking + research.md §R-08. |
| III | Configuração antes de Código | **PASS** | Tudo ajustável vive em `YaraConfig.txt` (novo `Configurable<UTF8Properties>`) e em `IPEDConfig.txt` (flag `enableYara`). Sem hardcode. Strings UI em `iped-app/resources/localization/{messages*.properties}`. |
| IV | Integridade Forense e Determinismo | **PASS** | Charset explícito UTF-8 em toda leitura/escrita de regras e matches. Logging via SLF4J. Datas via `java.time` com `ZoneOffset.UTC`. Ordenação de matches por `(namespace, rule, string_id, offset)` — determinística para o mesmo input. Erros isolados não abortam o caso (FR-005). |
| V | Disciplina de Concorrência e Isolamento de Processo | **PASS** | Uma instância de `YaraScanTask` por worker; `YaraScanner` (estado por thread) como campo de instância; rulesets compilados **uma vez** no `init()` estático e compartilhados read-only (libyara é thread-safe para scan após compile). Subitens criados via `IItem.createChildItem()` (mas a task **não cria subitens** — só anota matches no item recebido). UI threading: nova facet usa o painel de metadata existente, então segue automaticamente o contrato Swing EDT/JavaFX `Platform.runLater`. Engine nativa roda **in-process** via JNA — risco de crash documentado e mitigado (ver Complexity Tracking). |

**Resultado do gate inicial**: PASS. Há **uma justificativa registrada** em Complexity Tracking (engine YARA in-process via JNA em vez de out-of-process puro como Sleuthkit).

### Post-Design Re-evaluation (após Phase 1)

Após produzir `research.md`, `data-model.md`, `contracts/*` e `quickstart.md`, todos os cinco gates **permanecem PASS** sem novos desvios introduzidos pelo design:

- **I**: `contracts/ExtraProperties.contract.md` e `contracts/lucene-fields.contract.md` confirmam que só há adições; o prefixo `yara:` garante zero colisão com chaves existentes. `AppAnalyzer`, `BasicProps` e `IndexItem` permanecem intocados.
- **II**: A árvore de arquivos prevista mantém todas as adições isoladas em `iped.engine.task.yara.*` e `iped.engine.config.YaraConfig`. A única modificação a `task`-existente seria no `TaskInstaller.xml` (lista de tasks), que é o ponto de extensão canônico.
- **III**: O contrato `YaraConfig.txt.contract.md` cobre todos os ajustes; `IPEDConfig.keys.contract.md` cobre a flag de enable. Localização documentada em R-13.
- **IV**: `data-model.md` fixa ordenação determinística dos matches; `lucene-fields.contract.md` carrega `engineVersion` por item para auditoria; charset UTF-8 reafirmado em `YaraConfig.txt.contract.md`.
- **V**: R-04 detalha o lifecycle compile (singleton) + `yrx_scanner_create` por worker + `yrx_scanner_destroy`/`yrx_rules_destroy` em `finish()`, com lock estático single-shot na compilação. Nenhum acesso a `Manager`/`Worker`/`ProcessingQueues` muda.

**Conclusão (Phase 1)**: nenhuma nova entrada em Complexity Tracking é necessária após Phase 1.

### Pós-implementação (2026-05-22) — adição de desvio do Princípio II

Durante a validação manual da User Story 3 (`--yara-only`) a primeira implementação (caminho standalone via `YaraRerunRunner`) falhou em produção. O round-trip `Document → IItem → Document` não é safe (ver research.md §R-08 "História/v1 rejeitada"). A v1 foi removida e substituída por duas branches mínimas em `SkipCommitedTask` + `IndexTask` (rev-2). Isso é um **desvio adicional do Princípio II** — duas tasks existentes foram tocadas — e foi adicionado à tabela em Complexity Tracking abaixo.

## Project Structure

### Documentation (this feature)

```text
specs/001-yara-rules-engine/
├── plan.md                           # Este arquivo (output de /speckit-plan)
├── research.md                       # Phase 0 — decisões técnicas + alternativas
├── data-model.md                     # Phase 1 — entidades e propriedades persistidas
├── quickstart.md                     # Phase 1 — passo-a-passo de habilitação e verificação
├── contracts/
│   ├── YaraConfig.txt.contract.md    # Schema do arquivo de configuração
│   ├── IPEDConfig.keys.contract.md   # Chaves adicionadas em IPEDConfig.txt
│   ├── ExtraProperties.contract.md   # Constantes novas em iped-api
│   ├── lucene-fields.contract.md     # Campos Lucene introduzidos
│   └── cli-yara-only.contract.md     # Flag --yara-only do Bootstrap
├── checklists/
│   └── requirements.md               # Já existe — checklist de qualidade da spec
└── tasks.md                          # Phase 2 — gerado por /speckit-tasks
```

### Source Code (repository root)

```text
iped-api/
└── src/main/java/iped/properties/
    └── ExtraProperties.java                                   # MODIFICA (apenas adiciona constantes)

iped-engine/
├── pom.xml                                                    # MODIFICA (adiciona dependência JNA 5.7.0)
└── src/main/java/iped/engine/
    ├── config/
    │   └── YaraConfig.java                                    # NOVO Configurable (com ${IPED_HOME} expansion)
    └── task/
        ├── SkipCommitedTask.java                              # MODIFICA mínimo (branch yara-only: skip setToIgnore)
        ├── index/
        │   └── IndexTask.java                                 # MODIFICA mínimo (branch yara-only: updateDocuments)
        └── yara/
            ├── YaraScanTask.java                              # NOVA AbstractTask
            ├── YaraEngine.java                                # bindings JNA (libyara-x-capi)
            ├── YaraInstallPaths.java                          # helper de detecção da raiz IPED (auto-detect DLL)
            ├── YaraRulesetLoader.java                         # discovery e compile de .yar/.yara
            ├── YaraMatch.java                                 # POJO do match (rule, namespace, tags, strings)
            ├── MatchedString.java                             # POJO de matched-string (offset + bytes)
            ├── YaraScanner.java                               # per-worker thread-bound scanner
            ├── YaraMatchSerializer.java                       # serialização JSON do match detail
            └── YaraReportRenderer.java                        # render HTML-safe do JSON p/ HTMLReportTask
└── src/test/java/iped/engine/
    ├── config/
    │   └── YaraConfigTest.java
    └── task/yara/
        ├── YaraEngineTest.java                                # integration-gated (assumeTrue libyara-x-capi)
        ├── YaraMatchSerializerTest.java
        ├── YaraReportRendererTest.java
        ├── YaraRulesetLoaderTest.java
        └── YaraScanTaskIntegrationTest.java                   # integration-gated

iped-app/
├── pom.xml                                                    # MODIFICA (execução copy-yara-x: tools/yara-x → release)
├── resources/config/
│   ├── IPEDConfig.txt                                         # MODIFICA (adiciona enableYara=false com aviso [BETA])
│   ├── conf/
│   │   ├── TaskInstaller.xml                                  # MODIFICA (adiciona <task class=".../YaraScanTask"/>)
│   │   └── YaraConfig.txt                                     # NOVO
│   └── profiles/                                              # NÃO modificado — feature é opt-in só no root IPEDConfig.txt
└── src/main/java/iped/app/processing/
    ├── CmdLineArgsImpl.java                                   # MODIFICA mínimo (flag --yara-only, requer -d, implica --continue)
    └── Main.java                                              # MODIFICA mínimo (pre-check enableYara em modo yara-only)

tools/yara-x/                                                  # NOVO diretório de runtime (no release)
├── win64/
│   └── yara_x_capi.dll                                        # binário pré-compilado do upstream libyara-x-capi
├── linux64/
│   └── libyara_x_capi.so                                      # idem (release Linux x64)
├── LICENSE                                                    # BSD 3-clause (YARA-X)
└── README.md                                                  # versão pinned + como atualizar

ThirdParty.txt                                                 # MODIFICA (registra YARA-X + JNA)
licenses/                                                      # MODIFICA (adiciona YARA-X.txt)
.github/workflows/maven.yml                                    # MODIFICA (instala libyara-x-capi no Linux CI)
```

**Structure Decision**: **Mantém a estrutura Maven multi-módulo existente**; nenhum módulo novo é criado. A feature respeita o mapeamento do CLAUDE.md raiz ("Nova task no pipeline → `iped-engine/.../task/`"). Todos os arquivos novos ficam sob `iped-engine/src/main/java/iped/engine/task/yara/` (subpacote dedicado, sem invadir o pacote `task` raiz) e `iped-app/resources/config/conf/YaraConfig.txt`. As entradas em `TaskInstaller.xml`, `IPEDConfig.txt` e `localization/` são adições. A engine nativa fica em `tools/yara-x/<os>/`, exatamente como Sleuthkit/Tesseract/RegRipper já residem em `tools/`.

## Complexity Tracking

> Apenas as violações/desvios reais em relação à constituição estão aqui registrados.

| Violation / desvio | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| **`libyara-x-capi` carregada in-process via JNA** (Princípio V valoriza isolamento out-of-process para componentes propensos a crash). | (a) SC-001 exige ≤15% overhead em 1M itens — fork/exec por item ou comunicação IPC por item tornam isso inviável. (b) YARA-X é uma biblioteca **leitora** de patterns escrita em Rust (memory-safe por construção); superfície de ataque é menor ainda que a libyara C clássica, que já era considerada segura por VirusTotal/ClamAV/Velociraptor. (c) O upstream do YARA-X publica binários self-contained pré-compilados — não há toolchain extra para o release do IPED gerenciar. | **Out-of-process via CLI `yara-x`**: cogitado e descartado por overhead inaceitável de IPC por item. **Batch CLI**: inviável porque o IPED lê via Sleuthkit out-of-process — não há caminho no FS para carved items, subitens e itens em containers. **Mitigação**: (1) timeout configurável por item via `yrx_scanner_set_timeout` (FR-007); (2) erros nativos são capturados por `Throwable` no laço de scan e marcam o item como "skipped"; (3) tamanho máx default 250 MB evita patológicos; (4) PR explicitará em "impacto em concorrência" (Princípio V, §4) que o failure mode é "este item entra em skipped", **nunca** "caso aborta". |
| **`CmdLineArgsImpl.java` reconhece flag `--yara-only`** (Princípio II prefere extensão modular sem tocar core). | FR-011 exige rerun YARA-only sobre caso pronto. Sem flag de modo, o `Manager` rodaria todas as tasks habilitadas novamente. A alternativa é um Configurable booleano "rerun mode" — porém isso obriga o perito a editar config antes de rodar e desfaz após, o que é fonte de erro em ambiente forense. Uma flag CLI é a interface idiomática para "modo de execução". | **Configurable booleano `yaraOnlyRerun`**: descartado porque é frágil em fluxo de uso forense (esquecer de desligar leva a rodar só YARA quando se quer pipeline completo). **Profile dedicado `yara-only`**: descartado por duplicar a definição do pipeline e ficar desatualizado quando o perfil base muda. **Mitigação**: a alteração em `CmdLineArgsImpl` é restrita a parsing/validação + um getter `isContinue()` que retorna `true` quando `yaraOnly`; sem mudar lógica de fila/worker. |
| **Rev-2 (2026-05-22): `SkipCommitedTask.process` + `IndexTask.process` ganham branch yara-only** (Princípio II prefere não tocar tasks existentes). | A v1 implementou `--yara-only` via classe standalone `YaraRerunRunner` que bypassava o `Manager` (honrando o Princípio II ao máximo). Na validação manual com o caso `F:\yara-test` (438k itens), a v1 falhou catastroficamente: (a) NPE em `Item.setName(null)` para docs fragmento sem `BasicProps.NAME`; (b) `IllegalArgumentException: cannot change field "language:all_detected" doc values type=SORTED_SET to SORTED` — o ciclo `Document → IItem → Document` via `IndexItem.getItem` colapsa metadados multi-valor, gerando schema conflict no `updateDocument`. Conclusão: o round-trip não é safe para os campos atuais. O design correto **precisa** reusar o pipeline normal de geração de `Document` (que vem de um `IItem` fresco do `DataSourceReader`), o que exige tocar `SkipCommitedTask` (deixar item commitado fluir) e `IndexTask` (decidir add vs update por `trackId`). | **Manter `YaraRerunRunner` standalone com manipulação manual de `Document`** (copiar fields originais + sobrescrever só `yara:*`): rejeitado — `IndexReader.document(docId)` retorna só fields STORED; doc values e fields indexed-but-not-stored seriam perdidos no `updateDocument`, causando perda de dados destrutiva. **Lucene partial field update via `updateBinaryDocValue`/`updateSortedDocValue`**: rejeitado — `yara:rule` precisa ser indexed (não só docvalue) para virar faceta UI. **Mitigação**: ambas as branches são pequenas (≤10 linhas cada); não mexem em concorrência nem em estado compartilhado das tasks; preservam comportamento original quando `!yaraOnly`. `YaraRerunRunner.java` (~370 LOC) e `YaraRerunRunnerTest.java` foram **removidos**. Documentação: research.md §R-08 (rev-2). |
| **Rev-3 (2026-05-25): `MetadataPanel.getHighlightTerms` reconhece prefixo `yara:`** (Princípio II prefere não tocar a UI). | FR-008a adiciona paridade com o caminho já existente para `Regex:*` / `NER:*`: quando o usuário faceta `yara:rule` ou `yara:tag`, os bytes que casaram (a partir do JSON `yara:matches`) viram termos de highlight nos viewers de texto. Sem isso, o usuário só consegue ver a regra que casou — não onde no item. A extensão é puramente aditiva (novo branch em `getHighlightTerms`) e bounded por dois limites: docs visitados (`MAX_DOCS_FOR_YARA_HIGHLIGHT = 4096`) e termos coletados (`MAX_TERMS_TO_HIGHLIGHT = 1024` — já existia). Helper testável (`YaraHighlightSupport.decodeHexToPrintable`) vive em `iped-engine/task/yara/` com 11 testes de unidade. | **Adicionar campo Lucene `yara:hit` multi-valorado com os bytes brutos**: rejeitado por explosão de cardinalidade — regras `$hex` casam bytes arbitrários, cada valor único vira termo no `SortedSetDocValuesField`, inflando disco e quebrando UX da lista lateral. **Highlight dinâmico per-item via hook no viewer**: rejeitado por ser mudança arquitetural muito maior (highlight hoje é setado globalmente no `App` quando filter aplica, não por item visualizado). **Mitigação**: bytes não-imprimíveis são descartados (text viewers não conseguem ancorar neles); leitor faz `leafReader.getSortedSetDocValues(field)` fresh (não reusa a SSDV stateful do `MetadataSearch`); falha graciosa por log se SSDV não existir. |
| **Rev-3 (2026-05-25): `YaraScanner` ganha extração real de `MatchedString[]` via `yrx_rule_iter_patterns` + `yrx_pattern_iter_matches`** (Princípio I não permite regressão). | A v1.0 do binding deixou o caminho de extração desligado (Javadoc admitia `strings: []` "permitido pelo contrato"). Em produção (caso real `F:\yara-test` com regras `domain`/`suspicious_strings`), todo `yara:matches` chegou com `strings:[]`, o que torna o highlight da rev-3 do `MetadataPanel` no-op — o painel não tem byte algum pra decodificar. A correção popula `YRX_MATCH{offset, length}` e recorta os bytes do buffer Java do scan corrente, hex-encode lowercase, cap em `matchHexMaxBytes`. Adiciona 3 funções e 2 callbacks ao binding JNA + a struct `YRX_MATCH`; nenhum impacto em concorrência (callbacks rodam na mesma thread do worker durante `yrx_scanner_scan`). | **Manter `strings:[]` e oferecer apenas viewer dedicado de `yara:matches` (T030)**: rejeitado porque viewer dedicado também depende dos dados existirem no JSON — sem `MatchedString[]` não há offset nem hex para renderizar. **Re-scan no momento do highlight**: rejeitado por custo (re-abrir stream, recompilar contexto). **Mitigação**: cap em `matchHexMaxBytes` evita hex gigante para patterns patológicos; truncated flag continua honrado; o callback nativo lê `offset`/`length` da `YRX_MATCH` *antes* de retornar (a libyara-x-capi libera o struct no retorno do callback — documentado no header §131-138). |
| **Rev-4 (2026-05-27): Per-rule field `yara:match:<namespace>/<name>` denormalizado em vez de JSON-scan no panel** (Princípio I — adição). | Validação manual mostrou que o `MetadataPanel` exibia `yara:matches` como uma faceta única (um JSON gigante por item), enquanto o usuário esperava o mesmo padrão visual do `Regex:CPF`/`Regex:EMAIL`: uma faceta por categoria, valores casados listados dentro. A solução: `YaraScanTask.persistMatches()` denormaliza no momento do scan e escreve `yara:match:<namespace>/<name>` por regra que casou, com cada valor sendo um matched-string distinto (decodificado via `YaraHighlightSupport.decodeHexForFacet` — texto ASCII se imprimível, hex lowercase senão). `MetadataPanel.getHighlightTerms()` simplifica drasticamente: o branch JSON+SSDV-scan da rev-3 (~70 linhas) é removido, substituído por uma adição de `ExtraProperties.YARA_MATCH_PREFIX` à lista de prefixos literais junto com `Regex:`/`NER:`. | **Manter o branch JSON-parsing da rev-3 e só adicionar i18n**: rejeitado — o usuário queria a estrutura visual idêntica ao Regex (faceta por categoria), não só o highlight; o JSON-scan era O(N docs × parse) por seleção, vs. zero overhead na rev-4 (Lucene já tem o índice por campo). **Não denormalizar e ter um viewer dedicado pra `yara:matches`**: rejeitado por desviar do mental model do perito. **Mitigação**: zero overhead em runtime (já estamos iterando matches no `persistMatches`); cardinalidade nova bounded por # regras matched × # patterns × # strings — em casos reais (`F:\yara-test`, 32k itens, 5 regras: cada item tem ≤ 5 campos `yara:match:*`, cada um com ≤ ~10 valores únicos). `yara:matches` JSON preservado para auditoria; `yara:rule`/`yara:tag` agregados preservados para "panorama" do caso. |
| **Rev-5 (2026-05-27): Remoção dos campos `yara:rule` e `yara:matches`** (Princípio I — remoção válida pois nunca foram released; a feature inteira vive na branch `001-yara-rules-engine` que não foi merged em `master`). | Após a rev-4 a faceta UI ficou idiomática (`yara:match:<rule>` por regra, mirror do `Regex:<categoria>`), mas o dropdown ainda mostrava duas entradas redundantes: `yara:rule` (lista agregada — derivável enumerando os field names com prefixo `yara:match:`) e `yara:matches` (JSON gigante por item — só interessante para auditoria fina). O perito pediu remoção para limpar a UI. Consequências: (a) bloco estruturado YARA do `HTMLReportTask` (rendering via `YaraReportRenderer`) sai junto — o relatório passa a exibir `yara:tag` e `yara:match:*` como campos normais multi-valor; perde-se a granularidade fina de offset/meta/hex. (b) Classes `YaraMatchSerializer` (170 LOC + 10 tests) e `YaraReportRenderer` (~100 LOC + 10 tests) tornam-se código morto e são removidas. | **Esconder no dropdown via skiplist no `MetadataPanel.updateProps()`**: rejeitado pelo usuário — quis remoção real, não maquiagem. **Migrar `yara:matches` para `setTempAttribute` (mantém JSON em memória durante o pipeline para o HTMLReportTask consumir, fora do índice)**: rejeitado porque `HTMLReportTask` lê do `IPEDSource` (índice Lucene já finalizado), não da `IItem` viva — separação de processos. **Mitigação**: o caminho de update via `--yara-only` (`updateDocuments`) garante que casos pré-existentes têm os campos removidos no próximo rerun. `MatchedString.offset` continua disponível em memória dentro do `YaraScanTask` para qualquer feature futura (basta passar via `setTempAttribute`); o que foi cortado é apenas a persistência. |
