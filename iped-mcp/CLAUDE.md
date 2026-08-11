# Módulo `iped-mcp`

> **Servidor MCP.** Expõe casos IPED já processados a agentes de LLM, por JSON-RPC 2.0 sobre stdio, e distribui a skill que ensina o agente a usá-lo com disciplina pericial.

> Este módulo **não modifica nenhuma classe existente**. Consome apenas API pública do `iped-engine` e do `iped-api`. Os dois únicos arquivos existentes tocados são aditivos: o `pom.xml` da raiz (registro do módulo) e `iped-app/pom.xml` (empacotamento no release).

## 1. Propósito

- **Consulta paginada** de um caso, com contagem exata independente do que é devolvido.
- **Agregações** por dimensão sem materializar itens.
- **Descoberta de vocabulário** de campos, com sugestão de nomes próximos.
- **Inspeção de item**: metadados, texto, miniatura, conteúdo bruto, hierarquia — todos com teto de volume e ausência declarada.
- **Curadoria**: marcadores e seleção, desabilitados por padrão.
- **Trilha de auditoria** append-only encadeada por hash, gravada antes de cada operação.
- **Artefatos de saída**: xlsx, CSV e JSON do conjunto completo, sem trafegar pela conversa.
- **Política de egresso** opcional, aplicada no servidor.

Versão `4.3.1`. Java 11. Spec: [`specs/001-iped-llm-integration/`](../specs/001-iped-llm-integration/).

## 2. Estrutura

```
iped/mcp/
├── McpServerMain.java       # entry point stdio; inicialização programática (FR-064)
├── Diagnostics.java         # verificação de pré-requisitos, log por SLF4J
├── config/McpServerConfig   # Configurable<UTF8Properties> lido de conf/McpServerConfig.txt
├── protocol/                # JsonRpcCodec, McpError, ToolDescriptor, McpDispatcher
├── session/                 # Session, CaseRegistry, CaseValidator, OpenCase, ConcurrencyGuard
├── query/                   # PagedSearcher, Aggregator, SnippetBuilder, FieldVocabulary, FieldNames, Cursor
├── item/                    # ItemView, ContentAccess
├── curation/BookmarkWriter  # marcadores e seleção sobre Bookmarks/saveState
├── McpRelayMain.java        # relay stdio↔socket, para o harness em outra máquina
├── transport/               # Transport, StdioTransport, SocketTransport, HandshakeCodec
├── audit/                   # AuditRecord, AuditTrail, AuditSync, SessionManifest
├── egress/EgressPolicy      # opcional, inativa por padrão
├── export/ArtifactWriter    # xlsx (POI streaming), CSV, JSON
└── tools/                   # uma classe por grupo de ferramentas MCP
```

Recursos em `src/main/resources/skill/`: `SKILL.md` (fonte canônica), `references/`, `install/`.

## 3. Decisões que condicionam o desenho

Três achados de [research.md](../specs/001-iped-llm-integration/research.md) explicam por que o código é como é. Mexer neles sem reler a pesquisa costuma reintroduzir o problema que eles resolvem.

| Achado | Consequência no código |
|---|---|
| **O SDK MCP oficial exige Java 17+**; o release embarca JRE 11 | `protocol/` implementa JSON-RPC 2.0 direto sobre Jackson. Sem SDK, os testes de contrato do handshake são a única proteção contra regressão de protocolo. |
| **`IPEDSearcher.searchAll()` materializa todo o conjunto** | `PagedSearcher` **não usa `IPEDSearcher`**. Usa `QueryBuilder` para a semântica do IPED e `IndexSearcher` + `TopFieldCollector` + `searchAfter` para colher só a página. Trocar isso reintroduz o defeito que a feature existe para remover. |
| **Não há `lucene-facet` na árvore** e casos antigos não têm `FacetField` | `Aggregator` conta sobre `SortedSetDocValues`/`SortedDocValues`, no padrão de `TimelineResults`. |

Um quarto achado veio de campo, não da pesquisa:

