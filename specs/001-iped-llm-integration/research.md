# Phase 0 — Pesquisa e decisões técnicas

**Feature**: 001-iped-llm-integration | **Data**: 2026-08-04

Todas as incógnitas do Technical Context foram resolvidas. Cada decisão abaixo foi verificada contra o código da árvore 4.3.1 ou contra fonte externa citada — nenhuma é presumida.

---

## R1 — Onde o servidor executa e como acessa o caso

**Decisão**: novo módulo Maven **`iped-mcp`** no próprio repositório, em **Java 11**, com acesso direto às APIs do `iped-engine` (`IPEDSource`, `QueryBuilder`, `Bookmarks`, `LoadIndexFields`).

**Rationale**:
- O spec fixa que a integração é distribuída com o release (FR-054) e que o release embarca um **JRE 11** (`iped-app/pom.xml`, alvo `java:jre:zip`). Qualquer artefato precisa rodar nesse runtime.
- FR-064 exige inicialização programática por um processo hospedeiro. O consumidor futuro é a UI do IPED, que é Java — subir um processo filho Java é trivial e não introduz runtime novo.
- Acesso direto elimina a ponte JVM↔Python da POC, que é a origem de boa parte da fragilidade dela (bootstrap manual da JVM, `_ensure_jvm` em toda chamada, dicionário global sem proteção de concorrência).
- `parent pom.xml` fixa `maven.compiler.source/target = 11` e os módulos herdam. Um módulo novo se encaixa sem tocar em nada existente.

**Alternativas rejeitadas**:
- **Servidor Python + pyjnius (abordagem da POC)**: avaliada explicitamente a pedido, e rejeitada por um fato que parece contrariá-la mas não contraria. O IPED **já distribui Python**, porém via **JEP** — CPython embarcado *dentro* da JVM, dirigido por Java (`iped-engine/.../task/PythonTask.java`, `iped-app/resources/scripts/tasks/*.py`). A POC usa **pyjnius, que é a direção oposta**: JVM embarcada dentro do Python. Um servidor MCP em Python precisaria de pyjnius, que a distribuição não traz — seria uma segunda pilha nativa no release, não reaproveitamento da existente.

  O balanço decisivo é de onde cai o custo. Python compra o SDK oficial e elimina o risco de R2; em troca, tudo o que dá valor a esta feature — `QueryBuilder` + `searchAfter` (R3), `SortedSetDocValues` (R4), `Highlighter` (R5) — passa a cruzar a ponte, sem tipagem. A camada de protocolo é escrita **uma vez** e é pequena; a ponte é paga **em toda ferramenta**, e é onde a POC já se mostrava frágil. Somam-se a cadeia de inicialização com duas JVMs no mesmo fluxo (UI do IPED → Python → JVM do jnius) e a pressão sobre SC-010, que exige instalação em 15 minutos por quem não é desenvolvedor.
- **Adaptador sobre a Web API existente** (`iped.engine.webapi`, Jersey+Grizzly): reaproveitaria pouco. Aquela API não tem paginação, agregação nem descoberta de vocabulário — os três problemas centrais desta feature —, e acrescentaria um salto HTTP que conflita com FR-057 (sem exposição de rede por padrão).
- **Módulo em Java 17+**: inviável. O JRE embarcado é 11, e o `CLAUDE.md` do repositório é explícito em que a migração para Java 21 vive no branch `master` e não se aplica a esta árvore.

---

## R2 — Protocolo MCP em Java 11

**Decisão**: implementar a superfície MCP necessária **diretamente**, sobre JSON-RPC 2.0 em transporte **stdio**, usando Jackson (já presente na árvore via `jersey-media-json-jackson` e `jackson-core`).

