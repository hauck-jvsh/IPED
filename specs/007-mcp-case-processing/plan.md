# Implementation Plan: Criação de caso pelo servidor MCP — processar a evidência

**Branch**: `007-mcp-case-processing` | **Date**: 2026-08-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-mcp-case-processing/spec.md`

## Summary

O servidor MCP passa a criar casos, e a forma como ele os cria é decidida por uma restrição de build,
não por preferência: **`iped-app` depende de `iped-mcp`**, então `iped-mcp` não pode chamar
`Bootstrap` nem `iped.app.processing.Main` — a dependência inversa seria circular. O processamento é
portanto invocado **fora do processo**, executando o `iped.jar` da instalação que o próprio servidor
já localiza por `Diagnostics.resolveIpedRoot()`.

Essa restrição entrega de graça quase tudo o que a spec pede do ciclo de vida. Um processo externo dá
identificador de sistema operacional para cancelar (FR-023), código de saída para desfecho (FR-029),
isolamento de memória que mantém o servidor leve enquanto o motor toma a máquina (FR-025), e a morte
junto com o servidor que FR-024 exige. É também o padrão que o próprio IPED já usa em três lugares
(`SleuthkitClient`, `ParsingProcess`, o próprio `Bootstrap`), portanto não é mecanismo novo no projeto.

**US1 — processar até um caso consultável.** `JobRunner` monta a linha de comando a partir de um
`ProcessingRequest` já validado e executa o `iped.jar`. Confinamento de origem por lista de permissão
e confinamento de destino por raízes de caso reaproveitam `PathConfinement` da 006 sem alterá-lo — a
classe já resolve caminho real e já distingue os vereditos de que precisamos.

**US2 — acompanhar, sobreviver à sessão, interromper.** O fluxo padrão do filho é a única fonte de
progresso disponível de fora do processo, e serve a três requisitos de uma vez: alimenta o progresso
(FR-020), é gravado como log do trabalho (FR-042) e fornece o trecho diagnóstico da falha (FR-043).
`JobStore` persiste o estado fora da memória do servidor (FR-041) na área de auditoria, com retenção
indefinida (FR-045). Cancelar é destruir a **árvore** de processos, não o filho: `Bootstrap` gera um
neto, e matar só o filho deixaria o motor rodando.

**US3 — confinar e mostrar.** Postura consultável e registrada; recusas com diagnóstico que nomeia o
pedido e as áreas permitidas.

**Uma decisão sai do módulo.** Senha de contêiner cifrado só entra no IPED por `-p` na linha de
comando, e linha de comando é legível por outros processos da máquina. Isso satisfaz a letra de FR-015
— que fala de pedido, resposta, trilha e log — e contraria o motivo dela. O plano acrescenta um
parâmetro `-passwordFile` ao `iped-app`, aditivo, no mesmo padrão de `sharedSecretFile` da 006: a
configuração diz **onde**, nunca **qual**. É a única mudança fora do `iped-mcp` e está justificada em
Complexity Tracking.

Nenhuma dependência nova. Tudo em `java.base`.

## Technical Context

**Language/Version**: Java 11 (restrição de runtime — o release embarca JRE 11). `ProcessHandle` e
`ProcessHandle.descendants()` são Java 9+, disponíveis.

**Primary Dependencies**: nenhuma nova. `java.lang.ProcessBuilder`/`ProcessHandle` e `java.nio.file`
de `java.base`; Jackson, Lucene e POI já presentes no módulo.

**Storage**: sistema de arquivos — área de auditoria da estação (`jobs/` para estado e log de
trabalho), pasta do caso produzido, áreas de leitura e raízes de caso declaradas.

**Testing**: JUnit 4 (`org.junit.Test`, `TemporaryFolder`), nas três suítes existentes do módulo:
`contract/`, `integration/`, `unit/`. As suítes que exigem processamento real dependem de
`-Diped.mcp.ipedRoot` e `-Djvm` apontando ao JRE 11 do release, como as suítes de caso já existentes.

**Target Platform**: Windows e Linux. Windows é a referência para confinamento de caminho (junção de
diretório), Linux é a referência para exposição de linha de comando (`/proc/PID/cmdline` legível por
outros usuários) — as duas plataformas expõem riscos diferentes e as duas precisam ser exercitadas.

**Project Type**: extensão de módulo Maven de servidor, dentro de projeto multi-módulo.

**Performance Goals**: aceite do pedido < 5 s independentemente do tamanho da evidência (SC-002);
progresso com informação nova a cada 60 s ou declaração de fase sem medida (SC-007); cancelamento
concluído em < 60 s (SC-014). As metas de consulta de 001 e 006 permanecem válidas durante o
processamento (FR-025).

**Constraints**: sem dependência fora de `java.base`; nada além do protocolo na saída padrão do
servidor — o fluxo do filho é capturado por tubo e **nunca repassado**; `AuditRecord` **não ganha
campo**, por invariante registrada do módulo; um trabalho por vez.

**Scale/Scope**: evidências de dezenas a centenas de gigabytes, processamentos de horas a dias; um
trabalho ativo por servidor; registros de trabalho acumulando indefinidamente na ordem de dezenas por
ano, não milhares.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constituição do IPED — branch 4.3.1, versão 1.0.0.

| Princípio | Avaliação | Evidência |
|---|---|---|
| **I. Integridade da evidência é inviolável** | **PASSA — e é o princípio central desta feature** | O processamento lê evidência e escreve caso novo; a origem nunca é destino de escrita. Quem abre a evidência é o motor do IPED pelos caminhos que ele já usa (`SleuthkitClient`, leitores de fonte de dados), inalterados — este plano não acrescenta nenhum caminho de acesso a evidência. FR-031 e SC-015 fixam a exigência, e SC-015 a mede por comparação bit a bit antes e depois, em todos os desfechos. O confinamento de origem (FR-006) estreita ainda mais o que é alcançável |
| **II. Caso processado é contrato permanente** | **PASSA** | Nenhum nome de campo Lucene, nenhuma configuração de `AppAnalyzer`, nenhum método removido ou renomeado. O caso produzido nasce do **motor padrão, invocado pela linha de comando padrão** — é por construção indistinguível de um caso da CLI, que é o que FR-027 e SC-008 exigem. `AuditRecord` não ganha campo: o estado de trabalho vive em `JobStore`, arquivo novo na área de auditoria, e o vínculo sessão↔trabalho no `SessionManifest` da 006. Trilhas já emitidas permanecem verificáveis |
| **III. Estender antes de modificar** | **PASSA com uma ressalva registrada** | O grosso é aditivo: `processing/` inteiro e `ProcessingTools` são classes novas; `PathConfinement` é **reutilizado sem alteração**. Três modificações são inerentes ao pedido — `McpServerConfig` (chaves novas), `SessionTools` (postura) e `Session` (advertência de abertura). Nenhuma toca `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`, `SleuthkitClient` ou o Aho-Corasick, e a razão é estrutural, não disciplina: o processamento roda **em outro processo**, então não há como este módulo alterar invariante de concorrência do pipeline. A ressalva é o `-passwordFile` em `iped-app` — ver Complexity Tracking |
| **IV. Comportamento configurável vive em configuração** | **PASSA — princípio dirigente do desenho** | Habilitação, áreas de leitura, raízes de caso, perfis permitidos, margem de disco e localização do repositório de segredos vão todos para `McpServerConfig` e `conf/McpServerConfig.txt`, enumerados em [contracts/config-surface.md](./contracts/config-surface.md). Habilitar ou desabilitar o processamento é edição de configuração, nunca recompilação. Nenhum perfil é embutido em código: a lista permitida é declarada |
| **V. Nada implícito no que varia por ambiente** | **PASSA — e exige atenção nova** | Charset explícito na leitura do fluxo do filho. Logging por SLF4J no servidor; o fluxo do filho é capturado e gravado, **nunca repassado à saída padrão**. Duas exigências novas por serem específicas deste plano: o **locale do processo filho é declarado explicitamente** (`-Diped-locale`), porque o progresso é lido de mensagens que o IPED localiza, e herdar o locale da máquina tornaria a leitura dependente de onde o servidor roda; e a **JVM usada é a do release**, declarada, não a que estiver no `PATH` |

**Restrições da plataforma**

- **Java 11 como runtime**: satisfeito por construção. `ProcessHandle`, `ProcessHandle.descendants()` e
  `ProcessHandle.Info.startInstant()` são Java 9+ e estão em `java.base`. Nenhuma biblioteca nova, logo
  nenhum baseline a verificar.
- **Módulo novo para capacidade de escopo próprio**: **não se aplica, e a razão importa**. Um módulo
  separado precisaria de `McpServerConfig`, `AuditTrail`, `SessionManifest`, `McpDispatcher` e
  `PathConfinement`, todos internos do `iped-mcp`, e a fronteira nova não compraria isolamento algum —
  o isolamento real desta feature é o **processo**, não o artefato Maven.
- **Ferramenta externa nova**: nenhuma. `ThirdParty.txt` não muda. O `iped.jar` invocado é o do próprio
  release.

**Fluxo de desenvolvimento**: `mvn -pl iped-mcp -am install` e `mvn -pl iped-mcp test` antes de
qualquer commit; `mvn -pl iped-app -am install` também, por causa do `-passwordFile`.
`iped-mcp/CLAUDE.md` precisa de seções novas — invariantes de processamento e limitações conhecidas —
e `iped-app/CLAUDE.md` precisa registrar o parâmetro novo.

**Resultado do portão: PASSA com uma violação justificada.** Ver Complexity Tracking.

### Re-avaliação após Phase 1

Reavaliado com [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/)
e [quickstart.md](./quickstart.md) escritos. **Sem mudança de resultado.**

Duas coisas se firmaram no desenho e merecem registro, porque ambas reforçam princípios em vez de
apenas não os violar:

- **Princípio II ficou mais forte, não mais fraco.** A decisão R1 — invocar o `iped.jar` em vez de
  dirigir `Manager` de dentro — significa que o caso produzido percorre exatamente o mesmo caminho de
  código que um caso da CLI. Não há "caso feito pelo MCP" a divergir do "caso feito pela linha de
  comando"; há um só caminho, com um chamador a mais.
- **Princípio I ganhou uma verificação que a spec não pedia.** R6 mostrou que o cancelamento precisa
  destruir a árvore de processos, e um cancelamento parcial deixaria o motor lendo evidência depois de
  o servidor ter declarado o trabalho encerrado. A verificação de que a árvore morreu inteira
  (SC-014) passa a ser também uma verificação de Princípio I, não só de tempo de resposta.

## Project Structure

### Documentation (this feature)

```text
specs/007-mcp-case-processing/
├── plan.md              # Este arquivo
├── spec.md              # Requisitos — 49 FR, 24 SC, 9 clarificações
├── research.md          # Phase 0 — oito decisões, quatro forçadas por leitura do código
├── data-model.md        # Phase 1 — entidades, estados e transições do trabalho
├── quickstart.md        # Phase 1 — roteiro de validação ponta a ponta
├── contracts/           # Phase 1 — superfícies expostas
│   ├── config-surface.md      # chaves novas de conf/McpServerConfig.txt
│   ├── tool-surface.md        # ferramentas MCP novas e efeito nas existentes
│   └── job-lifecycle.md       # estados, transições e reconciliação na volta
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 — criado por /speckit-tasks, não por este comando
```

### Source Code (repository root)

```text
iped-mcp/src/main/java/iped/mcp/
├── config/
│   └── McpServerConfig.java            # MODIFICADO: habilitação, áreas de leitura, raízes de caso,
│                                       #   perfis permitidos, margem de disco, repositório de segredos
├── processing/                         # NOVO — todo o pacote
│   ├── ProcessingRequest.java          # pedido validado por inteiro antes de virar trabalho (FR-017)
│   ├── ProcessingJob.java              # estado, avanço, instantes, desfecho (FR-018..FR-024)
│   ├── JobStore.java                   # persistência fora da memória, retenção indefinida (FR-041, FR-045)
│   ├── JobRunner.java                  # ProcessBuilder sobre iped.jar; ciclo de vida; cancelamento de árvore
│   ├── ProgressReader.java             # progresso e log a partir do fluxo do filho (FR-020, FR-042, FR-043)
│   ├── OrphanReconciler.java           # ProcessHandle + startInstant na volta do servidor (FR-024)
│   ├── SourceConfinement.java          # lista de permissão de leitura (FR-006, FR-007, FR-008)
│   ├── CaseRootConfinement.java        # raízes de caso, separadas das de exportação (FR-009, FR-010)
│   ├── DiskPreflight.java              # adverte, nunca recusa (FR-044)
│   ├── ProfileRegistry.java            # perfis permitidos, declarados (FR-013)
│   └── SecretResolver.java             # referência → senha do lado do servidor (FR-015)
├── tools/
│   ├── ProcessingTools.java            # NOVO: iped_process_evidence, iped_job_status,
│   │                                   #   iped_cancel_job, iped_resume_job
│   └── SessionTools.java               # MODIFICADO: postura de processamento (FR-004)
├── session/
│   ├── Session.java                    # MODIFICADO: advertência de abertura (FR-005)
│   ├── CasePool.java                   # LIDO, não alterado: saber se o destino é caso aberto agora (FR-010)
│   └── CaseValidator.java              # MODIFICADO: recusa passa a nomear o trabalho conhecido (FR-028)
├── audit/
│   └── SessionManifest.java            # MODIFICADO: vínculo sessão↔trabalho e postura vigente (FR-038)
└── export/
    └── PathConfinement.java            # REUTILIZADO sem alteração

