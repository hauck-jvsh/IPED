# Feature Specification: Confinamento de escrita e transporte de rede para o servidor MCP

**Feature Branch**: `006-export-allowlist-socket-transport`

**Created**: 2026-08-10

**Status**: Draft — clarificado em 2026-08-10 e ampliado com FR-034/SC-015 na pesquisa de Phase 0; sem marcadores pendentes

**Input**: User description: "Agora vamos melhorar esse MCP. Vamos implemntar o Nível 0 do plano escalonado (allow-list no checkDestination). Depois vamos acicionar ao MCP suporte a socket, permitindo que ele funcione em uma máquina diferente da máquina onde o harness roda."

---

## Contexto e problema

A feature [001-iped-llm-integration](../001-iped-llm-integration/spec.md) entregou o servidor MCP sob a decisão **D2 — implantação em estação de trabalho individual**: um operador, componente local, transporte por fluxos padrão de processo, sem exposição de rede. Sob essa decisão, autenticação, isolamento entre operadores e controle de concorrência entre sessões remotas foram explicitamente dispensados, e a garantia de FR-057 ("sem expor sua superfície de ferramentas à rede na configuração padrão") passou a valer por construção: não existia transporte capaz de expor coisa alguma.

O uso em campo revelou o custo dessa topologia. Com harness e servidor no mesmo processo-pai e sob a mesma conta de usuário, **a fronteira do MCP é decorativa**: o agente alcança o sistema de arquivos da estação por ferramentas próprias do harness, sem passar por ferramenta MCP nenhuma. Um episódio concreto ocorreu — um agente gravou arquivos em pastas que a configuração do harness declarava proibidas. Nada foi burlado; a restrição vivia em texto e em configuração que o próprio processo do agente lê, não em mecanismo que o sistema operacional imponha.

Disso decorrem dois problemas distintos, com custos e prazos distintos:

1. **O próprio servidor não confina o que escreve.** A única escrita que o servidor faz por pedido do agente é o artefato de saída, e a regra vigente (FR-068 de 001) é uma **lista de recusa**: proíbe a pasta do caso e libera todo o resto do sistema de arquivos alcançável pela conta que executa o servidor. Uma lista de recusa protege o caso e não protege a estação.

2. **A fronteira não pode ser materializada porque servidor e harness são inseparáveis.** Isolar o agente — em VM, em contêiner ou sob outra conta de usuário — exige que o servidor continue do lado da evidência enquanto o harness vai para o lado isolado. O transporte atual não permite essa separação: ele nasce e morre como processo filho do harness.

Esta feature entrega as duas coisas, em ordem de custo crescente: o confinamento de escrita, que vale por si e independe de qualquer topologia; e um transporte de rede opcional, que torna possível colocar o agente do outro lado de uma fronteira imposta pelo sistema operacional, com a evidência acessível apenas através da superfície de ferramentas do MCP.

**O que esta feature não é.** Ela não constrói o isolamento — não define VM, contêiner, conta de serviço nem regra de rede. Ela remove o impedimento técnico que hoje torna esse isolamento impossível, e confina o que o servidor escreve. A escolha e a montagem do ambiente isolado são operacionais e ficam com quem implanta.

### Relação com a spec 001

Esta feature **numera seus próprios requisitos a partir de FR-001**. Requisitos da feature 001 são sempre citados com a origem explícita — "FR-068 de 001" — para que nenhuma referência fique ambígua. Nenhum requisito de 001 é removido ou relaxado aqui; três são estendidos (FR-057, FR-068 e FR-043 de 001) e a extensão está nomeada no requisito correspondente.

Vale registrar a leitura que sustenta a compatibilidade: **FR-057 de 001 restringe a exposição de rede "na configuração padrão"**, não em toda configuração. Um transporte de rede desligado por padrão e ativado por decisão explícita do perito é compatível com o texto vigente. O que **não** sobrevive intacto é a decisão **D2**, que dispensava autenticação e controle de concorrência remota *porque* nenhuma configuração podia produzir sessão remota. As três clarificações desta spec são exatamente sobre o que substitui aquela dispensa.

---

## Clarifications

### Session 2026-08-10