**Rationale**:
- O SDK oficial `io.modelcontextprotocol.sdk` declara baseline **Java 17+** — verificado no repositório oficial, que exibe o selo "Java Version: 17+". É incompatível com o JRE 11 embarcado. Este é o achado que mais condiciona o plano.
- A superfície necessária para um servidor só-de-ferramentas é pequena: handshake `initialize`, `tools/list`, `tools/call` e as notificações associadas. É JSON-RPC 2.0 sobre stdin/stdout — bem delimitado e testável.
- **stdio** é o transporte com melhor suporte transversal entre os harnesses alvo (FR-062) e é o que não expõe porta de rede, satisfazendo FR-057 por construção em vez de por configuração.

**Alternativas rejeitadas**:
- **Adotar o SDK oficial mesmo assim**: quebraria o build da árvore e não rodaria no runtime distribuído.
- **Elevar o módulo para Java 17 isoladamente**: o Maven permite compilar um módulo com `release` maior, mas o artefato não executaria no JRE 11 do release — trocaria um problema de build por um problema de runtime em campo, que é pior.

**Risco assumido**: passamos a acompanhar mudanças de versão do protocolo por conta própria. Mitigação: isolar a camada de protocolo do restante, com testes de contrato sobre o handshake, e negociar/declarar a versão suportada explicitamente.

---

## R3 — Paginação e contagem total

**Decisão**: **não usar `IPEDSearcher`** no caminho de consulta paginada. Usar `QueryBuilder` para interpretar e reescrever a consulta, e então `IndexSearcher.searchAfter(...)` do Lucene para colher apenas a página pedida; contagem total por `IndexSearcher.count(query)`.

**Rationale**: este é o achado mais importante da pesquisa. `IPEDSearcher.searchAll()` (`iped-engine/src/main/java/iped/engine/search/IPEDSearcher.java:143`) coleta **todos** os documentos correspondentes:

```java
collector = new NoScoringCollector(ipedCase.getReader().maxDoc());
ipedCase.getSearcher().search(query, collector);
```

e, quando pontua, itera `searchAfter` em blocos de `MAX_SIZE_TO_SCORE = 1000000` **até esgotar o conjunto**. Ou seja, a ausência de paginação não é um defeito da POC — está na API do engine que a POC chamou. Consultar `IPEDSearcher` num caso de 10 M com consulta ampla materializa o conjunto inteiro, o que inviabiliza SC-002 e contraria FR-013.

Reaproveitamos de `QueryBuilder` o que carrega semântica do IPED e não pode ser reimplementado: `getQuery(String)` (sintaxe de consulta do IPED, FR-011), `rewriteQuery(...)` e `getMatchAllItemsQuery()`.

**Determinismo (FR-019)**: ordenação explícita com desempate estável — por score e, em empate, por ordem de documento (`SortField.FIELD_DOC`) — para que a mesma consulta produza a mesma página na mesma ordem.

**Alternativas rejeitadas**:
- **Paginar sobre o resultado de `IPEDSearcher`**: resolveria a resposta, não o custo. O trabalho caro já teria sido feito.
- **Alterar `IPEDSearcher` para paginar**: mexeria em API central usada pela UI e por outros consumidores; o `CLAUDE.md` do engine marca a área de busca como sensível. Fora de escopo e desnecessário — a paginação vive no módulo novo.

---

## R4 — Agregações sem `lucene-facet`

**Decisão**: implementar contagens agregadas sobre **DocValues** (`SortedSetDocValues`), seguindo o padrão já usado em `TimelineResults`.

**Rationale**: o `iped-engine/pom.xml` declara `lucene-core`, `analysis-common`, `backward-codecs`, `highlighter`, `queryparser`, `misc` e `join` — **não há `lucene-facet`**. Introduzir o módulo de facets exigiria índice construído com `FacetField`, o que casos já processados não têm; seria inútil para o acervo existente e contrariaria FR-054.

`TimelineResults` (`iped-engine/.../search/TimelineResults.java`) já demonstra o acesso a `SortedSetDocValues` sobre o `AtomicReader` do caso, que é o mecanismo disponível e compatível com índices existentes.

