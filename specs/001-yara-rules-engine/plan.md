# Implementation Plan: YARA Rules Engine para IPED

**Branch**: `001-yara-rules-engine` | **Date**: 2026-05-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from [specs/001-yara-rules-engine/spec.md](spec.md)

## Summary

Adicionar uma engine de regras YARA integrada ao pipeline do IPED, permitindo que peritos apliquem catálogos de regras (`.yar`/`.yara`/`.yarc`, YARA 4.x) aos artefatos de um caso e usem os matches resultantes como facetas de busca, bookmark e relatório. A solução é entregue como **uma nova task do engine** (`YaraScanTask`, padrão `AbstractTask`) com seu próprio `Configurable` (`YaraConfig`), opcionalmente desligada por default e ativável via `IPEDConfig.txt` por perfil — sem alterar tasks existentes, propriedades indexadas existentes nem o modelo de concorrência do `Worker`. A engine YARA é embarcada como biblioteca nativa em `tools/yara/<os>/` (mesmo padrão de Sleuthkit/Tesseract) e acessada via JNA, sem chamadas a `System.out` e sem alterar a estabilidade dos campos Lucene já existentes. Re-execução "YARA-only" sobre casos prontos é entregue como modo de processamento adicional (CLI `--yara-only`) reutilizando o `Manager` existente.

## Technical Context

**Language/Version**: Java 11 (Liberica/BellSoft Full JDK), `maven.compiler.source/target = 11`, UTF-8 fonte.

**Primary Dependencies (novas)**:
- `libyara` 4.5.x — engine YARA nativa, distribuída em `tools/yara/{win64,linux64}/` (binários + módulos `pe`, `elf`, `math`, `hash`, `magic`, `dotnet`, `time`).
- **JNA** 5.13.x — bridge Java↔C (já compatível com Java 11). Já presente no classpath via outras dependências (`net.java.dev.jna:jna`); a usar a versão já resolvida pelo parent POM. Se não estiver, declarar em `iped-engine/pom.xml`.
- Wrapper interno `iped.engine.task.yara.YaraEngine` — bindings JNA finos, escritos neste repositório (sem dependência de binding externo abandonado).

**Primary Dependencies (reutilizadas, sem alteração)**:
- Apache Lucene 9.2.0 (`IndexTask`) — recebe propriedade multi-valorada `yara:rule`.
- SLF4J + Log4j 2 — todo logging passa por aqui.
- `SleuthkitClient` — *não* é tocado; o YARA consome o stream do `IItem` (que já encapsula leitura via Sleuthkit out-of-process).
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

**Target Platform**: Windows (x64) e Linux (x64) — mesmas plataformas oficialmente suportadas pelo IPED. macOS fica fora; em SO sem `libyara` disponível, a feature degrada silenciosamente (FR-014).

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
- Não tocar `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`, `AppAnalyzer` (Princípio I/II) além do necessário para reconhecer o modo `--yara-only` (extensão minimalista do CLI parser).
- Toda string visível ao usuário em `iped-app/resources/localization/` PT-BR + EN (Princípio III).
- Native libs em `tools/yara/<os>/` — não no `PATH` do sistema (constituição: "Restrições de Build").

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
| II | Extensão Modular em vez de Modificação | **PASS** | Feature entregue como **nova `AbstractTask`** (`YaraScanTask`) + entrada em `TaskInstaller.xml`. Nenhuma task existente é alterada. Não há ramificação condicional em `Manager`/`Worker` — só o reconhecimento de uma nova flag CLI `--yara-only` em `Bootstrap` (única exceção, justificada na seção "Complexity Tracking"). |
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
- **V**: R-04 detalha o lifecycle `yr_initialize`/compile/scan/`yr_finalize` com lock estático single-shot. Nenhum acesso a `Manager`/`Worker`/`ProcessingQueues` muda — só `Bootstrap`/`processing.Main` recebem a flag `--yara-only`, mudança já registrada em Complexity Tracking.

**Conclusão**: nenhuma nova entrada em Complexity Tracking é necessária após Phase 1. Pronto para `/speckit-tasks`.

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
├── pom.xml                                                    # MODIFICA se JNA ainda não estiver declarada
└── src/main/java/iped/engine/
    ├── config/
    │   └── YaraConfig.java                                    # NOVO Configurable
    └── task/
        └── yara/
            ├── YaraScanTask.java                              # NOVA AbstractTask
            ├── YaraEngine.java                                # bindings JNA (libyara)
            ├── YaraRulesetLoader.java                         # discovery e compile de .yar/.yara/.yarc
            ├── YaraMatch.java                                 # POJO do match (rule, namespace, tags, strings)
            ├── YaraScanner.java                               # per-worker thread-bound scanner
            └── YaraMatchSerializer.java                       # serialização JSON do match detail
