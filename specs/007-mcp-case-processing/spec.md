# Feature Specification: Criação de caso pelo servidor MCP — processar a evidência

**Feature Branch**: `007-mcp-case-processing`

**Created**: 2026-08-21

**Status**: Draft — clarificado em 2026-08-21; sem marcadores pendentes

**Input**: User description: "Agora vamos integrar ao MCP a capacidade criar um caso utilizando o IPED. (processar a evidência)"

---

## Contexto e problema

A feature [001-iped-llm-integration](../001-iped-llm-integration/spec.md) entregou um servidor que **lê** casos. A [006-export-allowlist-socket-transport](../006-export-allowlist-socket-transport/spec.md) confinou o que ele **escreve** e permitiu colocá-lo do outro lado de uma fronteira imposta pelo sistema operacional. As duas partem do mesmo pressuposto, nunca escrito porque nunca foi questionado: **o caso já existe**. Alguém — o perito, na linha de comando ou na UI — apontou o IPED para a evidência, escolheu o perfil, esperou as horas e entregou ao agente uma pasta pronta.

Esse pressuposto cobra em dois lugares distintos.

**No tempo do perito.** A parte cara do exame não é a consulta; é o processamento. É onde ficam as horas de espera e, sobretudo, onde ficam os erros que só aparecem no fim: perfil inadequado ao material, evidência apontada pela cópia em vez do original, senha de contêiner esquecida, disco de saída que enche na décima hora. O agente entra em cena depois que tudo isso já passou — a etapa do exame em que ele mais poderia reduzir retrabalho é a única que ele não alcança.

**Na topologia dividida.** Esta é a parte que a 006 tornou aguda sem pretender. O valor daquela feature é que o agente não tem caminho nenhum até a evidência que não passe por uma ferramenta MCP. Para consulta, é exatamente o que se quis. Para processamento, é uma parede: criar caso significa ler o disco de evidência, e ler o disco de evidência é precisamente o que a fronteira impede. O perito precisa sair do ambiente isolado, ir até a máquina da evidência, executar a linha de comando à mão e voltar. **Quanto melhor o isolamento funciona, mais cara fica essa ida e volta** — e ela ocorre uma vez por evidência, não uma vez por caso.

### O que muda de natureza nesta feature

Três coisas deixam de valer, e nenhuma delas é detalhe de implementação.

**1. O servidor passa a ler caminhos que o perito não escolheu um a um.** A 006 confinou a escrita porque escrita fora de lugar é dano imediato. Ninguém precisou confinar a leitura, porque o único caminho que o servidor lia era o do caso que o perito nomeou — e um caso é, por definição, o material que alguém já decidiu expor.

Processar quebra essa simetria. **Um processamento converte qualquer caminho legível pela conta que executa o servidor em conteúdo indexado, consultável e exportável pela superfície que já existe.** Apontar o processador para a pasta de documentos do próprio servidor produz um caso com aquela pasta dentro, e a partir daí a busca e a exportação a servem como serviriam qualquer evidência. A lista de permissão de escrita da 006 não vê problema algum nisso: o artefato nasce dentro de uma raiz permitida. O que veio de lugar indevido não foi o arquivo — foi o **conteúdo**.

**2. A unidade de trabalho deixa de caber numa chamada.** Toda ferramenta existente responde em segundos e é atômica: pergunta, resposta, fim. Processamento leva de minutos a dias, e o modelo de sessão da 006 amarra a posse de um caso a uma conexão viva, soltando-a quando a conexão cai. Um processamento que morresse porque o harness saiu seria pior do que inútil — queimaria horas e deixaria uma pasta de caso pela metade. E fechar a entrada padrão é como todo harness sinaliza encerramento (FR-035 de 006): a queda da sessão não é caso de borda, é o caminho normal de saída.

**3. O servidor deixa de ser um consumidor barato da máquina.** Servir consultas custa uma fração de núcleo e memória medida em página. Processar toma a máquina inteira — todos os núcleos, orçamento de memória dimensionado em dezenas de gigabytes, disco em regime sustentado por horas. Dois processamentos simultâneos terminam mais tarde do que os mesmos dois em sequência, e degradam a consulta de quem estiver usando outro caso no mesmo servidor.

### O que esta feature não é

Ela **não** faz aquisição de evidência: o material já está no sistema de arquivos que o servidor alcança. Ela **não** altera o pipeline de processamento, não cria perfis novos e não expõe ajuste fino de tasks — isso continua sendo matéria de configuração (Princípio IV da constituição). Ela **não** constrói fila de trabalho entre máquinas nem serviço de processamento multiusuário: o alvo é a estação do perito e a máquina da evidência na topologia dividida. Ela **não** acrescenta evidência a caso já concluído — decisão registrada nas clarificações, com a fronteira materializada em FR-040. E ela **não** dimensiona hardware; a decisão sobre o que a máquina aguenta permanece com quem implanta.

---

## Relação com as specs anteriores

Esta feature **numera seus requisitos a partir de FR-001**. Requisitos anteriores são sempre citados com a origem explícita — "FR-068 de 001", "FR-001 de 006" — para que nenhuma referência fique ambígua.

Nenhum requisito anterior é removido ou relaxado. Quatro são estendidos, e a extensão está nomeada no requisito correspondente:

| Requisito anterior | Como esta feature o estende |
|---|---|
| **Modo de acesso somente-leitura por padrão** (001) | Processamento é uma **terceira classe** de operação, não uma variedade de curadoria. Habilitar curadoria não habilita processar. |
| **FR-001 de 006 — confinamento de escrita de artefato** | Um caso não é um artefato. Raízes de caso são declaradas **à parte** das raízes de exportação: um relatório tem megabytes, um caso tem centenas de gigabytes, e reaproveitar a mesma lista permitiria em silêncio que uma pasta de laudos recebesse um índice inteiro. |
| **FR-036 de 006 — caminhos pertencem ao servidor** | A lição vale agora para uma **classe nova de caminho**: o da evidência e o do destino do caso. Um agente que sai procurando a evidência no sistema de arquivos do próprio ambiente isolado repete a falha silenciosa que a 006 diagnosticou. |
| **FR-037 de 001 / FR-033 de 006 — reconstituição pela trilha** | A trilha precisa reconstituir um trabalho que **começa numa sessão e termina em outra**, ou fora de qualquer sessão. Nenhuma operação anterior tinha essa forma. |

Fica registrada também a leitura que sustenta a compatibilidade com o **Princípio I** da constituição: processar **lê** evidência e **escreve** um caso novo. A evidência original é aberta em somente-leitura e não é modificada em nenhum modo. O que esta feature acrescenta ao risco não é alteração de evidência — é alcance de leitura, e é disso que os requisitos de confinamento tratam.

---

## Clarifications

### Session 2026-08-21

