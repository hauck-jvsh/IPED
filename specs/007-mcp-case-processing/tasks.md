---
description: "Task list for feature implementation"
---

# Tasks: Criação de caso pelo servidor MCP — processar a evidência

**Input**: Design documents from `/specs/007-mcp-case-processing/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: incluídos. Não por preferência de método: o `iped-mcp` já organiza suítes em `unit/`,
`contract/` e `integration/`, o fluxo de desenvolvimento da constituição exige `mvn test` passando, e
três defeitos desta feature — cancelamento parcial, órfão vivo, log no canal do protocolo — **passam em
qualquer teste de requisição/resposta** e só aparecem em verificação escrita para eles.

**Organization**: por história de usuário, para que cada uma seja implantável e verificável sozinha.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode correr em paralelo (arquivos distintos, sem dependência pendente)
- **[Story]**: a qual história pertence (US1, US2, US3)
- Caminho de arquivo exato em cada tarefa

## Path Conventions

Módulo Maven multi-módulo. Fonte em `iped-mcp/src/main/java/iped/mcp/`, testes em
`iped-mcp/src/test/java/iped/mcp/{unit,contract,integration}/`.

**Nenhum código-fonte fora do `iped-mcp` é alterado** — Complexity Tracking do [plan.md](./plan.md)
está vazio. A única exceção não é código: `iped-app/resources/config/conf/McpServerConfig.txt`, o
arquivo de configuração **distribuído** deste módulo, que mora ali porque é de lá que os recursos do
release são empacotados. É onde toda chave de `McpServerConfig` vive desde a 001.

---

## Phase 1: Setup

**Purpose**: preparar o terreno mínimo. Esta feature estende um módulo existente; não há projeto a inicializar.

- [X] T001 Criar `iped-mcp/src/main/java/iped/mcp/processing/package-info.java` documentando em inglês a invariante que governa o pacote inteiro: o motor **nunca** roda dentro do processo do servidor, porque `iped-app` depende de `iped-mcp` e a chamada inversa seria circular ([research.md](./research.md) R1). Sem esse registro, a primeira pessoa a tentar `new Manager(...)` aqui descobre o motivo pelo erro de compilação, não pela documentação
- [X] T002 Estender `iped-mcp/src/test/java/iped/mcp/McpTestSupport.java` com `requireSourceEvidence()` e `requireCaseRoot()`, seguindo a disciplina já existente: **pula** quando a evidência não está configurada, **falha** quando ela está mas a instalação (`-Diped.mcp.ipedRoot`) ou o runtime (`-Djvm`) não estão — alguém pediu execução real e pular ali reportaria "nada a fazer" para bancada mal configurada

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: o que toda história precisa. Sem esta fase, nenhuma das três começa.

**⚠️ CRITICAL**: nenhuma história pode começar antes do checkpoint desta fase.

- [X] T003 Acrescentar a `iped-mcp/src/main/java/iped/mcp/config/McpServerConfig.java` as nove chaves de [contracts/config-surface.md](./contracts/config-surface.md). Separador `;`, **nunca** `,` — caminho de arquivo carrega vírgula. `processingSourceAreas` e `processingCaseRoots` **sem padrão de propósito**: com `processingEnabled=true` e qualquer das duas vazia, o servidor recusa processar e diz qual chave falta. Raiz padrão inventada seria permissão que ninguém concedeu
- [X] T004 [P] Criar `iped-mcp/src/main/java/iped/mcp/processing/ProcessingRequest.java` e `ProcessingJob.java` conforme [data-model.md](./data-model.md), com a máquina de estados. `INTERRUPTED` **não** é terminal — é o único ponto de partida da retomada. Campo de senha **não existe** em nenhuma das duas
- [X] T005 [P] Criar `iped-mcp/src/main/java/iped/mcp/processing/JobStore.java`: um arquivo por trabalho em `<auditoria>/jobs/<id>/job.json`, gravado a cada transição, retenção indefinida (FR-045). **Não tocar `AuditRecord`** — a documentação do módulo é explícita: um campo a mais invalida a verificação de trilhas já emitidas ([research.md](./research.md) R3)
- [X] T006 [P] Criar `iped-mcp/src/main/java/iped/mcp/processing/SourceConfinement.java` delegando ao `PathConfinement` existente **sem alterá-lo** — a classe já recebe as raízes por parâmetro. Vereditos `ALLOWED`, `OUTSIDE_ROOTS`, `UNRESOLVABLE`, `AREA_UNAVAILABLE`. Resolução **no momento do pedido**, não na inicialização (FR-039)
- [X] T007 [P] Criar `iped-mcp/src/main/java/iped/mcp/processing/CaseRootConfinement.java`, também sobre `PathConfinement`, com lista **separada** da de exportação (FR-009). Um artefato tem megabytes, um caso tem centenas de gigabytes: reaproveitar `exportRoots` deixaria uma pasta de laudos receber um índice inteiro
- [X] T008 [P] Criar `iped-mcp/src/main/java/iped/mcp/processing/ProfileRegistry.java` lendo `processingProfiles`. Nenhum perfil embutido em código
- [X] T009 Criar `iped-mcp/src/main/java/iped/mcp/tools/ProcessingTools.java` com as quatro ferramentas de [contracts/tool-surface.md](./contracts/tool-surface.md) registradas e o **portão de habilitação** (FR-001, FR-002): com `processingEnabled=false` elas não aparecem na superfície, e um pedido forçado é recusado **antes da leitura de qualquer argumento** e sem tocar o sistema de arquivos. O portão vai antes de ler argumento, como o portão de modo de acesso já faz (depende de T003)
- [X] T010 [P] Criar `iped-mcp/src/test/java/iped/mcp/contract/NoProcessingByDefaultTest.java` para SC-012: com a configuração distribuída sem edição, nenhuma ferramenta de processamento aparece **e** um pedido forçado não lê nem escreve nada. Verificar só a ausência da ferramenta não prova a segunda metade, que é a que importa

**Checkpoint**: configuração, entidades, confinamento e portão existem. As três histórias podem começar.

---

## Phase 3: User Story 1 - Processar uma evidência declarada até um caso consultável (Priority: P1) 🎯 MVP

**Goal**: do pedido a um caso que a ferramenta existente abre, sem ninguém tocar na máquina do servidor.

**Independent Test**: Cenário 1 do [quickstart.md](./quickstart.md). Nenhum arquivo de acompanhamento ou de postura é necessário; a história é implantável sozinha.

### Tests for User Story 1

- [X] T011 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/integration/ProcessEvidenceEndToEndTest.java` para SC-001, SC-002 e SC-008: pedido aceito em **menos de 5 s cronometrados** independentemente do tamanho da evidência, trabalho até `COMPLETED`, caso aberto pela ferramenta existente, e `item_count` do desfecho **coincidindo** com a contagem do caso aberto
- [X] T012 [P] [US1] Acrescentar a `iped-mcp/src/test/java/iped/mcp/integration/ProcessEvidenceEndToEndTest.java` a verificação de SC-015 e do Princípio I: hash da evidência de origem antes e depois, idêntico. Repetir ao fim de **todo** desfecho, não só do sucesso — SC-015 exige a identidade em falha, cancelamento e interrupção também
- [X] T013 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/unit/DiskPreflightTest.java` para SC-019 e SC-021: o mínimo calculado bate com `origem × percentual` (500 GB a 50% = 250 GB); destino abaixo do mínimo é **aceito** com advertência carregando os três números; **0% de recusas** por esse motivo; imagem **segmentada** é medida pelo conjunto, não pelo primeiro `.E01`; e origem imensurável no orçamento produz exigência **indisponível**, não suposta. Um teste que afirme recusa aqui codifica a decisão contrária à que o perito tomou
- [ ] T014 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/integration/SecretHandlingTest.java` para SC-011 e SC-025: processar contêiner cifrado por `secret_ref` e verificar que a senha não aparece **nos quatro lugares que FR-015 nomeia** — resposta, trilha, `processing.log` e pedido registrado. **Não** afirmar ausência em `argv`: a senha está lá por decisão registrada (R5), e um teste que a afirmasse ausente codificaria o contrário do que foi decidido. Em vez disso, afirmar que o aceite **carrega a declaração** de FR-050