**Consequência a vigiar**: agregação por DocValues custa proporcional ao acervo, não ao resultado. É exatamente o que SC-015 mede (agregação < 15 s em 10 M). Se a medição reprovar, o recurso é cache do panorama por caso, invalidado por versão do índice — não mudança de estratégia.

---

## R5 — Trechos de contexto (snippets)

**Decisão**: usar `lucene-highlighter`, já declarado como dependência do `iped-engine` na versão 9.2.0.

**Rationale**: FR-015 pede um trecho por item evidenciando a correspondência. Sem isso o agente recebe uma lista de nomes de arquivo e precisa abrir cada item para entender por que casou — que é justamente o padrão de N chamadas que esta feature existe para eliminar. A dependência já está na árvore; não há custo de adoção.

**Limite**: só se aplica a itens com conteúdo textual indexado. Para os demais, o campo de trecho vem ausente e declarado como tal, conforme FR-022.

---

## R6 — Descoberta de vocabulário de campos

**Decisão**: expor `LoadIndexFields.getFields(...)` do engine, complementado por sugestão de campos semelhantes por distância de edição.

**Rationale**: `iped-engine/.../search/LoadIndexFields.java` já lê os `FieldInfos` de todos os segmentos e devolve os nomes reais do índice, excluindo campos internos. É exatamente o que FR-007 pede e já está pronto — a "regra de ouro" que a POC documentava como procedimento manual vira uma ferramenta.

FR-008 (sugerir campos próximos quando o nome não existe) é acréscimo pequeno sobre essa lista. É o que fecha o laço de autocorreção descrito na US1 e o que sustenta SC-006.

---

## R7 — Trilha de auditoria: formato, local e durabilidade

**Status**: decisão **reaberta e fechada em 2026-08-04**, conforme mandava T007. Substitui a resolução provisória da clarificação (opção C).

**Decisão**: registro **append-only** encadeado por hash, em **JSON Lines**. A **área de auditoria da estação é buffer write-ahead**, não o lar da trilha — o lar é a **pasta do caso**, para onde a trilha é sincronizada **automaticamente**, com degradação explícita quando o caso está em mídia somente-leitura.

**Rationale — o formato**:
- Append-only com encadeamento de hash dá a detecção de adulteração de FR-034 sem infraestrutura externa: alterar ou remover um registro quebra a cadeia a partir dali.
- JSON Lines satisfaz FR-036 nos dois eixos — legível por humano e processável por máquina — e é naturalmente append-only.

**Rationale — o local**. A resolução anterior conflacionava duas propriedades distintas:

| Propriedade | O que protege | Mecanismo |
|---|---|---|
| **Durabilidade contra crash** | Encerramento anormal no meio da sessão | Escrita e `fsync` por operação na área da estação |
| **Sobrevivência a handoff e reimagem** | Caso entregue ou estação formatada | Co-localização com o caso |

A escrita com `fsync` por operação resolve a primeira e não toca a segunda. Sob a decisão D2 — estação isolada, sem rede — **a pasta do caso é o único armazenamento durável disponível**, porque é ela que é arquivada e entregue. Logo a co-localização não é conveniência de entrega: é o mecanismo de durabilidade.

**Por que a objeção original a colocar a trilha no caso não se sustenta.** A recomendação anterior invocou a imutabilidade da pasta como "a propriedade forense mais forte". Isso confundiu **a evidência e o estado de análise não podem ser alterados** — que é a garantia real — com **a pasta não pode mudar como objeto de sistema de arquivos**, que é apenas um proxy conveniente por ser testável com um hash. A analogia física é direta: o formulário de cadeia de custódia viaja dentro do saco de evidência, e ninguém considera o saco adulterado por isso. O formulário não é a evidência; é o registro sobre o exame dela.

