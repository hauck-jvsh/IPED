# Módulo `iped-mcp`

> **Servidor MCP.** Expõe casos IPED a agentes de LLM, por JSON-RPC 2.0 sobre stdio, e distribui a skill que ensina o agente a usá-lo com disciplina pericial. Desde a 007 também **cria** caso, processando evidência com o motor do IPED fora do processo.

> Este módulo **não modifica nenhuma classe existente**. Consome apenas API pública do `iped-engine` e do `iped-api`. Os dois únicos arquivos existentes tocados são aditivos: o `pom.xml` da raiz (registro do módulo) e `iped-app/pom.xml` (empacotamento no release).

## 1. Propósito

- **Consulta paginada** de um caso, com contagem exata independente do que é devolvido.
- **Filtro opcional por bookmark na busca**, intersectado com a consulta sem materializar seus ids.
- **Agregações** por dimensão sem materializar itens.
- **Descoberta de vocabulário** de campos, com sugestão de nomes próximos.
- **Inspeção de item**: metadados, texto, miniatura, conteúdo bruto, hierarquia — todos com teto de volume e ausência declarada.
- **Projeção de campos escolhidos** sobre um lote de ids: `iped_get_items` com `fields` devolve só os campos nomeados, resolvidos contra o vocabulário do caso antes de ler qualquer documento.
- **Curadoria**: marcadores e seleção, desabilitados por padrão.
- **Trilha de auditoria** append-only encadeada por hash, gravada antes de cada operação.
- **Artefatos de saída**: xlsx, CSV e JSON do conjunto completo, sem trafegar pela conversa.
- **Exportação de item**: um item como arquivo na pasta configurada — os bytes dele, conferidos contra o hash do caso, ou o texto extraído. Sem teto: o teto de leitura protege a conversa, não o arquivo.
- **Política de egresso** opcional, aplicada no servidor.
- **Criação de caso**: processa evidência com o motor do IPED em processo externo, com acompanhamento, cancelamento e retomada. Desabilitada por padrão.

Versão `4.3.1`. Java 11. Specs: [`001-iped-llm-integration`](../specs/001-iped-llm-integration/), [`006-export-allowlist-socket-transport`](../specs/006-export-allowlist-socket-transport/), [`007-mcp-case-processing`](../specs/007-mcp-case-processing/).

## 2. Estrutura

```
iped/mcp/
├── McpServerMain.java       # entry point stdio; inicialização programática (FR-064)
├── Diagnostics.java         # verificação de pré-requisitos, log por SLF4J
├── config/McpServerConfig   # Configurable<UTF8Properties> lido de conf/McpServerConfig.txt
├── protocol/                # JsonRpcCodec, McpError, ToolDescriptor, McpDispatcher
├── session/                 # Session, CaseRegistry, CaseValidator, OpenCase, ConcurrencyGuard
├── query/                   # PagedSearcher, Aggregator, SnippetBuilder, FieldVocabulary, FieldNames, Cursor
├── item/                    # ItemView, FieldSelection, ContentAccess
├── curation/BookmarkWriter  # marcadores e seleção sobre Bookmarks/saveState
├── McpRelayMain.java        # relay stdio↔socket, para o harness em outra máquina
├── transport/               # Transport, StdioTransport, SocketTransport, HandshakeCodec
├── audit/                   # AuditRecord, AuditTrail, AuditSync, SessionManifest
├── processing/              # criação de caso: JobRunner, ProgressReader, JobStore, confinamentos
├── egress/EgressPolicy      # opcional, inativa por padrão
├── export/                  # ArtifactWriter (xlsx/CSV/JSON), ItemFileWriter, EvidenceFileName, PathConfinement
└── tools/                   # uma classe por grupo de ferramentas MCP
```

Recursos em `src/main/resources/`:

- `skill/` — `SKILL.md` (fonte canônica), `references/`, `install/`.
- `bridge/` — `mcp-bridge.py` e o invólucro `iped-mcp-bridge`: a **segunda implementação do
  intermediário**, equivalente ao `McpRelayMain` e falando o mesmo protocolo com o mesmo servidor.
  Existe porque o ambiente isolado é onde peso custa mais caro: exigir um JRE lá dentro para uma
  linha de handshake e dois bombeamentos de bytes acrescenta superfície e mais um runtime a manter
  atualizado, no ambiente cujo valor inteiro é ser pequeno o bastante para se auditar (FR-037 de 006).
  Copiado para `bridge/` no release pela execução `copy-mcp-bridge` do `iped-app/pom.xml` — cópia
  única, sem geração e sem verificação de paridade, ao contrário da skill.

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
- **Bookmark na busca é filtro Lucene, não expansão de ids.** `BookmarkQuery` percorre o DocValues
  numérico de `id` e consulta a associação corrente no `IBookmarks`. Ela declara
  `isCacheable = false` porque o marcador pode mudar enquanto o searcher permanece aberto.