### Implementation for User Story 1

- [X] T015 [US1] Criar `iped-mcp/src/main/java/iped/mcp/processing/JobRunner.java`: monta a linha de comando do `iped.jar` a partir do `ProcessingRequest` validado e executa com `ProcessBuilder`. JVM do release e locale **declarados**, nunca herdados (`processingJvm`, `processingLocale`) — herdar o locale faria a leitura de progresso depender de onde o servidor foi instalado, contra o Princípio V (depende de T004)
- [X] T016 [US1] Implementar em `JobRunner.java` a validação total anterior de FR-017: configuração, caminhos, perfil e destino verificados **antes** de qualquer processo nascer. Espaço em disco **não** entra aqui — FR-017 delimita a promessa a configuração, caminho e perfil, e o disco é tratado por T019
- [X] T017 [US1] Implementar em `JobRunner.java` o registro anterior à ação: `AuditTrail` `STARTED` **antes de qualquer leitura da evidência** (FR-032). Se o registro não puder ser gravado, o trabalho não inicia — invariante já vigente do módulo, aplicada a uma operação nova
- [X] T018 [US1] Implementar em `JobRunner.java` o desfecho a partir do código de saída (FR-026, FR-029). `Bootstrap` propaga o código do filho por `System.exit(exit)`, então o código de saída é o contrato. Ler `iped/data/evidences_processing_status` por `EvidenceStatus` para `failed_evidences`, que distingue "nunca processado" (`null`) de "processado sem falhas" (lista vazia)
- [X] T067 [US1] Implementar em `JobRunner.java` a classificação de zero itens de FR-048: percurso completo com contagem zero é `COMPLETED`, **nunca** `FAILED`. Evidência vazia ou de formato não suportado é resposta legítima do exame; apresentá-la como falha faria o perito procurar defeito onde há resultado (depende de T018)
- [X] T068 [US1] Implementar em `JobRunner.java` a distinção de FR-049: antes de classificar a falha, verificar se a origem ainda está acessível — sumiu a mídia ou caiu o compartilhamento produz `SOURCE_INACCESSIBLE`, com retomada possível; origem presente e ilegível produz `SOURCE_UNREADABLE`. São problemas de naturezas diferentes e mudam o que o perito faz em seguida (depende de T018)
- [X] T019 [P] [US1] Criar `iped-mcp/src/main/java/iped/mcp/processing/DiskPreflight.java`: `mínimo = tamanho da origem × processingMinFreeSpacePercentOfSource`, comparado com o espaço livre da unidade de destino, e **adverte, nunca recusa** (FR-044). Usar `FileStore.getUsableSpace()`, não `getFreeSpace()` — o segundo ignora cota e blocos reservados e mentiria a favor. A advertência carrega os **três números** (origem, mínimo, livre) e vai no aceite e na trilha
- [X] T066 [P] [US1] Implementar em `DiskPreflight.java` a medição do **conjunto** de FR-046: imagem segmentada (`.E01`, `.E02`, …) somada por inteiro, pasta lógica somada recursivamente. Medir só o primeiro segmento daria uma fração do real e a advertência nunca sairia no caso que mais precisa dela. A medição é limitada pelo orçamento de aceite de 5 s de FR-018: estourando, a exigência é declarada **indisponível**, nunca suposta — somar uma pasta grande em compartilhamento de rede não cabe em 5 s
- [X] T020 [P] [US1] Criar `iped-mcp/src/main/java/iped/mcp/processing/SecretResolver.java`: resolve `secret_ref` por `processingSecretsFile`, do lado do servidor, e grava a senha em arquivo temporário de permissão restrita, apagado ao fim do trabalho. O valor **não** entra em `ProcessingJob`, `JobStore` nem trilha
- [X] T021 [US1] Passar a senha resolvida ao motor por `-p`, no esquema posicional que `CmdLineArgsImpl.getDataSourcePassword` já usa (`-d <origem> -p <senha>`), em `iped-mcp/src/main/java/iped/mcp/processing/JobRunner.java`. Implementar junto a declaração de FR-050: **todo aceite com `secret_ref` carrega o aviso** de que a senha vai por linha de comando e fica legível a outras contas da máquina enquanto o processo existe. A declaração não é enfeite — é o que transforma a limitação em decisão informada de quem implanta (depende de T020)
- [X] T022 [US1] Implementar `iped_process_evidence` em `ProcessingTools.java` conforme [contracts/tool-surface.md](./contracts/tool-surface.md): devolve `job_id` **sem aguardar a conclusão** (FR-018), declara `paths_are_server_side: true` (FR-036), recusa argumento desconhecido em vez de ignorá-lo (FR-016). Sem esse último, `-X` e opções de motor entrariam pela porta dos fundos e o confinamento seria contornável por parâmetro
- [X] T023 [US1] Implementar em `ProcessingTools.java` o teto de um trabalho por vez (FR-019), com `JOB_ALREADY_RUNNING` nomeando o trabalho em curso
- [X] T061 [US1] Implementar `display_name` → `-dname` em `iped-mcp/src/main/java/iped/mcp/processing/JobRunner.java` (FR-014), para que o caso resultante identifique a evidência como o perito espera. Ausente, o motor usa o nome do arquivo — comportamento válido, e a ausência precisa ser tratada como ausência, não como string vazia passada ao motor
- [X] T062 [US1] Implementar em `iped-mcp/src/main/java/iped/mcp/processing/CaseRootConfinement.java` a segunda metade de FR-010: recusar destino que **seja, ou esteja dentro de**, a pasta de um caso aberto por qualquer sessão, consultando o `CasePool`. A primeira metade (destino com caso concluído) é T046; esta é distinta e mais perigosa — escrever um caso por cima de outro que está sendo consultado agora corrompe as duas coisas ao mesmo tempo
- [X] T063 [P] [US1] Criar `iped-mcp/src/test/java/iped/mcp/integration/ConcurrentProcessingRefusalTest.java` para SC-013: 100% dos pedidos concorrentes recusados com o trabalho em curso identificado, e o pedido recusado **não** perturba o trabalho que corre