- Q: O servidor autentica quem conecta pelo transporte de rede, e protege o que trafega? → A: **Segredo compartilhado declarado em configuração, canal em claro.** Fecha "qualquer processo que alcance a porta lê o caso inteiro" por custo compatível com SC-010 de 001. Não protege o conteúdo em trânsito — e por isso o alcance da recomendação fica delimitado: o transporte é adequado quando o trânsito é interface virtual da mesma máquina física ou segmento de rede confiável, e **a ausência de proteção do canal passa a ser fato declarado ao perito na abertura da sessão**, não pressuposto silencioso. Autenticação mútua por certificados fica registrada como evolução prevista, não construída aqui. Requisitos FR-026 a FR-028 criados.
- Q: O servidor atende um cliente por vez, ou vários? → A: **Várias sessões somente-leitura simultâneas, no máximo uma com escrita.** Atende o caso real de dois peritos consultando o mesmo caso sem reabrir o controle de concorrência de escrita entre agentes. Exige arbitrar a posse da escrita, sua liberação em queda de conexão, e a convivência com a detecção de concorrência local de FR-028 de 001, que continua valendo por cima. Requisitos FR-029 a FR-031 criados.
- Q: Consequência não antecipada da resposta anterior — com várias sessões sobre o mesmo caso, a trilha de auditoria ainda reconstitui o exame? → A: **Não sem trabalho adicional, e por isso vira requisito.** A trilha do módulo é **por sessão, não por caso** — limitação já conhecida e registrada. Com uma sessão por vez ela era irrelevante, porque a sequência de sessões sobre um caso era total. Com sessões simultâneas, o histórico de um caso passa a estar repartido entre trilhas paralelas, e FR-037 de 001 — "um segundo examinador reproduz a sequência de consultas e chega ao mesmo conjunto de itens" — deixa de ser satisfeito por uma trilha isolada. As trilhas concorrentes precisam ser reconciliáveis em uma história única e ordenável do caso. FR-033 criado.
- Q: Quem é o operador registrado numa sessão de rede? → A: **Ambas as identidades**, com a conta que executa o servidor como autoritativa e a identidade declarada pelo cliente registrada como **alegação explicitamente não verificada**. A trilha não afirma mais do que pode provar e ainda assim carrega quem disse ser. A distinção precisa sobreviver à exportação da trilha: uma alegação que se lê como fato verificado num laudo é pior do que alegação nenhuma. Requisito FR-032 criado.

### Session 2026-08-10 — achado da pesquisa de Phase 0

Um defeito veio da sondagem experimental do planejamento, não de raciocínio sobre o código. Está medido em [research.md](./research.md) R2.

- Q: Um destino dentro de uma raiz permitida pode aceitar a escrita e não guardar nada. Isso é problema desta feature? → A: **Sim, e a allow-list não o resolve.** Um destino como `<raiz>\NUL` fica **dentro** da raiz declarada, então FR-001 o aprova corretamente — não houve escape de raiz nenhuma. A escrita retorna sucesso, o tamanho reportado vem zero e o arquivo não existe. O agente receberia artefato declarado gerado, com contagem de itens, sobre arquivo que não está lá. É a mesma classe de defeito que a invariante "ausência ≠ vazio" do módulo existe para impedir, e a única razão de não ter aparecido antes é que ninguém exportou para um nome de dispositivo. Na mesma sondagem, `CON` criou um arquivo real e um nome terminado em espaço foi rejeitado na entrada — ou seja, o comportamento varia por nome e por versão do sistema, o que condena qualquer lista de nomes proibidos a ser incompleta. **FR-034** e **SC-015** criados, exigindo verificação **posterior à escrita** em vez de julgamento do nome.

### Session 2026-08-11 — achado do primeiro teste de campo do transporte

O servidor foi exercitado fora da suíte, contra a instalação real (`C:\iped\iped-mcp\iped-4.3.1`, JRE 11 embarcado), com transporte de rede ativo. Sete verificações passaram — recusa sem segredo, recusa com segredo errado, sessão servida, postura, identidade dupla, sessões simultâneas, trilhas sem colisão. Uma reprovou.

- Q: O relay respondeu corretamente e **não terminou** quando o stdin acabou. Isso é defeito de implementação ou falta requisito? → A: **Falta requisito.** FR-017 governa o servidor: a queda de uma conexão libera o caso. O que aconteceu foi o contrário — a conexão **não caiu**, porque o relay parou de bombear no fim da entrada sem avisar o servidor, que seguiu esperando requisição enquanto o relay seguia esperando resposta. O servidor se comportou como especificado; ninguém havia dito que o relay precisa propagar o encerramento. Em uso real isso deixa processo pendurado e sessão segurando o caso e a reivindicação de escrita até o timeout de ociosidade — e **fechar o stdin do processo filho é como todo harness suportado sinaliza encerramento**, de modo que a falha não é um caso de borda, é o caminho normal de saída. **FR-035** e **SC-016** criados.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Confinar onde o servidor pode escrever (Priority: P1)

O perito declara, na configuração da instalação, as pastas onde artefatos de saída podem ser gravados — tipicamente sua pasta de trabalho e a pasta de laudos. A partir daí, qualquer pedido de exportação para fora dessas pastas é recusado antes de qualquer escrita, com diagnóstico que diz o destino pedido e onde a gravação é permitida. O agente corrige e reemite; o perito não precisa auditar destino a destino.