**Por que a trilha também não pode viver só dentro do caso.** Casos periciais ficam com frequência em mídia protegida contra escrita ou em compartilhamento somente-leitura. Se a pasta do caso fosse o único destino possível, FR-035 recusaria toda operação nesse cenário e a ferramenta ficaria inutilizável num caso de uso comum. Daí o desenho em dois níveis.

**Desenho resultante**:

1. Escrita na área de auditoria da estação com `fsync` a cada operação — durabilidade contra crash.
2. Sincronização **automática** para subpasta de auditoria dentro do caso, no encerramento e periodicamente durante a sessão. Não é ação manual do perito.
3. Caso em mídia somente-leitura: a cópia da estação é autoritativa e a sessão **avisa na abertura** que a trilha não poderá ser co-localizada.
4. Cada registro carrega vínculo forte com o caso — caminho canônico mais identidade do índice — permitindo reassociar uma trilha órfã.
5. Na abertura, se existe trilha anterior daquele caso na estação mas não na pasta do caso, a sessão **reporta**. É o item de maior valor do conjunto: os demais reduzem a chance de perder, este garante que uma perda seja percebida em vez de descoberta no momento em que a trilha seria necessária.

**Consequência sobre SC-003**: o critério passa a garantir que **evidência, índice e estado de análise** (marcadores, seleção) permanecem bit a bit idênticos em modo somente-leitura, com a subpasta de auditoria excluída **por nome**. A exclusão é estreita e o teste continua verificável: tudo fora dela é hasheado, de modo que uma escrita indevida em qualquer outro lugar ainda reprova.

**Falha ao registrar** (FR-035): a operação é recusada antes de executar, não depois. Registro primeiro, ação em seguida.

**O que continua não resolvido, e não deve ser apresentado como resolvido**: se o caso não for arquivado corretamente, ou se a política de retenção do acervo não cobrir o que foi entregue, a trilha se perde com ele. Isso é organizacional, não técnico, e permanece registrado nas suposições do spec.

---

## R8 — Detecção de acesso concorrente

**Decisão**: arquivo de trava na área de trabalho do servidor, combinado com verificação da trava que a própria UI do IPED mantém sobre o caso; leitura nunca é bloqueada, escrita é recusada quando há outro acessor.

**Rationale**: FR-028 restringe a exigência a outros processos na mesma máquina, o que a decisão D2 (estação individual) torna suficiente. `Bookmarks` expõe métodos `synchronized` e `saveState`, o que protege dentro do processo mas não entre processos — daí a necessidade da trava externa. O modo padrão somente-leitura (FR-025) já torna o caminho de escrita excepcional.

---

## R9 — Portabilidade entre harnesses e fonte única da skill

**Decisão**: transporte stdio, sem recurso específico de cliente; conteúdo instrucional da skill em **Markdown canônico único**, com empacotadores finos por harness gerados no build.

**Rationale**: FR-062 exige verificação em Claude Code, Codex e OpenCode; FR-063 proíbe conteúdo duplicado entre formatos. Os harnesses divergem no **empacotamento** (arquivo de entrada, frontmatter, convenção de diretório), não no conteúdo instrucional. Manter um corpo canônico e gerar os invólucros mantém a orientação idêntica entre harnesses — que é o que FR-063 realmente protege: orientação divergente entre harnesses produziria análises divergentes sobre a mesma evidência.

**Carga de contexto (FR-051)**: a skill se divide em um documento de entrada enxuto e referências carregadas sob demanda, preservando a estrutura que a POC já acertou.

---

## R10 — Modelo local como operação recomendada

**Decisão**: documentar operação com harness de modelo local (OpenCode) como configuração recomendada, e verificar SC-014 nos dois modos.

