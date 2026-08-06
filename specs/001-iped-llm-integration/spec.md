# Feature Specification: Integração IPED ↔ LLM (Servidor MCP + Skill de agente)

**Feature Branch**: `001-iped-llm-integration`

**Created**: 2026-08-04

**Status**: Entregue e encerrada em 2026-08-06, com três verificações **dispensadas por decisão do perito** e não realizadas — T006, T073 e T079. O que fica não verificado está nominado na clarificação de encerramento abaixo e em [tasks.md](./tasks.md); encerrar a spec não converte nada disso em verificado.

**Input**: User description: "Vamos criar um MCP e uma Skill para a integrar o IPED as ferramentas de LLM. Já fiz alguns POCS sobre isso, mas agora vamos fazer algo mais sério. A POC de MCP está em C:\Users\joaopaulo_jpva\Documents\python-projects\AI\codex-iped-mcp. A POC de Skill está em C:\Users\joaopaulo_jpva\Desktop\iped-skill. Essas POCs são para você se inspirar. Não precisa necessariamente aproveitá-las. Quero que, se possível, você faça algo melhor."

---

## Contexto e problema

Peritos usam o IPED para processar e indexar evidências digitais, mas a análise do caso processado depende de conhecimento especializado da UI e, sobretudo, da sintaxe de consulta e do **vocabulário de campos** do índice — que varia por versão do IPED, por parsers executados e por configuração regional. Perguntas de investigação simples ("quais fotos têm GPS na região X?", "houve menção a pagamento em cripto nas conversas?") custam muitos passos manuais.

Duas provas de conceito já demonstraram viabilidade: um servidor MCP que expõe o caso a um agente, e uma skill que orienta o agente. Ambas provaram o conceito, mas não têm as garantias que um contexto pericial exige: não há trilha de auditoria, não há proteção contra escrita acidental no caso, os resultados de busca não são paginados (uma consulta ampla pode devolver dezenas de milhares de itens de uma vez), o agente precisa adivinhar nomes de campos por tentativa e erro, e não há política definida sobre qual conteúdo de evidência pode sair do ambiente pericial.

Esta feature entrega a versão de produção dessa integração: uma superfície de ferramentas estável e auditável sobre casos do IPED, e uma skill que ensina o agente a usá-la com a disciplina metodológica que um laudo exige.

---

## Clarifications

### Session 2026-08-04

- Q: Onde a trilha de auditoria deve ser gravada — dentro da pasta do caso, ou em um local separado fora dela? → A: **Revisado no planejamento (2026-08-04).** A resposta inicial foi "fora da pasta do caso, com exportação opcional ao encerrar" (opção C), aceita como provisória. Ao reabrir a decisão antes da implementação, ficou definido: a área da estação é **buffer write-ahead** com escrita e sincronização a cada operação, e a **pasta do caso é o lar da trilha**, para onde ela é sincronizada automaticamente. Degrada com aviso quando o caso está em mídia somente-leitura. Ver R7 em [research.md](./research.md).
- Q: Qual é o maior tamanho de caso que a integração precisa atender bem, em número de itens? → A: Até ~10 milhões de itens — operação com vários dispositivos apreendidos. Metas de desempenho passam a ser medidas nessa escala.
- Q: Quais aplicações-cliente de agente a integração precisa atender na primeira entrega? → A: Múltiplos harnesses de linha de comando/IDE — Claude Code, Codex e OpenCode —, sendo OpenCode o preferido por executar LLMs locais. O servidor não pode depender de recurso exclusivo de nenhum cliente e a skill não pode existir apenas no formato de um deles. A UI do IPED é consumidora futura do mesmo MCP e da mesma skill, via harness acionado em segundo plano; não faz parte desta entrega.
- Q: Quais versões do IPED devem ter seus casos abertos pela integração? → A: Toda a linha 4.x como faixa declarada e testada. Fora dela, tenta abrir e recusa com diagnóstico claro, sem leitura parcial apresentada como completa.
- Q: Em quais formatos a integração precisa entregar os artefatos de saída da US3? → A: xlsx, CSV e JSON — perito, intercâmbio e automação. Markdown fica de fora por só servir a volumes que já cabem na própria conversa. Requisitos FR-066 a FR-070 criados para cobrir a US3, que até então não tinha nenhum.

### Session 2026-08-06 — achados do primeiro teste de campo

Dois defeitos vieram do primeiro deploy usado por perito fora da bancada de desenvolvimento (`C:\iped\iped-mcp`, caso real). Ambos se manifestaram como a mesma coisa aos olhos de quem testava: "o MCP não consegue consultar campos de metadado".

- Q: Quando o agente escreve uma mensagem JSON malformada, o servidor deve encerrar? → A: **Não.** A falha de parse era propagada para fora do laço de leitura e derrubava o processo — uma consulta mal escrita custava a sessão inteira e todos os casos abertos nela. Passa a responder `-32700` e continuar servindo, como o JSON-RPC 2.0 exige. O gatilho real: `\:` cru dentro de string JSON, que é escape inválido e é exatamente o que um agente escreve ao tentar escapar nome de campo namespaced.
- Q: O servidor deve reescrever a expressão do perito quando ela falha só por colon não escapado em nome de campo? → A: **Por padrão não.** O diagnóstico passa a carregar a grafia corrigida já verificada contra o caso, e o agente acerta na segunda tentativa; a expressão registrada e respondida continua sendo a que foi pedida. Reescrita automática existe como opção de configuração (`autoEscapeFieldNames`, desligada por padrão) para modelos locais mais fracos, e quando ligada o reparo é **declarado** no resultado (`query_normalized`), nunca silencioso. Requisitos **FR-075 a FR-078** criados — FR-071 a FR-074 já pertenciam à revisão de durabilidade da auditoria.
- Q: A dimensão de agregação deve aceitar campo arbitrário? → A: **Fora de escopo por ora.** `iped_aggregate` mantém o conjunto fechado (categoria, tipo de conteúdo, período, evidência, marcador) do FR-016. Agregar por campo arbitrário com DocValues é viável e vira feature própria se a demanda se confirmar em campo.

### Session 2026-08-06 — cobertura completa das 25 ferramentas

Teste de cobertura sobre caso real de 781.246 itens e 455 campos indexados, exercitando as 25 ferramentas com caminhos de erro e parâmetros opcionais. Um defeito funcional encontrado.

