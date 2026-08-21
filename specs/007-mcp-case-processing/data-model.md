# Phase 1 — Data Model: criação de caso pelo servidor MCP

**Feature**: [007-mcp-case-processing](./spec.md) | **Date**: 2026-08-21

Entidades derivadas da seção Key Entities da spec, com os campos, as regras de validação que os
requisitos impõem e as transições de estado. Nada aqui é formato de rede — a superfície exposta está
em [contracts/](./contracts/).

---

## ProcessingRequest

O que o perito quer processar. Existe apenas até virar `ProcessingJob`; um pedido recusado nunca vira
trabalho e nunca toca o sistema de arquivos do destino.

| Campo | Tipo | Origem | Regra |
|---|---|---|---|
| `sourcePath` | caminho | pedido | MUST resolver dentro de área de leitura declarada, pelo caminho **real** (FR-006, FR-007) |
| `displayName` | texto | pedido | opcional; vira `-dname`. Ausente, o motor usa o nome do arquivo (FR-014) |
| `destinationPath` | caminho | pedido | MUST resolver dentro de raiz de caso declarada; MUST NOT conter caso concluído (FR-009, FR-010, FR-040) |
| `profile` | texto | pedido | MUST constar de `processingProfiles` (FR-013) |
| `secretRef` | texto | pedido | opcional; nome que o servidor resolve do lado dele. **Nunca a senha** (FR-015) |

**Validação é total e anterior.** FR-017 exige que tudo o que pode ser sabido antes seja verificado
antes: configuração, caminhos, perfil, existência do destino. Espaço em disco **não** entra aqui —
FR-044 o trata como advertência, nunca como recusa.

**O que o pedido não aceita.** FR-016 é uma regra de forma: não há campo de repasse livre. Um campo
desconhecido é recusa, não é ignorado. Sem isso, `-X` e opções de motor entrariam pela porta dos
fundos e o confinamento inteiro seria contornável por parâmetro.

---

## ProcessingJob

A execução de um pedido ao longo do tempo. É a entidade central e a única que sobrevive à sessão.

| Campo | Tipo | Notas |
|---|---|---|
| `jobId` | texto | estável, opaco, **não é segredo** — conhecê-lo permite acompanhar, não dá acesso ao caso |
| `state` | estado | ver máquina abaixo |
| `request` | `ProcessingRequest` | o pedido já validado, sem `secretRef` resolvido |
| `pid` | inteiro | identificador do processo filho |
| `processStart` | instante | de `ProcessHandle.Info.startInstant()`; desempata reaproveitamento de identificador (R7) |
| `acceptedAt` / `startedAt` / `endedAt` | instantes | `endedAt` ausente enquanto corre |
| `requestedBy` | texto | operador da sessão que pediu, na forma dupla de FR-032 de 006 |
| `cancelledBy` | texto | quem pediu o cancelamento — pode não ser quem iniciou (FR-023) |
| `progress` | `JobProgress` | ver abaixo |
| `outcome` | `JobOutcome` | ausente até terminar |
| `logPath` | caminho | `<auditoria>/jobs/<id>/processing.log` (FR-042) |
| `diskWarning` | texto | presente quando o livre na unidade de destino fica abaixo de `tamanho da origem × percentual declarado`; carrega os três números. Nunca impede o trabalho (FR-044). Ausente também quando o tamanho da origem não pôde ser medido no orçamento de aceite — indisponível, não zero (FR-046) |

### Máquina de estados

```
                  ┌──────────────┐
   pedido válido  │   ACCEPTED   │
   ─────────────► └──────┬───────┘
                         │ processo iniciado
                         ▼
                  ┌──────────────┐  cancelamento   ┌───────────┐
                  │   RUNNING    ├────────────────►│ CANCELLED │
                  └──┬────┬──────┘                 └───────────┘
        saída 0 ─────┘    └───── saída ≠ 0 ────►┌──────────┐
                 │                              │  FAILED  │
                 ▼                              └──────────┘
          ┌────────────┐        servidor reiniciado   ┌─────────────┐
          │ COMPLETED  │        com trabalho vivo ───►│ INTERRUPTED │
          └────────────┘                              └──────┬──────┘
                                                             │ retomada
                                                             ▼
                                                        (novo RUNNING,
                                                         mesmo jobId)
```

Regras que a máquina precisa respeitar:

- **`ACCEPTED` é curto e observável.** FR-018 exige resposta em menos de 5 s; o identificador é
  devolvido neste estado, antes de o processo existir.
- **Estados terminais são `COMPLETED`, `FAILED`, `CANCELLED`.** `INTERRUPTED` **não** é terminal: é o
  único de onde a retomada de FR-030 parte.
- **`INTERRUPTED` só é atribuído na volta do servidor**, pela reconciliação de R7 — nunca em tempo de
  execução, porque um servidor vivo que perde o filho o vê pelo código de saída, o que é `FAILED`.
- **A retomada mantém o `jobId`.** FR-034 exige reconstituir um trabalho; dois identificadores para a
  mesma evidência partiriam a história em duas.

### JobProgress