**Checkpoint**: T011 passa inteira. **US1 está completa e implantável sem nenhuma linha de US2 ou US3.**

---

## Phase 4: User Story 2 - Acompanhar, sobreviver à sessão e interromper (Priority: P2)

**Goal**: o trabalho longo é observável, sobrevive à sessão, morre com o servidor e é retomável.

**Independent Test**: Cenários 3, 4, 5 e 6 do [quickstart.md](./quickstart.md).

### Tests for User Story 2

- [ ] T024 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/JobSurvivesSessionTest.java` para SC-005 e SC-006: encerrar o harness fechando a entrada padrão, esperar, reabrir sessão e consultar pelo `job_id`. **Comparar avanço, não só estado** — um trabalho congelado também responde `RUNNING`, e um teste que só confira o estado passaria com o processamento parado
- [X] T025 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/CancelJobTest.java` para SC-014: cancelar de uma **sessão diferente** da que iniciou (FR-023), concluir em menos de 60 s, e **verificar que nenhum processo da árvore sobreviveu**. O último passo é o cerne: um cancelamento que mate só o filho passa nos anteriores e deixa o motor lendo evidência
- [X] T026 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/OrphanReconciliationTest.java` para FR-024: matar o servidor **abruptamente**, subir de novo, e obter `INTERRUPTED` — nunca `RUNNING`, nunca `UNKNOWN_JOB` — com nenhum órfão vivo. Abrupto é o ponto: o encerramento ordenado exercita o gancho de desligamento, que é o caminho fácil
- [X] T027 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/ResumeJobTest.java` para SC-017: retomada mantém o `job_id` e **não reprocessa** o que já foi processado. Medir por tempo ou contagem contra um processamento do zero — `COMPLETED` sozinho não prova que nada foi refeito
- [X] T028 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/IncompleteCaseNotOpenableTest.java` para SC-009: destino de trabalho falho, cancelado e interrompido **nunca** abre como caso completo. O mecanismo já existe (`CASE_INCOMPLETE`, `CASE_IN_PROCESSING`); o teste fixa os três desfechos
- [ ] T071 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/OutcomeClassificationTest.java` para SC-022 e SC-024: evidência sem conteúdo recuperável termina `COMPLETED` com zero itens, **nunca** `FAILED`; e origem removida no meio produz `SOURCE_INACCESSIBLE`, distinto de `SOURCE_UNREADABLE` sobre origem presente e corrompida
- [X] T029 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/unit/ProgressReaderTest.java` cobrindo as **duas fontes** de FR-020, com amostras em mais de um locale: **contadores** lidos por forma numérica (`n/m`, `(p%)`, `GB/h`), que devem sair idênticos em qualquer locale; e **fase** lida do texto da mensagem sob locale declarado. Um teste que só confira contadores passa com a fase nunca reportada — que é metade do requisito
- [X] T065 [P] [US2] Acrescentar a `iped-mcp/src/test/java/iped/mcp/unit/ProgressReaderTest.java` a fase **sem número nenhum**: amostras de descoberta, commit e otimização, que `Manager` publica apenas como eventos `"mensagem"`. Nessa janela — minutos, num caso grande — a fase é a **única** resposta a "está vivo?", e é onde um analisador ancorado só em números fica mudo
- [X] T030 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/ProcessingLogTest.java` para SC-018: falha real é diagnosticada pelo `diagnostic_excerpt` **sem acessar a máquina do servidor**; `log_path` existe e contém o log completo; e **nenhum byte de log alcança o canal do protocolo**. A última verificação é a invariante da 006 aplicada a uma fonte nova, e a falha correspondente não é ruidosa — parece defeito de protocolo do servidor

