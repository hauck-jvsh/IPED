# Phase 1 — Guia de validação

**Feature**: 001-iped-llm-integration | **Data**: 2026-08-04

Cenários executáveis que provam a feature ponta a ponta. Cada um mapeia para critérios de sucesso do [spec.md](./spec.md). Detalhes de contrato em [contracts/mcp-tools.md](./contracts/mcp-tools.md); entidades em [data-model.md](./data-model.md).

Este é um guia de validação, não de implementação.

---

## Pré-requisitos

| Item | Como obter |
|---|---|
| JDK 11 com JavaFX | Liberica OpenJDK 11 Full; `JAVA_HOME` apontando para ele |
| Build do IPED | `mvn clean install` na raiz → `target/release/iped-4.3.1/` |
| **Caso de referência pequeno** | Conteúdo conhecido e **não sensível**, reconstruível a partir de script versionado |
| **Caso de referência grande** | ~10 M itens, para SC-002 e SC-015. Pode ser sintético |
| Harness | Ao menos um de: Claude Code, Codex, OpenCode |

> O caso de referência pequeno é pré-requisito de quase tudo. Sem ele, SC-001, SC-005, SC-006, SC-008 e SC-009 não são verificáveis de forma repetível. Construí-lo é tarefa da implementação, não pressuposto dela.

---

## Build e verificação estática

```bash
mvn -pl iped-mcp -am install     # módulo novo + dependências
mvn -pl iped-mcp test            # testes do módulo
```

---

## Cenário 1 — Handshake do protocolo *(contrato)*

**Prova**: R2 — a camada MCP escrita à mão fala o protocolo corretamente.

1. Iniciar o servidor com stdio ligado a um par de pipes.
2. Enviar `initialize`; conferir que a resposta declara a versão de protocolo suportada e a capacidade de ferramentas.
3. Enviar `tools/list`; conferir que **toda** ferramenta de [contracts/mcp-tools.md](./contracts/mcp-tools.md) aparece com esquema de entrada válido.
4. Enviar `tools/call` com ferramenta inexistente; conferir erro JSON-RPC bem formado, não exceção.

**Esperado**: handshake completo sem cliente real envolvido. Este cenário é o que protege contra regressão de protocolo, já que não há SDK cuidando disso.

---

## Cenário 2 — Abertura e panorama *(SC-013, SC-015)*

1. `iped_open_case` no caso de referência → sucesso, com `total_items` e `iped_version`.
2. Repetir a chamada → **sucesso de novo**, mesmo `case_id`. Idempotência (FR-004).
3. `iped_case_overview` → categorias e marcadores com contagem, em uma chamada.
4. Apontar para pasta que não é caso → erro `NOT_A_CASE` **com remédio**, não exceção.
5. Apontar para caso em processamento → `CASE_IN_PROCESSING`.
6. Repetir 1 e 3 no caso grande, cronometrando.

**Esperado**: passos 1–5 corretos; passo 6 com abertura + panorama < 30 s.

---

## Cenário 3 — Paginação e contagem *(SC-002, SC-007)*

Este é o cenário que separa a entrega da POC.

1. `iped_search` com consulta ampla (ex.: correspondendo a > 1 M itens no caso grande). Para "todo item", `*:*` — **nunca** `*` sozinho.
2. Conferir `total_matches` exato, `total_matches_exact: true` **e** `items` limitado a `page_size`.
3. Paginar com `next_cursor` até o fim; conferir que nenhum item se repete e nenhum falta.
4. Repetir a mesma consulta desde o início; conferir **página idêntica, na mesma ordem** (FR-019).
5. Cronometrar a primeira página.
6. Repetir a consulta ampla com `timeout_ms` pequeno o bastante para cortar a varredura: conferir `partial: true`, `total_matches_exact: false` com o total declarado como **piso**, e **nenhum** `next_cursor` — com `next_cursor_omitted` dizendo por quê (FR-079, FR-082).
7. Repetir com `query: "*"`: conferir mesmo total que `*:*` e `query_normalized` declarando o reparo.
8. `iped_search` com `bookmark` e **sem** `query`: conferir que lista o marcador inteiro e que o total bate com o do mesmo marcador mais `*:*` (FR-081).