**Why this priority**: é a única parte que reduz risco sem depender de nenhuma mudança de topologia, de infraestrutura ou de rotina do perito. Vale sozinha, entra sozinha, e permanece valendo depois de tudo o mais que esta feature construir. É também a peça que a segunda história pressupõe: expor a superfície do servidor pela rede sem confinar antes o que ele escreve amplia o alcance de qualquer defeito de caminho.

**Independent Test**: com uma raiz de escrita declarada, pedir exportação para dentro dela (deve gravar), para fora dela (deve recusar sem criar nada), e para caminhos que aparentam estar dentro e resolvem para fora (devem recusar). Entregue sem tocar em transporte.

**Acceptance Scenarios**:

1. **Given** uma raiz de escrita declarada e um conjunto de itens não vazio, **When** o agente pede um artefato com destino dentro da raiz, **Then** o artefato é gravado e a resposta declara o caminho gravado.
2. **Given** a mesma configuração, **When** o agente pede destino fora de toda raiz declarada, **Then** a operação é recusada, nenhum arquivo e nenhuma pasta são criados no destino pedido, e a recusa nomeia as raízes onde a gravação é permitida.
3. **Given** um destino cujo caminho textual está dentro da raiz mas que resolve, no sistema de arquivos, para fora dela, **When** o agente pede a exportação, **Then** a operação é recusada pela mesma regra e pelo mesmo diagnóstico do cenário anterior.
4. **Given** uma raiz declarada que contém a pasta do caso, **When** o agente pede destino dentro da pasta do caso, **Then** a operação é recusada — a garantia de SC-003 de 001 prevalece sobre a permissão da raiz.
5. **Given** qualquer recusa dos cenários acima, **When** ela ocorre, **Then** consta da trilha de auditoria com o destino pedido e a regra aplicada.

---

### User Story 2 - Servir um caso a um harness que roda em outra máquina (Priority: P2)

O perito coloca o harness e o modelo em um ambiente isolado — uma máquina virtual, um contêiner ou outra máquina física — e mantém o servidor MCP junto do caso, na máquina onde a evidência está. O harness alcança o servidor por uma conexão de rede declarada explicitamente. O agente executa as mesmas ferramentas, com as mesmas respostas, e não tem caminho nenhum até o sistema de arquivos do caso que não passe por elas.

**Why this priority**: é o que transforma a fronteira do MCP de decorativa em real, e é o que o episódio de campo pede. Vem depois da P1 porque custa mais, porque depende de decisões de segurança ainda em aberto, e porque não faz sentido ampliar o alcance da superfície antes de confinar o que ela escreve.

**Independent Test**: com o transporte de rede ativado, conectar um harness executando em outra máquina (ou em VM sem acesso ao sistema de arquivos do hospedeiro) e executar o mesmo roteiro de consulta usado no transporte local, comparando as respostas.

**Acceptance Scenarios**:

1. **Given** o transporte de rede ativado e declarado, **When** um harness em outra máquina conecta e executa o roteiro de consulta de referência, **Then** as respostas são equivalentes às obtidas pelo transporte local sobre o mesmo caso.
2. **Given** uma instalação sem configuração de transporte de rede, **When** o servidor é iniciado, **Then** nenhuma porta de rede é aberta e o comportamento é idêntico ao vigente hoje.
3. **Given** uma sessão de rede em andamento, **When** a conexão do cliente cai no meio de uma operação, **Then** o servidor libera o caso, permanece disponível, e a operação interrompida consta da trilha com desfecho — nunca fica registrada apenas como iniciada.
4. **Given** o transporte de rede configurado para um ponto de escuta indisponível, **When** o servidor é iniciado, **Then** a falha é reportada com diagnóstico acionável e o servidor não passa a operar em silêncio como se estivesse servindo.
5. **Given** uma sessão de rede, **When** o agente pede um artefato de saída, **Then** o destino é interpretado no sistema de arquivos do servidor e a resposta deixa isso explícito, de modo que o perito não o confunda com um caminho da máquina do harness.
6. **Given** o transporte de rede ativado, **When** um cliente conecta sem apresentar o segredo correto, **Then** a conexão é encerrada sem que nenhuma ferramenta responda e sem revelar a existência de casos, e a tentativa fica registrada.
7. **Given** a configuração ativando o transporte de rede sem segredo declarado, **When** o servidor é iniciado, **Then** o ponto de escuta não é estabelecido e a causa é reportada — o servidor não passa a aceitar conexões sem autenticação.
8. **Given** duas sessões autenticadas sobre o mesmo caso, **When** ambas consultam, **Then** ambas obtêm resposta; **When** a segunda pede escrita enquanto a primeira detém a reivindicação, **Then** é recusada com diagnóstico que identifica a detentora; **When** a primeira cai, **Then** a segunda obtém a escrita sem reinício do servidor.

---

### User Story 3 - Enxergar qual superfície está exposta (Priority: P3)

