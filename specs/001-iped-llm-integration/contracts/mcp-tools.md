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

Abre também, somente para leitura, o repositório de previews do caso (FR-085): item decodificado de dentro de um contêiner não tem trecho de evidência que seja ele, e seus únicos bytes estão ali. Falhar nisso **não** impede a abertura — um caso sem previews segue consultável.

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

Libera recursos sem deixar trava pendente (FR-005) — inclusive o repositório de previews do caso, aberto na abertura porque item decodificado tem ali os únicos bytes dele (FR-085).

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
| `query` | string | **não** | com `bookmark`, o marcador inteiro (FR-081) |
| `bookmark` | string | não | sem filtro de marcador |
| `page_size` | inteiro | não | limitado por teto do servidor |
| `cursor` | string | não | primeira página |
| `timeout_ms` | inteiro | não | `queryTimeoutMs` |

`query` e `bookmark` não podem faltar os dois — sem nenhum dos dois não há o que procurar, e a recusa nomeia `*:*` como a forma barata de pedir tudo. **Para pedir todo item, escreva `*:*`, nunca `*` sozinho**: os dois significam o mesmo para quem escreve e não para o parser, e o servidor reconhece o segundo declarando o reparo em `query_normalized` (FR-082).

Retorna:

| Campo | Sempre presente | Observação |
|---|---|---|
| `total_matches` | **sim** | Total do conjunto, independente do que foi devolvido (FR-012). Vem da própria coleta — a página custa **uma** avaliação da consulta (FR-082) |
| `total_matches_exact` | **sim** | `false` quando o orçamento de tempo interrompeu a varredura: aí `total_matches` é **piso** |
| `items` | sim | `ItemView` já enriquecida, com `snippet` quando aplicável (FR-014, FR-015) |
| `bookmark` | não | Presente quando a busca foi restringida ao marcador informado |
| `query_note` | não | Presente quando a expressão foi suprida pelo servidor por vir só o marcador |
| `query_normalized` | não | Presente quando a expressão executada não é a pedida (escape de campo ou `*`) |
| `next_cursor` | não | Ausente na última página **e em página parcial** (FR-079) |
| `next_cursor_omitted` | não | O motivo, quando a página é parcial |
| `partial` | sim | `true` se houve esgotamento de tempo (FR-018) |

**Nunca** devolve o conjunto completo de uma consulta ampla (FR-013). Ordenação determinística (FR-019).

`timeout_ms` limita a **varredura**, não a montagem da consulta: expressão cuja expansão já é caríssima gasta tempo antes de o relógio ser consultado, então o parâmetro não é garantia de tempo de resposta.

Quando `bookmark` é informado, a busca retorna a interseção entre a expressão e os itens atualmente
associados ao marcador, sem materializar previamente sua lista de ids.

**Erros**: `QUERY_SYNTAX` com posição do problema (FR-017); `UNKNOWN_FIELD` com campos sugeridos
(FR-008); `BOOKMARK_NOT_FOUND` quando o nome do marcador não existe no caso.

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
| Parâmetro | Tipo | Obrigatório | Default |
|---|---|---|---|
| `case_id` | string | sim | |
| `item_ids` | lista de inteiro | sim | |
| `fields` | lista de string | não | propriedades essenciais |

Propriedades de um **lote**, em uma chamada, com teto de tamanho (FR-024). Existe para evitar N chamadas.

Sem `fields`, devolve as propriedades essenciais no formato plano de sempre. Com `fields`, devolve **só** os campos nomeados, em `items[].fields` com as chaves que o chamador pediu, mais `projection` e `projection_note` no topo — o que foi lido tem que ser legível na própria resposta. Nomes são os **planos** que `iped_list_fields`/`iped_item_fields` devolvem (a grafia de query, com colon escapado, também é aceita e vem declarada em `resolved_fields`), assim como as chaves que o servidor publica no item (`content_type`, `parent_id`, `bookmarks`, `selected`).

Nome que este caso não tem **recusa a chamada inteira** com `UNKNOWN_FIELD`, `details.similar` por nome rejeitado e `details.recognized_fields` — responder com itens sem o campo é indistinguível de itens que não o têm, e é assim que se produz um "nada encontrado" errado (FR-047). `content` é recusado com explicação própria: é indexado e não armazenado, então nenhuma projeção o devolve.

### `iped_item_metadata`
Metadados extraídos (EXIF, GPS, cabeçalhos, codec).

### `iped_item_text`
Texto extraído. Trunca com aviso e informa tamanho real (FR-021). Se não houver texto, declara ausência e aponta alternativas (FR-022).

Devolve `extracted_by` com o parser que efetivamente rodou — o fallback de strings cruas nunca falha, então "veio texto" sozinho não diz que o item foi compreendido. Quando o tipo do item não tem parser próprio (tipos que o processamento **atribui**, como os de chat decodificado), o tipo é detectado do conteúdo e a resposta traz `parsed_as` + `parsed_as_note` com os dois tipos: o do item é o que se cita (FR-083).

O motivo da ausência é **derivado do item** — registro decodificado, diretório, parsing expirado, media type — e nomeia os campos de metadado daquele item que carregam conteúdo, quando há (FR-084). Não enuncia hipóteses alternativas: para item decodificado elas eram todas falsas enquanto o conteúdo estava em `Message-Body`.

Falha do próprio servidor — recurso do caso que ele não abriu, inicialização que faltou — vem declarada como falha do servidor, não como ausência de texto no item, e não encaminha para `iped_item_content`, que alcança o item pela mesma tubulação (FR-086).

### `iped_export_item`
| Parâmetro | Tipo | Obrigatório | Default |
|---|---|---|---|
| `case_id` | string | sim | |
| `item_id` | inteiro | sim | |
| `text_only` | booleano | não | `false` |

Escreve **um item** como arquivo na pasta de exportação configurada, e devolve o caminho com os digests do que foi escrito (FR-087). **Não há destino como parâmetro**: o nome vem da evidência, e nome de material apreendido é a entrada em que menos se confia — o servidor o sanea e decide onde põe. O arquivo vai para `<exportRoot>/<case_id>/<item_id>-<nome>`, com pasta por caso porque id de item é local ao caso.

`text_only: false` (padrão) exporta os **bytes do próprio item** e confere o resultado contra o hash que o caso registrou (`hash_verified`, `hash_verified_against`, `hash_recorded_in_case`). `text_only: true` exporta o **texto extraído**, em UTF-8, pela mesma extração do `iped_item_text` — e diz que os digests não são comparáveis com o hash do caso, que é dos bytes.

Nada é truncado: os tetos do `iped_item_content` e do `iped_item_text` protegem a conversa, e arquivo em disco não é a conversa.

Item que não tem arquivo por trás — registro decodificado — exporta o preview que o IPED gerou, com `source_note` dizendo isso. Diretório e item de zero byte são declarados indisponíveis e **nada é escrito**: arquivo vazio com nome de item vira, depois, indistinguível de item realmente vazio.

A política de egresso é aplicada por chamada, na classe que os argumentos pedem (`binary` ou `text`), e não na ferramenta inteira.

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