## 4. Configuração

Tudo o que varia vive em `conf/McpServerConfig.txt` (Princípio IV da constituição), nunca em constante de código: área de auditoria, modo de acesso, política de egresso, tetos de página, de lote e de conteúdo (o `maxBatchSize` limita tanto quantos ids quanto quantos **nomes de campo** uma projeção pede — não há teto novo para isso), faixa de versão suportada, destino de exportação, reparo de nome de campo (`autoEscapeFieldNames`, desligado por padrão — ligado, uma expressão que só falha por colon não escapado é corrigida contra o vocabulário real do caso e o reparo vem declarado em `query_normalized`).

Acrescentado na 007: **criação de caso** (`processingEnabled`, `processingSourceAreas`, `processingCaseRoots`, `processingProfiles`, `processingMinFreeSpacePercentOfSource`, `processingSecretsFile`, `processingLocale`, `processingStallThresholdSeconds`, `processingJvm`). As duas listas de raízes **não têm padrão**, e vazio é erro de configuração, não permissão total — inventar raiz seria conceder o que ninguém concedeu, e é a lista onde errar transforma o servidor em conversor de sistema de arquivos em índice consultável.

Acrescentado na 006: **raízes de escrita** (`exportRoots`, separadas por `;` — vírgula cortaria caminho do Windows ao meio) e **transporte** (`transport`, `listenAddress`, `listenPort`, `sharedSecretFile`, `maxConcurrentSessions`, `sessionIdleTimeoutSeconds`). Endereço e porta **não têm padrão**, de propósito. O segredo tampouco vive aqui: a chave diz **onde** encontrá-lo.

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
| Ausência ≠ vazio | `ItemView.unavailable`, `ContentAccess.unavailable`, `FieldSelection.project` |
| **Motivo de ausência é derivado do item, não escolhido de uma lista** | `ContentAccess.noText` decide por `isDecodedData`, media type, `isTimedOut` e pelos campos de metadado que o próprio item tem. A mensagem anterior oferecia três hipóteses de uma vez — binário, não parseado ou cifrado — e para item decodificado **as três eram falsas** enquanto o conteúdo estava em `Message-Body`. `AvailabilityTest` exige que o motivo nomeie algo verdadeiro daquele item |
| Texto de item é o conteúdo dele, nunca o fonte | **`McpServerMain.bootstrapConfiguration` chama `SignatureTask.installCustomSignatures()`**, como o `UICaseDataLoader` faz antes de criar o parser da UI: o `conf/CustomSignatures.xml` declara `application/x-whatsapp-chat` como `sub-class-of text/html`, e é por essa hierarquia que o Tika acha o `HtmlParser`. Sem o registro, o fallback de strings cruas devolvia o HTML do preview como texto do chat. `ContentAccess.extractText` mantém a detecção por `hasSpecificParser` como segunda linha, para tipo que esta instalação não conhece. `ItemTextTest` fixa as duas |
| **Falha do servidor é declarada como falha do servidor, nunca como propriedade do item** | Abrir caso abre também o repositório de previews (`CasePool.configurePreviews`), porque item decodificado sem trecho de evidência próprio tem os únicos bytes dele no `previews.mv.db`. Enquanto isso faltava, `iped_item_text` respondia `available: false` a uma mensagem que **tinha** conteúdo, e o remédio mandava tentar `iped_item_content`, que falha pela mesma tubulação. `extractText` separa `RuntimeException` do resto e diz de quem é a culpa; `PreviewBackedContentTest` fixa |
| Projeção com nome que o caso não tem **recusa a chamada**, não devolve itens sem o campo | `FieldSelection.resolve` roda antes de ler documento e lança `UNKNOWN_FIELD` com os nomes próximos. Devolver a página com o campo ausente em todo item é indistinguível de itens que realmente não o têm — é a forma de "nada encontrado" errado que a FR-047 existe para impedir |
| Charset explícito, logging por SLF4J | `JsonRpcCodec`, `AuditTrail`; `System.out` corromperia o próprio protocolo |
| Nada além do protocolo alcança a saída padrão — **inclusive o que vem de fora do código** | O código respeita a linha acima; quem a contradizia era a **configuração de log da instalação**. As duas configurações distribuídas (`Log4j2ConfigurationConsoleOnly`, `Log4j2ConfigurationFile`) e o padrão da própria biblioteca apontam para `SYSTEM_OUT` — correto para a CLI e a UI, errado aqui. `conf/Log4j2ConfigurationMcp.xml` existe para isso e os comandos publicados nos guias **precisam** passá-la em `-Dlog4j.configurationFile`. Invariante mantida só no código não protege contra acoplamento por arquivo de configuração |
| Uma mensagem malformada é respondida e descartada, nunca fatal | `JsonRpcCodec.readMessage` → `McpError.MALFORMED_MESSAGE`; `McpServerMain.start` responde `-32700` e continue. Deixar a falha do Jackson escapar derrubava a sessão inteira e todos os casos abertos nela |
| Artefato só é gravado sob raiz declarada | `PathConfinement.resolve` chamado por `ExportTools.checkDestination` **antes** de `ArtifactWriter.write`. É lista de permissão, não de recusa, e a comparação é sobre o caminho **real** (`Path.toRealPath`) contra a raiz **real**. `File.getCanonicalPath()` **não atravessa junção de diretório no Windows** e por isso não pode voltar a ser usado aqui |
| Recusa de destino não deixa rastro | A criação de pastas intermediárias em `ArtifactWriter` acontece depois do veredito `ALLOWED`, nunca antes |
| Sucesso de exportação implica artefato existente | `ArtifactWriter.verifyArtifact` confere existência e tamanho depois de escrever. Contenção não é integridade: `<raiz>\NUL` fica dentro da raiz, aceita a escrita e não guarda nada |
| Nenhum erro devolve uma grafia que não parseia | `PagedSearcher.plan` verifica a correção contra o caso antes de sugeri-la; `FieldNames.toQueryForm` em todo remedy que cita nome de campo |
| Consulta reescrita é sempre declarada | `PagedSearcher.declareNormalization` → `query_normalized` no resultado de busca, agregação e exportação. Duas causas hoje, com nota própria cada uma: escape de nome de campo e `*` reconhecido como match-all |
| **Uma página custa uma avaliação da consulta** | O total vem de `TopDocs.totalHits`, não de um `searcher.count()` à parte. O `totalHitsThreshold = Integer.MAX_VALUE` proíbe término antecipado, então o coletor já conta todo o conjunto, em qualquer página e com qualquer cursor. `unit/CursorPaginationTest` fixa essa semântica do Lucene — se um upgrade mudar, `total_matches` passa a significar outra coisa em silêncio |
| Total interrompido é **piso declarado**, nunca exato disfarçado | `total_matches_exact: false` + `partial_note` dizendo "floor". **Não se lê do `TotalHits.relation`**, que devolve `EQUAL_TO` mesmo com a varredura cortada: ele descreve o teto, não a interrupção. A autoridade é a `TimeExceededException` |
| Página parcial não emite cursor | `PagedSearcher.search` só chama `nextCursor` quando `!partial`, e declara `next_cursor_omitted`. Cursor de página parcial retoma depois de posição que a varredura não alcançou; acerto ordenado antes dela sumiria desta página e de todas as seguintes. É o FR-079 aplicado a outra causa |
| Pedir "tudo" tem caminho barato, e a superfície não obriga a inventar consulta | `PagedSearcher.isMatchAllExpression` reconhece `*` e `*:*` **antes do parser**; `iped_search` aceita `bookmark` sem `query`. O reconhecimento é estreito de propósito — só a expressão que é apenas a estrela |
| **O motor nunca roda dentro do processo do servidor** | `iped-app` depende de `iped-mcp`, então chamar `Bootstrap` seria circular e o build recusa. `JobRunner` executa o `iped.jar` da instalação. Quem tentar `new Manager(...)` no pacote `processing/` descobre pelo erro de compilação — o `package-info.java` existe para que descubra antes |
| Instalação padrão não cria caso | `processingEnabled = false`; com ele desligado as ferramentas **não aparecem** em `tools/list` e uma chamada forçada é recusada antes de ler argumento. `NoProcessingByDefaultTest` verifica as duas metades — só a ausência da listagem não prova nada sobre cliente que chame assim mesmo |
| Cancelar destrói a **árvore**, não o filho | `Bootstrap` gera um neto e é o neto que lê evidência; o shutdown hook dele tem `process.destroy()` **comentado**. `JobRunner.destroyTree` mata descendentes antes do filho. `CancelJobTest` espera o neto existir antes de cancelar, senão não testa nada |
| Um trabalho não sobrevive ao servidor | Gancho de desligamento no caminho ordenado; `OrphanReconciler` no abrupto. Órfão vivo é **destruído**, não adotado, e a identidade é confirmada por PID **e** instante de início — só o PID mataria estranho por coincidência de numeração |
| Nada do motor alcança o canal do protocolo | O fluxo do filho é lido por tubo e gravado em `<auditoria>/jobs/<id>/processing.log`; nunca repassado. `ProcessingLogTest` afirma que toda linha escrita pelo servidor parseia como JSON-RPC |
| Caso de trabalho não concluído nunca abre como completo | `CaseValidator` consulta o `JobStore`. A verificação estrutural **não basta**: uma interrupção mid-processing deixa `index`, `data` e `lib` no lugar com índice comitado, indistinguível de caso pronto |
| `AuditRecord` continua sem campo novo | Estado de trabalho vive no `JobStore`; vínculo sessão↔trabalho e postura vigente vivem no próprio registro do trabalho |

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
mvn -pl iped-mcp test                                            # sem caso: 222 efetivos de 315 (93 pulam)