| Achado | Consequência no código |
|---|---|
| **Boa parte do vocabulário é namespaced com `:`** (`p2p:fileType`, `ufed:UserID`, `ai:csamDetector:status`) e o parser lê `:` como separador entre campo e valor | `FieldNames` guarda a grafia de query (`p2p\:fileType`). As ferramentas de vocabulário devolvem `query_form` junto do nome cru, e os erros `QUERY_SYNTAX`/`UNKNOWN_FIELD` carregam a expressão corrigida já verificada contra o caso. Aspas **não** são alternativa: `"p2p:fileType":"mp3"` é erro de sintaxe. |

Duas consequências práticas menos óbvias:

- **O campo `content` é indexado mas não armazenado.** Snippet exige reextrair o texto do item, o que é caro. Por isso `SnippetBuilder` trabalha sob três orçamentos (itens por página, bytes por item, tempo por página) e declara ausência quando estoura, em vez de devolver vazio.
- **Registro precede ação.** Como a trilha é append-only, cada operação gera **dois** registros encadeados: `STARTED` antes de executar (com parâmetros e estado anterior) e o desfecho depois, ligado por `refSeq`. Se o `STARTED` não puder ser gravado, a operação é recusada e não executa.

## 4. Configuração

Tudo o que varia vive em `conf/McpServerConfig.txt` (Princípio IV da constituição), nunca em constante de código: área de auditoria, modo de acesso, política de egresso, tetos de página, de lote e de conteúdo, faixa de versão suportada, destino de exportação, reparo de nome de campo (`autoEscapeFieldNames`, desligado por padrão — ligado, uma expressão que só falha por colon não escapado é corrigida contra o vocabulário real do caso e o reparo vem declarado em `query_normalized`).

Acrescentado nesta linha de trabalho: **raízes de escrita** (`exportRoots`, separadas por `;` — vírgula cortaria caminho do Windows ao meio) e **transporte** (`transport`, `listenAddress`, `listenPort`, `sharedSecretFile`, `maxConcurrentSessions`, `sessionIdleTimeoutSeconds`). Endereço e porta **não têm padrão**, de propósito. O segredo tampouco vive aqui: a chave diz **onde** encontrá-lo.

`McpServerConfig` implementa `Configurable<UTF8Properties>` e é carregado pelo `ConfigurationManager`. Os valores no código são **fallback de último recurso** para quando o arquivo não existe (teste isolado, instalação quebrada); o arquivo distribuído é a autoridade e carrega os mesmos valores.

## 5. Invariantes que não podem ser afrouxadas