- Q: Quem autoriza cada processamento — o agente inicia direto, ou cada pedido exige aprovação humana? → A: **Concessão por configuração.** O perito habilita a capacidade na configuração da instalação e, a partir daí, o agente inicia processamentos sem confirmação por pedido. É o mesmo modelo já vigente para a curadoria e é o que o Princípio IV da constituição pede: comportamento configurável vive em configuração. A consequência precisa ser dita, porque ela redistribui peso: **sem aprovação por pedido, o confinamento de origem e a postura declarada passam a carregar sozinhos a garantia** de que o processamento não alcança o que não deve. Duas coisas decorrem disso — a resposta da pergunta seguinte deixa de ser opcional, e a trilha precisa registrar **sob qual postura** cada trabalho foi autorizado, já que a autorização não deixa rastro próprio por ser anterior ao pedido. FR-003 resolvido; **FR-038** criado.
- Q: O servidor pode ler qualquer caminho para processar? → A: **Lista de permissão de áreas de leitura declaradas**, decidida sobre o caminho real resolvido no sistema de arquivos. Fecha por inteiro o vetor descrito no contexto — converter sistema de arquivos do servidor em índice consultável — que a lista de permissão de escrita da 006 não alcança, porque lá o artefato nasce dentro de raiz permitida e o problema está no conteúdo, não no arquivo. O custo é operacional e conhecido: mídia removível e compartilhamento de rede trocam de letra e de ponto de montagem entre exames. Esse custo vira requisito em vez de virar atrito: **as áreas declaradas são resolvidas no momento do pedido, não fixadas na inicialização**, para que um volume montado depois do início do servidor seja utilizável sem reiniciá-lo. FR-006 resolvido; **FR-039** criado.
- Q: (achado da análise de consistência) A senha só entra no IPED por `-p` na linha de comando, que no Linux é legível por qualquer conta da máquina. Vale mudar o `iped-app` para fechar isso? → A: **Não — usa-se `-p`, e a exposição passa a ser declarada.** A feature fica inteiramente dentro do `iped-mcp`, e o Constitution Check deixa de ter violação a justificar. O julgamento por trás é de proporção: a exposição só importa quando a máquina da evidência tem mais de uma conta, e a estação de perícia típica não tem — não vale alterar dois módulos e a interface `CmdLineArgs` por um risco que a implantação alvo não corre. O que **não** é aceitável é a exposição ficar tácita, e aqui vale o precedente da 006 sobre o canal sem proteção: a limitação vira **fato declarado ao perito**, não pressuposto silencioso. Quem implantar numa máquina compartilhada decide com a informação na mão. **FR-050**, **SC-025** criados; R5 reescrito; Complexity Tracking esvaziado.
- Q: (achado da análise de consistência) FR-044 dizia "espaço insuficiente" sem definir insuficiente, e a única quantificação vivia numa chave de configuração que a spec nunca autorizou. Como se calcula o mínimo? → A: **Percentual do tamanho da evidência de origem.** Margem em 50% e uma imagem E01 de 500 GB exigem 250 GB livres na unidade onde o caso vai ser gravado. É regra computável, explicável ao perito e ajustável por ele — em vez de uma estimativa interna que ninguém consegue conferir. Duas consequências não óbvias viraram requisito próprio: o tamanho precisa ser o do **conjunto**, porque imagem forense quase sempre vem segmentada e medir o primeiro `.E01` daria uma fração do real; e a medição precisa caber no orçamento de aceite de 5 s de FR-018, porque somar recursivamente uma pasta lógica em compartilhamento de rede não cabe — daí declarar indisponível em vez de atrasar. FR-044 reescrito; **FR-046**, **SC-021** criados.
- Q: Por quanto tempo o registro de um trabalho concluído continua recuperável? → A: **Indefinidamente, junto da área de auditoria.** Um registro de trabalho é um fato pericial — diz que uma evidência foi lida, com qual perfil, por quem e com qual desfecho —, e descartá-lo por decurso de prazo entraria em atrito com a cadeia de custódia que o módulo inteiro sustenta. O custo é desprezível: uma linha por trabalho, contra casos de centenas de gigabytes. Há um ganho secundário que decide o desempate: com retenção indefinida, **"desconhecido" volta a significar "nunca existiu aqui"**, em vez de ser ambíguo entre isso e "existiu e foi descartado" — distinção que importa para quem confere um laudo meses depois. **FR-045** criado; **SC-020** criado; edge case do identificador desconhecido explicitado.
- Q: O servidor verifica espaço em disco antes de aceitar, ou descobre falhando na décima hora? → A: **Verifica e adverte, mas nunca recusa por isso — a decisão de seguir fica com quem pede.** A estimativa de quanto um caso vai ocupar é ruim por natureza: um disco de vídeo já comprimido indexa pouco, um de documentos com exportação ligada indexa muito. Uma recusa apoiada nela bloquearia trabalho legítimo, e o perito é quem conhece o material. A advertência remove a surpresa sem tomar a decisão. A consequência precisa ficar dita em vez de suposta: **o tratamento reativo do disco cheio continua sendo peça de carga**, porque nada impede iniciar um trabalho que não vai caber — a promessa de FR-017 de falhar cedo vale para configuração, caminho e perfil, e não se estende a espaço. **FR-044** criado; **SC-019** criado; edge case correspondente reescrito.
- Q: O agente alcança a razão técnica de uma falha de processamento? → A: **Sim, por um trecho diagnóstico limitado no desfecho, tratado como conteúdo derivado de evidência.** O log do motor fica em arquivo do lado do servidor e o caminho dele vem sempre no desfecho; o trecho devolvido é limitado e atravessa a mesma fronteira de egresso que texto de item, não uma exceção nova. A razão de não ser tubulação livre de log é concreta: **o log de processamento carrega nomes e caminhos de itens da evidência**, e devolvê-lo em bruto faria conteúdo de evidência escapar da política que governa toda ferramenta que devolve conteúdo em 001. Fica também explicitada uma consequência da 006 que valeria de qualquer forma: o log do processamento não pode alcançar o canal do protocolo. **FR-042** e **FR-043** criados; **SC-018** criado.
- Q: Um processamento em andamento sobrevive ao encerramento do servidor? → A: **Não — o trabalho termina com o servidor e é retomado depois por FR-030.** A fronteira que importa fica explícita: o trabalho sobrevive ao fim da **sessão** (FR-021), não ao fim do **processo do servidor**. A perda não são as horas todas: o IPED consolida o que já processou, e a retomada aproveita esse trabalho, então o custo é o trecho não consolidado. A alternativa — processo destacado com identidade própria, reata de acompanhamento e cancelamento de processo que o servidor não possui — é um gerenciador de processos inteiro, mecanismo novo e grande contra o Princípio III, para cobrir um evento que não é rotineiro. Fica registrada como evolução possível, com gatilho declarado: a primeira implantação em que o servidor seja reiniciado por manutenção durante processamentos longos com frequência que doa. FR-024 explicitado; **FR-041** criado.
- Q: Uma sessão pode cancelar um trabalho que não foi ela quem iniciou? → A: **Sim — qualquer sessão autorizada cancela qualquer trabalho, e a trilha registra quem pediu.** A alternativa seria amarrar o cancelamento à posse, mas a identidade do cliente em sessão de rede é, por FR-032 de 006, uma **alegação explicitamente não verificada**: construir controle de autoridade sobre identidade que a própria trilha declara não conseguir provar seria segurança de fachada. A defesa real é o registro. E a regra oposta tem custo concreto: um trabalho iniciado por uma sessão que foi embora prenderia a máquina inteira até terminar. FR-023 estendido; edge case correspondente resolvido.
- Q: Até onde vai a mutação de caso nesta feature? → A: **Caso novo e retomada de trabalho interrompido; acréscimo de evidência a caso concluído fica fora.** A retomada não é refinamento: sem ela, um trabalho que cai na hora dezenove obriga a recomeçar do zero, e a feature não serviria justamente na evidência grande, que é a que motiva tudo isso. O acréscimo é outra coisa — mexe num caso que já foi tratado como contrato permanente sob o Princípio II e que outras sessões podem ter aberto, e por isso pede sua própria linha de trabalho. A fronteira precisa ser **visível**, não implícita: um pedido cujo destino é um caso concluído recebe recusa que diz que acréscimo não é suportado, distinta da recusa genérica de destino ocupado, para que ninguém a leia como defeito. FR-030 resolvido; **FR-040** criado.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Processar uma evidência declarada até um caso consultável (Priority: P1)

O perito declara, na configuração da instalação, onde a evidência pode ser lida e onde casos podem nascer. Trabalhando com o agente, ele pede o processamento de uma evidência nomeada, com um perfil nomeado, para um destino nomeado. O servidor aceita o pedido, devolve imediatamente um identificador de trabalho e começa a processar. Quando termina, o caso é aberto pela mesma ferramenta de sempre, na mesma sessão, sem que ninguém tenha tocado na máquina do servidor.