iped-mcp/src/main/resources/skill/
└── SKILL.md                            # MODIFICADO: caminhos de evidência pertencem ao servidor (FR-037)

iped-app/src/main/java/iped/app/processing/
└── CmdLineArgsImpl.java                # MODIFICADO: -passwordFile (aditivo — ver Complexity Tracking)

iped-engine/src/main/java/iped/engine/
├── CmdLineArgs.java                    # MODIFICADO: getPasswordFile() (acréscimo em interface)
└── datasource/DataSourceReader.java    # MODIFICADO: consome o arquivo além do -p existente

iped-mcp/src/test/java/iped/mcp/
├── unit/       ProcessingConfinementTest, DiskPreflightTest, ProgressReaderTest, JobStoreTest
├── contract/   ProcessingToolSchemaTest, ProcessingPostureTest, NoProcessingByDefaultTest
└── integration/ProcessEvidenceEndToEndTest, JobSurvivesSessionTest, CancelJobTest,
                OrphanReconciliationTest, IncompleteCaseNotOpenableTest
```

**Structure Decision**: extensão do `iped-mcp` com um pacote novo `processing/`, mais uma adição
pontual e aditiva ao `iped-app`/`iped-engine` para o arquivo de senha. O isolamento que importa nesta
feature é o **processo do motor**, não a fronteira de artefato Maven — por isso nenhum módulo novo.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **Mudança fora do `iped-mcp`**: parâmetro `-passwordFile` em `CmdLineArgsImpl` (`iped-app`), método novo em `CmdLineArgs` (`iped-engine`) e leitura em `DataSourceReader` | Senha de contêiner cifrado só entra no IPED por `-p` na linha de comando. Linha de comando é legível por outros processos da máquina — `/proc/PID/cmdline` no Linux é legível por qualquer usuário. FR-015 existe para que a senha não vaze; entregar a feature usando `-p` cumpriria as quatro palavras do requisito (pedido, resposta, trilha, log) e falharia no motivo dele | **Usar `-p` e documentar a exposição** foi rejeitado porque transforma um requisito de segurança em nota de rodapé, e porque a exposição atinge justamente a instalação de topologia dividida, onde a máquina da evidência pode ter mais de uma conta. **Não suportar contêiner cifrado** foi rejeitado porque estreitaria o escopo em silêncio: material cifrado é rotina em perícia, e a spec o trata como caso de borda esperado, não como fora de escopo. **Variável de ambiente** foi rejeitada porque exigiria a mesma mudança em `iped-app` para ser lida, sem a vantagem de o segredo ficar sob permissão de arquivo |

A adição é aditiva em todos os sentidos que a constituição protege: nenhum método é removido ou
renomeado, `CmdLineArgs` ganha um método e não perde nenhum, `-p` continua funcionando exatamente como
hoje, e nenhum componente da lista do Princípio III é tocado. O custo é que `mvn -pl iped-app -am
install` passa a fazer parte do fluxo de verificação desta feature.