- Q: O que fazer quando o servidor não consegue produzir um cursor de continuação utilizável? → A: **Não emitir cursor.** `iped_search` devolvia `next_cursor` cuja posição de ordenação era `NaN`, e retomar dali reiniciava da primeira página — laço de paginação que nunca terminava nem avançava, **sem sinal de erro**. Corrigido na origem (a posição vem do valor que o coletor comparou, não de `ScoreDoc.score`), e a regra vira requisito: cursor inutilizável é ausência declarada, não valor devolvido. FR-079 criado.
- Q: `similar: []` em `iped_check_field` significa "nenhum nome próximo" ou "sugestão não implementada"? → A: **Precisa ser distinguível na resposta.** O relatório registrou não conseguir separar as duas leituras — e é exatamente sobre essa resposta que se apoia uma afirmação de ausência. A resposta passa a declarar quantos nomes foram comparados. Coberto por FR-008, sem requisito novo.

### Session 2026-08-06 — encerramento da feature

- Q: T006, T073 e T079 continuam abertas. Encerrar a feature ou executá-las? → A: **Encerrar.** Decisão do perito, tomada com o escopo entregue e verificado em campo. As três **não foram executadas** e são registradas como dispensadas, não como concluídas. O que fica sem verificação, nominalmente:
  - **T006** — caso de referência não construído. **47 dos 151 testes continuam pulando**, e com eles 7 cenários do quickstart. As garantias que esses testes protegem estão exercitadas em campo sobre casos reais, mas não estão sob regressão automatizada: uma alteração futura que as quebre passa no `mvn test`.
  - **T073** — instalação nunca cronometrada em máquina limpa nos três harnesses. **SC-010 não verificado.**
  - **T079** — nunca executada contra harness de modelo local. **FR-065 não verificado** e, com ele, a salvaguarda operacional da decisão D3: como a política de egresso é inativa por padrão (FR-039), rodar contra modelo local é o que mantém conteúdo de evidência na estação. Enquanto isso não for verificado, o material de um caso trafega para o provedor do modelo em uso — o que o servidor declara na abertura de toda sessão, como manda FR-043.
- Q: Encerrar altera algum requisito? → A: **Não.** Nenhum FR foi removido ou relaxado. O escopo entregue é o que as tarefas marcadas concluídas descrevem; o que não foi verificado permanece escrito como não verificado.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Interrogar um caso processado em linguagem natural (Priority: P1)

O perito abre uma conversa com o assistente, aponta para a pasta de um caso já processado e faz perguntas de investigação em português comum. O assistente localiza os itens relevantes, inspeciona metadados e conteúdo, e responde com uma conclusão **ancorada em evidência**: cada afirmação vem acompanhada dos identificadores dos itens que a sustentam (caso, ID do item, nome, caminho na evidência e hash), de modo que o perito possa reabrir exatamente aqueles itens na UI do IPED e conferir.

Antes de consultar, o assistente se orienta sozinho: descobre quais categorias existem no caso, quais marcadores já foram criados e — crucialmente — **quais campos realmente existem naquele índice**, em vez de supor nomes de campos de outras versões. Quando uma consulta retorna zero resultados por nome de campo inexistente, o assistente identifica a causa e se corrige, em vez de reportar "nada encontrado".

**Why this priority**: É o valor central e o motivo de existir da feature. Sozinha, já substitui horas de navegação manual na UI. Todas as demais histórias pressupõem esta.

**Independent Test**: Contra um caso de referência com conteúdo conhecido, fazer 20 perguntas de investigação típicas (palavra-chave, faixa de datas, GPS, hash conhecido, tipo de arquivo, remetente de e-mail, tema em conversas) e verificar que as respostas citam exatamente os itens esperados, sem falsos positivos e sem omissões, e que os identificadores citados abrem os mesmos itens na UI do IPED.

**Acceptance Scenarios**:

1. **Given** um caso processado e íntegro, **When** o perito informa o caminho do caso, **Then** o assistente confirma a abertura e apresenta um panorama do acervo (total de itens, evidências incluídas, categorias com contagem, marcadores existentes) sem que o perito precise pedir.
2. **Given** um caso aberto, **When** o perito pergunta "quais documentos mencionam licitação em 2024?", **Then** o assistente informa **primeiro a quantidade** de itens correspondentes e devolve uma primeira página de resultados já enriquecida (nome, caminho, tamanho, datas, categoria e trecho de contexto que evidencia a correspondência), sem despejar o conjunto inteiro.
3. **Given** uma pergunta cuja consulta corresponde a dezenas de milhares de itens, **When** o assistente detecta o volume, **Then** ele apresenta o total e uma quebra por categoria/período e propõe refinamentos ao perito, em vez de tentar inspecionar todos os itens.
4. **Given** um caso cujo vocabulário de campos difere do esperado, **When** o assistente monta uma consulta com um nome de campo inexistente naquele índice, **Then** o sistema sinaliza explicitamente que o campo não existe e indica os campos disponíveis mais próximos, permitindo correção automática.
5. **Given** um item sem texto extraído (ex.: imagem sem OCR), **When** o perito pede o conteúdo, **Then** o assistente explica a ausência e oferece as alternativas disponíveis para aquele item (miniatura, metadados, campos indexados), em vez de retornar vazio sem explicação.
6. **Given** uma resposta conclusiva do assistente, **When** o perito pede a fundamentação, **Then** cada afirmação é rastreável até itens específicos identificados por caso + ID + hash.

---

### User Story 2 - Registrar achados de volta no caso, com auditoria (Priority: P2)

Concluída uma linha de análise, o perito pede ao assistente que preserve o achado **dentro do caso** — criando um marcador e associando os itens relevantes — para que o resultado reapareça na UI do IPED e nos relatórios oficiais, e não fique apenas no histórico da conversa.

Como isso altera o estado do caso, o sistema opera em modo somente-leitura por padrão. Escrita exige habilitação explícita, cada operação de escrita é confirmada com o perito antes de ser aplicada, e **toda** chamada de ferramenta — leitura e escrita — fica registrada em uma trilha de auditoria imutável e exportável, com data/hora, operador, parâmetros e efeito produzido.

**Why this priority**: Transforma a conversa em trabalho pericial aproveitável e é o que torna a ferramenta admissível em um fluxo formal. Depende da US1 para ter o que marcar.