**Why this priority**: é a feature. Sem ela o perito continua saindo do ambiente isolado para executar a linha de comando à mão, que é exatamente o custo que esta linha de trabalho existe para remover. Entrega valor sozinha: uma evidência processada e consultável já justifica a implantação, mesmo antes de qualquer refinamento de acompanhamento ou diagnóstico.

**Independent Test**: com uma área de leitura e uma raiz de caso declaradas, pedir o processamento de uma evidência pequena de referência, aguardar a conclusão, abrir o caso resultante e comparar a contagem de itens com a contagem declarada no desfecho.

**Acceptance Scenarios**:

1. **Given** processamento habilitado, uma evidência dentro de área de leitura declarada e um destino dentro de raiz de caso declarada, **When** o agente pede o processamento com um perfil permitido, **Then** o pedido é aceito, um identificador de trabalho é devolvido sem esperar a conclusão, e a resposta declara que os caminhos são interpretados no sistema de arquivos do servidor.
2. **Given** um trabalho aceito, **When** o processamento conclui com sucesso, **Then** o desfecho declara o caminho do caso, a contagem de itens e a duração.
3. **Given** um caso recém-processado, **When** o agente o abre pela ferramenta existente de abertura de caso, **Then** ele abre sem nenhum passo manual na máquina do servidor, e a contagem obtida coincide com a do desfecho.
4. **Given** a evidência original, **When** o processamento termina por qualquer desfecho, **Then** a evidência permanece bit a bit idêntica ao que era antes do pedido.
5. **Given** processamento não habilitado na configuração, **When** o agente tenta pedir um processamento, **Then** a capacidade não está disponível e nada no sistema de arquivos é lido ou escrito.

---

### User Story 2 - Acompanhar, sobreviver à sessão e interromper (Priority: P2)

O processamento leva horas. O perito acompanha o avanço pelo agente, fecha o harness para almoçar, reabre depois e reencontra o mesmo trabalho no ponto em que ele está. Se percebeu que apontou para a evidência errada, interrompe — e o que ficou pela metade não passa por caso pronto para ninguém.

**Why this priority**: sem isto a P1 funciona apenas para evidências pequenas o bastante para caber numa sessão, que não são as que importam. Vem depois da P1 porque a P1 é demonstrável sozinha com material de referência, e porque a forma correta de acompanhar depende de o processamento existir primeiro.

**Independent Test**: iniciar um processamento longo o bastante para atravessar uma queda de sessão, encerrar o harness no meio, reabrir, localizar o trabalho pelo identificador, confirmar que avançou durante a ausência, e então cancelá-lo e verificar o desfecho registrado.

**Acceptance Scenarios**:

1. **Given** um trabalho em andamento, **When** o agente consulta o avanço, **Then** obtém a fase corrente, o que já foi processado e o tempo decorrido; quando a fase não produz medida de avanço, isso é **declarado**, nunca substituído por estimativa inventada.
2. **Given** um trabalho em andamento, **When** a sessão que o pediu termina — por encerramento do harness ou por queda da conexão de rede —, **Then** o processamento continua.
3. **Given** um trabalho iniciado por uma sessão anterior, **When** uma sessão nova pergunta por ele pelo identificador, **Then** obtém o avanço corrente ou o desfecho, com o mesmo detalhe que a sessão original teria obtido.
4. **Given** um trabalho em andamento, **When** o agente pede o cancelamento, **Then** o processamento termina, o desfecho "cancelado" fica registrado com quem pediu e quando, e a resposta diz o que restou no destino.
5. **Given** um destino cujo processamento não concluiu — por falha, cancelamento ou interrupção —, **When** alguém tenta abri-lo pela ferramenta de abertura de caso, **Then** ele nunca é apresentado como caso completo.
6. **Given** um trabalho em andamento, **When** o processo do servidor termina e é reiniciado, **Then** uma consulta pelo identificador reporta o trabalho como interrompido, com desfecho determinável — nunca como ainda em andamento e nunca como inexistente.
7. **Given** um trabalho em andamento, **When** um segundo pedido de processamento chega, **Then** é recusado com diagnóstico que identifica o trabalho em curso.
8. **Given** um trabalho interrompido por reinício do servidor, **When** o agente pede a retomada, **Then** o processamento prossegue aproveitando o que já havia sido processado, e o desfecho final declara que houve retomada.

---

### User Story 3 - Confinar o que se lê, onde nasce o caso, e mostrar isso ao perito (Priority: P3)

O perito quer saber, sem ler código nem inspecionar o sistema operacional, se esta instalação processa, de onde ela pode ler, onde ela pode escrever caso e quais perfis estão liberados. E quando um pedido é recusado, quer que a recusa diga o suficiente para o agente corrigir sozinho, sem tentativa e erro sobre o sistema de arquivos alheio.

**Why this priority**: o **portão** de confinamento não é opcional e não espera — ele é condição da P1 e aparece nos critérios de aceitação dela. O que esta história acrescenta é a camada que torna o portão utilizável e auditável: recusa que se explica, resolução correta dos casos difíceis de caminho, e postura consultável. Vem por último porque as duas primeiras entregam valor sem ela, e porque o custo cai depois que elas existem.

**Independent Test**: em cada configuração possível (processamento desabilitado, habilitado com áreas declaradas), consultar a postura do servidor e comparar com o arquivo de configuração; então pedir processamento de origens e destinos fora das áreas declaradas e verificar recusa, ausência de leitura e ausência de rastro.