**Esperado**: primeira página < 5 s no caso grande; nenhuma resposta excede o teto de volume, mesmo com milhões de correspondências; e **uma** avaliação da consulta por página — total exato pago por passada separada roda fora do orçamento de tempo e é o que fazia uma consulta ampla pendurar em vez de responder parcial.

Os passos 6 a 8 são cobertos por `integration/SearchTotalsTest`.

**Armadilha a vigiar**: se a implementação chamar `IPEDSearcher`, este cenário passa em caso pequeno e falha no grande — porque `searchAll()` materializa todo o conjunto. Executar contra o caso grande é obrigatório, não opcional.

---

## Cenário 4 — Autocorreção de vocabulário *(SC-006)*

1. `iped_search` com nome de campo inexistente naquele índice (ex.: `mediaType` onde o índice usa `contentType`).
2. Conferir erro `UNKNOWN_FIELD` com `details.similar` contendo o nome correto.
3. `iped_list_fields` → nomes reais.
4. `iped_check_field` com nome errado → `exists: false` + sugestões.
5. Refazer a consulta com o nome sugerido → resultados.

**Esperado**: o agente se corrige sem intervenção humana. Menos de 5% das consultas emitidas retornam zero por nome de campo inexistente.

---

## Cenário 4a — Projeção de campos escolhidos *(FR-080)*

1. `iped_search` para obter um lote de ids; `iped_item_fields` em um deles para descobrir um campo específico daquele caso.
2. `iped_get_items` com esse campo e `name` em `fields` → só esses campos, com as chaves pedidas, para todos os ids em **uma** chamada.
3. Conferir contra a resposta sem `fields` do mesmo lote: mesmos `item_id`, e tamanho número, timestamp instante ISO e flag booleana nas duas formas.
4. Repetir com um nome que o caso não tem → `UNKNOWN_FIELD` com `details.similar` e `details.recognized_fields`, **nenhum item devolvido**.
5. Pedir `content` → recusa explicada apontando `iped_item_text`.

**Esperado**: nenhum caminho devolve item com o campo silenciosamente ausente — a projeção não é via para afirmar ausência. Coberto por `integration/FieldProjectionTest`.

---

## Cenário 5 — Agregação *(SC-015)*

1. `iped_aggregate` por `category` no caso grande, cronometrando.
2. Conferir que a soma dos buckets bate com o total do caso.
3. Repetir com `query` restritiva; conferir coerência com `total_matches` de `iped_search`.

**Esperado**: < 15 s no caso grande, sem materializar itens.

---

## Cenário 6 — Somente-leitura é real *(SC-003)*

1. Registrar hash recursivo da pasta do caso, **excluindo a subpasta de auditoria por nome**.
2. Sessão completa em modo padrão: abrir, panorama, buscas, inspeção de itens, texto, miniatura, binário.
3. Tentar `iped_create_bookmark` → `WRITE_NOT_ENABLED`.
4. Fechar o caso; recalcular o hash com a mesma exclusão.

**Esperado**: hash **idêntico** — evidência, índice e estado de análise intactos. A subpasta de auditoria terá crescido, e isso é correto: é registro sobre o exame, não parte do acervo examinado (FR-072, SC-003).

**Verificação que não pode faltar**: confirmar também que **nenhuma escrita ocorreu fora** da subpasta excluída. A exclusão é estreita de propósito — se ela virasse uma licença geral para escrever no caso, o critério perderia o sentido.

---

## Cenário 7 — Escrita, confirmação e estado anterior *(SC-004)*

Com `accessMode = READ_WRITE`:

1. Criar marcador, associar itens, renomear, remover itens.
2. Abrir o caso na UI do IPED → marcador presente com exatamente os itens esperados (FR-030).
3. `iped_export_audit`; conferir que operações de escrita carregam `priorState`.
4. Abrir o caso na UI do IPED e, com ele aberto, tentar escrever → `CONCURRENT_ACCESS` (FR-028).

**Esperado**: 100% das operações na trilha; 100% das escritas com estado anterior.

---

## Cenário 8 — Auditoria: cadeia, durabilidade e falha *(SC-004, SC-005)*

