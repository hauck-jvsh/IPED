# Implementation Plan: Confinamento de escrita e transporte de rede para o servidor MCP

**Branch**: `006-export-allowlist-socket-transport` | **Date**: 2026-08-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-export-allowlist-socket-transport/spec.md`

## Summary

Duas entregas em sequência, com a primeira independente da segunda.

**US1 — confinamento de escrita.** A regra de destino de artefato deixa de ser lista de recusa e
passa a ser lista de permissão, sobre raízes declaradas em `conf/McpServerConfig.txt`. A verificação
migra de `java.io.File` para `java.nio.file.Path`, resolvendo o ancestral existente mais profundo com
`toRealPath()` antes de comparar — decisão forçada por medição, não por preferência: `getCanonicalPath()`
**não atravessa junções de diretório no Windows**, e uma junção dentro da raiz declarada escaparia da
verificação atual ([research.md R1](./research.md)). A ordem passa a ser resolver → verificar → criar,
para que uma recusa não deixe pastas atrás de si.

**US2 — transporte de rede.** `McpServerMain.start(InputStream, OutputStream)` já é agnóstico de
transporte, porque foi escrito assim para FR-064 de 001. Servir um socket é entregar os fluxos de uma
conexão ao mesmo método. O que muda em volta: autenticação por segredo compartilhado em handshake
anterior ao JSON-RPC, `Session` deixando de ser por processo e passando a ser por conexão, casos
compartilhados em pool com contagem de referências para não multiplicar `IPEDSource`, e um relay
stdio↔socket distribuído no mesmo jar para que a configuração dos três harnesses não mude.

**US3 — visibilidade.** Postura vigente consultável e registrada: transporte, ponto de escuta, raízes,
reivindicações de escrita, identidade dupla do operador.

Nenhuma dependência nova. Tudo em `java.base`.

## Technical Context

**Language/Version**: Java 11 (restrição de runtime, não só de compilação — o release embarca JRE 11)

**Primary Dependencies**: nenhuma nova. `java.net` e `java.nio.file` de `java.base`; Jackson, Lucene e
POI já presentes no módulo

**Storage**: sistema de arquivos — pasta do caso (`<caso>/mcp-audit/`), área de auditoria da estação,
raízes de escrita declaradas

**Testing**: JUnit 4 (`org.junit.Test`, `TemporaryFolder`), nas três suítes existentes do módulo:
`contract/`, `integration/`, `unit/`

**Target Platform**: Windows e Linux, com Windows como plataforma de referência para a verificação de
caminho — é onde os mecanismos de escape medidos em R1 e R2 existem

**Project Type**: módulo Maven de servidor, dentro de projeto multi-módulo

**Performance Goals**: manter as metas de 001 sob transporte de rede — primeira página < 5 s em 95% das
consultas sobre 10 M itens, abertura de caso < 30 s (SC-006)

**Constraints**: sem dependência fora de `java.base`; sem `System.out` em nenhum caminho de código, no
servidor **e no relay**, porque em ambos stdout é o canal do protocolo; sem alteração no formato de
trilhas já emitidas

**Scale/Scope**: casos de até 10 M itens; poucas sessões simultâneas — a ordem de grandeza é "dois
peritos", não "cem clientes"

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constituição do IPED — branch 4.3.1, versão 1.0.0.

| Princípio | Avaliação | Evidência |
|---|---|---|
| **I. Integridade da evidência é inviolável** | **PASSA — e reforça** | A feature existe para estreitar o que o servidor pode escrever. A pasta do caso continua recusada como destino mesmo dentro de raiz declarada (FR-004). Nenhum caminho novo toca evidência original. As escritas do servidor continuam confinadas à subpasta de auditoria, que SC-003 de 001 exclui por nome |
| **II. Caso processado é contrato permanente** | **PASSA** | Nenhum nome de campo Lucene, nenhuma configuração de `AppAnalyzer`, nenhum método de `iped-api`. O manifesto de sessões (R7) é arquivo novo dentro de `<caso>/mcp-audit/`, área já escrita pelo servidor. Formato de trilhas já emitidas permanece intocado — a ordem de campos de `AuditRecord.toNodeWithoutHash` faz parte do hash e o desenho se acomoda a ela |
| **III. Estender antes de modificar** | **PASSA com ressalva registrada** | O grosso é aditivo: `PathConfinement`, `SocketTransport`, `McpRelayMain`, `SessionManifest`, `CasePool` são classes novas. Três modificações são inerentes ao pedido e não contornáveis: `ExportTools.checkDestination` (é o requisito), `ArtifactWriter` (ordem de criação de pastas) e `Session` (por processo → por conexão). Nenhuma delas está na lista de componentes que o princípio protege — `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`, `SleuthkitClient`, Aho-Corasick permanecem intocados |
| **IV. Comportamento configurável vive em configuração** | **PASSA — princípio dirigente do desenho** | Raízes de escrita, ativação do transporte, ponto de escuta e segredo vão todos para `McpServerConfig` e `conf/McpServerConfig.txt`. Nenhum valor em constante de código além dos fallbacks de último recurso que a classe já documenta. Habilitar ou desabilitar o transporte é edição de configuração, nunca recompilação |
| **V. Nada implícito no que varia por ambiente** | **PASSA — e é o princípio mais exigido aqui** | Ponto de escuta declarado, nunca `0.0.0.0` por omissão (FR-012). Segredo declarado, sem valor padrão (FR-026). Charset explícito na linha de handshake. Logging por SLF4J: **o relay é caminho novo onde `System.out` corromperia o protocolo do lado do harness**, exatamente como já vale para o servidor |

**Restrições da plataforma**

- **Java 11 como runtime**: satisfeito por construção — `java.net` e `java.nio.file` estão em
  `java.base`. Foi essa restrição que descartou o transporte MCP Streamable HTTP sobre
  `com.sun.net.httpserver`, cujo módulo `jdk.httpserver` pode não estar no JRE embarcado
  ([research.md R3](./research.md)).
- **Módulo novo para capacidade de escopo próprio**: não se aplica. Isto é extensão do `iped-mcp`,
  consumindo suas próprias classes internas; um módulo separado dividiria `Session`, `AuditTrail` e
  `McpDispatcher` entre dois artefatos sem ganho de fronteira.
- **Ferramenta externa nova**: nenhuma. `ThirdParty.txt` não muda.

**Fluxo de desenvolvimento**: `mvn -pl iped-mcp -am install` e `mvn -pl iped-mcp test` antes de
qualquer commit de código; `iped-mcp/CLAUDE.md` atualizado — a seção de invariantes e a de limitações
conhecidas mudam materialmente (transporte, exclusividade de escrita, trilha por sessão).

**Resultado do portão: PASSA.** Nenhuma violação a justificar; a seção Complexity Tracking fica vazia.

### Re-avaliação após Phase 1

Reavaliado com `data-model.md`, `contracts/` e `quickstart.md` escritos. **Sem mudança de resultado.**
O desenho de Phase 1 não introduziu nenhum componente que toque os elementos protegidos pelos
Princípios I e II, e reforçou o Princípio IV: toda a superfície nova de comportamento aparece em
[contracts/config-surface.md](./contracts/config-surface.md) como chave de configuração, nenhuma como
constante.

## Project Structure

### Documentation (this feature)

```text
specs/006-export-allowlist-socket-transport/
├── plan.md              # Este arquivo
├── spec.md              # Requisitos
├── research.md          # Phase 0 — oito decisões, quatro medidas experimentalmente
├── data-model.md        # Phase 1 — entidades e transições
├── quickstart.md        # Phase 1 — roteiro de validação
├── contracts/           # Phase 1 — superfícies expostas
│   ├── config-surface.md      # chaves de conf/McpServerConfig.txt
│   ├── transport-handshake.md # handshake de autenticação, anterior ao JSON-RPC
│   └── tool-surface.md        # efeito nas ferramentas MCP existentes
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 — criado por /speckit-tasks, não por este comando
```

### Source Code (repository root)

```text
iped-mcp/src/main/java/iped/mcp/
├── McpServerMain.java              # MODIFICADO: aceita transporte, sessão por conexão
├── McpRelayMain.java               # NOVO: relay stdio↔socket, segunda classe main do mesmo jar
├── config/
│   └── McpServerConfig.java        # MODIFICADO: raízes de escrita, transporte, ponto de escuta, segredo
├── export/
│   ├── ArtifactWriter.java         # MODIFICADO: resolver → verificar → criar; verificação pós-escrita
│   └── PathConfinement.java        # NOVO: resolução real de caminho e contenção em raiz
├── tools/
│   └── ExportTools.java            # MODIFICADO: checkDestination passa a delegar ao PathConfinement
├── transport/                      # NOVO
│   ├── Transport.java              # abstração sobre par de fluxos
│   ├── StdioTransport.java         # o de hoje, extraído
│   ├── SocketTransport.java        # ServerSocket, aceite, uma Session por conexão
│   └── HandshakeCodec.java         # linha de autenticação e identidade alegada
├── session/
│   ├── Session.java                # MODIFICADO: por conexão; identidade dupla; transporte na descrição
│   ├── CasePool.java               # NOVO: IPEDSource compartilhado com contagem de referências
│   ├── WriteClaims.java            # NOVO: registro caseId → sessionId, para nomear a detentora
│   └── ConcurrencyGuard.java       # MODIFICADO: diagnóstico que distingue sessão de processo
└── audit/
    ├── AuditRecord.java            # MODIFICADO: identidade alegada, transporte, origem
    └── SessionManifest.java        # NOVO: manifesto append-only em <caso>/mcp-audit/