**Acceptance Scenarios**:

1. **Given** qualquer configuração, **When** o perito consulta a postura da sessão, **Then** obtém se o processamento está habilitado, as áreas de leitura declaradas, as raízes de caso declaradas, os perfis permitidos e se há trabalho em andamento.
2. **Given** processamento habilitado, **When** a sessão abre, **Then** a advertência de abertura informa isso, junto do que FR-043 de 001 e a advertência de postura da 006 já exigem.
3. **Given** uma origem de evidência fora de toda área de leitura declarada, **When** o agente pede o processamento, **Then** é recusado antes de qualquer leitura da origem, e a recusa nomeia a origem pedida e as áreas permitidas.
4. **Given** uma origem cujo caminho textual está dentro de uma área declarada mas que resolve, no sistema de arquivos, para fora dela, **When** o agente pede o processamento, **Then** é recusado pela mesma regra e pelo mesmo diagnóstico.
5. **Given** um destino fora de toda raiz de caso declarada, **When** o agente pede o processamento, **Then** é recusado e nenhum arquivo e nenhuma pasta são criados no destino pedido.
6. **Given** um destino que já contém um caso concluído, **When** o agente pede o processamento, **Then** é recusado sem alterar nada do que lá está, e a recusa diz que acrescentar evidência a caso concluído não é suportado — não que o destino está apenas ocupado.
7. **Given** um perfil que não consta do conjunto permitido, **When** o agente o nomeia, **Then** o pedido é recusado e a recusa lista os perfis permitidos.
8. **Given** qualquer recusa acima, **When** ela ocorre, **Then** consta da trilha de auditoria com o que foi pedido e a regra aplicada.

---

### Edge Cases

- **O disco de saída enche na décima hora.** Caminho que continua vivo mesmo com a advertência de FR-044, porque a advertência não recusa: o trabalho precisa terminar com desfecho legível dizendo o que aconteceu e o que restou, em vez de morrer em silêncio deixando pasta pela metade.
- **A mídia da evidência some no meio** — disco removível desconectado, compartilhamento de rede que cai. O trabalho falha, e o desfecho distingue "evidência inacessível" de "evidência corrompida".
- **A evidência exige senha de contêiner cifrado.** O segredo precisa alcançar o processador sem transitar pela conversa, pela resposta ou pela trilha.
- **O destino do caso está dentro de uma raiz que também é raiz de exportação de artefato.** As duas listas podem se sobrepor; a regra aplicada a cada operação continua sendo a da sua própria classe.
- **O caso a ser criado tem o mesmo destino de um caso aberto agora por outra sessão.** Precisa ser recusado antes de tocar em qualquer coisa.
- **O processamento produz zero itens** — evidência vazia, ilegível ou de formato não suportado. Zero itens é desfecho legítimo e precisa ser distinguível de falha.
- **O avanço estaciona** num item muito grande ou numa fase sem medida. "Lento" e "travado" precisam ser distinguíveis por quem pergunta.
- **Alguém pergunta por um identificador de trabalho de outra instalação, ou inventado.** A resposta é "desconhecido", não erro obscuro nem invenção — e por FR-045 ela significa exatamente uma coisa: nunca existiu aqui.
- **A máquina reinicia no meio do processamento.** Ver cenário 6 da US2.
- **Uma segunda sessão pede o cancelamento de um trabalho que ela não iniciou.** É permitido por FR-023: não há posse a exigir. O registro nomeia quem pediu, e é ele que sustenta a explicação depois.

## Requirements *(mandatory)*

### Functional Requirements

#### Habilitação e postura

- **FR-001**: O sistema MUST manter a capacidade de processamento **desabilitada** na configuração padrão; habilitá-la MUST exigir declaração explícita na configuração da instalação, independente do modo de acesso de leitura e curadoria de 001.
- **FR-002**: Com a capacidade desabilitada, o sistema MUST recusar qualquer pedido de processamento antes de ler qualquer argumento e sem tocar no sistema de arquivos.
- **FR-003**: A autorização MUST ser concedida por configuração, não por pedido: com a capacidade habilitada, o sistema MUST aceitar pedidos válidos sem exigir confirmação humana por pedido.
- **FR-004**: O sistema MUST informar, quando consultado, se o processamento está habilitado, quais áreas de leitura e raízes de caso estão declaradas, quais perfis são permitidos e se há trabalho em andamento.
- **FR-005**: A advertência de abertura de sessão MUST declarar que o processamento está habilitado, estendendo FR-043 de 001 e a advertência de postura da 006.
- **FR-038**: A trilha MUST registrar, na abertura de cada sessão, a postura de processamento vigente — habilitação, áreas de leitura e raízes de caso declaradas —, de modo que se possa determinar sob qual autorização cada trabalho foi aceito. Decorre de FR-003: uma concessão por configuração é anterior ao pedido e não deixaria rastro próprio.

#### Confinamento do que é lido