| Invariante | Onde é aplicada |
|---|---|
| Instalação padrão não abre porta | `transport = stdio` é o padrão em `McpServerConfig`; `McpServerMain.createTransport` só constrói `SocketTransport` quando a configuração pede. `NoNetworkExposureTest` verifica |
| Transporte de rede não existe sem autenticação | `SocketTransport.bind()` recusa quando `resolveSharedSecret()` devolve nulo. Não há caminho que sirva peer não autenticado — o handshake precede a entrega dos fluxos ao dispatcher, então uma conexão recusada nunca alcança ferramenta alguma |
| O segredo nunca mora em arquivo distribuído | `McpServerConfig.resolveSharedSecret()` lê da variável `IPED_MCP_SHARED_SECRET` ou do arquivo apontado por `sharedSecretFile`. `conf/McpServerConfig.txt` declara **onde**, nunca **qual** |
| No máximo uma sessão escreve um caso | `ConcurrencyGuard.acquireWriteLock` sobre `access.lock`; duas sessões do mesmo processo colidem por `OverlappingFileLockException`. `WriteClaims` **não exclui** — só nomeia a detentora no diagnóstico |
| Identidade alegada nunca se lê como verificada | `OperatorIdentity.describe()` põe "unverified" **dentro do valor**, e é esse valor que vai para o campo `operator` da trilha. Nome de campo não sobrevive a ser copiado para um laudo; o valor sobrevive |
| Nenhuma operação executa sem registro prévio | `McpDispatcher.callTool` → `AuditTrail.recordStart` |
| Somente-leitura por padrão; curadoria recusada sem tocar o caso | portão de modo de acesso no `McpDispatcher`, antes de qualquer leitura de argumento |
| Política de egresso não contornável por escolha de ferramenta | classe de conteúdo declarada em `ToolDescriptor.returnsContent`, aplicada na fronteira do dispatcher |
| Estado anterior antes de operação destrutiva | `ToolDescriptor.capturingPriorState`, avaliado antes do `recordStart` |
| Referência a item sempre carrega o caso | contrato das ferramentas; `ToolSchemaTest` verifica |
| Ausência ≠ vazio | `ItemView.unavailable`, `ContentAccess.unavailable` |
| Charset explícito, logging por SLF4J | `JsonRpcCodec`, `AuditTrail`; `System.out` corromperia o próprio protocolo |
| Uma mensagem malformada é respondida e descartada, nunca fatal | `JsonRpcCodec.readMessage` → `McpError.MALFORMED_MESSAGE`; `McpServerMain.start` responde `-32700` e continue. Deixar a falha do Jackson escapar derrubava a sessão inteira e todos os casos abertos nela |
| Artefato só é gravado sob raiz declarada | `PathConfinement.resolve` chamado por `ExportTools.checkDestination` **antes** de `ArtifactWriter.write`. É lista de permissão, não de recusa, e a comparação é sobre o caminho **real** (`Path.toRealPath`) contra a raiz **real**. `File.getCanonicalPath()` **não atravessa junção de diretório no Windows** e por isso não pode voltar a ser usado aqui |
| Recusa de destino não deixa rastro | A criação de pastas intermediárias em `ArtifactWriter` acontece depois do veredito `ALLOWED`, nunca antes |
| Sucesso de exportação implica artefato existente | `ArtifactWriter.verifyArtifact` confere existência e tamanho depois de escrever. Contenção não é integridade: `<raiz>\NUL` fica dentro da raiz, aceita a escrita e não guarda nada |
| Nenhum erro devolve uma grafia que não parseia | `PagedSearcher.plan` verifica a correção contra o caso antes de sugeri-la; `FieldNames.toQueryForm` em todo remedy que cita nome de campo |
| Consulta reescrita é sempre declarada | `PagedSearcher.declareNormalization` → `query_normalized` no resultado de busca, agregação e exportação |

## 6. Dependências

| Lib | Para que |
|---|---|
| `iped-engine`, `iped-api`, `iped-utils` | `IPEDSource`, `QueryBuilder`, `Bookmarks`, `LoadIndexFields`, `IndexItem`, `BasicProps` |
| `lucene-core`, `lucene-highlighter`, `lucene-queryparser` 9.2.0 | busca paginada, DocValues, trechos |
| `jackson-core` 2.13.2 / `jackson-databind` 2.13.4.2 | JSON-RPC e serialização |
| `poi` / `poi-ooxml` 5.2.2 | xlsx em modo streaming; versão alinhada à que o Tika 2.4.0 traz |

Nenhum artefato novo entra no release além do próprio `iped-mcp.jar`: POI e Jackson já vinham transitivamente.

## 7. Testes

```bash
mvn -pl iped-mcp test                                            # sem caso: 99 testes efetivos

# Com caso, são necessários mais dois parâmetros — ver abaixo por quê:
mvn -pl iped-mcp test -Diped.mcp.ipedRoot=<release> -Djvm=<release>/jre/bin/java.exe \
    -Diped.mcp.test.referenceCase=<path>                         # + suítes de integração
mvn -pl iped-mcp test -Diped.mcp.ipedRoot=<release> -Djvm=<release>/jre/bin/java.exe \
    -Diped.mcp.test.largeCase=<path>                             # + SC-002 e SC-015
```

Abrir caso real no harness exige duas coisas que o teste unitário não exige, e nenhuma das duas é opcional:

- **`-Diped.mcp.ipedRoot`** — a configuração do engine (`IndexTaskConfig`, `AnalysisConfig`, `CategoryConfig`) tem que ser carregada de uma instalação antes de abrir caso, exatamente como o `McpServerMain.main` faz. Sem isso o `iped_open_case` falha com `CASE_INACCESSIBLE` e `IndexTaskConfig` nulo. `McpTestSupport.requireIpedConfiguration()` cuida disso, chamado de `requireReferenceCase()`/`requireLargeCase()`.
- **`-Djvm` apontando para o JRE 11 do release** — carregar o task installer arrasta o FST, que reflete em interno do JDK (`String.value`, `BigDecimal.intVal`, e mais conforme registra suas classes padrão). Java permite até a 15 e recusa a partir da 16. Em JVM ≥ 16 o harness **recusa antes de falhar**, com o comando pronto; abrir pacote a pacote com `--add-opens` é caça sem fim e um conjunto incompleto só desloca a confusão.

As suítes que precisam de caso **pulam** quando ele não está configurado, e **um teste pulado não é um teste que passou**. Quando o caso está configurado mas a instalação ou o runtime não, o harness **falha** em vez de pular: alguém pediu execução real, e pular ali reportaria "nada a fazer" para bancada mal configurada. A receita reprodutível do caso de referência está em [`src/test/resources/reference-case/README.md`](src/test/resources/reference-case/README.md).

`ScalePerformanceTest` contra o caso grande é inegociável: uma implementação que materializa o conjunto passa em todas as outras suítes deste módulo e só falha em campo. Ele imprime as medições ao final — pass/fail sozinho não mostra a corrida se aproximando do teto. Última execução, caso de **15.061.999 itens**:

| Medição | Resultado | Teto |
|---|---|---|
| `iped_open_case` + `iped_case_overview` | 3.050 ms | 30.000 ms |
| Primeira página de `*:*` (50 itens) | 655 ms | 5.000 ms |
| Total exato devolvendo 1 item | 817 ms | 5.000 ms |
| 10 páginas seguidas, pior caso | 836 ms — página 1: 836 ms, página 10: **479 ms** | 5.000 ms |
| `iped_aggregate` por categoria (41 valores) | 4.490 ms | 15.000 ms |

A linha que importa é a da paginação profunda: a página 10 é **mais rápida** que a primeira. O custo acompanha a página, não a profundidade — é isso que separa `PagedSearcher` de uma implementação que materializa o conjunto.

## 8. Skill

Fonte canônica única em `src/main/resources/skill/`. Os invólucros por harness são gerados no build (`generate-resources`) para `iped-app/resources/skills/{claude-code,codex,opencode}/iped-forensics/` e copiados para `skills/` no release. **Não edite os invólucros** — são regenerados a cada build e ignorados pelo git. `SkillParityTest` verifica que os três são byte a byte idênticos à fonte: orientação divergente entre harnesses produziria análises divergentes sobre a mesma evidência.

## 9. ⚠️ Áreas sensíveis

