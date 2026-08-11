# Contrato — handshake do transporte de rede

Precede o JSON-RPC e não faz parte dele. A razão está em [research.md R4](../research.md): se o
segredo viajasse em `initialize`, o dispatcher passaria a ter estado "autenticado ou não" e toda
ferramenta precisaria consultá-lo — a primeira que esquecesse seria um vazamento. Fora do protocolo,
uma conexão não autenticada nunca alcança o dispatcher, que é o que FR-013 exige.

## Sequência

```
cliente                                        servidor
   │                                              │
   │ conecta ─────────────────────────────────────▶
   │                                              │  aceita; teto de maxConcurrentSessions
   │                                              │  e prazo de handshake começam a contar
   │                                              │
   │ 1 linha UTF-8, terminada em \n ──────────────▶
   │   IPED-MCP/1 <segredo> [<operador-alegado>]  │
   │                                              │  comparação em tempo constante
   │                                              │
   │◀───────────────────── 1 linha UTF-8 ─────────│
   │   IPED-MCP/1 OK <sessionId>                  │
   │   ou  IPED-MCP/1 DENIED                      │  e fecha
   │                                              │
   │◀════════ JSON-RPC 2.0, exatamente como no stdio ════════▶
```

Depois do `OK`, os fluxos do socket são entregues a `McpServerMain.start(InputStream, OutputStream)`
sem nenhuma adaptação. É por isso que FR-015 — mesma superfície de ferramentas nos dois transportes —
sai por construção e não por disciplina de manutenção.

## Regras

| Regra | Requisito |
|---|---|
| Charset **explícito** UTF-8 na linha de handshake, em ambas as direções | Princípio V |
| Comparação do segredo em tempo constante | FR-013; comparação curto-circuitada transformaria a latência em oráculo |
| `DENIED` fecha a conexão sem revelar nada — nem versão de caso, nem existência de caso, nem se o segredo estava perto | FR-013 |
| Toda recusa é registrada com a origem, de forma que uma sequência de tentativas seja perceptível | FR-027 |
| Handshake que não chega dentro do prazo fecha a conexão | borda "conecta e não emite nada" |
| `<operador-alegado>` é **opcional**; ausente é estado válido e registrado como ausente | FR-020 |
| `<operador-alegado>` nunca substitui a identidade autoritativa, nem quando a autoritativa é a mesma string | FR-020, FR-032 |

## Relay

`iped.mcp.McpRelayMain` — segunda classe `main` do mesmo jar. O harness continua sendo configurado
para subir um processo local que fala stdio; esse processo é o relay.

```
opencode ──stdio──▶ McpRelayMain ──socket──▶ McpServerMain
 (VM isolada)        (VM isolada)             (host, junto da evidência)
```

O relay lê o segredo das mesmas duas fontes que o servidor (variável de ambiente ou arquivo apontado),
faz o handshake e bombeia bytes nas duas direções até qualquer lado fechar.

**Meio-fechamento ao fim da entrada, e isso não é detalhe (FR-035).** Quando a entrada vinda do
harness acaba, o relay MUST fechar o lado de escrita da conexão (`Socket.shutdownOutput`). Fechar o
stdin do processo filho é como os harnesses suportados sinalizam encerramento; sem o meio-fechamento
o servidor não fica sabendo, segue esperando requisição, e o relay segue esperando resposta — os dois
parados, com a sessão retendo o caso e a reivindicação de escrita até o teto de ociosidade. Medido em
campo antes de existir o requisito.

O sentido de descida roda na thread principal, de modo que o processo só termina quando o servidor
fecha o seu lado — saída limpa, não morte por timeout.

**Restrição que vale para o relay tanto quanto para o servidor**: nenhum `System.out`. No relay, stdout
é o canal do protocolo **para o harness** — um único print corrompe a sessão do lado do cliente,
exatamente como já vale do lado do servidor. Diagnóstico por SLF4J, para stderr ou arquivo.

## Configuração do harness

Não muda em forma, só em qual classe é invocada. Onde hoje se lê `iped.mcp.McpServerMain`, passa a se
ler `iped.mcp.McpRelayMain` com endereço e porta. Os três guias de instalação
(`skill/install/{claude-code,codex,opencode}.md`) ganham a variante de topologia dividida, mantendo a
fonte canônica única da skill.
