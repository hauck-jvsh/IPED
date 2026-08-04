# Phase 1 — Contrato das ferramentas MCP

**Feature**: 001-iped-llm-integration | **Data**: 2026-08-04

Superfície que o servidor expõe ao agente. Transporte **stdio**, JSON-RPC 2.0, protocolo MCP com `initialize`, `tools/list` e `tools/call` (R2).

## Princípios do contrato

Quatro regras valem para **toda** ferramenta e não se repetem em cada uma:

1. **Referência a item sempre carrega o caso.** Nenhuma ferramenta aceita `item_id` isolado. IDs são locais ao caso e colidem entre casos (FR-003).
2. **Erro é acionável por si só.** Toda falha traz o que é preciso para corrigir — campo inexistente vem com campos próximos, erro de sintaxe vem com a posição. O agente pode ser um modelo local (FR-065); mensagens que exigem dedução não servem.
3. **Ausência ≠ vazio.** Recurso indisponível é declarado com motivo, nunca devolvido como string vazia (FR-022).
4. **Toda chamada é auditada** antes de executar, leitura inclusive (FR-032, FR-035).

Convenção de nomes: prefixo `iped_`, `snake_case`.

---

## Gestão de sessão e caso

### `iped_session_info`
Estado da sessão: operador, modo de acesso, política de egresso vigente, casos abertos.

Sem parâmetros. Serve à advertência de FR-043 e à consulta de política de FR-042.

### `iped_open_case`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_path` | string | sim |

Valida integridade e faixa de versão antes de aceitar (FR-001, FR-002, FR-054). **Idempotente** — reabrir caso já aberto devolve sucesso, não erro (FR-004).

Retorna `case_id`, `total_items`, `iped_version`, `evidences`.

**Erros**: `NOT_A_CASE`, `CASE_INCOMPLETE`, `CASE_IN_PROCESSING`, `VERSION_UNSUPPORTED` — cada um com diagnóstico do que fazer.

### `iped_case_overview`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_id` | string | sim |

Panorama em **uma** chamada (FR-006): total de itens, evidências, categorias com contagem, marcadores com contagem. É a primeira chamada após abrir, e existe para que o agente não precise de N consultas para se orientar.

Alvo: < 30 s em caso de 10 M (SC-015).

### `iped_close_case`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_id` | string | sim |

Libera recursos sem deixar trava pendente (FR-005).

---

## Vocabulário de campos

### `iped_list_fields`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_id` | string | sim |

Nomes de campos **efetivamente presentes** naquele índice (FR-007). Fonte da verdade — prevalece sobre qualquer documentação em caso de conflito.

### `iped_check_field`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_id` | string | sim |
| `field` | string | sim |

Retorna `exists` e, quando falso, `similar` — campos próximos por distância de edição (FR-008). É o que fecha o laço de autocorreção da US1 e sustenta SC-006.

### `iped_item_fields`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_id` | string | sim |
| `item_id` | inteiro | sim |

Todos os campos indexados de um item — descoberta de vocabulário por exemplo concreto (FR-009).

---

## Consulta

### `iped_search`
| Parâmetro | Tipo | Obrigatório | Default |
|---|---|---|---|
| `case_id` | string | sim | |
| `query` | string | sim | |
| `page_size` | inteiro | não | limitado por teto do servidor |
| `cursor` | string | não | primeira página |
| `timeout_ms` | inteiro | não | |

Retorna:

| Campo | Sempre presente | Observação |
|---|---|---|
| `total_matches` | **sim** | Contagem exata, independente do que foi devolvido (FR-012) |
| `items` | sim | `ItemView` já enriquecida, com `snippet` quando aplicável (FR-014, FR-015) |
| `next_cursor` | não | Ausente na última página |
| `partial` | sim | `true` se houve esgotamento de tempo (FR-018) |

**Nunca** devolve o conjunto completo de uma consulta ampla (FR-013). Ordenação determinística (FR-019).

**Erros**: `QUERY_SYNTAX` com posição do problema (FR-017); `UNKNOWN_FIELD` com campos sugeridos (FR-008).

### `iped_aggregate`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_id` | string | sim |
| `dimension` | `category` \| `contentType` \| `period` \| `evidence` \| `bookmark` | sim |
| `query` | string | não |

