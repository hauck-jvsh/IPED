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
├── query/                   # PagedSearcher, Aggregator, SnippetBuilder, FieldVocabulary
├── item/                    # ItemView, ContentAccess
├── curation/BookmarkWriter  # marcadores e seleção sobre Bookmarks/saveState
├── audit/                   # AuditRecord, AuditTrail, AuditSync
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

Duas consequências práticas menos óbvias:

- **O campo `content` é indexado mas não armazenado.** Snippet exige reextrair o texto do item, o que é caro. Por isso `SnippetBuilder` trabalha sob três orçamentos (itens por página, bytes por item, tempo por página) e declara ausência quando estoura, em vez de devolver vazio.
- **Registro precede ação.** Como a trilha é append-only, cada operação gera **dois** registros encadeados: `STARTED` antes de executar (com parâmetros e estado anterior) e o desfecho depois, ligado por `refSeq`. Se o `STARTED` não puder ser gravado, a operação é recusada e não executa.

## 4. Configuração

Tudo o que varia vive em `conf/McpServerConfig.txt` (Princípio IV da constituição), nunca em constante de código: área de auditoria, modo de acesso, política de egresso, tetos de página, de lote e de conteúdo, faixa de versão suportada, destino de exportação.

`McpServerConfig` implementa `Configurable<UTF8Properties>` e é carregado pelo `ConfigurationManager`. Os valores no código são **fallback de último recurso** para quando o arquivo não existe (teste isolado, instalação quebrada); o arquivo distribuído é a autoridade e carrega os mesmos valores.

## 5. Invariantes que não podem ser afrouxadas

| Invariante | Onde é aplicada |
|---|---|
| Nenhuma operação executa sem registro prévio | `McpDispatcher.callTool` → `AuditTrail.recordStart` |
| Somente-leitura por padrão; curadoria recusada sem tocar o caso | portão de modo de acesso no `McpDispatcher`, antes de qualquer leitura de argumento |
| Política de egresso não contornável por escolha de ferramenta | classe de conteúdo declarada em `ToolDescriptor.returnsContent`, aplicada na fronteira do dispatcher |
| Estado anterior antes de operação destrutiva | `ToolDescriptor.capturingPriorState`, avaliado antes do `recordStart` |
| Referência a item sempre carrega o caso | contrato das ferramentas; `ToolSchemaTest` verifica |
| Ausência ≠ vazio | `ItemView.unavailable`, `ContentAccess.unavailable` |
| Charset explícito, logging por SLF4J | `JsonRpcCodec`, `AuditTrail`; `System.out` corromperia o próprio protocolo |

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
mvn -pl iped-mcp test                                            # sem caso: 76 testes efetivos
mvn -pl iped-mcp test -Diped.mcp.test.referenceCase=<path>       # + suítes de integração
mvn -pl iped-mcp test -Diped.mcp.test.largeCase=<path>           # + SC-002 e SC-015
```

As suítes que precisam de caso **pulam** quando ele não está configurado, e **um teste pulado não é um teste que passou**. A receita reprodutível do caso de referência está em [`src/test/resources/reference-case/README.md`](src/test/resources/reference-case/README.md).

`ScalePerformanceTest` contra o caso grande é inegociável: uma implementação que materializa o conjunto passa em todas as outras suítes deste módulo e só falha em campo.

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

## 10. Limitações conhecidas

- **Concorrência com a UI** é best-effort (ver acima).
- **Trilha por sessão, não por caso.** Uma sessão que abre dois casos sincroniza o mesmo arquivo para dentro dos dois. Sob a decisão D2 — estação individual, um caso por vez — isso é o comportamento certo; com dois casos abertos juntos, o perito vê ambos de qualquer forma.
- **Snippet custa reextração de texto**, limitado por orçamento. Itens além do orçamento vêm com o trecho declarado ausente.
- **A versão do caso é lida do nome dos jars em `iped/lib`.** Um caso com essa pasta podada é recusado com `VERSION_UNSUPPORTED` em vez de ser aberto sob suposição.
