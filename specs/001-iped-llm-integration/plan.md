# Implementation Plan: Integração IPED ↔ LLM (Servidor MCP + Skill de agente)

**Branch**: `001-iped-llm-integration` | **Date**: 2026-08-04 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-iped-llm-integration/spec.md`

## Summary

Expor casos processados do IPED a agentes de LLM através de um servidor MCP e de uma skill que ensina o agente a usá-lo com disciplina pericial. O servidor nasce como **novo módulo Maven `iped-mcp` em Java 11**, dentro deste repositório, falando MCP sobre JSON-RPC 2.0 em transporte **stdio**, com acesso direto às APIs do `iped-engine`.

Três achados da pesquisa condicionaram o desenho:

1. **O SDK MCP oficial para Java exige Java 17+** e o release do IPED embarca JRE 11 — a camada de protocolo é implementada diretamente sobre Jackson, que já está na árvore.
2. **`IPEDSearcher` materializa todo o conjunto de resultados** (`searchAll()` coleta sobre `maxDoc()` e itera até esgotar). A falta de paginação não era defeito da POC: está na API que ela chamava. O caminho de consulta paginada usa `QueryBuilder` para a semântica do IPED e `IndexSearcher.searchAfter` para colher só a página.
3. **Não há `lucene-facet` na árvore**, e casos já processados não têm `FacetField`. Agregações são feitas sobre `SortedSetDocValues`, seguindo o padrão de `TimelineResults`.

Duas alternativas foram avaliadas a pedido e rejeitadas, ambas detalhadas em [research.md](./research.md): implementar o servidor em Python (R1) e adotar Tool Search Tool ou Programmatic Tool Calling (R11). A segunda merece nota aqui porque o PTC é **incompatível com ferramentas MCP** por documentação explícita — e porque o problema que ele resolve é resolvido neste desenho do lado do servidor (FR-016, FR-024, FR-067), por um caminho que funciona também em Codex, OpenCode e modelo local.

## Technical Context

**Language/Version**: Java 11 (`maven.compiler.source/target = 11` no parent pom; JRE 11 embarcado no release)

**Primary Dependencies**:
- `iped-engine` — `IPEDSource`, `QueryBuilder`, `Bookmarks`, `LoadIndexFields`, `IndexItem`, `BasicProps`
- `iped-api` — interfaces (`IItem`, `IItemId`, `IIPEDSource`, `IBookmarks`)
- Lucene 9.2.0 — `lucene-core` (busca paginada, DocValues), `lucene-highlighter` (trechos), `lucene-queryparser`
- Jackson — serialização JSON-RPC (já presente via `jackson-core` / `jersey-media-json-jackson`)
- Apache POI — geração de xlsx; **já disponível transitivamente** via `tika-parsers-standard-package` em `iped-parsers-impl` e usado no engine, portanto não acrescenta artefato novo ao release. A declaração deve ser explícita no módulo, com versão alinhada à que o Tika 2.4.0 traz.

**Storage**:
- Leitura: índice Lucene do caso, via `IPEDSource` (`{caseDir}/iped/{index,data,lib}`)
- Escrita no caso: marcadores e seleção, via `Bookmarks.saveState` (somente com escrita habilitada)
- Trilha de auditoria: JSON Lines append-only encadeado por hash, gravado com `fsync` por operação na área da estação (buffer write-ahead) e sincronizado automaticamente para subpasta de auditoria **dentro da pasta do caso**, que é seu lar durável

**Testing**: JUnit 4.13.2 (declarado no parent pom). Três níveis — contrato do protocolo MCP, integração sobre caso de referência, unidade para paginação/agregação/auditoria.

**Target Platform**: Estação de trabalho pericial (Windows primário, Linux suportado), processo local sem exposição de rede (FR-057)

**Project Type**: Módulo de biblioteca + processo servidor, empacotado no release do IPED

**Performance Goals**: primeira página < 5 s em caso de 10 M (SC-002); abertura + panorama < 30 s e agregação < 15 s na mesma escala (SC-015)

**Constraints**:
- Runtime Java 11 — inviabiliza o SDK MCP oficial e qualquer dependência com baseline superior
- Custo de consulta proporcional ao resultado, não ao acervo (exceto panorama e agregações, conscientemente)
- Somente-leitura por padrão; nenhuma escrita sem registro prévio em auditoria
- Nenhuma modificação nos arquivos de evidência original, em qualquer modo
- Ajustes do servidor em `Configurable<T>` carregado de `conf/`, nunca em constantes de código (Princípio IV)
- Charset explícito em toda serialização; logging por SLF4J, sem `System.out`/`System.err` (Princípio V)
- Ferramentas precisam ser acionáveis por modelo local (FR-065): erros carregam a informação necessária à correção, sem exigir dedução

**Scale/Scope**: até ~10 milhões de itens por caso; múltiplos casos abertos por sessão, consulta sobre um caso por vez

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Status: PASSA, com duas correções aplicadas ao desenho.**

Avaliado contra a Constituição v1.0.0, ratificada em 2026-08-04 — depois deste plano ter sido escrito. O bloco anterior registrava "não avaliável" porque o portão ainda não existia; esta é a reavaliação que aquele registro previa.

| Princípio | Resultado |
|---|---|
| **I — Integridade da evidência é inviolável** | **Passa.** FR-031 proíbe modificar evidência original em qualquer modo; FR-025 fixa somente-leitura por padrão; FR-033 exige estado anterior em operação destrutiva; SC-003 verifica. A trilha de auditoria excede o exigido pelo princípio. |
| **II — Caso processado é contrato permanente** | **Passa.** O desenho lê nomes de campo via `LoadIndexFields` e nunca os define; não toca `BasicProps`, `IndexItem` nem `AppAnalyzer`; não altera método algum de `iped-api`. Nenhuma classe existente é modificada. |
| **III — Estender antes de modificar** | **Passa com folga.** Módulo novo consumindo apenas API pública. R3 rejeitou explicitamente alterar `IPEDSearcher`, apesar de ser a correção mais direta, justamente por ser API central usada pela UI — é este princípio aplicado antes de existir. Os dois arquivos tocados (parent `pom.xml`, `iped-app/pom.xml`) são aditivos. |
| **IV — Comportamento configurável vive em configuração** | **Corrigido.** Ver abaixo. |
| **V — Nada implícito no que varia por ambiente** | **Corrigido.** Ver abaixo. |

### Correção sob o Princípio IV

A lacuna era real: o servidor é externamente configurável — área de auditoria, modo de escrita, política de egresso, teto de página e de conteúdo — e nem o plano nem as tarefas mencionavam `Configurable<T>`.

A hipótese de desvio justificado (que `Configurable<T>` fosse específico do pipeline de tasks e não coubesse a um processo servidor) **não se sustenta**, e a verificação foi conclusiva em dois pontos:

- `Configurable<T>` vive em `iped-api` e é genérico — filtro de lookup de recurso e processamento de arquivos de configuração. `AbstractTaskConfig<T>` é que é a especialização para tasks.
- `ConfigurationManager` se instancia a partir de um `IConfigurationDirectory` e não depende de `Manager`, `Worker` ou de processamento em curso.

E o custo é praticamente nulo, porque `IPEDSource` **já** chama `Configuration.getInstance().loadConfigurables(...)` e `ConfigurationManager.get().findObject(...)`: o servidor terá de inicializar o sistema de configuração só para abrir um caso.

**Decisão**: os ajustes do servidor MUST viver em `Configurable<T>` carregado de `conf/`, como o restante do IPED. Acrescentada a tarefa T088.

### Correção sob o Princípio V

Charset e logging não estavam explicitados em lugar nenhum — nem no Technical Context, nem nas tarefas de `JsonRpcCodec`, `AuditTrail` ou diagnóstico. São exatamente os pontos onde o padrão herdado da plataforma vira defeito na máquina de outra pessoa.

**Decisão**: charset explícito em toda serialização (JSON-RPC sobre stdio e trilha em JSON Lines) e logging via SLF4J, sem `System.out`/`System.err`. Aplicado ao Technical Context e às tarefas T008, T017 e T067.

### Nota sobre a ordem

As duas correções custaram poucas linhas porque nenhum código existia ainda. Se a constituição tivesse sido ratificada depois da implementação, o Princípio IV significaria reescrever a camada de configuração do módulo. É argumento para ratificar governança antes de planejar, não depois.

## Project Structure

### Documentation (this feature)

```text
specs/001-iped-llm-integration/
├── plan.md              # Este arquivo
├── research.md          # Phase 0 — decisões técnicas e alternativas rejeitadas
├── data-model.md        # Phase 1 — entidades e invariantes
├── quickstart.md        # Phase 1 — guia de validação executável
├── contracts/
│   └── mcp-tools.md     # Phase 1 — contrato das ferramentas expostas
├── checklists/
│   └── requirements.md  # Checklist de qualidade do spec
└── tasks.md             # Phase 2 — gerado por /speckit-tasks, NÃO por este comando
```

### Source Code (repository root)

```text
iped-mcp/                                    # NOVO módulo Maven (Java 11)
├── pom.xml
└── src/
    ├── main/
    │   ├── java/iped/mcp/
    │   │   ├── McpServerMain.java           # entry point stdio; inicialização programática (FR-064)
    │   │   ├── config/
    │   │   │   └── McpServerConfig.java      # Configurable<T> lido de conf/ (Princípio IV)
    │   │   ├── protocol/                    # JSON-RPC 2.0 + handshake MCP (R2)
    │   │   │   ├── JsonRpcCodec.java
    │   │   │   ├── McpDispatcher.java       # initialize, tools/list, tools/call
    │   │   │   └── ToolDescriptor.java
    │   │   ├── session/
    │   │   │   ├── Session.java             # operador, modo de acesso, política vigente
    │   │   │   ├── CaseRegistry.java        # casos abertos; idempotência de abertura (FR-004)
    │   │   │   ├── CaseValidator.java       # integridade e faixa 4.x (FR-001, FR-002, FR-054)
    │   │   │   └── ConcurrencyGuard.java    # detecção de acesso concorrente (FR-028)
    │   │   ├── query/
    │   │   │   ├── PagedSearcher.java       # QueryBuilder + searchAfter (R3) — NÃO usa IPEDSearcher
    │   │   │   ├── Aggregator.java          # contagens via SortedSetDocValues (R4)
    │   │   │   ├── SnippetBuilder.java      # lucene-highlighter (R5)
    │   │   │   └── FieldVocabulary.java     # LoadIndexFields + sugestão de similares (R6)
    │   │   ├── item/
    │   │   │   ├── ItemView.java            # propriedades essenciais enriquecidas (FR-014)
    │   │   │   └── ContentAccess.java       # texto, miniatura, binário, com limites (FR-021)
    │   │   ├── curation/
    │   │   │   └── BookmarkWriter.java      # marcadores e seleção (FR-026, FR-027)
    │   │   ├── audit/
    │   │   │   ├── AuditTrail.java          # append-only encadeado por hash (R7)
    │   │   │   └── AuditRecord.java
    │   │   ├── egress/
    │   │   │   └── EgressPolicy.java        # opcional, inativa por padrão (FR-038 a FR-043)
    │   │   ├── export/
    │   │   │   └── ArtifactWriter.java      # xlsx / CSV / JSON (FR-066 a FR-070)
    │   │   └── tools/                       # uma classe por ferramenta MCP
    │   └── resources/
    │       └── skill/                       # conteúdo canônico da skill (FR-063)
    │           ├── SKILL.md
    │           └── references/
    └── test/java/iped/mcp/
        ├── contract/                        # handshake e esquema das ferramentas
        ├── integration/                     # contra caso de referência
        └── unit/