**Independent Test**: Com escrita habilitada, executar um ciclo completo (criar marcador → associar itens → renomear → remover itens) e verificar, ao abrir o caso na UI do IPED, que o marcador existe com exatamente os itens esperados; e verificar que a trilha de auditoria exportada reproduz a sequência integral de operações.

**Acceptance Scenarios**:

1. **Given** o sistema em modo padrão, **When** o assistente tenta criar ou apagar um marcador, **Then** a operação é recusada com mensagem clara de que o modo de escrita não está habilitado, e nada é gravado no caso.
2. **Given** o modo de escrita habilitado, **When** o assistente vai aplicar uma alteração, **Then** ele apresenta ao perito exatamente o que será alterado (marcador afetado, quantidade e amostra de itens) e só aplica após confirmação.
3. **Given** uma operação destrutiva (apagar ou renomear um marcador preexistente), **When** ela é solicitada, **Then** o sistema exige confirmação reforçada e registra na auditoria o estado anterior, permitindo reconstituir o que existia.
4. **Given** qualquer operação concluída, **When** o perito exporta a trilha de auditoria, **Then** ela contém, em ordem cronológica, toda a sessão — consultas executadas, itens consultados e alterações aplicadas — em formato legível e verificável.
5. **Given** a trilha de auditoria não pode ser gravada (disco cheio, permissão negada), **When** uma operação é solicitada, **Then** o sistema recusa a operação em vez de executá-la sem registro.
6. **Given** o mesmo caso aberto simultaneamente na UI do IPED, **When** o assistente tenta gravar, **Then** o sistema detecta o acesso concorrente e recusa a escrita com orientação ao perito, em vez de corromper ou sobrescrever silenciosamente o estado de marcadores.

---

### User Story 3 - Produzir um artefato de saída a partir dos achados (Priority: P3)

O perito pede um produto concreto: uma planilha com os itens de um marcador, uma tabela cronológica de mensagens, um resumo estruturado de um conjunto de itens, ou uma linha do tempo de atividade em uma data. O assistente monta o artefato sem trafegar o acervo inteiro pela conversa — para volumes altos, a extração é feita em lote e o resultado é entregue como arquivo, com a conversa contendo apenas o sumário e a validação.

**Why this priority**: É o que fecha o ciclo de trabalho e evita que a análise precise ser refeita manualmente. Depende de US1 (encontrar) e se beneficia de US2 (marcar), mas pode ser entregue e testada isoladamente sobre marcadores preexistentes.

**Independent Test**: Sobre um marcador com 5.000 itens, gerar uma planilha com as propriedades essenciais de todos eles e verificar que o arquivo está completo e correto, e que a conversa não recebeu os 5.000 registros.

**Acceptance Scenarios**:

1. **Given** um marcador com milhares de itens, **When** o perito pede um relatório dele, **Then** o artefato é produzido integralmente e a conversa recebe apenas contagem, amostra e caminho do arquivo gerado.
2. **Given** um conjunto de mensagens de conversa, **When** o perito pede a transcrição cronológica, **Then** o artefato agrupa por conversa e ordena por data/hora, identificando remetente e destinatário.
3. **Given** um pedido de relatório sobre um conjunto vazio, **When** o assistente executa, **Then** ele informa que não há itens e não gera artefato vazio silenciosamente.

---

### User Story 4 - Instalar e conectar a integração sem conhecimento prévio (Priority: P3)

Um perito que nunca configurou uma integração de agente instala a integração a partir da distribuição do IPED, seguindo instruções verificáveis, e confirma o funcionamento com uma checagem de diagnóstico que aponta com precisão o que está faltando (instalação do IPED não localizada, caso inacessível, versão incompatível) em vez de falhar com erro técnico opaco.

**Why this priority**: Sem isso, a feature não sai da bancada de quem a construiu. É pré-requisito operacional de todas as demais histórias, mas não bloqueia o desenvolvimento delas.

**Independent Test**: Em uma máquina limpa com apenas o IPED instalado, seguir o guia de instalação e obter uma sessão funcional respondendo a uma pergunta contra um caso de referência, cronometrando o processo.

**Acceptance Scenarios**:

1. **Given** uma máquina com o IPED instalado, **When** o perito segue o guia de instalação, **Then** a integração fica operante sem edição manual de arquivos de configuração além dos valores documentados.
2. **Given** uma configuração incompleta, **When** a checagem de diagnóstico é executada, **Then** ela reporta exatamente qual pré-requisito falta e como corrigi-lo.
3. **Given** um caminho apontado para uma pasta que não é um caso do IPED (ou é um caso ainda em processamento), **When** o perito tenta abri-lo, **Then** o sistema explica a natureza do problema em vez de retornar erro genérico.

---

### User Story 5 - Preparar evidência bruta para análise (Priority: P4 — fase 2, fora da entrega inicial)

> **Fora do escopo da primeira entrega.** Permanece especificada aqui para que a visão de ponta a ponta fique registrada e a fase 2 já parta de requisitos escritos. O plano e as tarefas da entrega inicial não devem cobri-la.

O perito pede ao assistente que processe uma evidência bruta (imagem forense, extração de celular, pasta) para gerar um caso analisável. O assistente reúne os parâmetros necessários, apresenta o plano de processamento para confirmação, dispara a execução de forma controlada e acompanha o progresso, alertando quanto à duração e sinalizando conclusão ou falha com base no resultado real da execução — nunca presumindo sucesso.

**Why this priority**: Fecha o ciclo ponta a ponta, mas é a parte de maior risco (execuções longas, opções destrutivas, consumo de recursos) e a de menor ganho relativo, já que peritos normalmente já têm rotinas estabelecidas de processamento. Entregável por último, e apenas se confirmado em escopo.

**Independent Test**: Processar uma evidência pequena de referência via assistente e verificar que o caso resultante é equivalente ao produzido pela linha de comando com os mesmos parâmetros, e que o assistente reportou corretamente conclusão e destino.

**Acceptance Scenarios**:

1. **Given** um pedido de processamento, **When** o assistente monta o plano, **Then** ele apresenta origem, destino, perfil e opções para confirmação explícita antes de iniciar.
2. **Given** uma pasta de destino que já contém um caso, **When** o processamento é solicitado, **Then** o assistente não sobrescreve: apresenta as alternativas (acrescentar evidência, continuar processamento interrompido, recomeçar do zero, outro destino) e exige escolha do perito, com alerta reforçado para a alternativa destrutiva.
3. **Given** um processamento em andamento, **When** o perito pergunta o andamento, **Then** o assistente reporta o estado real com base na execução, sem inventar progresso.
4. **Given** um processamento que terminou em erro, **When** o assistente reporta, **Then** ele afirma a falha explicitamente e apresenta o diagnóstico disponível, sem apresentar o caso como utilizável.