### Implementation for User Story 2

- [X] T031 [US2] Criar `iped-mcp/src/main/java/iped/mcp/processing/ProgressReader.java`: consome o fluxo padrão do filho por tubo e alimenta três destinos de uma vez — `JobProgress` (FR-020), `<auditoria>/jobs/<id>/processing.log` (FR-042) e o trecho diagnóstico (FR-043). Implementar **duas leituras distintas**, conforme [contracts/job-lifecycle.md](./contracts/job-lifecycle.md): contadores por forma numérica, e **fase pelo texto da mensagem sob o locale declarado** — os eventos `"mensagem"` são registrados verbatim, em prosa localizada, sem âncora numérica. O locale declarado não é salvaguarda secundária: é o que torna a fase legível. Charset **explícito**. O fluxo capturado **nunca** é repassado à saída padrão do servidor (depende de T015)
- [X] T069 [US2] Implementar em `iped-mcp/src/main/java/iped/mcp/processing/ProgressReader.java` a distinção parado/lento de FR-047: silêncio do fluxo acima de `processingStallThresholdSeconds` marca `stalled`, e a **fase corrente vai declarada junto** — sem ela a distinção não serve, porque minutos de silêncio durante consolidação de índice são normais e os mesmos minutos durante processamento de itens não são (depende de T031)
- [X] T070 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/unit/StallDetectionTest.java` para SC-023: fluxo silencioso além do limiar marca `stalled`; fluxo lento mas vivo **não** marca; e a fase acompanha a marcação nos dois casos
- [X] T032 [US2] Implementar em `ProgressReader.java` a disciplina de ausência: percentual **ausente** enquanto a descoberta não terminou, `measurable=false` quando a fase não produz medida, estimativa ausente quando não determinável (FR-020). Percentual zero num campo que deveria estar ausente é a forma mais fácil de mentir aqui
- [X] T033 [US2] Implementar `iped_job_status` em `ProcessingTools.java`, funcionando em **qualquer** sessão (FR-022), com `UNKNOWN_JOB` significando "nunca existiu nesta instalação" (FR-045)
- [X] T034 [US2] Declarar `diagnostic_excerpt` como conteúdo derivado de evidência em `iped-mcp/src/main/java/iped/mcp/protocol/ToolDescriptor.java` (campo `returnsContent`), aplicado ao descritor de `iped_job_status` em `iped-mcp/src/main/java/iped/mcp/tools/ProcessingTools.java` (FR-043), para que a política de egresso o governe **pela fronteira que já existe**. Um caminho próprio faria o log de processamento — que carrega nomes e caminhos de itens da evidência — escapar da política que governa toda ferramenta que devolve conteúdo
- [X] T035 [US2] Implementar `iped_cancel_job` em `ProcessingTools.java` destruindo a **árvore**: `ProcessHandle.descendants()` antes do filho, destruição normal, espera limitada, forçada se preciso. `Bootstrap` gera um neto e quem lê evidência é o neto ([research.md](./research.md) R6). Registrar `cancelled_by` — qualquer sessão autorizada cancela, e o registro é a defesa
- [X] T036 [US2] Criar `iped-mcp/src/main/java/iped/mcp/processing/OrphanReconciler.java`: na inicialização, para cada trabalho em `RUNNING`, consultar `ProcessHandle.of(pid)` e comparar `info().startInstant()` com o registrado. Só o identificador não basta — o sistema operacional o reaproveita, e destruir processo alheio por causa disso seria pior que o defeito. Órfão nosso é **destruído**, não adotado: adotar contradiria FR-024
- [X] T037 [US2] Acrescentar a `iped-mcp/src/main/java/iped/mcp/McpServerMain.java` o gancho de desligamento que destrói a árvore e grava `INTERRUPTED` (FR-024, caminho ordenado)
- [X] T038 [US2] Implementar `iped_resume_job` em `ProcessingTools.java` com `--continue`, mantendo o **mesmo `job_id`** (FR-030). Dois identificadores para a mesma evidência partiriam a história que FR-034 exige reconstituir. Pré-condição `INTERRUPTED`, ou `FAILED` com `resumable: true`; demais estados recusam com `JOB_NOT_RESUMABLE`
- [ ] T039 [US2] Implementar em `JobStore.java` o registro do desfecho **mesmo quando a sessão que pediu já terminou** (FR-033), e o vínculo sessão↔trabalho em `iped-mcp/src/main/java/iped/mcp/audit/SessionManifest.java`
- [ ] T064 [P] [US2] Criar `iped-mcp/src/test/java/iped/mcp/integration/ResponsivenessDuringJobTest.java` para FR-025: com um trabalho correndo, consultas sobre um caso já aberto continuam respondendo dentro das metas de 001, ou o servidor **declara a degradação**. O que ele não pode é parecer travado — um servidor que só volta a responder quando o processamento acaba é indistinguível, para quem espera, de um servidor morto

**Checkpoint**: T024 a T030 passam. Trabalho longo é observável, sobrevive, morre com o servidor e retoma.

---

## Phase 5: User Story 3 - Confinar o que se lê, onde nasce o caso, e mostrar isso ao perito (Priority: P3)

**Goal**: recusa que se explica, casos difíceis de caminho resolvidos, postura consultável.

**Independent Test**: Cenários 0, 2 e 8 do [quickstart.md](./quickstart.md).

### Tests for User Story 3

- [X] T040 [P] [US3] Criar `iped-mcp/src/test/java/iped/mcp/unit/ProcessingConfinementTest.java` para SC-003 e SC-004, com a linha que decide tudo: origem cujo caminho **textual** está dentro da área declarada mas que **resolve** para fora. No Windows, montar com **junção de diretório** — é o mecanismo que `getCanonicalPath()` não atravessa e que a 006 mediu. Verificar também: nenhum byte da origem lido na recusa, e nenhum arquivo ou pasta criados no destino. Incluir a sobreposição de listas: um destino sob raiz que é **ao mesmo tempo** raiz de caso e raiz de exportação é julgado pela regra de caso, não pela de artefato — as duas listas podem coincidir no sistema de arquivos sem que as regras se misturem
- [X] T041 [P] [US3] Acrescentar a `iped-mcp/src/test/java/iped/mcp/unit/ProcessingConfinementTest.java` a distinção que FR-039 exige: área declarada e **ausente** produz `AREA_UNAVAILABLE`, resposta distinta de origem não permitida. Fundir as duas faria o perito procurar erro de configuração quando só falta montar um disco
- [X] T042 [P] [US3] Acrescentar a `iped-mcp/src/test/java/iped/mcp/unit/ProcessingConfinementTest.java` a distinção de FR-040: destino com caso concluído produz `APPEND_NOT_SUPPORTED`, **não** `DESTINATION_HAS_CASE`. Confundir os dois faz uma decisão de escopo deliberada se ler como defeito
- [X] T043 [P] [US3] Criar `iped-mcp/src/test/java/iped/mcp/contract/ProcessingPostureTest.java` para FR-004 e SC-016: em cada configuração possível, a postura declarada bate com o arquivo e com a disponibilidade real de cada área; e a postura vigente é recuperável da trilha para 100% dos trabalhos
- [ ] T044 [P] [US3] Criar `iped-mcp/src/test/java/iped/mcp/integration/ProcessingRefusalAuditTest.java`: **toda** recusa desta feature consta da trilha com o que foi pedido e a regra aplicada (FR-008, cenário 8 da US3)

### Implementation for User Story 3

- [X] T045 [P] [US3] Implementar o veredito `AREA_UNAVAILABLE` em `SourceConfinement.java` e a resolução tardia de FR-039: áreas resolvidas **no momento do pedido**, para que volume montado depois do início do servidor funcione sem reiniciá-lo (depende de T006)
- [X] T046 [P] [US3] Implementar `APPEND_NOT_SUPPORTED` em `CaseRootConfinement.java`, distinto de `DESTINATION_HAS_CASE` (FR-040) (depende de T007)
- [X] T047 [US3] Fazer cada recusa de `ProcessingTools.java` **nomear a origem pedida e as áreas permitidas** (FR-008), no padrão que `ExportTools` já usa para destino recusado
- [X] T048 [P] [US3] Estender `iped-mcp/src/main/java/iped/mcp/tools/SessionTools.java` para declarar a postura de processamento em `iped_session_info` (FR-004): habilitação, áreas com disponibilidade corrente, raízes, perfis, trabalho ativo
- [X] T049 [P] [US3] Estender a advertência de abertura em `iped-mcp/src/main/java/iped/mcp/session/Session.java` para declarar que o processamento está habilitado (FR-005), junto do que FR-043 de 001 e a advertência de transporte da 006 já exigem
- [ ] T050 [US3] Registrar a postura vigente na abertura de sessão em `SessionManifest.java` (FR-038). É o que compensa a autorização por configuração: a concessão é anterior ao pedido e não deixaria rastro próprio, e sem isso não se saberia depois sob qual autorização um trabalho foi aceito
- [X] T051 [US3] Enriquecer o diagnóstico de `iped-mcp/src/main/java/iped/mcp/session/CaseValidator.java`: a recusa por `CASE_INCOMPLETE`/`CASE_IN_PROCESSING` passa a **nomear o trabalho conhecido** para aquele destino, seu desfecho e se a retomada é possível, em vez de dizer apenas "reprocesse o caso". O mecanismo de recusa já existe ([research.md](./research.md) R4); o que falta é precisão

**Checkpoint**: T040 a T044 passam. Confinamento explicável e postura auditável de dentro.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T052 [P] Acrescentar as nove chaves a `iped-app/resources/config/conf/McpServerConfig.txt` com os mesmos valores dos fallbacks de código e os comentários de [contracts/config-surface.md](./contracts/config-surface.md), incluindo por que `blind` e `pedo` **não** estão nos perfis padrão
- [ ] T053 [P] Estender a seção "Paths belong to the server" de `iped-mcp/src/main/resources/skill/SKILL.md` à classe nova de caminhos — evidência e destino (FR-037). A falha a evitar é a mesma que a 006 diagnosticou e é silenciosa: o agente lê um caminho do Windows, percebe que está em Linux, conclui que a evidência sumiu e procura no sistema de arquivos do próprio ambiente isolado, sem produzir erro nenhum
- [ ] T054 [P] Criar `iped-mcp/src/test/java/iped/mcp/integration/ProcessingReconstitutionTest.java` para SC-010 e FR-034: reconstituir, **só a partir dos registros**, quem pediu o quê, de onde, com qual perfil, sob qual postura e com qual desfecho — inclusive para trabalho que atravessa fronteira de sessão. Se a reconstituição exigir conhecimento fora dos arquivos, a divisão em três fontes de R3 está errada
- [X] T055 [P] Criar `iped-mcp/src/test/java/iped/mcp/unit/JobStoreTest.java` para SC-020: registro recuperável após qualquer número de reinícios; nenhum descarte por decurso de prazo
- [ ] T056 [P] Criar `iped-mcp/src/test/java/iped/mcp/contract/ProcessingToolSchemaTest.java`: as quatro ferramentas seguem os contratos do módulo — referência a caso sempre carrega o caso, classe de conteúdo declarada, esquemas estáveis
- [ ] T057 Atualizar `iped-mcp/CLAUDE.md`: seção de invariantes ganha as de processamento (motor fora do processo, cancelamento destrói a árvore, órfão é destruído, log nunca no canal do protocolo, `AuditRecord` intocado), e a de limitações conhecidas ganha a adoção de órfão como evolução prevista com gatilho declarado. Registrar também, explicitamente, a leitura que o módulo já pratica sem nunca ter declarado: **diagnóstico dirigido ao agente não é "texto visível ao usuário"** do Princípio V, e por isso vive em inglês junto do resto da superfície — o que é localizado é texto de interface do IPED, em `iped-app/resources/localization/`. Deixar a interpretação tácita a faz parecer descuido a cada revisão
- [ ] T058 Registrar a exposição de FR-050 nas limitações conhecidas de `iped-mcp/CLAUDE.md` e nos guias de instalação em `iped-mcp/src/main/resources/skill/install/`: a senha de contêiner cifrado vai ao motor por linha de comando e fica legível a outras contas da mesma máquina enquanto o processo existe. Dizer também **quando isso importa** — máquina de evidência compartilhada por mais de uma conta — e o que fazer nesse caso, porque limitação sem critério de aplicação vira ruído que o leitor aprende a pular
- [ ] T059 Rodar o roteiro completo do [quickstart.md](./quickstart.md), cenários 0 a 8, contra instalação real com evidência de referência e evidência longa, e registrar as medições de SC-002, SC-007 e SC-014 — pass/fail sozinho não mostra a corrida se aproximando do teto, que é a razão de `ScalePerformanceTest` imprimir números
- [ ] T060 `mvn -pl iped-mcp -am install` e `mvn -pl iped-mcp test`. Nenhum outro módulo precisa ser **compilado** por esta feature; para ver a configuração de T052 no release é preciso reempacotar o `iped-app`, o que é passo de empacotamento e não de verificação

---

## Dependencies & Execution Order

### Phase Dependencies

```
Setup (T001-T002)
   └─► Foundational (T003-T010)   ⚠️ bloqueia tudo
          ├─► US1 (T011-T023)  🎯 MVP
          ├─► US2 (T024-T039)   depende de US1 (JobRunner precisa existir para ser observado)
          └─► US3 (T040-T051)   independente de US2
                 └─► Polish (T052-T060)