O perito pergunta ao servidor — sem ler código, sem inspecionar o sistema operacional — qual transporte está ativo, onde ele escuta, quais raízes de escrita valem, e quem está conectado. A mesma informação aparece na advertência de abertura de sessão, junto da advertência já existente sobre qual conteúdo de evidência pode ser transmitido ao modelo.

**Why this priority**: uma configuração de segurança que não pode ser verificada de dentro é uma configuração em que ninguém confia, e o perito é quem assina o laudo. Vem por último porque as duas primeiras histórias entregam valor sem ela, mas ela é o que torna as duas auditáveis na prática — e o custo é pequeno depois que as outras existem.

**Independent Test**: em cada configuração possível (sem transporte de rede, com transporte de rede), consultar o servidor e comparar o que ele declara com o estado observável do sistema operacional e do arquivo de configuração.

**Acceptance Scenarios**:

1. **Given** qualquer configuração, **When** o perito consulta a postura vigente da sessão, **Then** obtém o transporte ativo, o ponto de escuta quando houver, e as raízes de escrita declaradas.
2. **Given** uma sessão estabelecida por rede, **When** a sessão abre, **Then** a advertência de abertura informa que o conteúdo de evidência trafegará por conexão de rede, além do que FR-043 de 001 já exige.
3. **Given** qualquer sessão, **When** ela é registrada na trilha, **Then** o transporte usado e a origem da conexão, quando houver, constam do registro.

---

### Edge Cases

**Confinamento de escrita**

- Destino cujo caminho aparenta estar sob a raiz declarada mas resolve para fora dela por ligação simbólica, junção de diretório ou ponto de reanálise.
- Destino nomeado por forma alternativa do mesmo caminho — nome curto no padrão 8.3, prefixo de caminho estendido, caminho de rede — que a comparação textual ingênua não reconhece como equivalente.
- Destino que nomeia um fluxo alternativo de dados sobre um arquivo permitido: o arquivo pai está dentro da raiz e a gravação vai para outro lugar.
- Destino que nomeia um dispositivo reservado do sistema operacional **dentro** da raiz permitida: a contenção aprova corretamente, a escrita é aceita e nada é retido.
- Destino cuja pasta-pai não existe: hoje a pasta é criada antes de qualquer verificação de permissão. A verificação precisa preceder a criação, ou a recusa deixa rastro.
- Raiz declarada que não existe, não é pasta, ou não é gravável pela conta que executa o servidor.
- Raiz declarada que contém a pasta do caso, ou que é a própria pasta do caso.
- Nenhuma raiz declarada — instalação existente atualizada sem tocar na configuração.
- Duas raízes declaradas em que uma contém a outra.

**Transporte de rede**

- Ponto de escuta já ocupado por outro processo, ou endereço inexistente na máquina.
- Transporte de rede ativado em configuração sem segredo declarado, ou com segredo vazio.
- Conexão que apresenta segredo incorreto, e sequência de conexões apresentando segredos incorretos.
- Conexão que autentica e nunca emite pedido, ocupando uma vaga de sessão indefinidamente.
- Conexão encerrada abruptamente durante operação longa — exportação de milhares de itens, agregação sobre o acervo completo.
- Mensagem cortada ao meio pelo fechamento da conexão, indistinguível de mensagem malformada.
- Segunda sessão pedindo escrita sobre um caso já reivindicado por outra.
- Sessão detentora da escrita que abandona a conexão sem encerrá-la, deixando a reivindicação pendurada.
- Harness que encerra fechando a entrada padrão do intermediário — o caminho normal de saída, não uma borda — sem que o encerramento alcance o servidor.
- Sessão que detém a reivindicação de escrita sobre um caso que a UI do IPED abre em seguida na mesma máquina.
- Duas sessões somente-leitura sobre o mesmo caso produzindo trilhas paralelas que precisam ser reconciliadas depois.
- Cliente que declara identidade de operador vazia, ausente, ou igual à da conta que executa o servidor.
- Caso em mídia somente-leitura combinado com sessão remota: a trilha não pode ser co-localizada (FR-073 de 001) e o perito não está na máquina do servidor para ver o aviso.
- Servidor remoto gerando artefato: o perito no harness não alcança o arquivo produzido pelo caminho que a resposta informa.

---

## Requirements *(mandatory)*

### Confinamento de escrita

