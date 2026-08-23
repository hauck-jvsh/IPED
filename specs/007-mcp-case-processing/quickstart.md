# Quickstart — validação da criação de caso pelo MCP

**Feature**: [007-mcp-case-processing](./spec.md) | **Date**: 2026-08-21

Roteiro de validação. Cada bloco declara o que prova e a qual critério de sucesso responde. Detalhes de
entidade estão em [data-model.md](./data-model.md); a superfície exposta, em [contracts/](./contracts/).

---

## Pré-requisitos

Os mesmos das suítes de caso já existentes no módulo, mais uma evidência de origem.

| Item | Por quê |
|---|---|
| Instalação de release (`-Diped.mcp.ipedRoot=<release>`) | A configuração do motor precisa ser carregada de uma instalação antes de abrir caso |
| JRE 11 do release (`-Djvm=<release>/jre/bin/java.exe`) | O task installer arrasta o FST, que reflete em interno do JDK; Java recusa a partir da 16 |
| Evidência pequena de referência | Um processamento que caiba em minutos |
| Evidência longa | Para atravessar queda de sessão, cancelamento e reinício |

As suítes que exigem evidência **pulam** quando ela não está configurada, e **um teste pulado não é um
teste que passou**. Quando a evidência está configurada mas a instalação ou o runtime não, o harness
**falha** em vez de pular — alguém pediu execução real.

```bash
mvn -pl iped-mcp test                       # sem evidência: unitários e de contrato
mvn -pl iped-mcp test -Diped.mcp.ipedRoot=<release> -Djvm=<release>/jre/bin/java.exe \
    -Diped.mcp.test.sourceEvidence=<evidência> -Diped.mcp.test.caseRoot=<raiz>
```

---

## Cenário 0 — Instalação padrão não processa

**Prova SC-012.** Com a configuração distribuída, sem edição:

1. Subir o servidor e listar a superfície de ferramentas.
2. **Esperado**: nenhuma ferramenta de processamento aparece.
3. Emitir um pedido de processamento à força.
4. **Esperado**: recusa `PROCESSING_DISABLED`, e **nada no sistema de arquivos foi lido ou escrito**.

O passo 4 é o que importa e o que um teste ingênuo esquece: a recusa precisa acontecer antes da leitura
de qualquer argumento (FR-002). Verificar só a ausência da ferramenta não prova isso.

---

## Cenário 1 — Do pedido ao caso consultável

**Prova SC-001, SC-002, SC-008, SC-015.** Com `processingEnabled = true`, uma área de leitura e uma
raiz de caso declaradas:

1. Registrar o hash da evidência de origem.
2. `iped_process_evidence` com origem dentro da área, destino dentro da raiz, perfil permitido.
3. **Esperado**: resposta em **menos de 5 s** com `job_id` — cronometrar, é SC-002 — e
   `paths_are_server_side: true`.
4. Acompanhar por `iped_job_status` até `COMPLETED`.
5. `iped_open_case` no destino.
6. **Esperado**: abre sem passo manual na máquina do servidor, e a contagem de itens **coincide** com
   `outcome.item_count`.
7. Recalcular o hash da evidência.
8. **Esperado**: idêntico ao passo 1.

O passo 7 é Princípio I medido, não presumido, e vale repetir ao fim de cada cenário seguinte — SC-015
exige a identidade em **todos** os desfechos, não só no sucesso.

---

## Cenário 2 — Confinamento

**Prova SC-003, SC-004.** Quatro pedidos que devem ser recusados:

| Pedido | Esperado |
|---|---|
| Origem fora de toda área declarada | `SOURCE_NOT_PERMITTED`, **nenhum byte da origem lido** |
| Origem cujo caminho textual está dentro da área mas resolve para fora (junção/link) | Mesma recusa, mesmo diagnóstico |
| Destino fora de toda raiz de caso | `DESTINATION_NOT_PERMITTED`, **nenhum arquivo e nenhuma pasta criados** |
| Destino que já contém caso concluído | `APPEND_NOT_SUPPORTED` — **não** `DESTINATION_HAS_CASE` |

O segundo é o cenário que comparação textual de prefixo deixa passar, e é por isso que a resolução é
sobre o caminho real. No Windows, montar a armadilha com **junção de diretório**: é o mecanismo que
`getCanonicalPath()` não atravessa e que a 006 mediu.

