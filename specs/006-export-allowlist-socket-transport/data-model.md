# Phase 1 — Modelo de dados

**Feature**: 006-export-allowlist-socket-transport | **Data**: 2026-08-10

Nenhuma destas entidades é persistida em banco. Três vivem só em memória pelo tempo de uma sessão,
duas são configuração lida na inicialização, e duas são registro append-only em disco. As regras de
validação vêm nominalmente dos requisitos do [spec.md](./spec.md).

---

## Raiz de escrita — `WriteRoot`

Pasta declarada sob a qual a gravação de artefatos é permitida.

| Campo | Tipo | Origem |
|---|---|---|
| `declared` | caminho como escrito na configuração | `exportRoots` em `conf/McpServerConfig.txt` |
| `resolved` | caminho real, por `toRealPath()` | calculado na inicialização |
| `state` | `USABLE`, `MISSING`, `NOT_A_DIRECTORY`, `NOT_WRITABLE` | sondado na inicialização |

**Regras**

- Zero ou mais por instalação (FR-005). Sem nenhuma declarada, vale a raiz padrão documentada na área
  de trabalho do usuário que executa o servidor, criada sob demanda — decisão registrada nas
  Assumptions do spec, que preserva FR-024.
- Raiz não `USABLE` é reportada no diagnóstico de inicialização e **não impede o servidor de subir**
  (FR-006), coerente com o comportamento já existente de `Diagnostics`. A primeira gravação sob ela
  falha com diagnóstico acionável.
- Não alterável por ferramenta exposta ao agente (FR-009). A configuração é lida uma vez, na
  inicialização, pelo mesmo caminho que todo o resto de `McpServerConfig`.
- Raízes aninhadas são permitidas e não são erro: contenção é "está sob **alguma** raiz".

---

## Destino resolvido — `ResolvedDestination`

Resultado de submeter um caminho pedido pelo agente à verificação de contenção. É valor imutável,
produzido por `PathConfinement` e consumido por `ArtifactWriter`.

| Campo | Tipo | Observação |
|---|---|---|
| `requested` | texto, como o agente escreveu | preservado para diagnóstico e trilha |
| `resolved` | caminho real | ancestral existente mais profundo resolvido, restante recomposto por cima |
| `root` | raiz que o contém | ausente quando recusado |
| `verdict` | `ALLOWED`, `OUTSIDE_ROOTS`, `INSIDE_CASE`, `UNRESOLVABLE` | |

**Regras**

- `toRealPath()` lança `NoSuchFileException` para arquivo inexistente, e o destino de uma exportação
  nunca existe. A resolução sobe até o ancestral existente mais profundo, resolve **esse**, e recompõe
  o restante ([research.md R1](./research.md)).
- Comparação sempre **real contra real**: a raiz também passa por `toRealPath()`. Comparar caminho cru
  contra caminho real produz falso positivo por caixa, por nome curto 8.3 e por prefixo estendido —
  todos medidos.
- `INSIDE_CASE` prevalece sobre `ALLOWED` (FR-004): destino dentro da pasta do caso é recusado mesmo
  quando a raiz declarada contém o caso.
- `UNRESOLVABLE` cobre o que `java.nio.file.Path` recusa na entrada — fluxo alternativo de dados,
  caractere ilegal, espaço ao final. São `InvalidPathException`, não escapes: viram recusa nomeada em
  vez de exceção técnica.
- Nenhum efeito no sistema de arquivos antes de `verdict == ALLOWED` (FR-002). A criação de pastas
  intermediárias, que hoje acontece antes de qualquer decisão, passa a acontecer depois.

---

## Ponto de escuta — `ListenEndpoint`

| Campo | Tipo | Regra |
|---|---|---|
| `address` | endereço declarado | sem padrão implícito; nunca todas as interfaces por omissão (FR-012) |
| `port` | inteiro | declarado |
| `active` | booleano | falso quando o transporte não foi configurado |

**Regras**

- Ausente por padrão. Instalação que não configura o transporte não abre porta (FR-011, SC-002).
- Ativação sem segredo declarado **não estabelece o ponto de escuta** (FR-026). Não há degradação
  silenciosa para transporte aberto.
