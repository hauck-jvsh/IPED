# Phase 0 — Research: criação de caso pelo servidor MCP

**Feature**: [007-mcp-case-processing](./spec.md) | **Date**: 2026-08-21

Oito decisões. Quatro foram **forçadas por leitura do código** e não tinham alternativa real depois
que o fato apareceu (R1, R4, R5, R6); as outras quatro são escolhas com alternativas defensáveis.
Cada uma registra o que foi decidido, por quê, e o que foi descartado.

---

## R1 — Como o servidor invoca o processamento

**Decision**: executar o `iped.jar` da instalação **como processo externo**, com a JVM do release.

**Rationale**: não há alternativa. `Bootstrap` e `iped.app.processing.Main` vivem em `iped-app`, e
`iped-app/pom.xml` **declara dependência de `iped-mcp`** — o módulo MCP é empacotado pelo app, e a
ordem em `pom.xml` confirma (`iped-mcp` antes de `iped-app`). Uma dependência de `iped-mcp` para
`iped-app` seria circular e o build a recusaria.

Descoberto o fato, ele deixa de ser obstáculo e vira o desenho certo. Um processo externo entrega,
sem mecanismo adicional, quatro coisas que a spec pede:

| Requisito | O que o processo externo dá |
|---|---|
| FR-023 cancelamento | identificador de processo do sistema operacional |
| FR-029 desfecho de falha | código de saída — `Bootstrap` propaga o do filho por `System.exit(exit)` |
| FR-025 servidor responsivo | isolamento de memória; o motor toma dezenas de gigabytes sem afetar o servidor |
| FR-024 trabalho morre com o servidor | é filho; destruí-lo no encerramento é uma chamada |

É também padrão estabelecido no projeto: `SleuthkitClient`, `ParsingProcess` e o próprio `Bootstrap`
já executam trabalho pesado fora do processo. Não estamos introduzindo uma técnica, estamos usando a
que a árvore já usa.

O servidor já sabe onde a instalação está: `Diagnostics.resolveIpedRoot()` existe e é usado por
`McpServerMain.main` para carregar a configuração do motor.

**Alternatives considered**:
- **Dirigir `Manager` de dentro do servidor.** `iped-mcp` depende de `iped-engine`, então `Manager`,
  `Statistics` e `Worker` são alcançáveis. Rejeitado por três razões independentes, cada uma
  suficiente: reimplementaria a orquestração que `iped.app.processing.Main` já faz, duplicando lógica
  que precisa continuar igual à da CLI; poria o orçamento de memória do motor dentro do servidor,
  contra FR-025; e faria o caso produzido percorrer um caminho de código diferente do da CLI, contra
  o Princípio II — que é exatamente o que FR-027 e SC-008 existem para impedir.
- **Mover `Bootstrap` para um módulo compartilhado.** Rejeitado: mudança estrutural de grande alcance
  para resolver um problema que o processo externo já resolve melhor.

---

## R2 — Como o progresso é observado de fora do processo

**Decision**: capturar o **fluxo padrão do processo filho** por tubo, executando com `--nogui
--nologfile`, e derivar dele o progresso, o log do trabalho e o trecho diagnóstico.

**Rationale**: `Statistics.get().getProcessed()` é a fonte de progresso do IPED e é **de processo**;
de fora não há como lê-la. `ProgressConsole` é quem a transforma em texto, e `--nologfile` redireciona
esse texto para a saída padrão do filho — que, sob `ProcessBuilder`, é um tubo que o servidor detém.

Isso resolve três requisitos com um mecanismo, o que é a razão principal de preferi-lo a ler o arquivo
de log do caso:

- **FR-020 progresso**: a linha traz `processados/total`, percentual e taxa.
- **FR-042 log em arquivo do lado do servidor**: o que é capturado é gravado em
  `<auditoria>/jobs/<id>/processing.log`, e esse é o caminho declarado no desfecho.
- **FR-043 trecho diagnóstico**: o fim do mesmo arquivo é o trecho que acompanha a falha.

**O que o código impõe: são duas fontes, não uma.** FR-020 pede duas coisas diferentes, e elas chegam
por caminhos diferentes do mesmo fluxo.