O quarto separa fronteira de escopo de destino ocupado (FR-040). Confundir os dois faz uma decisão
deliberada se ler como defeito.

**Verificar em todos**: a recusa consta da trilha com o pedido e a regra aplicada.

---

## Cenário 3 — Sobreviver à sessão

**Prova SC-005, SC-006.** Com a evidência longa:

1. Iniciar o processamento; anotar `job_id` e o avanço corrente.
2. **Encerrar o harness** — fechar a entrada padrão, que é como todo harness sai.
3. Aguardar tempo suficiente para o avanço mudar.
4. Abrir sessão nova e chamar `iped_job_status` com o mesmo `job_id`.
5. **Esperado**: o trabalho está vivo e **avançou** durante a ausência.

O passo 5 precisa comparar avanço, não só estado: um trabalho parado também responde `RUNNING`, e um
teste que só confira o estado passaria com o processamento congelado.

---

## Cenário 4 — Cancelamento

**Prova SC-014, e Princípio I.**

1. Iniciar o processamento da evidência longa; anotar o identificador de processo do filho **e os
   identificadores dos descendentes**.
2. `iped_cancel_job` de uma **sessão diferente** da que iniciou — FR-023 permite, e é o caso que a
   clarificação fixou.
3. **Esperado**: termina em menos de 60 s; estado `CANCELLED`; `cancelled_by` registrado com a
   identidade da segunda sessão.
4. **Verificar que nenhum processo da árvore sobreviveu.**

O passo 4 é o cerne. Um cancelamento que mate só o filho passa nos passos 1 a 3 e deixa o motor lendo
evidência — a falha que R6 identificou e que nenhum teste de requisição/resposta encontraria.

---

## Cenário 5 — Interrupção e retomada

**Prova SC-017, e FR-024.**

1. Iniciar o processamento da evidência longa.
2. **Matar o servidor abruptamente** (`kill -9` / encerramento forçado), não ordenado.
3. Subir o servidor de novo.
4. `iped_job_status` com o `job_id`.
5. **Esperado**: `INTERRUPTED` — nunca `RUNNING`, nunca `UNKNOWN_JOB`.
6. **Verificar que nenhum processo órfão sobreviveu** à reconciliação.
7. `iped_open_case` no destino: **esperado** recusa por `CASE_INCOMPLETE` ou `CASE_IN_PROCESSING`.
8. `iped_resume_job` com o mesmo `job_id`.
9. **Esperado**: prossegue, **sem reprocessar** o que já havia sido processado, mantendo o `job_id`.
10. Ao concluir, o caso abre normalmente.

O passo 2 precisa ser abrupto: o encerramento ordenado exercita o gancho de desligamento, que é o
caminho fácil. O difícil é o órfão, e é o que o passo 6 verifica.

O passo 9 mede a retomada por comparação de tempo ou de contagem contra um processamento do zero —
`COMPLETED` sozinho não prova que nada foi refeito.

---

## Cenário 6 — Falha diagnosticável

**Prova SC-018.** Provocar uma falha real — evidência corrompida, ou remover a mídia no meio:

1. **Esperado**: `FAILED` com `cause`, `resumable` e `diagnostic_excerpt`.
2. O `diagnostic_excerpt` permite identificar a causa **sem acessar a máquina do servidor**.
3. `log_path` aponta para um arquivo que existe e contém o log completo.
4. **Verificar que nenhum byte de log alcançou o canal do protocolo.**

O passo 4 é a invariante da 006 aplicada a uma fonte nova. A falha correspondente não é ruidosa: ela
corrompe a sessão e o sintoma parece defeito de protocolo do servidor.

Verificar também que o `diagnostic_excerpt` atravessa a política de egresso: com a política restritiva
ativa, ele é suprimido pela mesma fronteira que suprime texto de item, **não** por caminho próprio.

---

## Cenário 7 — Concorrência, disco e segredo

**Prova SC-013, SC-019, SC-011.**

| Passo | Esperado |
|---|---|
| Segundo pedido com um trabalho em andamento | `JOB_ALREADY_RUNNING`, identificando o trabalho em curso |
| Pedido cujo destino tem menos livre que `origem × percentual` | **Aceito**, com `disk_warning` trazendo os três números; advertência na trilha; **0% de recusas** por esse motivo |
| Origem segmentada em vários `.E01` | O mínimo é calculado sobre o **conjunto**; medir o primeiro segmento daria uma fração e a advertência não sairia (SC-021) |
| Processar contêiner cifrado com `secret_ref` | Sucesso; a senha **não aparece** na resposta, na trilha, no `processing.log` nem no pedido registrado; e o aceite **carrega a declaração** de FR-050 |