- **FR-006**: O sistema MUST confinar a origem da evidência às áreas de leitura declaradas na configuração — lista de permissão, não lista de recusa. Origem fora de toda área declarada MUST ser recusada, ainda que a conta que executa o servidor consiga lê-la.
- **FR-007**: A decisão de confinamento MUST ser tomada sobre o caminho **real** resolvido no sistema de arquivos, não sobre comparação textual de prefixo — pelas mesmas razões medidas na 006.
- **FR-008**: A recusa por origem não permitida MUST nomear a origem pedida e as áreas onde a leitura é permitida, e MUST ocorrer antes de qualquer byte da origem ser lido.
- **FR-039**: As áreas de leitura declaradas MUST ser resolvidas no momento do pedido, não fixadas na inicialização do servidor, de modo que um volume montado depois do início seja utilizável sem reiniciá-lo. Uma área declarada que não exista no momento do pedido MUST ser reportada como indisponível, e não confundida com origem não permitida.

#### Confinamento de onde o caso nasce

- **FR-009**: O sistema MUST confinar o destino do caso a **raízes de caso declaradas**, mantidas separadas das raízes de exportação de artefato de FR-001 de 006.
- **FR-010**: O sistema MUST recusar destino que já contenha um caso, e MUST recusar destino que seja, ou esteja dentro de, a pasta de um caso aberto por qualquer sessão.
- **FR-011**: Uma recusa de destino MUST NOT deixar rastro: nenhum arquivo e nenhuma pasta criados no caminho pedido.

#### O pedido de processamento

- **FR-012**: O pedido MUST nomear a origem da evidência, o destino do caso e o perfil de processamento.
- **FR-013**: O perfil MUST provir de um conjunto declarado na configuração; perfil fora do conjunto MUST ser recusado com a lista do que é permitido.
- **FR-014**: O pedido MUST aceitar um nome de exibição para a evidência, de modo que o caso resultante a identifique como o perito espera.
- **FR-015**: Senhas de contêiner cifrado MUST ser referenciadas por nome resolvido no lado do servidor; a senha em si MUST NOT trafegar no pedido, na resposta, na trilha ou no log.
- **FR-050**: A senha é entregue ao motor pela linha de comando, e por isso fica legível a outras contas da mesma máquina enquanto o processo existe. Essa exposição MUST ser **declarada ao perito** — na resposta de aceite de todo pedido que use referência de segredo, e nas limitações conhecidas da documentação do módulo. Ela MUST NOT ser pressuposto silencioso.
- **FR-016**: O pedido MUST NOT aceitar repasse livre de opções de processamento. Apenas os parâmetros declarados são honrados; qualquer outro MUST ser recusado.
- **FR-017**: O sistema MUST validar o pedido inteiro antes de iniciar o trabalho — um pedido que vai falhar por configuração, caminho ou perfil MUST falhar imediatamente, não na terceira hora. Espaço em disco MUST NOT ser causa de recusa: ele é tratado por FR-044.
- **FR-044**: O sistema MUST comparar, antes de iniciar, o espaço livre na unidade de destino com uma exigência mínima derivada do **tamanho da evidência de origem**: `mínimo = tamanho da origem × percentual declarado na configuração`. Abaixo desse mínimo, MUST declarar a advertência no aceite e registrá-la na trilha, com os três números — tamanho da origem, mínimo exigido e espaço livre. Essa verificação MUST NOT recusar o pedido: a decisão de seguir permanece com quem pediu.
- **FR-046**: O tamanho da origem MUST corresponder ao **conjunto completo** da evidência, não ao primeiro arquivo: imagem segmentada (`.E01`, `.E02`, …) e pasta lógica contam por inteiro. Quando o tamanho não puder ser determinado dentro do orçamento de aceite de FR-018, o sistema MUST declarar a exigência indisponível, em vez de atrasar o aceite ou de supor um valor.

#### Execução longa

- **FR-018**: O pedido MUST devolver um identificador de trabalho imediatamente, sem aguardar a conclusão; nenhuma chamada de ferramenta MUST bloquear pela duração do processamento.
- **FR-019**: O sistema MUST executar no máximo um trabalho de processamento por vez; pedido concorrente MUST ser recusado com diagnóstico que identifica o trabalho em curso.
- **FR-020**: O sistema MUST expor o avanço de um trabalho: fase corrente, volume já processado, tempo decorrido e, quando determinável, estimativa de conclusão. Quando a estimativa não é determinável, isso MUST ser declarado em vez de estimado.
- **FR-047**: A consulta de avanço MUST distinguir **trabalho lento de trabalho parado**, e MUST declarar a fase corrente junto dessa distinção. O mesmo silêncio significa coisas diferentes em fases diferentes: minutos sem novidade durante uma consolidação de índice é normal; os mesmos minutos durante o processamento de itens não é.
- **FR-021**: Um trabalho em andamento MUST sobreviver ao término da sessão que o pediu e à queda da conexão de transporte.
- **FR-022**: Uma sessão posterior MUST conseguir localizar um trabalho pelo identificador e obter avanço ou desfecho, inclusive após reinício do processo do servidor.
- **FR-023**: Um trabalho MUST ser cancelável por **qualquer sessão autorizada**, independente de ter sido ela a iniciá-lo, e o cancelamento MUST registrar quem pediu, quando, e o que restou no destino.
- **FR-024**: Um trabalho MUST terminar quando o processo do servidor termina — a sobrevivência exigida por FR-021 é à sessão, não ao servidor. Após o reinício, o trabalho MUST ser reportado como interrompido com desfecho determinável — nunca como em andamento e nunca como inexistente.
- **FR-041**: O estado de um trabalho MUST ser persistido fora da memória do processo do servidor, de modo que a interrupção de FR-024 e a retomada de FR-030 sejam determináveis a partir do disco, sem depender do que o processo anterior sabia.
- **FR-045**: Registros de trabalho MUST ser retidos indefinidamente, junto da área de auditoria, e MUST NOT ser descartados por decurso de prazo. Um identificador não encontrado MUST ser reportado como desconhecido, e essa resposta MUST significar que ele nunca existiu nesta instalação.
- **FR-025**: Enquanto um trabalho corre, o servidor MUST continuar respondendo a consultas sobre casos já abertos, ou MUST declarar a degradação; MUST NOT parecer travado.