# Com caso, são necessários mais dois parâmetros — ver abaixo por quê:
mvn -pl iped-mcp test -Diped.mcp.ipedRoot=<release> -Djvm=<release>/jre/bin/java.exe \
    -Diped.mcp.test.referenceCase=<path>                         # + suítes de integração
mvn -pl iped-mcp test -Diped.mcp.ipedRoot=<release> -Djvm=<release>/jre/bin/java.exe \
    -Diped.mcp.test.largeCase=<path>                             # + SC-002 e SC-015

# Processamento real (007): evidência de origem e raiz de caso declaradas.
mvn -pl iped-mcp test -Diped.mcp.ipedRoot=<release> \
    -Diped.mcp.test.sourceEvidence=<imagem> -Diped.mcp.test.caseRoot=<raiz>
```

As suítes de processamento precisam de duas coisas a mais, pelo mesmo motivo das de caso: uma
evidência para processar e uma raiz onde criar caso. Sem elas **pulam**. A raiz é declarada em vez de
cair num diretório temporário de propósito — defaultar ali funcionaria e deixaria de exercitar a
regra de confinamento que a feature existe para impor.

Duas medições da bancada de referência (imagem E01 de 8,57 GB, 48 núcleos), úteis para calibrar
expectativa: processamento completo em **103 s** com `fastmode`, e o teste ponta a ponta inteiro —
que inclui SHA-256 da evidência antes e depois — em **181 s**. Verificação real aqui é barata, o que
muda o que vale a pena testar de verdade em vez de simular.

Abrir caso real no harness exige duas coisas que o teste unitário não exige, e nenhuma das duas é opcional:

- **`-Diped.mcp.ipedRoot`** — a configuração do engine (`IndexTaskConfig`, `AnalysisConfig`, `CategoryConfig`) tem que ser carregada de uma instalação antes de abrir caso, exatamente como o `McpServerMain.main` faz. Sem isso o `iped_open_case` falha com `CASE_INACCESSIBLE` e `IndexTaskConfig` nulo. `McpTestSupport.requireIpedConfiguration()` cuida disso, chamado de `requireReferenceCase()`/`requireLargeCase()`.
- **`-Djvm` apontando para o JRE 11 do release** — carregar o task installer arrasta o FST, que reflete em interno do JDK (`String.value`, `BigDecimal.intVal`, e mais conforme registra suas classes padrão). Java permite até a 15 e recusa a partir da 16. Em JVM ≥ 16 o harness **recusa antes de falhar**, com o comando pronto; abrir pacote a pacote com `--add-opens` é caça sem fim e um conjunto incompleto só desloca a confusão.

### O que um caso de referência diferente do da receita revela

Rodar as suítes contra um caso real que **não** é o da receita
([`src/test/resources/reference-case/README.md`](src/test/resources/reference-case/README.md))
expôs cinco suítes com problema. Duas eram defeitos reais e foram corrigidas; três são suposições que
só valem para a receita. Nenhuma era regressão — as cinco estavam intocadas desde o commit da 001.

**Corrigidos.** Valiam para qualquer caso, e ninguém via porque as suítes pulavam:

| O quê | Diagnóstico |
|---|---|
| `McpSessionRule` não declarava raiz de escrita | A 006 tornou destino de artefato **lista de permissão**; a regra é da 001 e nunca foi atualizada. As quatro falhas do `ArtifactExportTest` eram `DESTINATION_REFUSED`. Quebrado desde a 006, invisível porque a suíte pula sem caso |
| `commons-io` divergente entre teste e release | O Maven resolve **2.11.0** no `iped-mcp` e **2.16.1** no `iped-app`. `commons-compress` 1.27.1 chama `BoundedInputStream.builder()`, ausente na 2.11.0 → `NoSuchMethodError` ao ler conteúdo de item do armazenamento SQLite. **Produção nunca foi afetada**; o errado era o módulo ser *testado* contra biblioteca diferente da que executa. Fixado no pom |

**Não corrigidos**, por serem suposição do teste e não do servidor:

| Suíte | Por que falha em outro caso |
|---|---|
| `InvestigationBatteryTest` | Procura arquivos **plantados pela receita** (`apagado-recibo.txt` em Q06). Um caso real não os tem |
| `PaginationTest.pagingCoversEverythingExactlyOnce` | Teto de **5000 páginas × 20 = 100.000 itens**. Contra caso maior o laço bate no teto. Vale corrigir para o teto escalar com `total_matches` |
| `VocabularyTest` (3) | Três suposições da receita: que o caso **não** tem campo `mediaType` (aqui tem, então vem `QUERY_SYNTAX` em vez de `UNKNOWN_FIELD`); que `campo:*` vale para todo campo (num campo **numérico** o parser recusa com `Unparseable number: "*"`); e que a sugestão nunca é namespaced — o teste concatena o nome cru em vez de usar o `query_form` que o servidor devolve exatamente para isso |

`FieldProjectionTest` é **agnóstico ao conteúdo de propósito**, para não entrar nessa lista: tira os ids
da própria busca e o campo específico do próprio `iped_list_fields`, em vez de esperar arquivo plantado
ou vocabulário conhecido. Verificado contra caso fora da receita (release `4.4.0-SNAPSHOT`), 7 de 7,
nenhum pulado. É só leitura — `iped_search`, `iped_get_items`, `iped_list_fields`,
`iped_case_overview` — então não precisa do cuidado com `bookmarks.iped` da seção abaixo.

**Em aberto**, caracterizado mas não resolvido:

`CaseOpenTest.closingReleasesTheCaseWithoutLeavingALock` falha com `AccessDeniedException` ao renomear
a pasta do caso depois de `iped_close_case`. Medido: falha com **um único ciclo abrir→fechar**,
isolada do resto da classe, e o rename **funciona** quando nenhum processo nosso está vivo. Não é
contaminação entre suítes nem processo externo — é a sessão ainda segurando algo em
`<caso>/mcp-audit/` depois de o caso fechar, provavelmente o alvo do `AuditSync`. Se é defeito ou
expectativa do teste mais estrita que o desenho, é questão da 001.

**Cuidado ao rodar essa suíte contra material de trabalho**: ela renomeia a pasta do caso e a renomeia
de volta. Aqui o primeiro rename falhou, mas se tivesse funcionado e o segundo falhasse, o caso
ficaria renomeado.

Rodando contra caso fora da receita, exclua as três incompatíveis:

```bash
mvn -pl iped-mcp test -Dtest='!InvestigationBatteryTest,!PaginationTest,!VocabularyTest' \
    -Diped.mcp.ipedRoot=<release> -Djvm=<release>/jre/bin/java.exe \
    -Diped.mcp.test.referenceCase=<caso>