---

### Edge Cases

- **Caso incompleto ou em processamento**: pasta de saída existe mas o índice está sendo escrito ou ficou truncado por interrupção → deve ser detectado na abertura e recusado com explicação, nunca produzindo resultados parciais apresentados como completos.
- **Acesso concorrente**: caso aberto simultaneamente pela UI do IPED, por outro perito ou por outra sessão de agente → leitura deve ser segura; escrita deve ser bloqueada ou serializada, jamais gerando estado inconsistente de marcadores.
- **Caso portátil movido de máquina**: caminhos relativos e evidências ausentes → metadados devem continuar consultáveis; pedidos de conteúdo bruto devem informar que a evidência não está acessível, em vez de falhar de forma obscura.
- **Consulta de altíssima cardinalidade** (ex.: correspondendo a milhões de itens): deve ser respondida com contagem e agregações, nunca materializando o conjunto completo.
- **Consulta malformada ou com caracteres especiais não escapados**: deve retornar erro de sintaxe compreensível e sugestão de correção, não uma exceção técnica.
- **Termos acentuados**: a mesma busca deve se comportar de forma previsível com e sem acentuação, e a diferença de comportamento entre conteúdo textual e campos estruturados deve ser explicitada ao agente.
- **Item muito grande** (vídeo de centenas de MB): o conteúdo bruto nunca deve ser trafegado inteiro para a conversa; deve haver limite, aviso de truncamento e alternativa (miniatura, metadados).
- **Item cifrado ou protegido por senha**: deve ser identificado como tal, com metadados disponíveis e conteúdo indisponível declarado.
- **Colisão de identificadores entre casos**: IDs de item são locais ao caso → toda referência a item deve carregar o caso de origem, e a interface deve tornar impossível referenciar um item sem seu caso.
- **Caso de versão antiga do IPED**, sem campos que versões novas produzem → funcionalidades dependentes daqueles campos devem se degradar com aviso, não quebrar a sessão.
- **Sessão interrompida** (queda de conexão no meio de uma operação de escrita) → o caso não pode ficar em estado intermediário; a auditoria deve refletir o que efetivamente foi aplicado.
- **Trilha de auditoria indisponível ou adulterada** → operação recusada; adulteração detectável.
- **Material sensível** (categorias de conteúdo ilícito, dados pessoais, material protegido por sigilo) → sujeito à política de egresso definida, aplicada pelo sistema e não pela boa vontade do agente.
- **Pedido do agente por volume muito acima do necessário** (ex.: inspecionar 50.000 itens um a um) → o sistema deve limitar e o agente deve ser instruído a amostrar e consultar o perito.

---

## Requirements *(mandatory)*

### Functional Requirements

#### Acesso ao caso

- **FR-001**: O sistema MUST permitir abrir um caso do IPED a partir do caminho da sua pasta de saída, validando que se trata de um caso íntegro e completo antes de aceitá-lo.
- **FR-002**: O sistema MUST recusar, com diagnóstico específico, caminhos que não sejam casos do IPED, casos incompletos, casos em processamento ou casos de versão não suportada.
- **FR-003**: O sistema MUST permitir manter mais de um caso acessível na mesma sessão e MUST exigir que toda referência a item identifique o caso de origem.
- **FR-004**: O sistema MUST tratar a reabertura de um caso já aberto como operação idempotente e bem-sucedida, não como erro.
- **FR-005**: O sistema MUST liberar os recursos de um caso quando ele for fechado e ao término da sessão, sem deixar bloqueios pendentes sobre a pasta do caso.
- **FR-006**: O sistema MUST expor um panorama inicial do caso (total de itens, evidências que o compõem, categorias com contagem, marcadores existentes com contagem) em uma única operação.

#### Descoberta de vocabulário

- **FR-007**: O sistema MUST expor os nomes de campos efetivamente presentes no índice do caso aberto, para que consultas sejam montadas sobre o vocabulário real e não sobre suposições.
- **FR-008**: O sistema MUST, para um campo consultado, informar se ele existe naquele caso e, quando não existir, indicar campos disponíveis semelhantes.
- **FR-009**: O sistema MUST permitir obter, para um item, o conjunto completo de campos indexados, como recurso de descoberta de vocabulário por exemplo concreto.
- **FR-010**: A skill MUST instruir o agente a confirmar o vocabulário de campos do caso antes de montar consultas não triviais, e a tratar resultado zero como possível erro de nome de campo antes de concluir ausência de evidência.
- **FR-075**: Quando a grafia de um nome de campo dentro de uma expressão de consulta diferir do nome como o índice o guarda, o sistema MUST entregar **as duas formas** junto ao nome. Um vocabulário que só devolve a forma inutilizável em consulta não cumpre o FR-007: leva o agente a montar consulta que não executa.

#### Consulta e resultados