Contagens por dimensão **sem materializar itens** (FR-016). É como o agente responde "quantos por tipo?" sem inspecionar item algum, e como decide se deve refinar antes de listar.

Alvo: < 15 s em caso de 10 M (SC-015).

---

## Inspeção de item

### `iped_get_items`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_id` | string | sim |
| `item_ids` | lista de inteiro | sim |

Propriedades essenciais de um **lote**, em uma chamada, com teto de tamanho (FR-024). Existe para evitar N chamadas.

### `iped_item_metadata`
Metadados extraídos (EXIF, GPS, cabeçalhos, codec).

### `iped_item_text`
Texto extraído. Trunca com aviso e informa tamanho real (FR-021). Se não houver texto, declara ausência e aponta alternativas (FR-022).

### `iped_item_thumbnail`
Miniatura. Ausência declarada quando não existe.

### `iped_item_content`
| Parâmetro | Tipo | Obrigatório | Default |
|---|---|---|---|
| `case_id` | string | sim | |
| `item_id` | inteiro | sim | |
| `max_bytes` | inteiro | não | teto conservador |

Conteúdo bruto, com limite e sinalização de truncamento (FR-021). Sujeito à política de egresso quando ativa (FR-040).

### `iped_item_tree`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_id` | string | sim |
| `item_id` | inteiro | sim |

Contêiner pai e itens contidos, sem o agente montar consulta à mão (FR-023).

---

## Curadoria *(exige `accessMode = READ_WRITE`)*

Todas recusam com `WRITE_NOT_ENABLED` no modo padrão, **sem tocar o caso** (FR-025). Todas recusam com `CONCURRENT_ACCESS` quando o caso está aberto por outro processo local (FR-028).

| Ferramenta | Parâmetros | Requisito |
|---|---|---|
| `iped_list_bookmarks` | `case_id` | leitura |
| `iped_create_bookmark` | `case_id`, `name` | FR-026 |
| `iped_rename_bookmark` | `case_id`, `old_name`, `new_name` | FR-026; registra estado anterior (FR-033) |
| `iped_delete_bookmark` | `case_id`, `name` | FR-026; registra estado anterior (FR-033) |
| `iped_add_to_bookmark` | `case_id`, `name`, `item_ids` | FR-026 |
| `iped_remove_from_bookmark` | `case_id`, `name`, `item_ids` | FR-026 |
| `iped_get_selection` | `case_id` | leitura |
| `iped_set_selection` | `case_id`, `item_ids`, `selected` | FR-027 |

---

## Artefatos de saída

### `iped_export_artifact`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `case_id` | string | sim |
| `source` | `{bookmark}` \| `{query}` \| `{item_ids}` | sim |
| `format` | `xlsx` \| `csv` \| `json` | sim |
| `destination` | caminho | sim |

Conjunto **completo**, sem truncamento; a conversa recebe apenas contagem, amostra e caminho (FR-067). Destino não pode ser a pasta do caso por padrão (FR-068). Conjunto vazio informa e não gera arquivo (FR-070).

---

## Auditoria

### `iped_export_audit`
| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `destination` | caminho | sim |

Exporta uma cópia da trilha da sessão em JSON Lines, para destino escolhido pelo perito (FR-036).

**Isto não é o mecanismo de durabilidade.** A trilha é gravada na área da estação a cada operação e sincronizada **automaticamente** para dentro da pasta do caso (FR-071, FR-072). Esta ferramenta serve a entregas avulsas — anexar a trilha a um laudo, mandar para um segundo examinador — e não a preservação, que já acontece sem intervenção.

**Não existe ferramenta de escrita na trilha.** A trilha é somente-acréscimo pelo próprio servidor e não é alterável pelo agente (FR-034).

`iped_session_info` reporta o estado de sincronização (`STAGED` / `SYNCED`), a advertência de caso não gravável (FR-073) e qualquer trilha órfã detectada na abertura (FR-074).

---

## Erros — forma comum

```
{ "code": "<CÓDIGO>", "message": "<o que aconteceu>", "remedy": "<o que fazer>", "details": { ... } }
```

`remedy` não é ornamento: é o que permite a um modelo local se corrigir sozinho (FR-065). `UNKNOWN_FIELD` traz `details.similar`; `QUERY_SYNTAX` traz `details.position`; `WRITE_NOT_ENABLED` explica que a habilitação é externa ao agente.