**Rationale**: a decisão D3 do spec dispensa restrição de egresso por padrão, o que significa que conteúdo de evidência chega ao modelo. D4 é o que torna isso aceitável: com modelo local, o conteúdo não sai da estação. FR-065 exige que a integração permaneça funcional nesse cenário, o que impõe uma restrição concreta de desenho — as ferramentas precisam ser autoexplicativas e de baixa exigência de raciocínio, sem depender de capacidade que só modelos de fronteira têm.

**Consequência prática**: descrições de ferramenta e mensagens de erro precisam ser acionáveis por si só. Um erro do tipo "campo inexistente" tem de vir com a lista de campos próximos (FR-008) em vez de esperar que o modelo deduza o que fazer.

---

## R11 — Tool Search Tool e Programmatic Tool Calling

**Decisão**: **não adotar** nenhum dos dois como dependência. Ambos são recursos da API da Anthropic, configurados por quem chama a API — não por quem expõe ferramentas via MCP.

**Programmatic Tool Calling (PTC)** é **incompatível com ferramentas MCP** por documentação explícita (junto com `strict: true`, `disable_parallel_tool_use` e `tool_choice` forçado). Exige `code_execution_20260120` mais `allowed_callers: ["code_execution_20260120"]` **na definição da ferramenta custom**, o que um servidor MCP não tem como declarar. Não é escolha de desenho: é incompatibilidade declarada.

A avaliação valeu assim mesmo, porque **o problema que o PTC resolve é o problema central desta feature**. Ele faz o resultado da ferramenta retornar ao script em execução em vez do contexto do modelo, e só a saída final chega ao modelo — exatamente o que FR-067 e SC-012 exigem ao gerar artefato de 5.000 itens sem trafegá-los pela conversa.

A diferença é **onde** o problema é resolvido. O PTC resolve no cliente; este desenho resolve **no servidor**: agregações sem materializar itens (FR-016), lote em uma chamada (FR-024), artefato gravado em disco com apenas contagem, amostra e caminho retornando (FR-067). Mesma economia de contexto, por um caminho que funciona em Claude Code, Codex, OpenCode e modelo local — que é o que FR-062 e FR-065 exigem e o que o PTC não entregaria.

**Tool Search Tool** (`tool_search_tool_regex_20251119` / `_bm25_20251119`) é compatível com MCP e não tem impedimento, mas fica abaixo do limiar de utilidade: paga-se quando há algumas dezenas de ferramentas, e a superfície são 22. Quem o ativa é o harness — marcando ferramentas com `defer_loading: true` e declarando a ferramenta de busca. Do lado do servidor não há implementação; há apenas a obrigação de não atrapalhar, mantendo nomes e descrições que sobrevivam a uma busca por relevância. O contrato de ferramentas já satisfaz isso.

**Consequência para a fase 2**: quando a UI do IPED ganhar o painel de conversa (ver "Direção futura" no spec), o cenário muda **se** esse painel chamar a API diretamente em vez de apenas acionar um harness. Nesse caminho as operações de lote e agregação passariam a ser ferramentas *custom*, não MCP, e o PTC se tornaria aplicável. Ressalva que já se pode registrar: o PTC não está disponível em Amazon Bedrock nem em Vertex AI, o que restringe a escolha de provedor e colide com a preferência por modelo local da decisão D4.

---

## Incógnitas remanescentes

Nenhuma bloqueia o desenho. Registradas para verificação empírica na implementação:

| Item | Como resolver |
|---|---|
| Agregação por DocValues cumpre SC-015 (< 15 s em 10 M)? | Medir sobre caso de referência grande; se reprovar, adicionar cache de panorama invalidado por versão do índice. |
| Abertura de caso cumpre SC-015 (< 30 s em 10 M)? | Medir custo de `IPEDSource` + contagens iniciais. |
| Retenção da área de auditoria da estação está coberta pela política do acervo? | Pergunta organizacional, não técnica. Confirmar antes da implantação. |
| Variação real de vocabulário dentro da linha 4.x | Levantar sobre casos reais de versões distintas ao montar a matriz de teste de SC-013. |