- **FR-011**: O sistema MUST executar consultas sobre o índice do caso usando a mesma sintaxe de consulta suportada pela UI do IPED, sem exigir que o perito a conheça.
- **FR-012**: O sistema MUST retornar, para toda consulta, a contagem total de correspondências independentemente de quantos resultados sejam devolvidos.
- **FR-013**: O sistema MUST paginar resultados, com tamanho de página limitado e navegação determinística, e MUST nunca devolver o conjunto completo de uma consulta ampla em uma única resposta.
- **FR-079**: Um cursor de continuação devolvido MUST avançar: percorrer as páginas de uma consulta MUST visitar cada item uma vez e terminar. Quando o sistema não conseguir produzir um cursor utilizável, MUST omiti-lo em vez de devolver um que não avança — cursor que reinicia em silêncio faz o consumidor reprocessar a mesma página indefinidamente sem sinal de erro, que é falha pior do que a paginação indisponível.
- **FR-014**: O sistema MUST devolver resultados já enriquecidos com as propriedades essenciais de cada item (identificador, nome, caminho, tamanho, datas, categoria, hash, indicadores de deletado/recuperado/subitem), evitando uma consulta adicional por item.
- **FR-015**: O sistema MUST devolver, para consultas textuais, um trecho de contexto por item evidenciando o motivo da correspondência.
- **FR-016**: O sistema MUST permitir obter contagens agregadas por dimensões relevantes (categoria, tipo de conteúdo, período, evidência de origem, marcador) sem materializar os itens correspondentes.
- **FR-017**: O sistema MUST reportar erros de sintaxe de consulta de forma compreensível, indicando o ponto do problema.
- **FR-076**: Um erro de consulta MUST NOT propor ao agente uma grafia que não executa. Quando o sistema conseguir derivar uma correção e **verificá-la contra o caso aberto**, MUST nomeá-la no próprio erro. Orientação que devolve o agente à grafia que acabou de falhar produz laço de tentativas e leva à conclusão falsa de que o mecanismo de consulta é limitado.
- **FR-077**: O sistema MAY oferecer, como opção de configuração desligada por padrão, o reparo automático de expressão que falhe exclusivamente por grafia de nome de campo. Quando ativo e aplicado, o reparo MUST ser declarado no resultado da operação, com a expressão efetivamente executada. Consulta reescrita em silêncio MUST NOT ocorrer: o que foi contado precisa ser legível a partir da própria resposta, que é o que chega ao laudo.
- **FR-018**: O sistema MUST aplicar limite de tempo às consultas e reportar explicitamente quando um resultado for parcial por esgotamento de tempo.
- **FR-019**: O sistema MUST produzir resultados estáveis: a mesma consulta sobre o mesmo caso inalterado MUST retornar o mesmo conjunto, na mesma ordem.

#### Inspeção de item

- **FR-020**: O sistema MUST expor, para um item identificado por caso + identificador: propriedades essenciais, metadados extraídos, campos indexados, texto extraído, miniatura e conteúdo bruto.
- **FR-021**: O sistema MUST limitar o volume de texto e de conteúdo bruto devolvido em uma única operação, sinalizando truncamento e informando o tamanho real.
- **FR-022**: O sistema MUST informar de forma explícita quando um recurso não estiver disponível para o item (sem texto extraído, sem miniatura, evidência inacessível, item cifrado), distinguindo indisponibilidade de conteúdo vazio.
- **FR-023**: O sistema MUST permitir navegar a hierarquia de um item (contêiner pai e itens contidos) sem construção manual de consultas.
- **FR-024**: O sistema MUST permitir recuperar as propriedades essenciais de um lote de itens em uma única operação, com limite de tamanho de lote.

#### Marcadores e seleção

- **FR-025**: O sistema MUST operar em modo somente-leitura por padrão; operações que alteram o estado do caso MUST exigir habilitação explícita fora do controle do agente.
- **FR-026**: O sistema MUST permitir listar, criar, renomear e excluir marcadores, e associar e desassociar itens a marcadores, quando o modo de escrita estiver habilitado.
- **FR-027**: O sistema MUST permitir consultar e alterar o estado de seleção de itens quando o modo de escrita estiver habilitado.
- **FR-028**: O sistema MUST detectar que o caso está sendo acessado concorrentemente por outro processo na mesma máquina (tipicamente a UI do IPED) e MUST recusar a escrita nessa condição, com orientação ao perito.
- **FR-029**: A skill MUST instruir o agente a apresentar ao perito o efeito exato de qualquer alteração e obter confirmação antes de aplicá-la, com confirmação reforçada para exclusão e renomeação de marcadores preexistentes.
- **FR-030**: O sistema MUST persistir as alterações de forma que sejam visíveis ao reabrir o caso na UI do IPED.

#### Integridade e auditoria

- **FR-031**: O sistema MUST NOT modificar, sob nenhuma circunstância, os arquivos de evidência original.
- **FR-032**: O sistema MUST registrar em trilha de auditoria toda operação executada — leitura e escrita — com data/hora, operador da estação, caso, operação, parâmetros, volume de resultado e desfecho. Cada registro MUST carregar vínculo forte com o caso, de forma que permita reassociação posterior sem depender do caminho da pasta.
- **FR-033**: O sistema MUST registrar, para operações de escrita, o estado anterior suficiente para reconstituir o que existia antes.
- **FR-034**: A trilha de auditoria MUST ser somente-acréscimo, com adulteração detectável, e MUST NOT ser alterável por meio das ferramentas expostas ao agente.
- **FR-035**: O sistema MUST recusar operações quando não for possível registrá-las na trilha de auditoria.
- **FR-036**: O sistema MUST permitir exportar a trilha de auditoria de uma sessão em formato legível por humano e processável por máquina, para destino escolhido pelo perito.
- **FR-037**: A trilha de auditoria MUST conter informação suficiente para que um segundo examinador reproduza a sequência de consultas e chegue ao mesmo conjunto de itens.

*Requisitos acrescentados na revisão de durabilidade de 2026-08-04 (ver R7 em [research.md](./research.md)). Numeração não sequencial porque os identificadores anteriores já estão em uso.*

- **FR-071**: O sistema MUST gravar a trilha em área de auditoria da estação, com escrita e sincronização em disco **a cada operação**, de modo que encerramento anormal não perca o que já foi executado.
- **FR-072**: O sistema MUST sincronizar a trilha **automaticamente** para subpasta de auditoria dentro da pasta do caso, no encerramento da sessão e periodicamente durante ela. A co-localização MUST NOT depender de ação manual do perito.
- **FR-073**: Quando a pasta do caso não for gravável, o sistema MUST manter a cópia da estação como autoritativa e MUST advertir o perito na abertura da sessão de que a trilha não poderá ser co-localizada com o caso.
- **FR-074**: Na abertura de um caso, o sistema MUST verificar se existe trilha anterior daquele caso na área da estação sem correspondente na pasta do caso e, havendo, MUST reportá-la ao perito — convertendo perda silenciosa em perda visível.

#### Confidencialidade e egresso de conteúdo

Por decisão de escopo, **não há restrição de conteúdo por padrão**: metadados, texto, miniatura e conteúdo binário ficam disponíveis ao agente, e a responsabilidade sobre o que sai do ambiente pericial é de quem opera a estação. A política existe como recurso opcional, para quem precise ativá-la.