```

**US3 não depende de US2.** As duas dependem de Foundational e de US1, mas entre si não há
dependência: confinamento e postura não tocam acompanhamento nem cancelamento.

### Within User Story 1

T015 → T016 → T017 → T018 encadeados no mesmo arquivo. T019 e T020 em paralelo. T021 depende de T020.
T022 e T023 dependem de T015.

### Within User Story 2

T031 → T032 no mesmo arquivo. T033 depende de T031. T035, T036 e T037 em paralelo entre si.
T038 depende de T036.

### Parallel Opportunities

| Bloco | Tarefas |
|---|---|
| Foundational | T004, T005, T006, T007, T008, T010 |
| Testes de US1 | T011, T012, T013, T014, T063 |
| Implementação de US1 | T019, T066 (mesmo arquivo, T066 depois de T019) |
| Testes de US2 | T024 a T030, T065, T070, T071 — dez suítes juntas |
| Testes de US3 | T040 a T044 |
| Implementação de US3 | T045, T046, T048, T049 |
| Polish | T052, T053, T054, T055, T056 |

## Parallel Example: User Story 2

```bash
# As sete suítes de US2, juntas — arquivos distintos, sem dependência entre elas:
# T024 JobSurvivesSessionTest, T025 CancelJobTest, T026 OrphanReconciliationTest,
# T027 ResumeJobTest, T028 IncompleteCaseNotOpenableTest, T029 ProgressReaderTest,
# T030 ProcessingLogTest
mvn -pl iped-mcp test -Diped.mcp.ipedRoot=<release> -Djvm=<release>/jre/bin/java.exe \
    -Diped.mcp.test.sourceEvidence=<evidência> -Diped.mcp.test.caseRoot=<raiz>