#### Resultado

- **FR-026**: O desfecho de sucesso MUST declarar o caminho do caso, a contagem de itens e a duração.
- **FR-027**: Um caso concluído com sucesso MUST ser abrível pela ferramenta existente de abertura de caso sem nenhum passo manual na máquina do servidor.
- **FR-028**: Um destino cujo processamento não concluiu MUST NOT ser apresentado como caso completo pela ferramenta de abertura.
- **FR-029**: O desfecho de falha MUST declarar a causa e se a retomada é possível.
- **FR-042**: O log do motor de processamento MUST ser escrito em arquivo do lado do servidor e MUST NOT alcançar o canal do protocolo, sob a mesma razão que sustenta FR-038 de 006. O desfecho MUST declarar o caminho do log, sempre.
- **FR-043**: O desfecho de falha MUST carregar um trecho diagnóstico limitado do log. Esse trecho MUST ser classificado como conteúdo derivado de evidência e MUST estar sujeito à política de egresso de 001, pela mesma fronteira que governa texto de item — nunca por caminho próprio.
- **FR-048**: Um processamento que percorre a evidência inteira e produz **zero itens** MUST ser reportado como concluído, distinguível de falha. Evidência vazia, de formato não suportado ou sem conteúdo recuperável é resposta legítima do exame, e apresentá-la como falha faria o perito procurar defeito onde há resultado.
- **FR-049**: O desfecho de falha MUST distinguir **origem inacessível** de **origem ilegível**. Mídia removida, compartilhamento que caiu e permissão perdida são problemas do ambiente, corrigíveis e com retomada possível; conteúdo corrompido é problema da evidência e muda o que o perito faz em seguida.
- **FR-030**: O sistema MUST permitir retomar um trabalho não concluído que ele próprio iniciou, aproveitando o que já foi processado em vez de recomeçar do início.
- **FR-031**: O processamento MUST abrir a evidência em somente-leitura e MUST NOT modificá-la em nenhuma circunstância.
- **FR-040**: Acrescentar evidência a um caso já concluído está fora do escopo desta feature. Um pedido cujo destino é um caso concluído MUST ser recusado com diagnóstico que declare que o acréscimo não é suportado — distinto da recusa por destino ocupado de FR-010, para que a fronteira de escopo não seja lida como defeito.

#### Auditoria

- **FR-032**: O pedido MUST ser registrado na trilha **antes** de qualquer leitura da evidência; se o registro não puder ser gravado, o trabalho MUST NOT iniciar.
- **FR-033**: O desfecho MUST ser registrado ainda que ocorra depois do fim da sessão que pediu o trabalho.
- **FR-034**: A trilha MUST permitir que um segundo examinador reconstitua o que foi processado, a partir de qual origem, com qual perfil, por quem, com qual desfecho e em qual intervalo — inclusive quando início e fim caem em sessões diferentes. Estende FR-037 de 001 conforme emendado por FR-033 de 006.
- **FR-035**: Nenhum segredo MUST aparecer na trilha.

#### Topologia dividida

- **FR-036**: Origem e destino MUST ser interpretados no sistema de arquivos do servidor, e as respostas MUST declarar isso explicitamente. Estende FR-036 de 006 para a classe nova de caminhos.
- **FR-037**: A orientação distribuída ao agente MUST ensinar que caminhos de evidência e de destino não são verificáveis do lado do agente e MUST NOT ser procurados no sistema de arquivos do ambiente do harness.

### Key Entities