- **FR-038**: O sistema MUST permitir, por padrão, o retorno de metadados, texto extraído, miniatura e conteúdo binário de qualquer item, sujeito apenas aos limites de volume de FR-021.
- **FR-039**: O sistema MUST oferecer uma política de egresso opcional que, quando ativada, restrinja quais classes de conteúdo de evidência podem ser devolvidas ao agente, distinguindo, no mínimo, metadados de conteúdo (texto, miniatura e binário) e permitindo restrição por categoria ou classificação de sensibilidade atribuída no processamento.
- **FR-040**: Quando ativada, a política de egresso MUST ser aplicada pelo próprio sistema, de modo que o agente não consiga contorná-la pela escolha de ferramenta ou parâmetro.
- **FR-041**: O sistema MUST registrar na auditoria toda vez que conteúdo for bloqueado pela política, identificando o item e a regra aplicada.
- **FR-042**: O sistema MUST permitir que o perito consulte a política vigente na sessão, inclusive quando ela estiver inativa.
- **FR-043**: O sistema MUST advertir o perito, na abertura da sessão, sobre qual conteúdo de evidência poderá ser transmitido ao modelo de linguagem na configuração vigente.

#### Orientação do agente (skill)

- **FR-044**: A skill MUST orientar o agente a se orientar antes de consultar (panorama do caso, marcadores existentes, vocabulário de campos) e a estreitar consultas progressivamente, apresentando contagens antes de inspecionar itens.
- **FR-045**: A skill MUST instruir o agente a amostrar quando o volume for alto e a consultar o perito sobre refinamento, em vez de processar conjuntos grandes item a item.
- **FR-046**: A skill MUST exigir que toda conclusão apresentada ao perito seja acompanhada dos identificadores dos itens que a sustentam.
- **FR-047**: A skill MUST proibir o agente de afirmar ausência de evidência sem antes ter validado o vocabulário de campos e testado formulações alternativas da consulta.
- **FR-048**: A skill MUST proibir o agente de inferir, extrapolar ou preencher lacunas sobre o conteúdo da evidência; afirmações devem se limitar ao que os dados retornados sustentam, e incertezas devem ser declaradas.
- **FR-049**: A skill MUST conter fluxos de trabalho de ponta a ponta para os cenários periciais recorrentes (localização geográfica, análise de conversas, itens deletados e recuperados, correspondência por hash, correlação por e-mail, linha do tempo, levantamento de dados pessoais, panorama de acervo).
- **FR-050**: A skill MUST descrever a sintaxe de consulta e o vocabulário canônico de campos do IPED, incluindo as divergências conhecidas entre versões e configurações, e MUST subordinar essa documentação à descoberta em tempo de execução em caso de conflito.
- **FR-051**: A skill MUST ser organizada de modo que apenas o material relevante ao pedido em curso seja carregado, mantendo o custo de contexto proporcional à tarefa.
- **FR-052**: A skill MUST instruir o agente a tratar material de evidência como sensível ao apresentá-lo ao perito, evitando reprodução desnecessária de conteúdo cuja natureza a própria consulta já indica ser ilícito ou protegido por sigilo.

#### Instalação e diagnóstico

- **FR-053**: O sistema MUST oferecer uma verificação de diagnóstico que valide todos os pré-requisitos e reporte especificamente o que estiver faltando e como corrigir.
- **FR-054**: O sistema MUST ser distribuído junto com o IPED e MUST suportar casos produzidos por qualquer versão da linha **4.x**, que é a faixa declarada e testada. Para casos fora dessa faixa, o sistema MUST tentar abrir, declarar o que conseguiu interpretar e recusar com diagnóstico específico quando não for possível — nunca falhando de forma obscura nem apresentando leitura parcial como completa.
- **FR-055**: A configuração MUST NOT exigir a inclusão de caminhos específicos de máquina ou credenciais em arquivos versionados.
- **FR-056**: O sistema MUST registrar seu próprio diagnóstico operacional de forma separada da trilha de auditoria pericial.
- **FR-057**: O sistema MUST operar como componente local da estação de trabalho, sem expor sua superfície de ferramentas à rede na configuração padrão.

#### Portabilidade entre harnesses

A integração é consumida por mais de um harness de agente e, no futuro, por um painel de conversa dentro da própria UI do IPED, que acionará um harness em segundo plano. Nada aqui pode presumir um cliente específico nem um humano configurando um aplicativo de desktop.

- **FR-062**: O servidor MUST aderir ao protocolo padrão de ferramentas de agente e MUST NOT depender de recurso exclusivo de um cliente. A entrega MUST ser verificada funcionando com Claude Code, Codex e OpenCode.
- **FR-063**: O conteúdo instrucional da skill MUST ter fonte canônica única, com empacotamento fino por harness. Conteúdo duplicado entre formatos MUST NOT ser mantido em paralelo, para que a orientação não divirja entre harnesses.
- **FR-064**: O servidor MUST poder ser iniciado e conectado de forma programática, por um processo hospedeiro, sem depender de edição manual de configuração por um humano.
- **FR-065**: A integração MUST permanecer funcional quando acionada por um harness executando um modelo de linguagem local, e MUST NOT depender de capacidade disponível apenas em modelos de provedores externos.
- **FR-078**: Uma mensagem malformada MUST ser respondida com o erro de protocolo previsto e descartada, e MUST NOT encerrar a sessão. Uma única mensagem mal escrita não pode custar a sessão inteira nem os casos abertos nela — o agente que a produziu precisa receber diagnóstico corrigível, não conexão morta.

#### Geração de artefatos de saída

Requisitos que sustentam a US3, que até a clarificação de 2026-08-04 não tinha nenhum.

- **FR-066**: O sistema MUST gerar artefatos de saída a partir de um conjunto de itens definido por marcador, por resultado de consulta ou por lista explícita, nos formatos **xlsx**, **CSV** e **JSON**.
- **FR-067**: O artefato MUST conter o conjunto **completo** de itens, sem paginação nem truncamento, e MUST ser produzido sem trafegar os itens pela conversa — a conversa recebe apenas contagem, amostra e o caminho do arquivo gerado.
- **FR-068**: O destino do artefato MUST ser escolhido pelo perito e MUST NOT ser, por padrão, a pasta do caso, para preservar a garantia de SC-003.
- **FR-069**: Para conjuntos de mensagens, o sistema MUST produzir artefato agrupado por conversa e ordenado cronologicamente, identificando remetente e destinatário.
- **FR-070**: Sobre um conjunto vazio, o sistema MUST informar a ausência de itens e MUST NOT gerar artefato vazio. Toda geração de artefato MUST ser registrada na trilha de auditoria com a definição do conjunto, a contagem e o destino, de modo que o artefato seja reproduzível.