| O que FR-020 pede | De onde vem | O que a torna legível |
|---|---|---|
| Contadores — processados/total, percentual, taxa | `ProgressConsole.update()`, que concatena números em torno de prefixos localizados | As **formas numéricas**: `n/m`, `(p%)`, `GB/h` são estáveis em qualquer locale |
| **Fase corrente** | Eventos `"mensagem"`, que `ProgressConsole.propertyChange` registra **verbatim** (`LOGGER.log(MSG, (String) evt.getNewValue())`) | Nada — é prosa localizada pura, **sem nenhuma âncora numérica** |

A consequência decide o desenho e é fácil de errar: **o locale declarado não é salvaguarda
secundária, é o que torna a fase legível.** Um analisador ancorado só em números lê os contadores
perfeitamente e **nunca sabe nomear a fase** — e a fase é metade do que FR-020 exige. Ancorar nas
formas numéricas protege os contadores contra troca de locale; declarar o locale é o que permite ler
a fase.

Isso importa mais justamente quando o progresso importa mais. `Manager` dispara `"update"` em laço de
**um segundo** durante o processamento de itens, então ali os contadores são abundantes. Mas antes e
depois desse laço — descoberta, commit, otimização, que num caso grande levam minutos — **não há
número nenhum**, só eventos `"mensagem"`. É exatamente a janela em que o perito mais pergunta "está
vivo?", e é a janela em que só a fase responde.

Herdar o locale da máquina, aqui, seria o que o Princípio V proíbe — e teria o efeito concreto de
fazer a fase deixar de ser reportada numa instalação em português.

**Alternatives considered**:
- **Ler o arquivo de log do caso** (`-log <path>` e acompanhar o arquivo). Rejeitado: mesma
  fragilidade de texto localizado, mais latência de descarga em disco, e um arquivo a mais para
  gerenciar. O tubo dá o mesmo conteúdo em tempo real.
- **Contar documentos no índice Lucene em construção.** Rejeitado: o índice fica em pasta temporária
  durante o processamento — é justamente o que `CaseValidator` usa para detectar `CASE_IN_PROCESSING`
  — e o número não corresponde ao avanço percebido, porque itens são indexados em lote.
- **Acrescentar uma saída estruturada de progresso ao `iped-app`.** Seria melhor de consumir, mas é
  mudança em módulo alheio para resolver o que o parsing ancorado em números já resolve. Fica
  registrado como evolução, com gatilho: se o formato de `ProgressConsole` mudar e quebrar a leitura,
  a saída estruturada passa a valer o custo.

---

## R3 — Onde vive o estado do trabalho

**Decision**: `JobStore`, arquivo por trabalho em `<área de auditoria>/jobs/<id>/`, gravado a cada
transição de estado; retenção indefinida.

**Rationale**: FR-041 exige estado fora da memória do processo, FR-045 exige retenção indefinida, e
FR-022 exige que uma sessão posterior encontre o trabalho. Um arquivo por trabalho satisfaz os três
sem índice a manter, e a área de auditoria é o lugar natural porque um registro de trabalho **é** fato
pericial: diz que uma evidência foi lida, com qual perfil, por quem, com qual desfecho.

**A restrição que decide o formato**: a documentação do módulo é explícita — *"`AuditRecord`: **Não
acrescente campo.** `AuditTrail.verify` recompõe `toNodeWithoutHash` a partir do que lê, então um
campo a mais muda o resultado para registros já emitidos."* O estado de trabalho **não pode** entrar
no `AuditRecord`. Daí a divisão:

| Fato | Onde |
|---|---|
| Pedido registrado antes de qualquer leitura (FR-032) | `AuditTrail`, como `STARTED` de chamada de ferramenta — forma que já existe |
| Estado e avanço do trabalho | `JobStore` |
| Desfecho quando a sessão já terminou (FR-033) | `JobStore` |
| Vínculo sessão ↔ trabalho e postura vigente (FR-038) | `SessionManifest`, onde a 006 já pôs transporte e origem |

FR-034 — reconstituição — é satisfeito pela junção dos três, e é essa junção que
[quickstart.md](./quickstart.md) exercita.

**Alternatives considered**:
- **Acrescentar campos ao `AuditRecord`.** Rejeitado pela invariante citada: invalidaria a verificação
  de trilhas já emitidas. Foi exatamente por isso que a 006 pôs transporte e origem no manifesto.
- **Estado apenas em memória, reconstruído do log.** Rejeitado: violaria FR-041 e tornaria FR-024
  indecidível após reinício.

