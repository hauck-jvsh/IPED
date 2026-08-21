# Contract — ciclo de vida do trabalho

**Feature**: [007-mcp-case-processing](../spec.md)

O contrato temporal: o que acontece entre o aceite e o desfecho, quem observa o quê, e como o servidor
decide o estado de um trabalho ao voltar de um reinício. É a parte da feature sem precedente no
módulo — toda ferramenta existente é atômica e responde em segundos.

---

## Sequência normal

```
sessão            servidor                    processo filho (iped.jar)
  │                  │                                  │
  ├─ process_evidence┤                                  │
  │                  ├─ valida tudo (FR-017)            │
  │                  ├─ AuditTrail STARTED (FR-032)     │   ← antes de qualquer leitura
  │                  ├─ preflight de disco (FR-044)     │
  │                  ├─ JobStore: ACCEPTED              │
  │                  ├──────── spawn ──────────────────►│
  │◄─ job_id ────────┤                                  │  ← < 5 s (SC-002)
  │                  ├─ JobStore: RUNNING               │
  │                  │◄──── fluxo padrão (tubo) ────────┤
  │                  ├─ progresso + processing.log      │
  ├─ job_status ────►│                                  │
  │◄─ progress ──────┤                                  │
  │                  │                                  │
  ╳ sessão termina   │                                  │  ← trabalho continua (FR-021)
                     │◄──── código de saída ────────────┤
                     ├─ JobStore: COMPLETED / FAILED    │  ← registrado mesmo sem sessão (FR-033)
```

**A ordem das três primeiras operações do servidor não é negociável.** Validar, registrar, então agir.
O registro precede a ação por invariante já vigente do módulo; se o `STARTED` não puder ser gravado, o
trabalho não inicia. O preflight de disco vem **depois** do registro e **não** é portão: ele adverte
(FR-044).

---

## Observação do progresso

O fluxo padrão do filho é a única fonte disponível de fora do processo (R2), e serve a três
requisitos ao mesmo tempo:

| Consumo | Requisito |
|---|---|
| números extraídos → `JobProgress` | FR-020 |
| bytes gravados → `<auditoria>/jobs/<id>/processing.log` | FR-042 |
| fim do arquivo → `diagnostic_excerpt` na falha | FR-043 |

**São duas fontes no mesmo fluxo, e cada uma tem sua regra:**

| Fonte | Regra | Por quê |
|---|---|---|
| **Contadores** (processados/total, percentual, taxa) | Ancorar nas **formas numéricas** — `n/m`, `(p%)`, `GB/h` | `ProgressConsole.update()` concatena números em torno de prefixos localizados; os números sobrevivem à troca de locale, as palavras não |
| **Fase corrente** | Ler do texto da mensagem, sob **locale declarado** (`processingLocale`) | Os eventos `"mensagem"` são registrados verbatim, em prosa localizada, **sem nenhuma âncora numérica**. Não há como lê-los senão pelas palavras |

**O locale declarado não é salvaguarda secundária: é o que torna a fase legível.** Um analisador
ancorado só em números lê os contadores e nunca nomeia a fase — metade de FR-020 ficaria por cumprir,
e o teste que só conferisse contadores passaria.

E a fase é justamente o que responde quando os contadores calam. `Manager` dispara `"update"` a cada
segundo durante o processamento de itens; antes e depois desse laço — descoberta, commit, otimização,
minutos num caso grande — **não sai número nenhum**. É a janela em que "está vivo?" é a pergunta, e
só a fase a responde.

**O fluxo capturado nunca é repassado.** O tubo é do servidor; escrevê-lo na saída padrão do servidor
corromperia o protocolo. É a mesma invariante que a 006 fixou, aplicada a uma fonte nova.

---

## Cancelamento

```
cancel_job → destruir descendentes → destruir filho → esperar → forçar se preciso → CANCELLED
```

`ProcessHandle.descendants()` **antes** do filho. `Bootstrap` monta o classpath e gera outra JVM; quem
lê evidência é o neto. Um cancelamento que mate só o filho deixa o motor lendo evidência e escrevendo
no destino depois de o servidor ter declarado o trabalho encerrado — defeito de Princípio I, não
apenas de tempo de resposta.

SC-014 mede os 60 s. A verificação que importa igualmente é que **a árvore inteira morreu**.

Qualquer sessão autorizada cancela (FR-023); `cancelled_by` é registrado.

---

## Encerramento do servidor

**Ordenado**: gancho de desligamento destrói a árvore e grava `INTERRUPTED`. É FR-024 cumprido no
caminho normal.

**Abrupto** (queda de energia, `kill -9`): o filho sobrevive à morte do pai nos dois sistemas
operacionais. O `JobStore` fica com `RUNNING` gravado, e é isso que a reconciliação resolve.

---

## Reconciliação na volta

Ao iniciar, para cada trabalho em `RUNNING` no `JobStore`:

```
ProcessHandle.of(pid)
  │
  ├─ ausente ──────────────────────────────► INTERRUPTED
  │
  └─ presente
       └─ info().startInstant() == processStart ?
            ├─ não ─► outro processo reaproveitou o identificador ─► INTERRUPTED
            └─ sim ─► órfão nosso ─► destruir a árvore ─► INTERRUPTED
```

**Por que comparar o instante de início.** O sistema operacional reaproveita identificadores de
processo. Só o número não prova identidade, e destruir um processo alheio porque ele ocupou o número
seria pior do que o defeito que a reconciliação corrige.

**Por que destruir o órfão em vez de adotá-lo.** FR-024 diz que o trabalho termina com o servidor.
Adotar contradiria o requisito; deixá-lo correr deixaria evidência sendo lida sem nenhum observador —
o mesmo defeito do cancelamento parcial. Adoção fica registrada como evolução prevista, com o gatilho
declarado na spec.

**O resultado é sempre determinável.** FR-024 proíbe as duas respostas erradas: "ainda em andamento"
e "inexistente". Os três ramos levam a `INTERRUPTED`, que é estado não terminal e ponto de partida da
retomada.

---

## Retomada

`INTERRUPTED` → novo processo com `--continue` → `RUNNING`, **mesmo `job_id`**.

O motor aproveita o que consolidou; `EvidenceStatus` (`iped/data/evidences_processing_status`) guarda
o mapa evidência→sucesso e distingue "nunca processado" (`null`) de "processado sem falhas" (lista
vazia). SC-017 exige que a retomada não reprocesse o que já foi processado, e é esse estado do motor
que a sustenta.

Manter o identificador é exigência de FR-034: dois identificadores para a mesma evidência partiriam a
história que a trilha precisa reconstituir.

---

## Reconstituição

Nenhuma das três fontes basta sozinha, e a divisão é imposta, não escolhida — `AuditRecord` **não
pode ganhar campo**, sob pena de invalidar a verificação de trilhas já emitidas (R3).

| Pergunta | Fonte |
|---|---|
| Quem pediu, quando, com quais parâmetros | `AuditTrail` — `STARTED` da chamada |
| Sob qual postura a autorização valia | `SessionManifest` (FR-038) |
| O que aconteceu depois, inclusive após o fim da sessão | `JobStore` (FR-033) |
| Qual sessão originou qual trabalho | `SessionManifest`, vínculo sessão↔trabalho |

FR-034 é satisfeito pela junção das três, e é essa junção que o roteiro de
[quickstart.md](../quickstart.md) exercita — não cada arquivo isolado.