- **FR-001**: O sistema MUST recusar a gravação de qualquer artefato cujo destino não esteja contido em uma **raiz de escrita declarada em configuração**. A permissão MUST ser expressa como conjunto do que é permitido, nunca como conjunto do que é proibido — uma lista de recusa deixa liberado tudo o que não foi antecipado, e o que não foi antecipado é exatamente onde o defeito mora.
- **FR-002**: A recusa MUST ocorrer **antes de qualquer efeito no sistema de arquivos**, incluindo a criação de pastas intermediárias. Uma operação recusada MUST NOT deixar rastro no destino pedido.
- **FR-003**: A verificação MUST ser feita sobre o caminho **efetivamente alcançado** pelo sistema de arquivos, após resolução de todo mecanismo que permita a um caminho aparentar estar dentro da raiz e resolver para fora dela. Comparação textual de prefixo MUST NOT ser suficiente.
- **FR-004**: A pasta do caso MUST permanecer recusada como destino, **mesmo quando estiver contida em uma raiz declarada**. A garantia de SC-003 de 001 prevalece sobre a permissão da raiz; FR-068 de 001 continua valendo integralmente e esta feature apenas o cerca por fora.
- **FR-005**: O sistema MUST aceitar mais de uma raiz de escrita, para acomodar a separação corrente entre pasta de trabalho e pasta de entrega sem obrigar o perito a escolher uma só.
- **FR-006**: Uma raiz declarada que não exista, não seja pasta ou não seja gravável MUST ser reportada no diagnóstico de inicialização, e a primeira tentativa de gravação sob ela MUST falhar com diagnóstico acionável em vez de erro técnico opaco.
- **FR-007**: Toda recusa de destino MUST ser registrada na trilha de auditoria, com o destino pedido e a regra aplicada, no mesmo padrão que FR-041 de 001 estabelece para conteúdo bloqueado por política de egresso.
- **FR-008**: A recusa devolvida ao agente MUST nomear as raízes onde a gravação é permitida, de modo que a correção seja possível na tentativa seguinte sem intervenção do perito.
- **FR-009**: O conjunto de raízes de escrita MUST NOT ser alterável pelas ferramentas expostas ao agente, pela mesma razão que FR-034 de 001 protege a trilha de auditoria.
- **FR-034**: Depois de gravar um artefato, o sistema MUST verificar que ele **existe no caminho resolvido e retém o que foi escrito**, e MUST NOT reportar sucesso quando não retiver. Um destino pode estar legitimamente dentro de uma raiz permitida e ainda assim descartar a escrita — nomes de dispositivo reservados do sistema operacional aceitam os bytes e não guardam nada —, e nessa situação FR-001 não se aplica, porque o pedido não escapou de raiz alguma. Julgar o **nome** do destino contra uma lista de nomes proibidos MUST NOT ser o mecanismo: o conjunto varia por sistema e por versão, e uma lista incompleta devolve exatamente a falha que deveria impedir. Artefato declarado gerado, com contagem de itens, sobre arquivo que não está lá é pior do que falha na exportação: o perito só descobre quando for buscar a entrega.

### Transporte de rede