iped-mcp/src/main/resources/skill/
├── SKILL.md                        # MODIFICADO: destino de artefato é do lado do servidor
└── install/                        # MODIFICADO: topologia dividida nos três guias

iped-mcp/src/test/java/iped/mcp/
├── unit/
│   ├── PathConfinementTest.java    # NOVO: a bateria de SC-001, incluindo junção e dispositivo
│   └── HandshakeCodecTest.java     # NOVO
├── contract/
│   └── TransportParityTest.java    # NOVO: FR-015, mesma superfície nos dois transportes
└── integration/
    ├── SocketTransportTest.java    # NOVO: autenticação, queda de conexão, ponto de escuta ocupado
    ├── ConcurrentSessionsTest.java # NOVO: FR-014, FR-029, FR-030, FR-031
    └── AuditReconciliationTest.java# NOVO: FR-033, SC-013

iped-app/resources/config/conf/
└── McpServerConfig.txt             # MODIFICADO: chaves novas, todas desligadas por padrão
```

**Structure Decision**: o trabalho fica inteiramente dentro de `iped-mcp`, sem módulo novo. Duas
subpastas nascem — `transport/` e as classes novas de `session/` — seguindo a organização por
responsabilidade que o módulo já usa (`query/`, `item/`, `audit/`, `egress/`). `PathConfinement` fica
em `export/` por ser onde seu único consumidor vive hoje; se um segundo consumidor aparecer, sobe para
um pacote comum, e não antes.

A ordem de entrega segue a prioridade do spec: **US1 é entregável e implantável sozinha**, sem nenhum
arquivo de `transport/`. Isso não é acaso de planejamento — é o que o pedido do usuário determinou
("primeiro o Nível 0, depois o socket") e o que reduz risco: expor a superfície pela rede antes de
confinar o que ela escreve ampliaria o alcance de qualquer defeito de caminho.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Sem violações. O portão constitucional passa em todos os cinco princípios e nas três restrições de
plataforma, antes e depois do desenho de Phase 1.