| Campo | Notas |
|---|---|
| `phase` | fase corrente declarada |
| `processedItems` / `discoveredItems` | do fluxo do filho (R2) |
| `percent` | ausente enquanto a descoberta não terminou — **ausente, não zero** |
| `estimatedCompletion` | ausente quando não determinável |
| `measurable` | booleano; falso declara que a fase corrente não produz medida |
| `lastObservedAt` | instante da última leitura |
| `stalled` | booleano; verdadeiro quando o silêncio do fluxo passa de `processingStallThresholdSeconds` (FR-047). **Sempre acompanhado de `phase`** — o mesmo silêncio é normal durante consolidação de índice e anômalo durante processamento de itens |

**A regra que governa este bloco inteiro** é a invariante do módulo: ausência ≠ vazio. FR-020 proíbe
estimativa inventada, e `measurable = false` é como isso é dito. Um percentual zero num campo que
deveria estar ausente é a forma mais fácil de mentir aqui, e SC-007 mede exatamente a alternativa:
informação nova, ou declaração de que a fase não produz medida.

### JobOutcome

| Campo | Presente quando |
|---|---|
| `casePath`, `itemCount`, `duration` | `COMPLETED` (FR-026). `itemCount = 0` é conclusão legítima, **nunca** falha (FR-048) |
| `cause`, `resumable` | `FAILED`, `INTERRUPTED` (FR-029). `cause` distingue `SOURCE_INACCESSIBLE` — mídia removida, compartilhamento caído, permissão perdida, com retomada possível — de `SOURCE_UNREADABLE`, que é problema da evidência e muda o que o perito faz em seguida (FR-049) |
| `diagnosticExcerpt` | `FAILED` — trecho limitado, **conteúdo derivado de evidência** sob a política de egresso (FR-043) |
| `remainingAtDestination` | `CANCELLED`, `FAILED` (FR-023) |
| `failedEvidences` | de `EvidenceStatus.getFailedEvidences()` (R4) |

`itemCount` MUST coincidir com o que o caso devolve ao ser aberto — é o que SC-008 verifica, e é a
única ligação entre o desfecho e a realidade do índice.

---

## DeclaredArea e CaseRoot

Duas listas de raízes, deliberadamente separadas (FR-009, R8).

| Entidade | Chave | Resolvida | Verdicts |
|---|---|---|---|
| `DeclaredArea` (leitura) | `processingSourceAreas` | **no momento do pedido** (FR-039) | `ALLOWED`, `OUTSIDE_ROOTS`, `UNRESOLVABLE`, `AREA_UNAVAILABLE` |
| `CaseRoot` (escrita de caso) | `processingCaseRoots` | no momento do pedido | `ALLOWED`, `OUTSIDE_ROOTS`, `INSIDE_CASE`, `UNRESOLVABLE` |

`AREA_UNAVAILABLE` existe por FR-039 e por uma razão prática: uma área declarada num volume que ainda
não foi montado não é o mesmo que uma origem proibida. Fundir os dois faria o perito procurar erro de
configuração quando só falta conectar um disco.

Ambas usam `PathConfinement.resolve` sem alteração — a classe já recebe as raízes por parâmetro (R8).

---

## SecretReference

| Campo | Notas |
|---|---|
| `name` | o que o pedido carrega |
| — | **não há campo de valor nesta entidade** |

A resolução acontece no `SecretResolver`, do lado do servidor, e o valor resultante vai para um arquivo
de permissão restrita passado ao motor por `-passwordFile` (R5). O valor não é campo de nenhuma
entidade persistida, não entra no `JobStore` e não entra na trilha. É a forma da 006 — a configuração
diz onde, nunca qual — aplicada um nível abaixo.

---

## ProcessingPosture

Projeção somente-leitura, montada sob demanda para FR-004 e registrada no `SessionManifest` por FR-038.

| Campo | Fonte |
|---|---|
| `enabled` | `processingEnabled` |
| `sourceAreas`, `caseRoots` | configuração, com disponibilidade corrente de cada uma |
| `allowedProfiles` | `processingProfiles` |
| `activeJob` | `jobId` e estado, ou ausente |

Registrar a postura na abertura de sessão é o que compensa a autorização por configuração: como a
concessão é anterior ao pedido, ela não deixaria rastro próprio, e sem esse registro não se saberia
depois sob qual autorização um trabalho foi aceito.

---

## Onde cada entidade é persistida

| Entidade | Local | Formato | Retenção |
|---|---|---|---|
| `ProcessingJob` | `<auditoria>/jobs/<id>/job.json` | JSON | indefinida (FR-045) |
| log do trabalho | `<auditoria>/jobs/<id>/processing.log` | texto, charset explícito | indefinida |
| `ProcessingRequest` | dentro de `job.json` | — | com o trabalho |
| postura e vínculo sessão↔trabalho | `SessionManifest` | linha por sessão | com o manifesto |
| registro de pedido e recusas | `AuditTrail` | `AuditRecord` **sem campo novo** | com a trilha |
| `SecretReference` resolvida | arquivo temporário de permissão restrita, apagado ao fim | — | não persiste |

A divisão entre `JobStore` e `AuditTrail` não é organização: é imposta pela invariante de que
`AuditRecord` não pode ganhar campo, sob pena de invalidar a verificação de trilhas já emitidas (R3).