---

## R4 — Como um caso incompleto é impedido de abrir

**Decision**: nada a construir. `CaseValidator` já recusa, com dois códigos distintos.

**Rationale**: leitura do código mostrou que FR-028 está em boa parte satisfeito antes de começarmos.
`CaseValidator.validate` já lança:

- `CASE_IN_PROCESSING` quando o índice ainda está na pasta temporária — o estado exato de um caso
  sendo processado agora;
- `CASE_INCOMPLETE` quando falta `iped/index`, `iped/data` ou `iped/lib`, e quando o índice não tem
  ponto de commit (`findSegmentsFile(indexDir) == null`) — o estado de um caso cujo processamento
  morreu no meio.

O que falta é **precisão de diagnóstico**, não mecanismo: hoje a mensagem diz "reprocesse o caso",
sem saber que existe um trabalho conhecido para aquele destino. Com `JobStore`, a recusa passa a poder
nomear o trabalho, seu desfecho e se a retomada é possível.

O motor também mantém `iped/data/evidences_processing_status` (`EvidenceStatus`), com um mapa
evidência→sucesso, e `getFailedEvidences()` distingue "nunca processado" (`null`) de "processado sem
falhas" (lista vazia). É a fonte certa para o desfecho por evidência.

**Alternatives considered**:
- **Marcador próprio do MCP na pasta do caso.** Rejeitado: escreveria na pasta do caso o que o motor
  já registra, e duplicaria uma verdade que pode divergir.

---

## R5 — Como a senha alcança o motor

**Decision**: acrescentar `-passwordFile` ao `iped-app`, e o servidor resolve a referência de segredo
para um arquivo legível apenas pela conta que o executa.

**Rationale**: a busca por configuração de senha no motor não achou nenhuma — não existe
`PasswordsConfig`, não existe arquivo em `conf/`. O único caminho é `-p` em `CmdLineArgsImpl`, e
`DataSourceReader` o consome de `getPasswords()`.

Linha de comando é legível por outros processos: no Linux `/proc/PID/cmdline` é legível por qualquer
usuário da máquina. FR-015 nomeia quatro lugares onde a senha não pode aparecer — pedido, resposta,
trilha, log — e a tabela de processos não é nenhum deles. Usar `-p` cumpriria a letra e falharia o
motivo, numa instalação (a de topologia dividida) que pode ter mais de uma conta na máquina da
evidência.

O padrão a seguir já existe no módulo: `sharedSecretFile` da 006, onde a configuração diz **onde** o
segredo está, nunca **qual** é. `-passwordFile` é a mesma forma, um nível abaixo.

**Alternatives considered**: registrados na tabela Complexity Tracking do [plan.md](./plan.md) —
usar `-p` e documentar; não suportar contêiner cifrado; variável de ambiente. Os três foram rejeitados
lá, com o motivo de cada um.

---

## R6 — O que exatamente é cancelado

**Decision**: destruir a **árvore** de processos — `ProcessHandle.descendants()` antes do filho —, com
destruição normal seguida de forçada após espera limitada.

**Rationale**: `Bootstrap` não processa; ele monta o classpath e **gera outra JVM**
(`ProcessBuilder` na linha 176, `process.waitFor()` na 196) que executa `iped.app.processing.Main`.
Quem lê evidência é o neto. Matar só o filho deixaria o motor rodando, lendo evidência, escrevendo no
destino — depois de o servidor ter declarado o trabalho encerrado.

Isso eleva o cancelamento de requisito de tempo de resposta a requisito de **Princípio I**: um
cancelamento parcial deixa evidência sendo lida por um processo que ninguém está mais observando.
SC-014 mede os 60 s; a verificação que importa igualmente é que a árvore inteira morreu.

`ProcessHandle.descendants()` é Java 9+ e está em `java.base`, então não custa dependência.

**Alternatives considered**:
- **Destruir só o filho.** Rejeitado pelo motivo acima.
- **Sinal cooperativo de parada ao motor.** Rejeitado: não existe superfície para isso na CLI, e
  construí-la seria mudança no motor — precisamente o que o Princípio III manda evitar.

---

## R7 — Como a interrupção é determinada na volta do servidor

**Decision**: `JobStore` registra identificador de processo **e instante de início**; na volta, o
servidor consulta `ProcessHandle.of(pid)` e compara `info().startInstant()`. Se um processo vivo casar,
ele é **destruído** e o trabalho marcado interrompido.