└── src/test/java/iped/engine/task/yara/
    ├── YaraConfigTest.java
    ├── YaraEngineTest.java
    ├── YaraRulesetLoaderTest.java
    ├── YaraScanTaskTest.java
    └── fixtures/                                              # .yar de teste e amostras binárias

iped-app/
├── resources/config/
│   ├── IPEDConfig.txt                                         # MODIFICA (adiciona enableYara=false)
│   ├── conf/
│   │   ├── TaskInstaller.xml                                  # MODIFICA (adiciona <task class=".../YaraScanTask"/>)
│   │   └── YaraConfig.txt                                     # NOVO
│   └── profiles/
│       ├── forensic/IPEDConfig.txt                            # MODIFICA (enableYara opcional por profile)
│       └── ...                                                # idem para demais profiles, conforme política
├── resources/localization/
│   ├── messages.properties                                    # MODIFICA (chaves yara.*)
│   └── messages_pt_BR.properties                              # MODIFICA (chaves yara.*)
├── src/main/java/iped/app/bootstrap/
│   └── Bootstrap.java                                         # MODIFICA mínimo (reconhece flag --yara-only)
└── src/main/java/iped/app/processing/
    └── Main.java                                              # MODIFICA mínimo (rota --yara-only para Manager)

tools/yara/                                                    # NOVO diretório de runtime (no release)
├── win64/
│   └── libyara.dll                                            # binário pré-compilado, com módulos pe/elf/.../time
├── linux64/
│   └── libyara.so.10
├── LICENSE                                                    # BSD 3-clause (YARA)
└── README.md                                                  # como atualizar a versão

ThirdParty.txt                                                 # MODIFICA (registra YARA + JNA se nova)
licenses/                                                      # MODIFICA (adiciona LICENSE-YARA, LICENSE-JNA se nova)
.github/workflows/maven.yml                                    # MODIFICA (instala libyara no Linux CI)
```

**Structure Decision**: **Mantém a estrutura Maven multi-módulo existente**; nenhum módulo novo é criado. A feature respeita o mapeamento do CLAUDE.md raiz ("Nova task no pipeline → `iped-engine/.../task/`"). Todos os arquivos novos ficam sob `iped-engine/src/main/java/iped/engine/task/yara/` (subpacote dedicado, sem invadir o pacote `task` raiz) e `iped-app/resources/config/conf/YaraConfig.txt`. As entradas em `TaskInstaller.xml`, `IPEDConfig.txt` e `localization/` são adições. A engine nativa fica em `tools/yara/<os>/`, exatamente como Sleuthkit/Tesseract/RegRipper já residem em `tools/`.

## Complexity Tracking

> Apenas as violações/desvios reais em relação à constituição estão aqui registrados.

| Violation / desvio | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| **`libyara` carregada in-process via JNA** (Princípio V valoriza isolamento out-of-process para componentes propensos a crash). | (a) SC-001 exige ≤15% overhead em 1M itens — fork/exec por item ou comunicação IPC por item tornam isso inviável. (b) libyara é uma biblioteca **leitora** (não interpreta código do item escaneado; só varre bytes contra patterns), com superfície de ataque muito menor que o Sleuthkit (que parseia metadata de filesystems hostis). (c) `yara` 4.x é amplamente usado em-processo em ferramentas como VirusTotal, ClamAV, Velociraptor. | **Out-of-process via `yara` CLI**: cogitado e descartado por overhead inaceitável de IPC por item. **Batch CLI** (yara contra lista de paths): inviável porque o IPED lê via Sleuthkit out-of-process — não há caminho no FS para carved items, subitens e itens em containers. **Mitigação**: (1) timeout configurável por item (FR-007) impede travas; (2) erros nativos são capturados por `Throwable` no laço de scan e marcam o item como "skipped"; (3) tamanho máx default 250 MB evita patológicos; (4) PR explicitará em "impacto em concorrência" (Princípio V, §4 do workflow constitucional) que o failure mode é "este item entra em skipped", **nunca** "caso aborta". |
| **`Bootstrap.java` / `processing/Main.java` recebem reconhecimento de `--yara-only`** (Princípio II prefere extensão modular sem tocar core). | FR-011 exige rerun YARA-only sobre caso pronto. Sem flag de modo, o `Manager` rodaria todas as tasks habilitadas novamente. A alternativa é um Configurable booleano "rerun mode" — porém isso obriga o perito a editar config antes de rodar e desfaz após, o que é fonte de erro em ambiente forense. Uma flag CLI é a interface idiomática para "modo de execução". | **Configurable booleano `yaraOnlyRerun`**: descartado porque é frágil em fluxo de uso forense (esquecer de desligar leva a rodar só YARA quando se quer pipeline completo). **Profile dedicado `yara-only`**: descartado por duplicar a definição do pipeline e ficar desatualizado quando o perfil base muda. **Mitigação**: a alteração em `Bootstrap` é uma única condição (passar a flag a `processing.Main`), sem mudar lógica de fila/worker. Em PR, dedicar seção a "impacto em pipeline" justificando o desvio. |