- **FR-010**: O sistema MUST oferecer, além do transporte local vigente, um transporte por conexão de rede que permita ao harness executar em máquina distinta daquela onde o servidor e o caso residem.
- **FR-011**: O transporte de rede MUST estar **desativado por padrão**. Uma instalação que não o configure explicitamente MUST NOT abrir porta alguma, preservando FR-057 de 001 sem depender de o perito saber que precisa desligar algo.
- **FR-012**: Quando ativado, o ponto de escuta MUST ser declarado explicitamente em configuração. O sistema MUST NOT adotar valor implícito que exponha mais superfície do que foi pedido — em particular, MUST NOT escutar em todas as interfaces por omissão.
- **FR-013**: O sistema MUST autenticar o cliente que se conecta pelo transporte de rede por **segredo compartilhado declarado em configuração**, antes de atender qualquer pedido. Uma conexão que não apresente o segredo correto MUST NOT obter resposta a nenhuma ferramenta, nem informação que revele a existência ou o conteúdo de casos. A decisão de 001 que dispensava autenticação (D2) valia sob a premissa de que nenhuma configuração podia produzir sessão remota, e essa premissa deixa de valer aqui.
- **FR-026**: O sistema MUST recusar-se a ativar o transporte de rede quando nenhum segredo estiver declarado, ou quando o declarado for vazio. Ativação sem segredo MUST NOT degradar silenciosamente para transporte aberto — a falha MUST ser reportada com diagnóstico acionável e o ponto de escuta MUST NOT ser estabelecido.
- **FR-027**: Toda tentativa de autenticação recusada MUST encerrar a conexão e MUST ser registrada, com a origem da tentativa, de forma que uma sequência de tentativas seja perceptível a quem inspecione os registros.
- **FR-028**: O segredo MUST NOT ser exigido em arquivo distribuído com o release nem em arquivo versionado, estendendo FR-055 de 001 — que já veda credenciais em arquivos versionados — ao material que a instalação passa a exigir.
- **FR-014**: O sistema MUST admitir **múltiplas sessões simultâneas em modo somente-leitura** e, sobre um mesmo caso, **no máximo uma sessão com capacidade de escrita**. Sessões somente-leitura concorrentes MUST NOT bloquear umas às outras.
- **FR-029**: A exclusividade de escrita MUST ser por caso, não por servidor: duas sessões escrevendo em casos distintos MUST ser possíveis. A tentativa de obter escrita sobre um caso já reivindicado MUST ser recusada com diagnóstico que identifique a sessão detentora, sem revelar mais sobre ela do que o necessário para o perito resolver o conflito.
- **FR-030**: A queda ou o encerramento da sessão detentora da escrita MUST liberar a reivindicação sem exigir reinício do servidor e sem deixar o caso permanentemente bloqueado para as demais sessões.
- **FR-031**: A detecção de concorrência local de FR-028 de 001 — outro processo da mesma máquina com o caso aberto, tipicamente a UI do IPED — MUST continuar vigorando **por cima** da exclusividade entre sessões. Uma sessão pode deter a reivindicação de escrita e ainda assim ter a escrita recusada porque a UI mantém o caso aberto; as duas condições são independentes e ambas precisam ser satisfeitas.
- **FR-015**: A superfície de ferramentas MUST ser idêntica nos dois transportes — mesmas ferramentas, mesmos parâmetros, mesma semântica de resposta. Nenhuma ferramenta MUST existir apenas em um deles, pela mesma razão que a skill tem fonte canônica única: comportamento divergente produziria análises divergentes sobre a mesma evidência.
- **FR-016**: Uma mensagem malformada recebida pela rede MUST seguir a regra de FR-078 de 001 — respondida com o erro de protocolo previsto e descartada, nunca fatal para a sessão.
- **FR-017**: A queda da conexão de um cliente MUST liberar o caso que aquela sessão mantinha aberto e MUST NOT encerrar o servidor. Operação em curso interrompida por queda de conexão MUST receber desfecho na trilha; um registro de início sem desfecho correspondente MUST NOT ser o resultado normal de uma desconexão.
- **FR-018**: A falha em estabelecer o ponto de escuta MUST produzir diagnóstico acionável e MUST NOT resultar em servidor que aparenta estar servindo sem estar.
- **FR-035**: Um intermediário entre o harness e o servidor MUST propagar o encerramento do harness até o servidor, de modo que a sessão termine e o caso seja liberado. Fechar a entrada padrão do processo filho é como os harnesses suportados sinalizam encerramento; um intermediário que apenas pare de encaminhar deixa o servidor esperando uma requisição que não virá e a si próprio esperando uma resposta que não virá, com a sessão retendo o caso e a reivindicação de escrita até o teto de ociosidade. Esta é a **saída normal**, não uma borda: um encerramento que só funciona por timeout não é um encerramento.
- **FR-019**: Em sessão de rede, o destino de artefato MUST ser interpretado no sistema de arquivos **do servidor**, e a resposta MUST declarar isso explicitamente. O perito opera de outra máquina e não tem como distinguir, pelo caminho devolvido, qual sistema de arquivos o produziu.

### Identidade, auditoria e visibilidade

- **FR-020**: Para sessão estabelecida por rede, a trilha de auditoria MUST registrar **duas identidades**: a da conta que executa o servidor, como autoritativa, e a declarada pelo cliente na abertura da sessão, como alegação. FR-032 de 001 exige "operador da estação", conceito que pressupõe uma única máquina; sob transporte de rede o termo se desdobra e a trilha MUST carregar os dois sem fundi-los.
- **FR-032**: A identidade declarada pelo cliente MUST ser distinguível da autoritativa em todo lugar onde a trilha for lida ou exportada, incluindo a exportação legível por humano exigida por FR-036 de 001. Uma alegação apresentada de forma que se leia como identidade verificada MUST NOT ocorrer — num laudo, isso é pior do que não registrar identidade alguma.
- **FR-033**: Quando mais de uma sessão operar sobre o mesmo caso, as trilhas resultantes MUST ser reconciliáveis em uma história única e ordenável daquele caso. A reconstituição exigida por FR-037 de 001 — segundo examinador reproduz a sequência e chega ao mesmo conjunto — MUST permanecer possível a partir do material que acompanha o caso, sem depender de o examinador saber de antemão quantas sessões existiram nem de posse simultânea de todas elas.
- **FR-021**: A trilha MUST registrar, para cada sessão, o transporte utilizado e a origem da conexão quando houver, de modo que a reconstituição exigida por FR-037 de 001 continue possível quando o exame tiver sido conduzido remotamente.
- **FR-022**: O sistema MUST permitir que o perito consulte, na sessão, o transporte ativo, o ponto de escuta quando houver, as raízes de escrita declaradas e o estado da reivindicação de escrita sobre os casos abertos — inclusive quando o transporte de rede estiver inativo, no mesmo padrão de FR-042 de 001 para a política de egresso.
- **FR-023**: A advertência de abertura de sessão exigida por FR-043 de 001 MUST informar, quando a sessão for de rede, que o conteúdo de evidência trafegará por conexão de rede **e que o canal não é protegido**. Sob o transporte local esse conteúdo nunca deixava o processo; a mudança é material, o perito precisa sabê-la antes de abrir o caso, e é dela que depende a decisão de manter ou não o trânsito confinado a uma única máquina física.