- **Pedido de processamento**: o que o perito quer processar — origem da evidência, nome de exibição, destino do caso, perfil, referência de segredo quando houver. É validado por inteiro antes de virar trabalho.
- **Trabalho de processamento**: a execução de um pedido ao longo do tempo. Tem identificador estável, estado (aceito, em andamento, concluído, falho, cancelado, interrompido), avanço, instantes de início e fim, quem o pediu e desfecho. Sobrevive à sessão que o criou.
- **Área de leitura declarada**: raiz do sistema de arquivos do servidor sob a qual evidência pode ser lida.
- **Raiz de caso declarada**: raiz do sistema de arquivos do servidor sob a qual casos podem nascer. Distinta da raiz de exportação de artefato.
- **Perfil permitido**: nome de um perfil de processamento liberado nesta instalação.
- **Referência de segredo**: nome que o servidor resolve para uma senha do lado dele. Nunca a senha.
- **Caso resultante**: a pasta produzida pelo trabalho. Só é caso quando o trabalho concluiu.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Uma evidência de referência é levada do pedido a um caso consultável sem que ninguém toque na máquina do servidor durante o processo.
- **SC-002**: A chamada que inicia o processamento responde em menos de 5 segundos, independentemente do tamanho da evidência.
- **SC-003**: 100% dos pedidos com origem fora das áreas declaradas são recusados sem que um único byte da origem seja lido.
- **SC-004**: 100% das recusas de destino não deixam nenhum arquivo nem nenhuma pasta no caminho pedido.
- **SC-005**: Um processamento em andamento sobrevive ao encerramento da sessão que o pediu em 100% das tentativas.
- **SC-006**: Uma sessão nova localiza um trabalho pelo identificador e obtém avanço ou desfecho em 100% das consultas, inclusive após reinício do servidor.
- **SC-007**: Durante um processamento de referência, a consulta de avanço nunca fica mais de 60 segundos sem informação nova ou sem declarar que a fase corrente não produz medida.
- **SC-008**: O caso produzido abre pela ferramenta existente e sua contagem de itens coincide exatamente com a contagem declarada no desfecho.
- **SC-009**: Zero ocorrências de caso incompleto aberto como se fosse completo, em todos os desfechos não concluídos testados (falha, cancelamento, interrupção por reinício).
- **SC-010**: A partir apenas da trilha, um segundo examinador reconstitui origem, perfil, operador, intervalo e desfecho de 100% dos trabalhos, inclusive dos que atravessam fronteira de sessão.
- **SC-011**: Zero ocorrências de senha em resposta, trilha ou log, sob inspeção de uma execução com contêiner cifrado.
- **SC-012**: Numa instalação padrão, sem habilitação explícita, nenhuma capacidade de processamento é alcançável pelo agente.
- **SC-013**: 100% dos pedidos concorrentes são recusados com identificação do trabalho em curso.
- **SC-014**: Um cancelamento leva o processamento a terminar em menos de 60 segundos, com desfecho registrado.
- **SC-015**: A evidência de origem permanece bit a bit idêntica após 100% dos trabalhos, qualquer que seja o desfecho.
- **SC-016**: A postura sob a qual cada trabalho foi autorizado — habilitação, áreas de leitura e raízes de caso vigentes — é recuperável da trilha para 100% dos trabalhos.
- **SC-017**: Um trabalho interrompido é retomado e concluído sem reprocessar o que já havia sido processado, em uma execução de referência que atravessa um reinício do servidor.
- **SC-018**: Uma falha de processamento de referência é diagnosticada até a causa técnica sem que ninguém acesse a máquina do servidor, e nenhum byte de log alcança o canal do protocolo.
- **SC-019**: 100% dos pedidos cujo destino tem menos espaço livre que o mínimo calculado recebem a advertência no aceite, com os três números, e a registram na trilha; 0% deles são recusados por essa razão.
- **SC-021**: O tamanho considerado é o do conjunto completo da evidência em 100% dos casos, incluindo imagem segmentada em múltiplos arquivos — uma imagem de 500 GB partida em segmentos nunca é medida pelo primeiro segmento.
- **SC-022**: Um processamento que produz zero itens é reportado como concluído em 100% das execuções, nunca como falha.
- **SC-023**: Trabalho parado e trabalho lento são distinguíveis pela consulta de avanço, com a fase corrente declarada junto, em 100% das consultas.
- **SC-024**: Origem que se torna inacessível no meio produz desfecho distinto do de origem ilegível, em 100% das execuções.
- **SC-025**: 100% dos aceites de pedido que usam referência de segredo carregam a declaração de exposição por linha de comando de FR-050.
- **SC-020**: Um registro de trabalho permanece recuperável pelo identificador após qualquer número de reinícios do servidor, e nenhum registro é descartado por decurso de prazo.

## Assumptions

- O servidor executa na máquina onde a evidência está acessível e onde há uma instalação completa do IPED — a mesma topologia que a 006 descreve para o lado da evidência.
- A instalação que hospeda o servidor é a que processa. O caso nasce na mesma versão que o servidor serve, de modo que a validação de versão de 001 o aceita sem passo adicional.
- A evidência já foi adquirida e está presente no sistema de arquivos do servidor. Aquisição está fora do escopo.
- Um trabalho por vez basta: o gargalo é a máquina, não a fila. Enfileiramento de trabalhos pendentes está fora do escopo desta feature.
- O ajuste fino do pipeline continua sendo matéria de configuração da instalação; o agente escolhe entre perfis, não dentro deles.
- O identificador de trabalho não é segredo: conhecê-lo permite acompanhar, não concede acesso ao caso resultante.
- O perito que habilita o processamento na configuração está autorizando todos os trabalhos que vierem depois dentro das áreas e perfis que declarou. A decisão de escopo é feita uma vez, no arquivo, e não por pedido — decorrência direta de FR-003.
- As áreas de leitura declaradas são poucas e estáveis o bastante para serem mantidas à mão. Descoberta automática de mídia e resolução por rótulo de volume estão fora do escopo; FR-039 cobre apenas a resolução tardia do que já foi declarado.
- A área de auditoria é gravável e persiste entre execuções do servidor, na mesma máquina. FR-041 e FR-045 dependem disso; a durabilidade do estado de um trabalho deixou de ser premissa e virou requisito.
- Um caso criado por esta feature é indistinguível, para todos os efeitos de consulta, de um caso criado pela linha de comando.