1. Sessão com várias operações; exportar a trilha.
2. Conferir `seq` monotônico sem lacunas e cadeia de hash íntegra.
3. Alterar um registro no arquivo exportado e revalidar → **adulteração detectada**.
4. Tornar a área de auditoria não gravável; tentar qualquer operação → recusada **antes** de executar (FR-035).
5. **Matar o processo do servidor no meio de uma sessão**; reabrir a trilha → operações concluídas até ali estão presentes.
6. Encerrar uma sessão normalmente e conferir que a trilha apareceu na subpasta de auditoria **dentro do caso**, íntegra e encadeada, **sem nenhuma ação manual** (FR-072).
7. Abrir o caso a partir de mídia protegida contra escrita → a sessão funciona, a cópia da estação é autoritativa, e a advertência de não co-localização aparece na abertura (FR-073).
8. Apagar a subpasta de auditoria do caso deixando a trilha na estação; reabrir o caso → a sessão **reporta a trilha órfã** (FR-074).
9. Entregar a trilha a um segundo examinador; reproduzir a sequência de consultas.

**Esperado**: o passo 5 valida a durabilidade contra crash, e o 6 valida a durabilidade contra handoff — são falhas diferentes com mecanismos diferentes, e passar em um não implica passar no outro. O passo 8 é o que garante que uma perda seja **percebida**; sem ele, todos os demais apenas reduzem a probabilidade de perder sem nunca avisar quando acontece. O passo 9 chega ao mesmo conjunto de itens.

---

## Cenário 9 — Artefatos de saída *(SC-012)*

1. Marcador com 5.000 itens.
2. `iped_export_artifact` em `xlsx`, depois `csv`, depois `json`.
3. Conferir 5.000 registros completos e corretos em cada arquivo.
4. Conferir que a conversa recebeu apenas contagem, amostra e caminho.
5. Tentar exportar conjunto vazio → informa e **não** cria arquivo.
6. Tentar destino dentro da pasta do caso → recusado por padrão (FR-068).

---

## Cenário 10 — Os três harnesses *(SC-010, FR-062)*

Para **cada** um de Claude Code, Codex e OpenCode:

1. Máquina limpa, apenas com o IPED instalado.
2. Seguir o guia de instalação, cronometrando.
3. Abrir o caso de referência e fazer uma pergunta de investigação.
4. Conferir que a skill carregada é a mesma dos demais harnesses (FR-063).

**Esperado**: < 15 min do zero à primeira resposta, em cada harness. Orientação idêntica entre eles — orientação divergente produziria análises divergentes sobre a mesma evidência.

---

## Cenário 11 — Modelo local *(FR-065, D4)*

1. Configurar OpenCode com modelo local.
2. Executar a bateria de perguntas do Cenário 12.
3. Conferir que erros são autocorrigíveis pelo modelo — em especial `UNKNOWN_FIELD` com `details.similar`.

**Esperado**: funcional sem depender de capacidade exclusiva de modelo de fronteira. Este é o cenário que sustenta a decisão D3 na prática: com modelo local, conteúdo de evidência não sai da estação.

---

## Cenário 12 — Bateria de investigação *(SC-001, SC-008, SC-009)*

Sobre o caso de referência de conteúdo conhecido, 30 perguntas cobrindo: palavra-chave, faixa de datas, GPS, hash conhecido, tipo de arquivo, remetente de e-mail, tema em conversas, itens deletados, itens recuperados por carving, dados pessoais.

Para cada resposta, conferir:
- Itens citados **conferem** com o gabarito.
- Toda afirmação conclusiva vem com os itens que a sustentam (FR-046).
- Nenhuma extrapolação além do que os dados retornados sustentam (FR-048).

**Esperado**: ≥ 90% de acerto, **zero** falso positivo apresentado como conclusão, 100% das conclusões com itens citados.

---

## Cenário 13 — Diagnóstico *(SC-011)*

Provocar cada falha de configuração e conferir que o diagnóstico diz **o que fazer**:

| Falha provocada | Esperado |
|---|---|
| IPED não localizado | Aponta o que configurar |
| Caso inacessível | Distingue "não existe" de "sem permissão" |
| Caso de versão fora da faixa 4.x | Declara a faixa suportada |
| Área de auditoria não gravável | Explica que a operação foi recusada e por quê |
| Caso portátil com evidência ausente | Metadados consultáveis; conteúdo bruto declara indisponibilidade |

**Esperado**: 100% das falhas testadas resultam em diagnóstico acionável, nenhuma em erro técnico opaco.