### Compatibilidade e implantação

- **FR-024**: Uma instalação existente MUST continuar funcionando após a atualização sem alteração de configuração, exceto pelo confinamento de escrita, que passa a vigorar. A mudança de comportamento MUST ser documentada como alteração incompatível de configuração.
- **FR-025**: A documentação de instalação MUST cobrir a topologia dividida — servidor junto da evidência, harness em ambiente isolado —, incluindo o que precisa ser verificado para que a separação seja real e não aparente.

### Key Entities

- **Raiz de escrita**: pasta declarada em configuração sob a qual a gravação de artefatos é permitida. Zero ou mais por instalação; não alterável pelo agente.
- **Destino de artefato**: caminho pedido pelo agente para um artefato de saída, sempre interpretado no sistema de arquivos do servidor. Precisa ser resolvido ao caminho efetivo antes de qualquer decisão sobre ele.
- **Ponto de escuta**: endereço e porta onde o transporte de rede aceita conexões. Existe apenas quando explicitamente declarado.
- **Sessão de transporte**: uma conexão de cliente autenticada, com seu transporte, sua origem, suas duas identidades de operador e o conjunto de casos que mantém abertos. Termina liberando tudo o que mantinha, inclusive reivindicações de escrita.
- **Reivindicação de escrita**: vínculo exclusivo entre uma sessão e um caso, que habilita as operações de curadoria. No máximo uma por caso, independente do número de sessões somente-leitura sobre ele. Liberada no encerramento da sessão detentora, normal ou anormal.
- **Identidade do operador**: par formado pela identidade autoritativa — a conta que executa o servidor — e pela alegação declarada pelo cliente. As duas coexistem no registro e nunca se fundem.
- **Postura vigente**: o conjunto do que está ativo e declarado em uma sessão — transporte, ponto de escuta, raízes de escrita, modo de acesso, política de egresso, estado das reivindicações de escrita — consultável pelo perito.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Sobre uma bateria de tentativas de gravação fora das raízes declaradas, cobrindo no mínimo caminho relativo, ligação simbólica, junção de diretório, forma alternativa de nomear o mesmo caminho, caminho de rede e fluxo alternativo de dados, **100% são recusadas** e nenhuma deixa arquivo ou pasta no destino pedido.
- **SC-002**: Em uma instalação sem transporte de rede configurado, a inspeção das portas de rede do sistema operacional após a inicialização do servidor mostra **nenhuma porta aberta por ele**, em 100% das verificações.
- **SC-003**: Um harness executando em ambiente sem qualquer acesso ao sistema de arquivos do caso completa o roteiro de consulta de referência e obtém resultados **equivalentes** aos do transporte local sobre o mesmo caso, com divergência zero no conjunto de itens retornado.
- **SC-004**: A garantia de SC-003 de 001 — evidência, índice e estado de análise bit a bit idênticos após sessão somente-leitura, com a subpasta de auditoria excluída por nome — permanece verificada **também sob transporte de rede**.
- **SC-005**: 100% das operações iniciadas em sessões encerradas por queda de conexão constam da trilha com desfecho registrado; nenhuma permanece apenas como iniciada.
- **SC-006**: As metas de desempenho de 001 permanecem atendidas sob transporte de rede quando servidor e harness estão na mesma máquina física — primeira página em menos de 5 segundos em 95% das consultas sobre caso de 10 milhões de itens (SC-002 de 001), abertura de caso em menos de 30 segundos (SC-015 de 001).
- **SC-007**: 100% das falhas de configuração testadas — raiz inexistente, raiz não gravável, ponto de escuta ocupado, endereço inválido — produzem diagnóstico acionável, sem erro técnico opaco, mantendo SC-011 de 001.
- **SC-008**: Um perito que não participou da configuração determina, consultando apenas o servidor, qual transporte está ativo e quais raízes de escrita valem, e o que ele obtém **coincide com o estado observável do sistema operacional** em 100% das configurações testadas.
- **SC-009**: Um perito segue a documentação da topologia dividida e obtém a primeira resposta com harness e servidor em máquinas distintas em menos de 30 minutos, sem consultar o código-fonte.
- **SC-010**: Nenhuma sessão de rede é aberta sem que a advertência correspondente — trânsito de conteúdo de evidência por rede, canal não protegido — tenha sido apresentada, em 100% das sessões.
- **SC-011**: Nenhuma conexão sem o segredo correto obtém resposta de ferramenta ou informação sobre a existência de casos, em 100% das tentativas testadas; e nenhuma configuração que ative o transporte sem segredo declarado resulta em ponto de escuta estabelecido, em 100% das tentativas.
- **SC-012**: Com sessões somente-leitura concorrentes sobre um mesmo caso, **nenhuma bloqueia a outra** e todas obtêm resultados idênticos aos de uma sessão isolada; e em 100% das tentativas no máximo uma sessão detém a escrita sobre o mesmo caso, incluindo pedidos simultâneos. Após a queda da detentora, outra sessão obtém a escrita sem reinício do servidor.
- **SC-013**: Partindo apenas do material que acompanha o caso, um segundo examinador reconstitui a sequência completa de operações de um exame conduzido por **duas sessões simultâneas** e chega ao mesmo conjunto de itens, sem saber de antemão quantas sessões existiram — mantendo SC-005 de 001 sob o novo modelo de concorrência.
- **SC-014**: Em 100% das leituras e exportações da trilha, a identidade alegada pelo cliente é distinguível da autoritativa sem consulta a documentação externa.
- **SC-016**: Ao encerramento do harness, o intermediário e a sessão que ele abriu terminam **sem depender de nenhum tempo de espera**, em 100% das execuções; nenhum caso permanece retido e nenhuma reivindicação de escrita sobrevive ao harness que a originou.
- **SC-015**: Nenhum destino que aceite a escrita sem retê-la produz resposta de sucesso. Sobre uma bateria de nomes de dispositivo reservados situados **dentro** de uma raiz permitida, 100% das tentativas são reportadas como falha com diagnóstico acionável, e nenhuma resposta declara artefato gerado sobre arquivo inexistente.