**Rationale**: FR-024 diz que o trabalho termina com o servidor. Encerramento ordenado cumpre isso com
um gancho de desligamento. Encerramento abrupto — queda de energia, `kill -9` — não: o filho
sobrevive à morte do pai nos dois sistemas operacionais. Sem reconciliação, o servidor voltaria e veria
estado "em andamento" para um processo que pode estar vivo ou morto, e FR-024 proíbe as duas respostas
erradas ("ainda em andamento" e "inexistente").

Comparar apenas o identificador de processo é inseguro: o sistema operacional reaproveita
identificadores, e um processo qualquer poderia ocupar o número. `startInstant()` desempata — um
processo com o mesmo identificador mas início posterior ao registrado não é o nosso.

Encontrado o órfão vivo, a resposta certa é **destruí-lo**, não adotá-lo: adotar contradiria FR-024, e
deixá-lo correr deixaria evidência sendo lida sem observador — o mesmo defeito de R6.

**Alternatives considered**:
- **Adotar o órfão e reatar o acompanhamento.** É a opção B da clarificação de escopo, recusada pelo
  perito na sessão de clarificação. Fica registrada como evolução prevista, com o gatilho declarado na
  spec: implantação em que reinício por manutenção durante processamentos longos se torne frequente.
- **Confiar só no identificador de processo.** Rejeitado pelo reaproveitamento de identificador.

---

## R8 — Como as raízes de caso se relacionam com as raízes de exportação

**Decision**: chave de configuração separada (`processingCaseRoots`), verificada pelo `PathConfinement`
existente, **sem alterá-lo**.

**Rationale**: `PathConfinement.resolve(requested, roots, casePath, ...)` já recebe a lista de raízes
como parâmetro e já devolve vereditos que distinguem fora-de-raiz, dentro-do-caso e irresolvível. Ele
foi escrito genérico o bastante; passar outra lista é usá-lo como pretendido, não estendê-lo.

Separar as chaves é exigência de FR-009 e a razão está na ordem de grandeza: um artefato de exportação
tem megabytes, um caso tem centenas de gigabytes. Reaproveitar `exportRoots` permitiria em silêncio
que uma pasta de laudos recebesse um índice inteiro, e o perito que declarou aquela raiz estava
autorizando planilhas, não acervos.

As áreas de **leitura** (`processingSourceAreas`) usam a mesma resolução de caminho real, com uma
diferença registrada em FR-039: são resolvidas **no momento do pedido**, não na inicialização, para que
mídia montada depois do início do servidor funcione sem reiniciá-lo. Uma área declarada e ausente é
reportada como **indisponível**, resposta distinta de "origem não permitida" — confundir as duas faria
o perito procurar erro de configuração onde só falta montar um disco.

**Alternatives considered**:
- **Uma lista só para tudo.** Rejeitado por FR-009 e pelo argumento de ordem de grandeza.
- **Generalizar `PathConfinement` com um tipo de raiz.** Rejeitado: a classe já é parametrizada pela
  lista; um enum de tipo acrescentaria estado sem mudar comportamento, e a documentação do módulo
  marca essa classe como área sensível.

---

## Resumo das decisões

| # | Decisão | Natureza |
|---|---|---|
| R1 | Processamento em processo externo, executando `iped.jar` | Forçada pela direção da dependência Maven |
| R2 | Progresso, log e diagnóstico do fluxo padrão do filho, locale declarado | Escolha, com cuidado imposto pelo código |
| R3 | `JobStore` na área de auditoria; `AuditRecord` intocado | Forçada pela invariante do hash da trilha |
| R4 | Recusa de caso incompleto já existe; falta precisão de diagnóstico | Forçada pelo código existente |
| R5 | `-passwordFile` aditivo no `iped-app` | Forçada pela ausência de qualquer outro caminho |
| R6 | Cancelar destrói a árvore, não o filho | Forçada por `Bootstrap` gerar um neto |
| R7 | Reconciliação por identificador **e** instante de início; órfão é destruído | Escolha |
| R8 | Raízes de caso separadas das de exportação; `PathConfinement` reutilizado | Escolha |

Nenhum `NEEDS CLARIFICATION` permanece. Nenhuma dependência nova entra no release.