#### Preparo de evidência *(fase 2 — fora da entrega inicial, ver US5)*

- **FR-058**: O sistema MUST permitir iniciar o processamento de uma evidência bruta em um caso de destino, com apresentação prévia do plano completo para confirmação do perito.
- **FR-059**: O sistema MUST NOT sobrescrever um destino já ocupado; MUST apresentar as alternativas disponíveis e exigir escolha explícita, com alerta reforçado para a alternativa destrutiva.
- **FR-060**: O sistema MUST reportar o andamento e o desfecho do processamento com base no resultado real da execução, e MUST NOT apresentar como utilizável um caso cujo processamento falhou.
- **FR-061**: O sistema MUST validar a existência e a acessibilidade da evidência de origem antes de iniciar o processamento.

---

### Key Entities

- **Caso**: acervo processado do IPED, identificado pelo caminho da sua pasta de saída. Agrega itens, marcadores, estado de seleção e o vocabulário de campos do seu índice. É a unidade de acesso e de escopo de todas as operações.
- **Evidência**: fonte ingerida que compõe um caso (imagem forense, extração de dispositivo, pasta, drive). Um caso pode conter várias; itens carregam a evidência de origem.
- **Item**: unidade de análise — arquivo, registro, mensagem, anexo. Identificado por caso + identificador local. Possui propriedades essenciais, metadados extraídos, campos indexados, texto, miniatura e conteúdo bruto, além de relação hierárquica com contêiner pai e itens contidos.
- **Consulta**: expressão de busca sobre o índice de um caso, com paginação e limite de tempo. Produz uma contagem total, uma página de resultados enriquecidos e, opcionalmente, agregações.
- **Vocabulário de campos**: conjunto de nomes de campos efetivamente presentes no índice de um caso. Varia por versão do IPED, parsers executados e configuração; é a base para montar consultas válidas.
- **Marcador**: rótulo aplicado a itens para agrupar achados. Persistido no caso e visível na UI do IPED e nos relatórios.
- **Seleção**: estado de marcação de itens, distinto de marcador, usado como conjunto de trabalho e base de exportação.
- **Trilha de auditoria**: registro cronológico, somente-acréscimo e com adulteração detectável, de todas as operações da sessão. Escrita na área de auditoria da estação a cada operação — que funciona como buffer write-ahead — e sincronizada automaticamente para dentro da pasta do caso, que é seu lar durável. Vinculada ao caso por caminho canônico e identidade do índice, o que permite reassociar uma trilha órfã. Base da cadeia de custódia e da reprodutibilidade da análise.
- **Política de egresso**: conjunto de regras que determina quais classes de conteúdo de evidência podem ser devolvidas ao agente. Aplicada pelo sistema, consultável pelo perito e registrada na auditoria quando bloqueia conteúdo.
- **Sessão**: contexto de trabalho que associa um operador, os casos abertos, o modo de acesso (somente-leitura ou escrita habilitada), a política de egresso vigente e a trilha de auditoria correspondente.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em um caso com 10 milhões de itens, o perito obtém resposta fundamentada e com itens citados para uma pergunta de investigação típica em menos de 2 minutos, contra 15 minutos ou mais de navegação manual equivalente.
- **SC-002**: 95% das consultas sobre um caso de 10 milhões de itens retornam a primeira página de resultados em menos de 5 segundos.
- **SC-003**: Em modo somente-leitura, a **evidência, o índice e o estado de análise** (marcadores, seleção) permanecem bit a bit idênticos após uma sessão completa, e os arquivos de evidência original permanecem inalterados em qualquer modo. A subpasta de auditoria é excluída da verificação **por nome**, por ser registro sobre o exame e não parte do acervo examinado; tudo fora dela é verificado, de modo que uma escrita indevida em qualquer outro lugar reprova o critério.
- **SC-004**: 100% das operações executadas na sessão constam da trilha de auditoria, e 100% das operações de escrita registram o estado anterior; a completude é verificável por reconciliação entre trilha e estado final do caso.
- **SC-005**: Um segundo examinador, partindo apenas da trilha de auditoria exportada, reproduz a análise e chega ao mesmo conjunto de itens em 100% dos casos de teste.
- **SC-006**: Menos de 5% das consultas emitidas pelo agente retornam zero resultados por nome de campo inexistente, contra a linha de base medida nas provas de conceito.
- **SC-007**: Nenhuma resposta a consulta excede o limite de volume definido, mesmo para consultas que correspondem a milhões de itens; a sessão nunca é interrompida por esgotamento de contexto causado por uma única resposta de ferramenta.
- **SC-008**: Em uma bateria de 30 perguntas de investigação sobre um caso de referência com conteúdo conhecido, o assistente atinge no mínimo 90% de acerto sem nenhum falso positivo apresentado como conclusão.
- **SC-009**: Nenhuma afirmação conclusiva do assistente aparece sem os itens que a sustentam, em 100% das respostas avaliadas.
- **SC-010**: Um perito sem experiência prévia com integrações de agente instala, conecta e obtém a primeira resposta em menos de 15 minutos seguindo apenas a documentação fornecida, e isso é verificado em **cada** harness suportado (Claude Code, Codex, OpenCode) — não apenas naquele usado durante o desenvolvimento.
- **SC-011**: Nenhuma configuração incorreta ou pré-requisito ausente produz erro técnico opaco: 100% das falhas de configuração testadas resultam em diagnóstico acionável.
- **SC-012**: A geração de um artefato de saída sobre um marcador de 5.000 itens conclui integralmente, nos três formatos suportados (xlsx, CSV, JSON), sem trafegar os itens pela conversa e com os 5.000 registros presentes e corretos no arquivo.
- **SC-013**: A integração abre e consulta, sem reconfiguração, casos produzidos por qualquer versão da linha 4.x do IPED — verificado sobre ao menos um caso da versão mais antiga e um da mais recente da linha. Casos fora da linha 4.x que não puderem ser interpretados são recusados com diagnóstico específico em 100% das tentativas, sem leitura parcial apresentada como completa.
- **SC-014**: Com a política de egresso ativada, nenhum conteúdo por ela bloqueado alcança o agente em 100% das tentativas de contorno testadas; com a política inativa, o perito é advertido na abertura da sessão sobre o conteúdo que poderá ser transmitido, em 100% das sessões.
- **SC-015**: Em um caso de 10 milhões de itens, a abertura do caso com o panorama inicial (FR-006) conclui em menos de 30 segundos, e uma agregação sobre o acervo completo (FR-016) conclui em menos de 15 segundos. Estas são as operações cujo custo cresce com o acervo e não com o resultado, e portanto as que determinam se o teto de escala é real.