```

## Implementation Strategy

### MVP (US1 apenas)

Setup + Foundational + US1 = T001 a T023. Entrega o que a feature existe para entregar: uma evidência
declarada vira caso consultável sem ninguém tocar na máquina do servidor. **Serve para evidência que
caiba numa sessão** — o que exclui as grandes, e é exatamente o que US2 corrige.

### Entrega incremental

1. **US1** — processa e abre. Implantável.
2. **US2** — deixa de exigir que o perito fique sentado esperando. É aqui que a feature passa a servir
   para evidência de verdade.
3. **US3** — torna as duas anteriores explicáveis e auditáveis de dentro.
4. **Polish** — documentação, configuração distribuída e a corrida completa do quickstart.

### Estratégia com mais de uma pessoa

Depois do checkpoint de Foundational, US1 e US3 podem correr em paralelo entre duas pessoas: não
compartilham arquivo além de `ProcessingTools.java`, e nele tocam métodos distintos. US2 espera US1,
porque não há o que observar antes de existir o que observar.

## Notes

- **Nenhum código-fonte fora do `iped-mcp` é alterado.** O plano previa acrescentar `-passwordFile` ao `iped-app`
  porque `-p` põe a senha em `argv`; a decisão foi de proporção — a exposição só se realiza em máquina
  de evidência com mais de uma conta — e ela cedeu lugar a FR-050, que **declara** a limitação em vez
  de escondê-la. T021 e T058 são o par que implementa essa decisão: um declara no aceite, o outro
  documenta. Se algum dia ela for revista, o gatilho está escrito no plano.
- **Três tarefas de teste protegem defeitos que nenhum teste de requisição/resposta encontra**: T025
  (árvore de processos), T026 (órfão vivo) e T030 (log no canal do protocolo). As três vieram da
  leitura do código, não de raciocínio sobre a spec.
- **T061 a T071 têm número fora da ordem de leitura.** T061-T064 vieram de uma conferência de
  cobertura requisito a requisito, que achou quatro lacunas: FR-014 (nome de exibição), a segunda
  metade de FR-010 (destino dentro de caso aberto agora), FR-025 (servidor responsivo) e SC-013 (sem
  suíte própria). T065-T066 e T067-T071 vieram de `/speckit-analyze`: a fase sem âncora numérica, a
  medição do conjunto segmentado, e os três desfechos que a spec descrevia nos edge cases sem nenhuma
  tarefa atrás — zero itens, parado versus lento, e inacessível versus ilegível. Estão todas nas fases
  a que pertencem; os identificadores não foram renumerados para que nenhuma referência já feita a
  T001-T060 mude de alvo.
- **FR-003 não tem tarefa, e é assim que deve ser.** Ele diz que a autorização é por configuração e
  que **não** existe aprovação por pedido — requisito satisfeito por ausência. Está registrado aqui
  para que ninguém, mais adiante, "implemente o que falta" e reverta em silêncio a decisão tomada na
  clarificação.
- **`AuditRecord` não ganha campo em nenhuma tarefa.** Se alguma implementação parecer exigir isso, a
  resposta certa é `JobStore` ou `SessionManifest` — a divisão é imposta pela invariante do hash da
  trilha, não por organização.
