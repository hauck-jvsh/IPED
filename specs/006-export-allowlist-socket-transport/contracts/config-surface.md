# Contrato — superfície de configuração

Chaves novas em `conf/McpServerConfig.txt`. Princípio IV da constituição: tudo o que varia vive aqui,
nada em constante de código além dos fallbacks de último recurso que `McpServerConfig` já documenta.

## Confinamento de escrita

| Chave | Padrão | Efeito |
|---|---|---|
| `exportRoots` | vazio | Raízes sob as quais artefatos podem ser gravados. Separador `;`, como o `PATH` do Windows — vírgula não serve, porque caminho de arquivo a contém com frequência. Vazio significa a raiz padrão descrita abaixo |
| `allowExportIntoCaseFolder` | `false` | **Chave existente, semântica estreitada.** Continua sendo a válvula de escape para gravar dentro da pasta do caso; deixa de ser válvula de escape para gravar fora das raízes |

**Raiz padrão.** Sem `exportRoots` declarado, vale uma raiz documentada na área de trabalho do usuário
que executa o servidor, criada sob demanda. É o que preserva FR-024: instalação existente continua
funcionando após a atualização sem editar configuração, e ainda assim passa a estar confinada. A pasta
do caso continua recusada em qualquer hipótese.

**A mudança de semântica de `allowExportIntoCaseFolder` é o ponto de atenção da atualização.** Hoje ela
faz `checkDestination` retornar antes de qualquer verificação — ligada, todo o disco fica liberado.
Passa a suprimir **apenas** o veredito `INSIDE_CASE`; `OUTSIDE_ROOTS` permanece recusado com ela ligada.
Quem a usa hoje para gravar em pasta arbitrária vai começar a receber recusa, e é essa a intenção.

## Transporte

| Chave | Padrão | Efeito |
|---|---|---|
| `transport` | `stdio` | `stdio` ou `socket`. Em `stdio`, nenhuma porta é aberta e o comportamento é idêntico ao vigente (FR-011, SC-002) |
| `listenAddress` | **sem padrão** | Endereço de escuta. Sem valor implícito, e em particular nunca todas as interfaces por omissão (FR-012, Princípio V) |
| `listenPort` | **sem padrão** | Porta de escuta |
| `sharedSecretFile` | **sem padrão** | Caminho de um arquivo cujo conteúdo é o segredo |
| `maxConcurrentSessions` | `4` | Teto de sessões simultâneas. A ordem de grandeza prevista é "dois peritos", não "cem clientes" |
| `sessionIdleTimeoutSeconds` | `300` | Encerra conexão autenticada que não emite pedido, para que não ocupe vaga indefinidamente |

### O segredo nunca é declarado em configuração — só onde encontrá-lo

FR-028 veda exigir o segredo em arquivo distribuído com o release ou versionado, e
`conf/McpServerConfig.txt` **é** distribuído com o release. Então a configuração declara **onde** o
segredo está, nunca **qual** ele é:

1. Variável de ambiente `IPED_MCP_SHARED_SECRET`, quando presente; ou
2. o conteúdo do arquivo apontado por `sharedSecretFile`.

Nenhum dos dois tem valor padrão. Com `transport = socket` e nenhuma das duas fontes disponível, ou com
o valor resolvido vazio, **o ponto de escuta não é estabelecido** e a causa é reportada (FR-026). Não
existe caminho em que o transporte suba sem autenticação.

O mesmo par de fontes alimenta o relay do lado do harness, para que o segredo não precise aparecer na
configuração do harness — que costuma ser versionada.

## Diagnóstico de inicialização

`Diagnostics` ganha verificações, seguindo o padrão existente de "falha não é sempre fatal, mas é
sempre reportada":

| Verificação | Falha significa |
|---|---|
| Cada raiz de `exportRoots` existe, é pasta e é gravável | Reportado; servidor sobe; a primeira gravação sob a raiz falha com diagnóstico acionável (FR-006) |
| Com `transport = socket`, o segredo resolve para valor não vazio | **Fatal para o transporte**: ponto de escuta não estabelecido (FR-026) |
| Com `transport = socket`, `listenAddress` e `listenPort` declarados e vinculáveis | Reportado; servidor não aparenta servir (FR-018) |