- Falha ao vincular — porta ocupada, endereço inexistente — é diagnóstico acionável, e o servidor não
  passa a aparentar que serve (FR-018).

---

## Identidade do operador — `OperatorIdentity`

Par que substitui o campo único de hoje.

| Campo | Origem | Estatuto |
|---|---|---|
| `authoritative` | `System.getProperty("user.name")` do processo servidor | verificada |
| `claimed` | declarada pelo cliente no handshake | **alegação não verificada** |

**Regras**

- As duas coexistem e nunca se fundem (FR-020).
- A distinção sobrevive à exportação legível por humano da trilha (FR-032, FR-036 de 001). Em
  exportação para humano, nome de campo não basta: a alegação aparece rotulada como não verificada.
- `claimed` ausente ou vazia é estado válido — o handshake não a exige — e é registrada como ausente,
  nunca substituída pela autoritativa.
- Sob transporte local, `claimed` não existe e o comportamento é o de hoje.

### Como o par é gravado — decisão revista na implementação (2026-08-11)

Este documento previa **campos novos em `AuditRecord`** para a identidade alegada, o transporte e a
origem. **Não foi feito assim**, e a razão é uma restrição que o próprio módulo já registrava: a ordem
dos campos em `AuditRecord.toNodeWithoutHash` faz parte do hash encadeado, e a verificação de uma
trilha recomputa esse nó a partir do que lê. Acrescentar campo mudaria o que a verificação recomputa
para registros já emitidos, quebrando exatamente a garantia que a trilha existe para dar.

O que foi feito:

| Dado | Onde vai |
|---|---|
| Identidade dupla | No campo `operator`, **que já existe**, renderizado por `OperatorIdentity.describe()` como `conta (client claims: X, unverified)`. A palavra "unverified" faz parte do valor, não da documentação em volta — é o valor que sobrevive a ser copiado para um laudo |
| Transporte e origem | No **manifesto de sessões**, que é por sessão e não por operação. São propriedade da sessão inteira; repeti-los em cada registro seria redundância, não informação |
| Identidade dupla, em forma de máquina | Em `iped_session_info`, como `operator.authoritative` / `operator.claimed` / `operator.claimed_is_verified` |

FR-020, FR-021 e FR-032 continuam atendidos, e o formato de `AuditRecord` permanece byte a byte o que
era. Nenhum requisito foi relaxado para acomodar isso.

---

## Sessão de transporte — `Session` (modificada)

Deixa de ser uma por processo e passa a ser uma por conexão ([research.md R5](./research.md)).

| Campo | Novo? | Observação |
|---|---|---|
| `sessionId` | existente | já é UUID por sessão; passa a distinguir conexões |
| `operator` | **modificado** | passa a ser `OperatorIdentity` |
| `transport` | **novo** | `STDIO` ou `SOCKET` |
| `origin` | **novo** | origem da conexão, ausente sob stdio |
| `auditTrail` | existente | continua uma por sessão, arquivo `session-<uuid>.jsonl` |
| `concurrencyGuard` | existente | passa a ser por sessão |
| `caseRegistry` | **modificado** | passa a obter casos do `CasePool` em vez de abrir os seus |

### Transições

```
                  ┌─────────────┐
   conexão ──────▶│  CONNECTED  │
                  └──────┬──────┘
                         │ handshake
              ┌──────────┴──────────┐
     segredo  │                     │  segredo ausente,
     correto  ▼                     ▼  vazio ou incorreto
        ┌───────────────┐     ┌──────────┐
        │ AUTHENTICATED │     │ REJECTED │──▶ conexão fechada,
        └───────┬───────┘     └──────────┘    tentativa registrada (FR-027)
                │ fluxos entregues ao dispatcher
                ▼
          ┌──────────┐   queda de conexão
          │ SERVING  │──────────┐
          └────┬─────┘          │
               │ fim normal     │
               ▼                ▼
          ┌──────────────────────────┐
          │         CLOSING          │  libera reivindicação de escrita (FR-030),
          │                          │  devolve casos ao pool, fecha e sincroniza
          │                          │  a trilha, encerra o manifesto
          └────────────┬─────────────┘
                       ▼
                   ┌────────┐
                   │ CLOSED │
                   └────────┘
```