A última linha tem uma armadilha que vale nomear, porque ela se parece com um teste esquecido. A
senha **está** em `argv` enquanto o processo corre, e isso é decisão registrada em R5, não descuido:
fechá-la custaria alterar `iped-app` e a interface `CmdLineArgs` por um risco que só se realiza em
máquina de evidência com mais de uma conta.

Portanto o teste afirma os **quatro lugares** que FR-015 nomeia, e afirma a **presença da
declaração** — nunca ausência em `argv`. Um teste que afirmasse ausência ali estaria codificando o
contrário da decisão tomada, e reprovaria uma implementação correta.

---

## Cenário 8 — Postura e reconstituição

**Prova SC-016, SC-020, e FR-034.**

1. Consultar `iped_session_info`: postura declarada bate com o arquivo de configuração e com a
   disponibilidade real de cada área.
2. Abrir sessão com processamento habilitado: a advertência de abertura declara isso.
3. Depois de vários trabalhos, com pelo menos um atravessando fronteira de sessão: reconstituir, **só
   a partir dos registros**, quem pediu o quê, de onde, com qual perfil, sob qual postura, e com qual
   desfecho.
4. Reiniciar o servidor várias vezes; confirmar que registros antigos continuam recuperáveis pelo
   identificador.

O passo 3 é o que valida a divisão em três fontes de R3. Se a reconstituição exigir conhecimento fora
dos arquivos, a divisão está errada — e a divisão é imposta pela invariante de que `AuditRecord` não
pode ganhar campo, então corrigir seria mudar o desenho, não o teste.

---

## Última execução — 2026-08-23

Bancada: `RockPi4.E01`, 8,57 GB, segmento único; instalação `C:\iped\iped-mcp\iped-4.3.1` com JRE
11.0.13; raiz de caso em `H:\iped-cases`; 48 núcleos.

**273 testes, 0 falhas, 52 pulados** — os pulados são as suítes que exigem *caso* de referência
(`referenceCase`, `largeCase`), não evidência. Todas as suítes de processamento executaram.

| Medição | Resultado | Teto |
|---|---|---|
| Processamento completo, `fastmode`, 319.641 itens | **103 s** | — |
| Aceite do pedido (SC-002) | abaixo do teto, cronometrado no teste | 5.000 ms |
| Ponta a ponta com SHA-256 antes e depois (SC-001, SC-008, SC-015) | **181 s** | — |
| Cancelamento de árvore, das duas suítes (SC-014) | **6,4 s** para dois casos | 60.000 ms |
| Reconciliação de órfão, incluindo a guarda de reuso de PID | **0,14 s** | — |
| Retomada completa: interromper, reconciliar, continuar, validar | **242 s** | — |
| Consulta durante processamento (FR-025) | **62 s** para dois casos, ambos sob o orçamento | 15.000 ms |

A linha que mais importa é a última: o servidor responde enquanto o motor toma a máquina. É a
propriedade que justifica todo o arranjo fora do processo, e é barata de verificar — mas um servidor
que só responde depois que o processamento acaba é, para quem espera, indistinguível de um morto.

**O que não foi exercitado**: contêiner cifrado de verdade. A bancada não tem um, então SC-011 e
SC-025 são verificados com uma referência de segredo resolvida sobre evidência não cifrada — o que
prova que a senha não aparece nos quatro lugares e que o aviso sai, mas não que a decifragem
funciona. Fica registrado como lacuna de verificação, não como requisito não construído.

## Cobertura

| Critério | Cenário |
|---|---|
| SC-001, SC-002, SC-008, SC-015 | 1 |
| SC-003, SC-004 | 2 |
| SC-005, SC-006 | 3 |
| SC-014 | 4 |
| SC-009, SC-017 | 5 |
| SC-018 | 6 |
| SC-011, SC-013, SC-019 | 7 |
| SC-010, SC-016, SC-020 | 8 |
| SC-007 | 1, 3 — medido continuamente durante o acompanhamento |
| SC-012 | 0 |