---

## Decisões de escopo

Resolvidas com o solicitante em 2026-08-04.

- **D1 — Preparo de evidência fica para a fase 2.** A entrega inicial cobre apenas a análise de casos já processados. A US5 e os FR-058 a FR-061 permanecem escritos no spec para preservar a visão de ponta a ponta, mas estão explicitamente fora do plano e das tarefas desta entrega.
- **D2 — Implantação em estação de trabalho individual.** Um operador, componente local, sem exposição de rede na configuração padrão (FR-057). Dispensa autenticação, isolamento entre operadores e controle de concorrência entre sessões remotas; a detecção de concorrência se limita a outros processos na mesma máquina, tipicamente a UI do IPED (FR-028). A trilha de auditoria identifica o operador da estação (FR-032).
- **D3 — Sem restrição de conteúdo por padrão; política de egresso opcional.** Metadados, texto, miniatura e binário ficam disponíveis ao agente por padrão (FR-038), sujeitos apenas aos limites de volume. A política de egresso existe como recurso ativável (FR-039 a FR-042) e o perito é advertido na abertura da sessão sobre o que poderá ser transmitido na configuração vigente (FR-043). A responsabilidade sobre o que sai do ambiente pericial é de quem opera a estação.

- **D4 — Múltiplos harnesses, com LLM local como caminho preferencial.** A integração é consumida por Claude Code, Codex e OpenCode (FR-062 a FR-065), sendo OpenCode o preferido por executar modelos locais. Servidor aderente ao protocolo padrão, skill com fonte canônica única e inicialização programática.

**Consequência a observar no planejamento**: D3 significa que conteúdo de evidência — potencialmente incluindo material ilícito, dados pessoais e material sob sigilo — é transmitido ao modelo de linguagem por padrão. D4 é o que torna isso aceitável: com um harness executando modelo **local**, o conteúdo não sai da estação, e a política de egresso opcional deixa de ser a única salvaguarda. A recomendação de operação padrão (modelo local) deve ser explícita no plano e na documentação de instalação; usar provedor externo passa a ser a escolha que exige ativar a política de egresso.

## Direção futura *(fora do escopo desta entrega)*

O objetivo de longo prazo é um painel de conversa dentro da UI do IPED — no estilo do chat do VS Code — em que o analista "conversa com a evidência". Esse painel não implementa lógica de análise própria: ele aciona um harness em segundo plano, que consome exatamente este mesmo servidor MCP e esta mesma skill.

Nada disso é construído aqui, e o plano não deve cobri-lo. Está registrado porque restringe o desenho atual de três formas concretas, já refletidas em FR-062 a FR-065: o consumidor pode ser um processo e não uma pessoa, o harness não é conhecido de antemão, e a skill precisa sobreviver a mais de um formato de empacotamento.

---

## Assumptions

Suposições adotadas na ausência de definição explícita. Cada uma é um ponto de reversão barato se contrariada.

- **Localização do artefato**: a integração vive neste repositório do IPED e é distribuída com o release, versionando junto com o produto. Isso é o que dá a garantia de compatibilidade declarada em FR-054 e evita a deriva entre versões que as provas de conceito, mantidas fora do repositório, já apresentam.
- **Público**: peritos e analistas forenses, com domínio do IPED como ferramenta mas sem familiaridade com sintaxe de consulta de índice, protocolos de agente ou configuração de servidores.
- **Estado das evidências**: os casos analisados já foram processados e estão íntegros. Análise de casos em processamento está fora de escopo.
- **Escala alvo**: até ~10 milhões de itens por caso, o que cobre uma operação com vários dispositivos apreendidos. O IPED suporta acervos maiores; acima desse teto a integração deve continuar funcionando, mas os alvos de desempenho (SC-001, SC-002, SC-015) não são garantidos. O desenho não deve criar barreiras que impeçam elevar esse teto depois — em especial, operações cujo custo cresça com o acervo devem ficar confinadas a panorama e agregações.
- **Escopo de consulta**: consultas operam sobre um caso por vez. Ter vários casos abertos na mesma sessão é suportado; busca federada com resultado unificado entre casos fica fora desta entrega.
- **Idioma**: as instruções da skill e a documentação técnica são escritas em inglês, conforme a convenção do repositório para material novo; o assistente conversa com o perito no idioma que o perito usar. Mensagens de diagnóstico voltadas ao perito são localizadas em português e inglês.
- **Modo padrão**: somente-leitura. A habilitação de escrita é decisão de quem opera o ambiente, tomada fora do alcance do agente.
- **Retenção da auditoria**: a trilha acompanha o caso por sincronização automática (FR-072) e é retida pelo mesmo prazo dele, seguindo a política já vigente para o acervo pericial. Resta um ponto que a solução técnica não cobre: se o caso não for arquivado corretamente, a trilha se perde com ele. Isso é organizacional, não técnico, e vale confirmar antes da implantação.
- **Aproveitamento das provas de conceito**: as POCs são referência de escopo funcional e de aprendizado — em especial o mapeamento de campos e os fluxos periciais documentados — mas não são base de código. As lacunas que motivam esta feature (ausência de paginação, de auditoria, de proteção de escrita e de política de egresso) são estruturais e não se resolvem por incremento sobre elas.
- **Ambiente de execução**: estação de trabalho pericial individual (D2), com o IPED instalado e o caso em armazenamento local ou de rede acessível. A integração em si não depende de conectividade externa; o modelo de linguagem que a consome pode depender, conforme o provedor escolhido.
- **Identidade do operador**: por ser implantação individual (D2), a identidade registrada na auditoria é a do operador da estação, obtida do ambiente. Não há autenticação própria da integração.
- **Caso de referência para testes**: existirá um caso pequeno, de conteúdo conhecido e não sensível, versionável ou reconstruível, usado como base das verificações de aceitação. Sem ele, nenhum dos critérios de sucesso é verificável de forma repetível.