Uma conexão em `REJECTED` **nunca alcança o dispatcher** — é o que sustenta a exigência de FR-013 de
que ela não obtenha resposta de ferramenta nem informação sobre a existência de casos.

`CLOSING` é alcançado tanto pelo encerramento normal quanto pela queda, e faz a mesma coisa nos dois
caminhos. É por isso que FR-017 pode exigir desfecho na trilha para operação interrompida: o caminho
de limpeza é único.

---

## Reivindicação de escrita — `WriteClaim`

| Campo | Tipo |
|---|---|
| `caseId` | identificador do caso |
| `holder` | `sessionId` da detentora |
| `since` | instante |

**Regras**

- No máximo uma por caso, independente de quantas sessões somente-leitura existam sobre ele (FR-014).
- Por caso, não por servidor: sessões escrevendo em casos distintos coexistem (FR-029).
- O mecanismo de exclusão continua sendo o `access.lock` do `ConcurrencyGuard`. Duas sessões do mesmo
  processo abrem canais distintos sobre o mesmo arquivo e a segunda recebe
  `OverlappingFileLockException`, que o código **já** converte em `CONCURRENT_ACCESS`
  ([research.md R6](./research.md)). O registro em memória existe para **nomear** a detentora no
  diagnóstico, não para excluir.
- Liberada em `CLOSING`, normal ou anormal (FR-030). Nenhum caso fica bloqueado por sessão morta.
- A detecção de concorrência local de FR-028 de 001 vigora **por cima**: deter a reivindicação não
  dispensa o `probeBookmarksState`, e a UI do IPED segurando o estado de marcadores recusa a escrita
  de qualquer forma (FR-031).

---

## Caso compartilhado — `PooledCase`

| Campo | Tipo |
|---|---|
| `casePath` | caminho real do caso |
| `source` | `IPEDSource` aberto |
| `refCount` | número de sessões que o mantêm aberto |

**Regras**

- Uma instância por caminho de caso por processo. Abrir um caso já aberto incrementa a contagem e
  devolve o mesmo `IPEDSource` — duas sessões sobre um caso de 10 M itens não pagam duas vezes os 30 s
  de abertura nem a memória (SC-006).
- Fechado quando a contagem chega a zero.
- Leitura concorrente sobre o `IndexSearcher` do Lucene é segura, que é o que torna o
  compartilhamento possível.
- A reivindicação de escrita **não** é propriedade do caso compartilhado: vive na sessão. Um caso com
  três leitores e um escritor é uma `PooledCase` com `refCount = 4` e uma `WriteClaim`.

---

## Manifesto de sessões — `SessionManifest`

Arquivo append-only em `<caso>/mcp-audit/`, ao lado das trilhas que já são gravadas ali.

| Campo por entrada | Observação |
|---|---|
| `sessionId` | corresponde ao nome do arquivo `session-<uuid>.jsonl` |
| `startedAt`, `endedAt` | ordenação entre trilhas paralelas |
| `transport`, `origin` | FR-021 |
| `operatorAuthoritative`, `operatorClaimed` | FR-020, com a alegação rotulada |
| `recordCount` | permite detectar trilha truncada |

**Regras**

- Uma linha por sessão que tocou o caso. Append-only pelo mesmo motivo que a trilha é.
- Responde às duas perguntas que um examinador tem diante de N arquivos numa pasta: **são todos?** e
  **em que ordem?** (FR-033, SC-013).
- **Não reordena nem reescreve trilha alguma.** A ordem dos campos de `AuditRecord.toNodeWithoutHash`
  faz parte do hash de trilhas já emitidas; o manifesto se acomoda a ela.
- Degrada como a trilha degrada: caso em mídia não gravável mantém a cópia da estação como
  autoritativa (FR-073 de 001), e o manifesto acompanha esse destino.