```

### Escrita em caso de referência: faça backup do `bookmarks.iped`

As suítes de curadoria escrevem marcadores no caso e **se restauram no fim** — `BookmarkWriteTest`
tem limpeza explícita para que sejam repetíveis. Mas a restauração é do fim: **uma execução
interrompida no meio deixa o caso alterado**. Medido, não suposto: uma rodada cortada por timeout
mudou o `bookmarks.iped` de um caso real, e só o backup prévio permitiu devolvê-lo bit a bit.

Antes de apontar as suítes para caso que não seja descartável, copie
`<caso>/iped/bookmarks.iped` e guarde o SHA-256; confira e restaure ao fim.

A trilha de auditoria em `<caso>/mcp-audit/` **cresce a cada rodada** e é append-only por construção
— isso não se desfaz, e não deveria.

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

### O custo de uma página, medido — 2026-09-04

Um chamado de campo (`bookmark` + `query: "*"`) demorou minutos. Medido depois da correção, no **mesmo
caso** (8.553.336 itens, marcador de 1.905 itens), 20 itens por página, sem snippet, descontando
11,7 s de bootstrap + `iped_open_case`:

| Forma da chamada | Busca | `total_matches` | Exato | Cursor |
|---|---|---|---|---|
| `bookmark`, sem `query` | **849 ms** | 1.905 | sim | sim |
| `bookmark` + `*:*` | 1.214 ms | 1.905 | sim | sim |
| `bookmark` + `*` (reconhecido) | 1.046 ms | 1.905 | sim | sim |
| `bookmark` + `content:*` — o custo antigo | **48.905 ms** | ≥ 1.905 | **não** | **não** |

E, sem marcador, sobre os 8,5 M: `*:*` em **296 ms** com total exato; `*` em 505 ms, reescrito e
declarado.

Duas leituras:

- **~49 s → ~0,85 s** na chamada que veio de campo. E os 49 s **subestimam** o custo antigo: são
  medidos no código novo, que avalia a consulta uma vez; o antigo rodava também um `searcher.count()`
  sem orçamento sobre a mesma expressão, e o wildcard cobria `name` além de `content`.
- A última linha mostra as duas declarações novas funcionando sob estresse real: a varredura estourou
  o orçamento, então o total veio como **piso** (`total_matches_exact: false`) e **nenhum cursor** foi
  emitido. Antes, essa mesma chamada devolvia total exato pago fora do orçamento e um cursor que
  puliria acertos em silêncio.

## 8. Skill

Fonte canônica única em `src/main/resources/skill/`. Os invólucros por harness são gerados no build (`generate-resources`) para `iped-app/resources/skills/{claude-code,codex,opencode}/iped-forensics/` e copiados para `skills/` no release. **Não edite os invólucros** — são regenerados a cada build e ignorados pelo git. `SkillParityTest` verifica que os três são byte a byte idênticos à fonte: orientação divergente entre harnesses produziria análises divergentes sobre a mesma evidência.

### Rotas de instalação, revisadas em 2026-09-04

O `install/codex.md` mandava copiar a pasta e escrever ponteiro no `AGENTS.md`, o único mecanismo que
existia quando foi escrito. **O Codex ganhou diretório nativo de skills**: verificado no
`codex-cli 0.153.2`, as preinstaladas ficam em `$CODEX_HOME/skills/.system/<nome>/` com `SKILL.md` +
`references/`, e o `skill-installer` da própria OpenAI declara instalar em
`$CODEX_HOME/skills/<nome>`. O frontmatter que elas usam (`name`, `description`) é o que a nossa skill
já carrega, então ela entra sem edição. O guia passa a ramificar por versão — pasta de skills quando
existe, `AGENTS.md` quando não —, porque instalação que assume o mecanismo novo falha em silêncio no
build antigo. **Se `~/.codex/skills/` seguir link simbólico não foi verificado**; o guia diz para
confirmar e cair no `AGENTS.md`, que aceita caminho absoluto e não depende disso.

Também documentado, e medido em vez de suposto: **Codex dentro do WSL2 lançando o `java.exe` do
Windows** por interop (`command = /mnt/c/.../jre/bin/java.exe`, argumentos com caminho Windows). 25
ferramentas em `tools/list`, stdout só com JSON-RPC, log do engine no stderr. É a melhor das opções
porque o caminho continua sendo do Windows — o que mantém as `exportRoots` do `McpServerConfig.txt`
casando e o índice sendo lido nativamente —, e porque o `jre/` do release é Windows e não roda sob
Linux. O que ela **não** é: isolamento. Depende de `/mnt/c`, e aí o agente alcança a pasta do caso
por fora da superfície de ferramentas.

## 9. ⚠️ Áreas sensíveis

| Área | Cuidado |
|---|---|
| `PagedSearcher.forItems` | Replica a semântica de `IPEDSearcher` (rewrite com `mapChildToParentDocs`, exclusão de tree nodes). Divergir aqui muda silenciosamente o que uma consulta encontra. |
| `McpServerMain.installCustomSignatures` | **Registro de mime é inicialização, não configuração.** `SignatureTask.installCustomSignatures()` só define uma propriedade de sistema, que o Tika lê quando **constrói** o registro de tipos. Chamada depois de qualquer coisa ter tocado o Tika, ela não registra nada e **não avisa** — foi exatamente assim que o primeiro experimento pareceu refutar a hipótese certa. Precede o primeiro parser do processo, e é o que faz a extração de texto bater com a da UI por construção |
| `ContentAccess.extractText` | **Media type produzido por parser não tem parser próprio.** `application/x-whatsapp-chat` e `message/x-whatsapp-message` são tipos que o IPED *atribui*; fixá-los em `Indexer-Content-Type` seleciona nada e o `StandardParser` cai no `RawStringParser`, que **nunca falha** — devolve os bytes imprimíveis. Para um chat isso era o HTML do preview entregue como "texto extraído", em silêncio, com o teto gasto num favicon base64. Por isso o `hasSpecificParser` antes de fixar. Ao mexer aqui, meça em três classes de item — chat decodificado, PDF e binário — porque o fallback disfarça o erro como sucesso |
| `EvidenceFileName` | **Nome de item é entrada, não nome.** Ele foi escolhido por quem fez o arquivo, dentro de material apreendido. Sai daqui sem separador, sem o dois-pontos de fluxo alternativo (`laudo.txt:oculto` grava dentro de um arquivo que *está* na pasta permitida — confinamento sozinho não pega), sem caractere de controle e sem ponto ou espaço final, que o Windows descarta em silêncio fazendo o caminho do resultado apontar para arquivo inexistente. O prefixo com o id não é decoração: liga o arquivo ao item **e** neutraliza os nomes de dispositivo do Windows de uma vez |
| `CasePool.configurePreviews` / `closePreviews` | **Abrir caso é abrir os repositórios de onde o conteúdo dele sai, e fechar é devolvê-los.** O `PreviewRepositoryManager` é global ao processo e indexado por pasta: quem equilibra o par é a contagem de referências do pool, que libera quando a **última** sessão solta. Somente-leitura não é detalhe — `configureWritable` trava o H2 com exclusividade e tomaria do perito o caso aberto na interface. Ao escrever teste para isto, saiba que `hasPreview:true` são 1,7 milhão de itens quase todos com bytes próprios: a classe afetada é a que **não tem tamanho**, e a primeira versão do teste passou com o conserto removido |
| `timeout_ms` / `TimeLimitingCollector` | **Cobre a varredura, não o plano.** O relógio é consultado dentro de `collect()`; a expansão de multi-term acontece antes, na montagem da consulta, onde nada o interrompe. Foi por isso que `query: "*"` não voltava `partial` depois de 30 s — pendurava num lugar onde o cronômetro não existe. Nenhum texto do servidor pode apresentar `timeout_ms` como garantia de tempo de resposta |
| Custo de wildcard no vocabulário do IPED | O parser roda com `SCORING_BOOLEAN_REWRITE` e campos padrão `{name, content}` ([`QueryBuilder`](../iped-engine/src/main/java/iped/engine/search/QueryBuilder.java)), e o `IPEDSource` levanta `IndexSearcher.setMaxClauseCount(Integer.MAX_VALUE)`. Consequência: wildcard amplo **não falha**, vira uma cláusula por termo do índice e custa o dicionário inteiro. Quem for acrescentar reconhecimento de expressão precisa saber que o teto não protege nada aqui |
| `AuditTrail.digest` | A ordem dos campos em `AuditRecord.toNodeWithoutHash` faz parte do hash. Reordenar invalida a verificação de trilhas já emitidas. |
| Portão de escrita no `McpDispatcher` | Precisa continuar antes de qualquer leitura de argumento, ou "sem tocar o caso" deixa de ser verdade. |
| `ConcurrencyGuard` | A UI do IPED 4.3.1 não trava o caso. A detecção é cooperativa entre processos `iped-mcp` e best-effort para a UI — ausência de conflito **não** prova ausência de outro leitor. |
| `ItemView.storedFields` | Lê do documento armazenado, não do `IItem`. Acrescentar campo aqui é barato; trocar por reconstrução de item custa a latência da página. |
| `FieldSelection` | Tipagem tem que **concordar com o `ItemView`** campo a campo: tamanho é número, timestamp é instante ISO, flag é booleano nas duas formas — quem compara as duas respostas está comparando o mesmo item. Duas armadilhas do índice moram aqui: `isRoot` só é gravado quando verdadeiro, então **ausência é `false`**, não indeterminado; e campo binário (`thumbnail`, features, KnnVector) tem `stringValue()` nulo e é declarado em `unavailable` apontando a ferramenta que devolve bytes — projetá-lo como vazio seria mentir sobre o item. |
| `CasePool` | Um `IPEDSource` por caso por processo, com contagem de referências. `OpenCase.close()` **solta referência**, não fecha o handle — fechá-lo direto tira o searcher de baixo de outra sessão. O que é compartilhado é imutável depois do construtor; nada com estado mutável pode entrar aqui |
| `AuditRecord` | **Não acrescente campo.** `AuditTrail.verify` recompõe `toNodeWithoutHash` a partir do que lê, então um campo a mais muda o resultado para registros já emitidos. Foi por isso que identidade alegada foi para o campo `operator` e transporte/origem para o `SessionManifest` |
| `bridge/mcp-bridge.py` | Mesmas duas regras do `McpRelayMain`, e pelas mesmas razões: nada em stdout — que aqui é o canal de volta ao harness — e `shutdown(SHUT_WR)` no fim da entrada. Não tem suíte própria: é script de recurso, não classe compilada. Ao mexer, verifique à mão as duas coisas que importam — as chamadas respondem **e** o processo sai com código 0 quando o stdin fecha. A primeira sozinha não prova nada |
| `McpRelayMain.relay` | O `shutdownOutput()` no fim da entrada **não é limpeza opcional**. Sem ele o relay não termina quando o harness fecha o stdin — que é como todo harness sai — e a sessão fica segurando o caso e a reivindicação de escrita até o teto de ociosidade. Encontrado em campo, não na suíte: um relay pendurado passa em qualquer teste de requisição/resposta. `RelayShutdownTest` fixa |
| `HandshakeCodec` | Fora do JSON-RPC de propósito. Movê-lo para dentro de `initialize` daria ao dispatcher um estado "autenticado ou não" que toda ferramenta teria de consultar, e a primeira que esquecesse seria um vazamento |
| `PathConfinement` | Toda a classe existe porque comparação textual de prefixo não sustenta a regra. Trocar `toRealPath()` por `getCanonicalPath()` ou por `normalize()` reabre a junção de diretório; comparar com `String.startsWith` em vez de `Path.startsWith` faz uma raiz `D:\laudo` casar com `D:\laudos`. `PathConfinementTest` fixa os dois. O veredito de prefixo estendido `\\?\` **difere entre Java 11 e versões novas** — o teste afirma "recusado", não um veredito |
| `McpServerConfig.exportRoots` | Separador `;`, não `,`: caminho de arquivo carrega vírgula. Sem raiz declarada vale uma raiz padrão criada sob demanda, para que instalação existente continue funcionando ao atualizar (FR-024) |
| `allowExportIntoCaseFolder` | **Semântica estreitada.** Suprime só o veredito `INSIDE_CASE`; não reabre o resto do sistema de arquivos. Antes fazia `checkDestination` retornar antes de qualquer verificação |
| `FieldNames.escapeKnownFieldNames` | Só reescreve nome que **este caso tem**, fora de aspas e seguido de `:`. Afrouxar qualquer uma das três condições faz o servidor inventar restrição de campo ou alterar a frase que o perito procurava. |
| `ProgressReader` | Duas fontes, não uma. Contadores por **forma numérica** (`n/m`, `(p%)`), que sobrevivem a troca de locale; **fase** só por prosa localizada, sem âncora nenhuma — por isso o locale do filho é fixado, e não como salvaguarda secundária. O separador das linhas do motor é **tabulação**, e a saída é **CP1252** no Windows, não UTF-8: as duas coisas foram medidas contra o motor real, não supostas. E `stop()` **drena antes** de parar; inverter isso descarta justamente a cauda que explica uma falha |
| `DiskPreflight.isSiblingSegment` | Ancorado na extensão do arquivo nomeado, não num padrão geral. "Letra mais dois alfanuméricos" casa com `E01` e igualmente com `csv`, `txt` e `log` — que é o que ferramenta de aquisição deixa ao lado da imagem |
| `JobRunner.validate(request, resuming)` | Ocupação de destino é regra **de criação**. Numa retomada o destino tem que conter o caso parcial, e `HAS_FINISHED_CASE` vem da mesma verificação estrutural que não distingue concluído de interrompido. A fronteira do append é aplicada pelo estado registrado do trabalho, que é autoritativo |
| Watcher do `JobRunner` | Só conclui o processo que observou. Retomada mantém o `job_id`, então um watcher obsoleto concluiria a execução nova com o código de saída da antiga — guardado por PID |
| `Cursor` | A posição vem de `FieldDoc.fields[0]`, **nunca** de `ScoreDoc.score` — o `TopFieldCollector` deixa `NaN` ali. O formato do cursor decorre de `Cursor.SORT`: mudar a ordenação sem mudar o cursor faz a paginação reiniciar em silêncio. |

## 10. Limitações conhecidas

- **Concorrência com a UI** é best-effort (ver acima). Vale **por cima** da exclusividade entre sessões: deter a reivindicação de escrita não dispensa o `probeBookmarksState`, e a UI segurando o estado de marcadores recusa a escrita de qualquer forma.
- **O canal do transporte de rede não é protegido.** Autenticação por segredo compartilhado, conteúdo em claro. Adequado quando o trânsito fica dentro de uma máquina física — VM ou contêiner falando com o hospedeiro — ou em segmento confiável. Entre máquinas físicas em rede compartilhada, o conteúdo de evidência trafega legível para quem observe o segmento. Autenticação mútua por certificados é evolução prevista e não construída; o gatilho para retomá-la é a primeira implantação entre máquinas físicas distintas.
- **Trilha por sessão, não por caso**, agora com o `SessionManifest` como resposta: uma linha por sessão que tocou o caso, na subpasta de auditoria, respondendo "são todas?" e "em que ordem?". A limitação em si permanece — o que mudou é que ela deixou de impedir a reconstituição. Uma sessão que abre dois casos sincroniza o mesmo arquivo para dentro dos dois. Sob a decisão D2 — estação individual, um caso por vez — isso é o comportamento certo; com dois casos abertos juntos, o perito vê ambos de qualquer forma.
- **Snippet custa reextração de texto**, limitado por orçamento. Itens além do orçamento vêm com o trecho declarado ausente.
- **A senha de contêiner cifrado vai ao motor pela linha de comando.** É a única via que o IPED oferece — não existe configuração de senha no motor —, e ela fica legível a outras contas da mesma máquina enquanto o processo existe (`/proc/<pid>/cmdline` no Linux). Decisão tomada por proporção: a exposição só se realiza em máquina de evidência com **mais de uma conta**, e a estação típica tem uma. Não é tácita: todo aceite que usa referência de segredo declara isso na resposta. Fechá-la exigiria acrescentar `-passwordFile` ao `iped-app`; o gatilho para retomar essa opção é a primeira implantação em máquina de evidência compartilhada.
- **Retomada não é garantida por antecedência.** `--continue` só é aceito quando o destino já tem a pasta `iped`, e o servidor recusa antes com explicação própria quando não tem. Mas a verificação é **necessária, não suficiente**: uma interrupção logo após a pasta aparecer ainda falha dentro do motor, por estado que essa checagem não vê. Prever isso exigiria reimplementar a lógica de continuação do motor; quando acontece, o trabalho termina `FAILED` carregando as palavras do próprio motor.
- **Diagnóstico dirigido ao agente não é "texto visível ao usuário"** do Princípio V, e por isso vive em inglês junto do resto da superfície. O que é localizado é interface do IPED, em `iped-app/resources/localization/`. A interpretação já era praticada desde a 001 e está escrita aqui para parar de parecer descuido a cada revisão.
- **A versão do caso é lida do nome dos jars em `iped/lib`.** Um caso com essa pasta podada é recusado com `VERSION_UNSUPPORTED` em vez de ser aberto sob suposição.
