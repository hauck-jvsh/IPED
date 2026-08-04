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

**Decisão**: registro **append-only** encadeado por hash (cada registro carrega o hash do anterior), gravado em **JSON Lines** na área de auditoria da estação, fora da pasta do caso, com **escrita e flush imediatos a cada operação**.

**Rationale**:
- Append-only com encadeamento de hash dá a detecção de adulteração de FR-034 sem infraestrutura externa: alterar ou remover um registro quebra a cadeia a partir dali.
- JSON Lines satisfaz FR-036 nos dois eixos — legível por humano e processável por máquina — e é naturalmente append-only.
- Fora da pasta do caso por decisão registrada na clarificação, o que preserva SC-003.

**Sobre o risco em aberto do spec**: a formulação original ("exportar ao encerrar a sessão") é o que cria a perda em encerramento anormal. A decisão de **escrever e sincronizar a cada operação**, em vez de acumular em memória e despejar no fim, elimina a maior parte desse risco: o que foi feito já está em disco quando a sessão morre. Resta a reassociação — por isso cada trilha carrega identificação forte do caso, permitindo reencontrá-la depois mesmo que a pasta tenha mudado de lugar. A exportação para dentro do caso passa a ser conveniência de entrega, não o mecanismo de durabilidade. **Isso reduz o risco; não o encerra** — a trilha ainda vive em disco local e depende da política de retenção da estação, ponto que o spec manda confirmar.

**Falha ao registrar** (FR-035): a operação é recusada antes de executar, não depois. Registro primeiro, ação em seguida.

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
