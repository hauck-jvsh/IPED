---

description: "Task list for 006-export-allowlist-socket-transport"
---

# Tasks: Confinamento de escrita e transporte de rede para o servidor MCP

**Input**: Design documents from `/specs/006-export-allowlist-socket-transport/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: Incluídos. Não é escolha de estilo — dez dos quinze critérios de sucesso do spec são afirmações sobre comportamento observável que só uma suíte fecha, e o módulo já mantém três suítes (`contract/`, `integration/`, `unit/`). Onde o teste precede a implementação, está dito na tarefa e o motivo está junto.

**Organization**: agrupadas por história. **US1 não toca nenhum arquivo de `transport/`** — é o que torna o Nível 0 implantável antes de qualquer coisa de rede existir, conforme o pedido determinou.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivos distintos, sem dependência pendente)
- **[Story]**: US1, US2, US3
- Caminho de arquivo exato em toda tarefa

## Path Conventions

Módulo `iped-mcp` dentro do projeto multi-módulo. Fonte em `iped-mcp/src/main/java/iped/mcp/`, testes em `iped-mcp/src/test/java/iped/mcp/`, configuração distribuída em `iped-app/resources/config/conf/`.

---

## Phase 1: Setup

**Purpose**: fixar o ponto de partida para que toda falha posterior seja atribuível

- [X] T001 Registrar a linha de base em `iped-mcp/`: rodar `mvn -pl iped-mcp -am install` e `mvn -pl iped-mcp test`, e anotar **quantos testes passam e quantos pulam**. A feature 001 encerrou com o caso de referência não construído e 47 testes pulando por isso; sem esse número anotado, um teste que passe a pular por causa desta feature fica indistinguível dos que já pulavam

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: o único trabalho compartilhado pelas três histórias

**⚠️ Esta fase é deliberadamente curta.** US1 foi desenhada para não precisar de nada de US2, e a maior parte do que pareceria fundação — chaves de configuração, diagnóstico — é específica de cada história e vive nela.

- [X] T002 Estender `iped-mcp/src/test/java/iped/mcp/McpTestSupport.java` com auxiliares que as suítes das três histórias vão usar: criação de raiz de escrita temporária, criação e remoção de **junção de diretório** no Windows (`cmd /c mklink /J` e `cmd /c rmdir` — `Remove-Item -Recurse` sobre junção alcança o alvo e apagaria o que está do outro lado), e montagem de `McpServerConfig` em memória sem arquivo em disco

**Checkpoint**: fundação pronta — US1 pode começar

---

## Phase 3: User Story 1 - Confinar onde o servidor pode escrever (Priority: P1) 🎯 MVP

**Goal**: o destino de um artefato passa a ser aprovado por lista de permissão sobre raízes declaradas, verificada no caminho real, antes de qualquer efeito no sistema de arquivos — e o resultado da escrita é conferido.

**Independent Test**: Q1 do [quickstart.md](./quickstart.md). Nenhum arquivo de `transport/` é tocado; a história é implantável sozinha.

### Tests for User Story 1

> **T003 precisa falhar contra a implementação atual antes de qualquer mudança.** Esse é o ponto: a linha da junção é a prova de que o defeito medido em [research.md](./research.md) R1 é real neste código, e não uma preocupação teórica. Um teste escrito depois da correção não prova nada sobre o que existia antes.

- [X] T003 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/unit/PathConfinementTest.java` com a bateria de SC-001: caminho relativo com `..`, **junção de diretório dentro da raiz apontando para fora**, fluxo alternativo de dados sobre arquivo permitido, nome curto 8.3, diferença de caixa, prefixo `\\?\`, destino dentro da pasta do caso com a raiz contendo o caso, e destino válido. Rodar contra o código atual e **registrar quais linhas passam** — a da junção deve reprovar
- [X] T004 [P] [US1] Acrescentar a `PathConfinementTest` a verificação de FR-002: após cada recusa, **nenhum arquivo e nenhuma pasta** existem no destino pedido
- [X] T005 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/unit/ArtifactIntegrityTest.java` para FR-034/SC-015: destino nomeando dispositivo reservado (`NUL`, `CON`, `COM1`) **dentro** da raiz permitida. O teste afirma a **resposta devolvida** — falha com diagnóstico —, nunca o veredito de contenção, que é corretamente `ALLOWED`. Um teste que afirme o veredito passa com o defeito presente
- [X] T006 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/integration/ExportRefusalAuditTest.java` para FR-007: toda recusa consta da trilha com destino pedido e regra aplicada

### Implementation for User Story 1

- [X] T007 [P] [US1] Acrescentar `exportRoots` a `iped-mcp/src/main/java/iped/mcp/config/McpServerConfig.java`, separado por `;` conforme [contracts/config-surface.md](./contracts/config-surface.md) — vírgula não serve porque caminho de arquivo a contém —, com resolução por `toRealPath()` e estado por raiz (`USABLE`, `MISSING`, `NOT_A_DIRECTORY`, `NOT_WRITABLE`)
- [X] T008 [US1] Criar `iped-mcp/src/main/java/iped/mcp/export/PathConfinement.java`: resolver o **ancestral existente mais profundo** com `toRealPath()` e recompor o restante por cima — `toRealPath()` lança `NoSuchFileException` em arquivo inexistente e o destino de uma exportação nunca existe. Comparar **real contra real**, com a raiz também resolvida. Produzir `ResolvedDestination` com veredito `ALLOWED`, `OUTSIDE_ROOTS`, `INSIDE_CASE` ou `UNRESOLVABLE` (depende de T007)
- [X] T009 [US1] Converter `InvalidPathException` em veredito `UNRESOLVABLE` nomeado dentro de `PathConfinement.java`, em vez de deixá-la escapar como exceção técnica — é por onde fluxo alternativo de dados e espaço ao final chegam
- [X] T010 [US1] Reescrever `checkDestination` em `iped-mcp/src/main/java/iped/mcp/tools/ExportTools.java` para delegar a `PathConfinement`, mantendo `INSIDE_CASE` prevalecendo sobre `ALLOWED` (FR-004) e fazendo `DESTINATION_REFUSED` **nomear as raízes permitidas** (FR-008)
- [X] T011 [US1] Estreitar a semântica de `allowExportIntoCaseFolder` em `ExportTools.java`: passa a suprimir **apenas** o veredito `INSIDE_CASE`; `OUTSIDE_ROOTS` continua recusado com a chave ligada. Hoje ela faz `checkDestination` retornar antes de qualquer verificação, liberando o disco inteiro
- [X] T012 [US1] Inverter a ordem em `iped-mcp/src/main/java/iped/mcp/export/ArtifactWriter.java`: a criação de pastas intermediárias (`Files.createDirectories`, hoje na linha 95, antes de qualquer decisão) passa a acontecer **depois** do veredito `ALLOWED`, para que uma recusa não deixe rastro (FR-002)
- [X] T013 [US1] Migrar o caminho de escrita de `ArtifactWriter.java` de `java.io.File` para `java.nio.file.Path`. `File` aceita fluxo alternativo de dados e `FileOutputStream` grava nele; `Paths.get` o rejeita na entrada
- [X] T014 [US1] Acrescentar a `ArtifactWriter.java` a verificação pós-escrita de FR-034: o artefato existe no caminho resolvido e retém o que foi escrito. **Sem lista de nomes proibidos** — na sondagem de R2, `CON` criou arquivo real e `NUL` não, e o conjunto varia por sistema e por versão
- [X] T015 [US1] Implementar a raiz padrão de FR-024 em `McpServerConfig.java`: sem `exportRoots` declarado, vale uma raiz documentada na área de trabalho do usuário que executa o servidor, criada sob demanda. Instalação existente continua funcionando e ainda assim passa a estar confinada
- [X] T016 [US1] Acrescentar a `iped-mcp/src/main/java/iped/mcp/Diagnostics.java` a sondagem de cada raiz na inicialização (FR-006): reportado, servidor sobe, primeira gravação sob raiz inutilizável falha com diagnóstico acionável
- [X] T017 [US1] Registrar a recusa na trilha com destino pedido e regra aplicada, em `ExportTools.java`, no padrão que FR-041 de 001 usa para conteúdo bloqueado por egresso (FR-007)
- [X] T018 [US1] Acrescentar `exportRoots` e a nota de semântica estreitada de `allowExportIntoCaseFolder` a `iped-app/resources/config/conf/McpServerConfig.txt`, com os mesmos valores dos fallbacks de código

**Checkpoint**: T003 passa inteira, incluindo a linha da junção. **US1 está completa e implantável sem nenhuma linha de US2.**

---

## Phase 4: User Story 2 - Servir um caso a um harness em outra máquina (Priority: P2)

**Goal**: transporte por conexão de rede, desligado por padrão, autenticado por segredo compartilhado, com várias sessões somente-leitura e no máximo uma com escrita por caso.

**Independent Test**: Q3 a Q7 do quickstart, com o harness em ambiente sem acesso ao sistema de arquivos do caso.

### Tests for User Story 2

- [X] T019 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/contract/TransportParityTest.java` para FR-015: o conjunto de ferramentas registradas e seus esquemas são idênticos nos dois transportes. Orientação divergente entre harnesses foi o motivo de a skill ter fonte canônica única; superfície divergente entre transportes teria o mesmo efeito
- [X] T020 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/unit/HandshakeCodecTest.java`: linha bem formada, segredo incorreto, segredo ausente, operador alegado ausente (estado válido), operador alegado presente, linha malformada
- [X] T021 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/SocketTransportTest.java`: conexão sem segredo e com segredo incorreto não obtêm resposta de ferramenta **nem informação sobre existência de casos**; `transport = socket` sem segredo resolvível **não estabelece o ponto de escuta**; porta ocupada produz diagnóstico e não servidor que aparenta servir; conexão ociosa é encerrada no prazo
- [X] T022 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/ConcurrentSessionsTest.java`: duas sessões somente-leitura não se bloqueiam e devolvem o mesmo que uma sessão isolada; a segunda a pedir escrita é recusada **nomeando a detentora**; queda da detentora libera a reivindicação sem reinício; UI do IPED segurando o caso recusa a escrita mesmo para a detentora (FR-014, FR-029, FR-030, FR-031)
- [X] T023 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/ConnectionDropTest.java` para FR-017/SC-005: queda no meio de operação longa deixa o servidor disponível, libera o caso, e a operação interrompida **consta da trilha com desfecho** — `STARTED` sem desfecho não pode ser o resultado normal de uma desconexão
- [X] T024 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/AuditReconciliationTest.java` para FR-033/SC-013: exame conduzido por duas sessões simultâneas é reconstituível a partir do que acompanha o caso, sem saber de antemão quantas sessões existiram

### Implementation for User Story 2

- [X] T025 [P] [US2] Criar `iped-mcp/src/main/java/iped/mcp/transport/Transport.java` e `StdioTransport.java`, extraindo o comportamento atual sem alterá-lo. `McpServerMain.start(InputStream, OutputStream)` já é agnóstico de transporte — foi escrito assim para FR-064 de 001 — então esta é extração, não redesenho
- [X] T026 [P] [US2] Criar `iped-mcp/src/main/java/iped/mcp/transport/HandshakeCodec.java` conforme [contracts/transport-handshake.md](./contracts/transport-handshake.md): linha UTF-8 **explícita** nas duas direções, comparação do segredo em **tempo constante**, `DENIED` que não revela nada (depende de T020)
- [X] T027 [US2] Resolver o segredo em `iped-mcp/src/main/java/iped/mcp/config/McpServerConfig.java` a partir da variável `IPED_MCP_SHARED_SECRET` ou do arquivo apontado por `sharedSecretFile` — **nunca do próprio arquivo de configuração**, que é distribuído com o release e cairia no que FR-028 veda. Valor ausente ou vazio com `transport = socket` impede o ponto de escuta (FR-026)
- [X] T028 [US2] Acrescentar a `McpServerConfig.java` as chaves `transport`, `listenAddress`, `listenPort`, `sharedSecretFile`, `maxConcurrentSessions` e `sessionIdleTimeoutSeconds`, **sem padrão** para endereço e porta — Princípio V, e em particular nunca todas as interfaces por omissão (FR-012)
- [X] T029 [US2] Criar `iped-mcp/src/main/java/iped/mcp/transport/SocketTransport.java`: vincular, aceitar, aplicar handshake, criar uma `Session` por conexão, respeitar `maxConcurrentSessions` e o prazo de ociosidade. Uma conexão recusada **nunca alcança o dispatcher** (depende de T026, T027, T028)
- [X] T030 [US2] Converter `iped-mcp/src/main/java/iped/mcp/session/Session.java` de uma por processo para uma por conexão, e trocar o campo único `operator` pelo par `OperatorIdentity` — autoritativa do processo, alegada do handshake, que **nunca se fundem** (FR-020). O comentário atual amarra a decisão à premissa D2 que esta feature remove
- [X] T031 [US2] Criar `iped-mcp/src/main/java/iped/mcp/session/CasePool.java` com contagem de referências por caminho de caso. Sem isso, duas sessões sobre um caso de 10 M itens pagam duas vezes os 30 s de abertura e a memória, e SC-006 cai (depende de T030)
- [X] T032 [US2] Fazer `iped-mcp/src/main/java/iped/mcp/session/CaseRegistry.java` obter casos do `CasePool` em vez de abrir os seus (depende de T031)
- [X] T033 [US2] Criar `iped-mcp/src/main/java/iped/mcp/session/WriteClaims.java`, registro `caseId` → `sessionId`. Ele **não exclui** — a exclusão continua sendo o `access.lock` do `ConcurrencyGuard`, que já converte `OverlappingFileLockException` em `CONCURRENT_ACCESS` e portanto já separa sessões do mesmo processo. O registro existe para **nomear** a detentora (FR-029)
- [X] T034 [US2] Corrigir o diagnóstico de `iped-mcp/src/main/java/iped/mcp/session/ConcurrencyGuard.java`, que afirma "by another process on this machine" — falso quando a detentora é outra sessão do mesmo processo — e fazê-lo nomear a sessão detentora. `probeBookmarksState` continua rodando antes do lock, de modo que FR-031 vale por cima (depende de T033)
- [X] T035 [US2] Implementar o encerramento de sessão em `Session.java` e `SocketTransport.java` de modo que o caminho de limpeza seja **único** para fim normal e para queda: libera reivindicação, devolve caso ao pool, fecha e sincroniza a trilha (FR-017, FR-030)
- [X] T036 [US2] Levar identidade alegada, transporte e origem à trilha **sem acrescentar campo a `AuditRecord`**. Revisto na implementação: não basta não reordenar — `AuditTrail.verify` recompõe `toNodeWithoutHash` a partir do que lê, então um campo a mais muda o resultado para registros já emitidos. A alegação vai no campo `operator` existente, renderizada por `OperatorIdentity.describe()` com "unverified" dentro do próprio valor; transporte e origem vão para o `SessionManifest`, onde são propriedade da sessão e não de cada operação (FR-020, FR-021, FR-032)
- [X] T037 [US2] Criar `iped-mcp/src/main/java/iped/mcp/audit/SessionManifest.java`, append-only em `<caso>/mcp-audit/`, uma linha por sessão. Responde às duas perguntas de quem abre a pasta com N arquivos: **são todos?** e **em que ordem?** (FR-033). Degrada como a trilha degrada em mídia não gravável (depende de T036)
- [X] T038 [US2] Criar `iped-mcp/src/main/java/iped/mcp/McpRelayMain.java`, relay stdio↔socket. **Nenhum `System.out`**: no relay, stdout é o canal do protocolo para o harness, e um único print corrompe a sessão do lado do cliente, exatamente como já vale no servidor. Lê o segredo das mesmas duas fontes que o servidor (depende de T026)
- [X] T054 [US2] **Meio-fechamento no fim da entrada** em `McpRelayMain.relay` (FR-035, SC-016), com `RelayShutdownTest` em `iped-mcp/src/test/java/iped/mcp/`. Tarefa criada depois do primeiro teste de campo, que reprovou aqui: o relay respondia tudo corretamente e não terminava quando o stdin acabava, deixando a sessão a segurar o caso e a reivindicação de escrita até o teto de ociosidade. Um relay pendurado passa em qualquer verificação de requisição/resposta — o que falha é o encerramento, e nada o exercitava
- [X] T039 [US2] Ligar o transporte em `iped-mcp/src/main/java/iped/mcp/McpServerMain.java`: escolher `StdioTransport` ou `SocketTransport` pela configuração, com `stdio` por padrão e **nenhuma porta aberta** quando não configurado (FR-011)
- [X] T040 [US2] Acrescentar a `Diagnostics.java` as verificações de transporte de [contracts/config-surface.md](./contracts/config-surface.md): segredo resolvível é fatal para o transporte; endereço e porta vinculáveis são reportados sem fazer o servidor aparentar que serve (FR-018)
- [X] T041 [US2] Declarar em `ExportTools.java` que, em sessão de rede, `destination` é caminho **no sistema de arquivos do servidor** (FR-019) — o perito está em outra máquina e não distingue pelo caminho devolvido
- [X] T042 [US2] Acrescentar as chaves de transporte a `iped-app/resources/config/conf/McpServerConfig.txt`, todas desligadas por padrão, com a orientação de que o segredo não vai nesse arquivo

**Checkpoint**: US1 e US2 funcionam, cada uma testável sozinha.

---

## Phase 5: User Story 3 - Enxergar qual superfície está exposta (Priority: P3)

**Goal**: transporte, ponto de escuta, raízes, reivindicações e identidade dupla consultáveis de dentro da sessão e presentes na advertência de abertura.

**Independent Test**: Q9 do quickstart — o que o servidor declara coincide com o estado observável do sistema operacional e com o arquivo de configuração.

> **Dependência real, registrada em vez de disfarçada**: esta história relata o que US1 e US2 constroem. A parte de raízes de escrita fica verificável assim que US1 entra; a de transporte e reivindicações, só depois de US2. Ela não é independente das outras duas, e afirmar o contrário produziria um plano que não executa.

- [X] T043 [P] [US3] Criar `iped-mcp/src/test/java/iped/mcp/integration/PostureVisibilityTest.java` para SC-008: em cada configuração, o que `iped_session_info` declara coincide com o arquivo de configuração e com as portas efetivamente abertas
- [X] T044 [US3] Acrescentar a postura vigente a `iped-mcp/src/main/java/iped/mcp/session/Session.java`, método `describe()`: transporte ativo, ponto de escuta quando houver, raízes declaradas com o estado de cada uma, reivindicação de escrita por caso aberto, identidade do operador como par. Responde **inclusive com o transporte inativo**, no padrão de FR-042 de 001 (FR-022)
- [X] T045 [US3] Acrescentar à lista de advertências de abertura em `Session.java`, para sessão de rede, que o conteúdo de evidência **trafega por rede e o canal não é protegido** (FR-023). É dessa informação que depende a decisão do perito de manter o trânsito numa máquina física
- [X] T046 [US3] Rotular a identidade alegada como **não verificada** na exportação legível por humano da trilha, em `iped-mcp/src/main/java/iped/mcp/tools/AuditTools.java` (FR-032). Nome de campo não basta numa exportação para humano: uma alegação que se lê como fato verificado num laudo é pior do que alegação nenhuma

**Checkpoint**: as três histórias funcionam.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T047 [P] Atualizar `iped-mcp/src/main/resources/skill/SKILL.md` com o destino de artefato sendo do lado do servidor e as raízes declaradas. **Editar apenas a fonte canônica** — os invólucros em `iped-app/resources/skills/{claude-code,codex,opencode}/` são regenerados no build e ignorados pelo git
- [X] T048 [P] Acrescentar a topologia dividida aos três guias em `iped-mcp/src/main/resources/skill/install/{claude-code,codex,opencode}.md`, incluindo o que verificar para que a separação seja real e não aparente, e **dizendo com todas as letras** que o canal não é protegido e que o alcance recomendado é trânsito dentro de uma máquina física ou segmento confiável (FR-025)
- [X] T049 Confirmar que `iped-mcp/src/test/java/iped/mcp/contract/SkillParityTest.java` continua passando após T047 e T048
- [X] T050 Atualizar `iped-mcp/CLAUDE.md`: seção de invariantes (confinamento de escrita, exclusividade por caso, handshake antes do JSON-RPC), seção de áreas sensíveis (`PathConfinement`, `CasePool`) e seção de limitações conhecidas — a de concorrência muda materialmente, e a trilha por sessão passa a ter o manifesto como resposta
- [X] T051 Executar Q1 e Q2 do [quickstart.md](./quickstart.md), que não exigem caso processado
- [ ] T052 Executar Q3 a Q10 do quickstart com caso processado e ambiente isolado, **registrando explicitamente o que for pulado** — um teste pulado não é um teste que passou, e a feature 001 encerrou com 47 pulando por caso de referência ausente
- [X] T053 Rodar `mvn -pl iped-mcp -am install` e `mvn -pl iped-mcp test`, comparando passa/pula com a linha de base de T001

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001)**: sem dependência
- **Foundational (T002)**: depende de T001; bloqueia as suítes das três histórias
- **US1 (T003–T018)**: depende de T002. **Não depende de US2 nem de US3**
- **US2 (T019–T042)**: depende de T002. Não depende de US1 para funcionar, mas **não deve ser entregue antes dela** — expor a superfície pela rede antes de confinar o que ela escreve amplia o alcance de qualquer defeito de caminho
- **US3 (T043–T046)**: depende de US1 e US2 pelo que relata
- **Polish (T047–T053)**: depende das histórias desejadas

### Within User Story 1

T003 e T004 antes de T008 (o teste precisa reprovar contra o código atual). T007 antes de T008. T008 antes de T010, T011 e T012. T012 antes de T013 e T014.

### Within User Story 2

T026, T027 e T028 antes de T029. T030 antes de T031, que precede T032. T033 antes de T034. T036 antes de T037. T029 e T039 fecham o caminho de rede.

### Parallel Opportunities

- **US1, testes**: T003, T004, T005 e T006 são arquivos distintos — todos [P]
- **US1, implementação**: T007 é independente de T008 no arranque; o resto encadeia
- **US2, testes**: T019 a T024 são seis arquivos distintos — todos [P]
- **US2, implementação**: T025 e T026 em paralelo; depois o encadeamento acima
- **Polish**: T047 e T048 em paralelo, T049 depois dos dois

## Parallel Example: User Story 1

```text
# As quatro suítes de US1, juntas:
T003 PathConfinementTest — bateria de SC-001
T004 verificação de ausência de rastro após recusa
T005 ArtifactIntegrityTest — dispositivo reservado
T006 ExportRefusalAuditTest — recusa na trilha
```

## Implementation Strategy

### MVP (US1 apenas)

1. T001, T002
2. T003–T018
3. **PARAR e VALIDAR**: Q1 do quickstart, e confirmar que a linha da junção — que reprovava em T003 — passa
4. Implantável. O Nível 0 do plano operacional está entregue e nenhuma linha de código de rede existe

### Entrega incremental

1. Setup + Foundational
2. US1 → validar → implantar (MVP)
3. US2 → validar → implantar
4. US3 → validar → implantar

### Estratégia com mais de uma pessoa

US1 e US2 podem ser trabalhadas em paralelo depois de T002, com a ressalva de ordem de **entrega** acima. US3 não paraleliza com as outras: ela relata o que elas produzem.

## Notes

- Antes de cada commit de código: `mvn -pl iped-mcp -am install` e `mvn -pl iped-mcp test`, conforme o fluxo de desenvolvimento da constituição
- `iped-mcp/CLAUDE.md` precisa ser atualizado quando contratos mudarem — T050 é o fechamento, não o único momento
- Nenhuma dependência nova entra no `pom.xml`. Se alguma tarefa parecer exigir uma, o desenho saiu do plano: `java.net` e `java.nio.file` estão em `java.base`, e essa foi a razão de descartar o transporte HTTP em [research.md](./research.md) R3
- Nenhum `System.out` em nenhum arquivo tocado, servidor ou relay