---

## Assumptions

- **A entrega é sequencial e a primeira história é autônoma.** O confinamento de escrita (US1) pode ser entregue, testado e implantado sem nenhuma parte do transporte de rede, e é assim que se pretende entregá-lo. O pedido do usuário nomeia essa ordem explicitamente.
- **Sem raiz declarada, o sistema adota uma raiz padrão documentada na área de trabalho do usuário que executa o servidor, criada sob demanda.** A alternativa — recusar toda exportação até que alguém configure — protege mais e quebra instalações existentes na atualização. A raiz padrão preserva FR-024 e mantém o confinamento vigente desde o primeiro minuto. A pasta do caso continua recusada em qualquer hipótese.
- **O ambiente isolado é responsabilidade de quem implanta.** Esta feature não escolhe nem configura máquina virtual, contêiner, conta de serviço ou regra de rede. Entrega a capacidade de separar; a separação é operacional.
- **O modelo de linguagem não é servido por este transporte.** O harness alcança seu modelo por conta própria, no ambiente isolado ou fora dele. O transporte desta feature liga harness e servidor MCP, e nada mais.
- **O artefato gerado permanece do lado do servidor.** Recuperá-lo a partir da máquina do harness está fora de escopo; o perito o alcança pelos mesmos meios com que alcança o caso.
- **A faixa de casos suportada não muda.** Linha 4.x, conforme SC-013 de 001.
- **Restrição de plataforma herdada.** Java 11 permanece restrição de runtime, e a preferência constitucional por dependências que executem nesse runtime se aplica a qualquer capacidade nova desta feature.
- **A skill permanece com fonte canônica única.** Se a orientação ao agente precisar mudar em função do transporte, muda na fonte e se propaga aos três harnesses, conforme o mecanismo já vigente.
- **O alcance recomendado do transporte é delimitado pela ausência de proteção do canal.** Com segredo compartilhado e canal em claro, o transporte é adequado quando o trânsito ocorre dentro de uma máquina física — máquina virtual ou contêiner falando com o hospedeiro — ou em segmento de rede confiável. Entre máquinas físicas em rede compartilhada, o conteúdo de evidência trafega legível para quem observe o segmento. A documentação de FR-025 precisa dizer isso com todas as letras; a spec não presume que quem implanta deduza.
- **Autenticação mútua por certificados é evolução prevista e não construída aqui.** Fica registrada para que a decisão de hoje seja legível como escolha de escopo, não como avaliação de que o problema não existe. O gatilho para retomá-la é a primeira implantação em que harness e servidor fiquem em máquinas físicas distintas.
- **A reconciliação de trilhas concorrentes (FR-033) não altera o formato de trilhas já emitidas.** A ordem dos campos que compõe o encadeamento por hash é contrato de material já produzido; qualquer mecanismo de reconciliação precisa se acomodar a ela em vez de reordená-la.

---

## Dependências

- Feature [001-iped-llm-integration](../001-iped-llm-integration/spec.md), entregue. Esta feature estende FR-057, FR-068 e FR-043 de 001, e substitui a dispensa de autenticação e controle de concorrência da decisão D2.
- Constituição do branch 4.3.1, versão 1.0.0 — em especial o Princípio IV (comportamento configurável vive em configuração) e o Princípio V (nada implícito no que varia por ambiente), que juntos determinam que ponto de escuta e raízes de escrita sejam declarados e nunca herdados de padrão de plataforma.