| Área | Cuidado |
|---|---|
| `PagedSearcher.forItems` | Replica a semântica de `IPEDSearcher` (rewrite com `mapChildToParentDocs`, exclusão de tree nodes). Divergir aqui muda silenciosamente o que uma consulta encontra. |
| `AuditTrail.digest` | A ordem dos campos em `AuditRecord.toNodeWithoutHash` faz parte do hash. Reordenar invalida a verificação de trilhas já emitidas. |
| Portão de escrita no `McpDispatcher` | Precisa continuar antes de qualquer leitura de argumento, ou "sem tocar o caso" deixa de ser verdade. |
| `ConcurrencyGuard` | A UI do IPED 4.3.1 não trava o caso. A detecção é cooperativa entre processos `iped-mcp` e best-effort para a UI — ausência de conflito **não** prova ausência de outro leitor. |
| `ItemView.storedFields` | Lê do documento armazenado, não do `IItem`. Acrescentar campo aqui é barato; trocar por reconstrução de item custa a latência da página. |
| `CasePool` | Um `IPEDSource` por caso por processo, com contagem de referências. `OpenCase.close()` **solta referência**, não fecha o handle — fechá-lo direto tira o searcher de baixo de outra sessão. O que é compartilhado é imutável depois do construtor; nada com estado mutável pode entrar aqui |
| `AuditRecord` | **Não acrescente campo.** `AuditTrail.verify` recompõe `toNodeWithoutHash` a partir do que lê, então um campo a mais muda o resultado para registros já emitidos. Foi por isso que identidade alegada foi para o campo `operator` e transporte/origem para o `SessionManifest` |
| `McpRelayMain.relay` | O `shutdownOutput()` no fim da entrada **não é limpeza opcional**. Sem ele o relay não termina quando o harness fecha o stdin — que é como todo harness sai — e a sessão fica segurando o caso e a reivindicação de escrita até o teto de ociosidade. Encontrado em campo, não na suíte: um relay pendurado passa em qualquer teste de requisição/resposta. `RelayShutdownTest` fixa |
| `HandshakeCodec` | Fora do JSON-RPC de propósito. Movê-lo para dentro de `initialize` daria ao dispatcher um estado "autenticado ou não" que toda ferramenta teria de consultar, e a primeira que esquecesse seria um vazamento |
| `PathConfinement` | Toda a classe existe porque comparação textual de prefixo não sustenta a regra. Trocar `toRealPath()` por `getCanonicalPath()` ou por `normalize()` reabre a junção de diretório; comparar com `String.startsWith` em vez de `Path.startsWith` faz uma raiz `D:\laudo` casar com `D:\laudos`. `PathConfinementTest` fixa os dois. O veredito de prefixo estendido `\\?\` **difere entre Java 11 e versões novas** — o teste afirma "recusado", não um veredito |
| `McpServerConfig.exportRoots` | Separador `;`, não `,`: caminho de arquivo carrega vírgula. Sem raiz declarada vale uma raiz padrão criada sob demanda, para que instalação existente continue funcionando ao atualizar (FR-024) |
| `allowExportIntoCaseFolder` | **Semântica estreitada.** Suprime só o veredito `INSIDE_CASE`; não reabre o resto do sistema de arquivos. Antes fazia `checkDestination` retornar antes de qualquer verificação |
| `FieldNames.escapeKnownFieldNames` | Só reescreve nome que **este caso tem**, fora de aspas e seguido de `:`. Afrouxar qualquer uma das três condições faz o servidor inventar restrição de campo ou alterar a frase que o perito procurava. |
| `Cursor` | A posição vem de `FieldDoc.fields[0]`, **nunca** de `ScoreDoc.score` — o `TopFieldCollector` deixa `NaN` ali. O formato do cursor decorre de `Cursor.SORT`: mudar a ordenação sem mudar o cursor faz a paginação reiniciar em silêncio. |

## 10. Limitações conhecidas

- **Concorrência com a UI** é best-effort (ver acima). Vale **por cima** da exclusividade entre sessões: deter a reivindicação de escrita não dispensa o `probeBookmarksState`, e a UI segurando o estado de marcadores recusa a escrita de qualquer forma.
- **O canal do transporte de rede não é protegido.** Autenticação por segredo compartilhado, conteúdo em claro. Adequado quando o trânsito fica dentro de uma máquina física — VM ou contêiner falando com o hospedeiro — ou em segmento confiável. Entre máquinas físicas em rede compartilhada, o conteúdo de evidência trafega legível para quem observe o segmento. Autenticação mútua por certificados é evolução prevista e não construída; o gatilho para retomá-la é a primeira implantação entre máquinas físicas distintas.
- **Trilha por sessão, não por caso**, agora com o `SessionManifest` como resposta: uma linha por sessão que tocou o caso, na subpasta de auditoria, respondendo "são todas?" e "em que ordem?". A limitação em si permanece — o que mudou é que ela deixou de impedir a reconstituição. Uma sessão que abre dois casos sincroniza o mesmo arquivo para dentro dos dois. Sob a decisão D2 — estação individual, um caso por vez — isso é o comportamento certo; com dois casos abertos juntos, o perito vê ambos de qualquer forma.
- **Snippet custa reextração de texto**, limitado por orçamento. Itens além do orçamento vêm com o trecho declarado ausente.
- **A versão do caso é lida do nome dos jars em `iped/lib`.** Um caso com essa pasta podada é recusado com `VERSION_UNSUPPORTED` em vez de ser aberto sob suposição.