iped-app/
├── pom.xml                                  # ALTERADO: empacotar iped-mcp no release
└── resources/
    └── skills/                              # invólucros finos por harness, gerados no build

pom.xml                                      # ALTERADO: <module>iped-mcp</module>
```

**Structure Decision**: módulo novo em vez de pacote dentro do `iped-engine`. O engine é marcado como área crítica no seu próprio `CLAUDE.md`, e esta feature não precisa tocá-lo — consome APIs públicas existentes (`IPEDSource`, `QueryBuilder`, `Bookmarks`, `LoadIndexFields`) sem alterar nenhuma. Módulo separado mantém a fronteira explícita, isola as dependências novas e permite versionar o servidor junto do release sem acoplar seu ciclo de vida ao do engine.

Dois arquivos existentes são tocados, ambos de forma aditiva: o parent `pom.xml` (registro do módulo) e `iped-app/pom.xml` (empacotamento no release). **Nenhuma classe existente é modificada.**

## Complexity Tracking

Não há violações de constituição a justificar, porque não há constituição ratificada. Registro aqui, por transparência, as duas escolhas que ampliam superfície e por que a alternativa simples foi rejeitada:

| Escolha | Por que é necessária | Alternativa simples rejeitada porque |
|---|---|---|
| Camada de protocolo MCP própria, em vez do SDK oficial | O SDK declara baseline Java 17+; o release embarca JRE 11 | Adotar o SDK quebraria o build e, pior, não executaria no runtime distribuído em campo |
| Caminho de consulta próprio, em vez de `IPEDSearcher` | `IPEDSearcher.searchAll()` materializa todos os matches, inviabilizando FR-013 e SC-002 em 10 M | Paginar sobre o resultado dele resolveria a resposta, não o custo — o trabalho caro já teria sido feito. Alterar `IPEDSearcher` mexeria em API central usada pela UI, marcada como sensível no `CLAUDE.md` do engine |

## Riscos herdados do spec

Dois pontos chegam ao plano deliberadamente abertos e não devem ser tratados como resolvidos:

1. **Durabilidade da trilha de auditoria — RESOLVIDO em 2026-08-04.** A decisão provisória foi reaberta e fechada antes de qualquer código: a área da estação vira buffer write-ahead com `fsync` por operação, e a pasta do caso vira o lar da trilha, por sincronização automática. SC-003 foi reescrito para garantir evidência, índice e estado de análise, com a subpasta de auditoria excluída por nome. Detalhe e fundamentação em R7 de [research.md](./research.md); requisitos em FR-071 a FR-074. Permanece aberto apenas o ponto organizacional: se o caso não for arquivado corretamente, a trilha se perde com ele.
2. **Egresso de conteúdo sem restrição por padrão (D3).** A salvaguarda passa a ser operacional, não técnica: rodar com harness de modelo local (D4, FR-065). Isso precisa estar na documentação de instalação como configuração recomendada, e não como nota de rodapé.
