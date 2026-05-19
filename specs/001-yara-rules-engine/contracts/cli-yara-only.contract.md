# Contract — CLI flag `--yara-only`

**Component**: `iped.app.bootstrap.Bootstrap` (entry point) → `iped.app.processing.Main` (delegate).

**Purpose**: implementar FR-011 (rerun YARA sobre caso processado, caso inteiro, sem reprocessar pipeline).

---

## Sintaxe

```text
iped --yara-only -d <CASE_OUTPUT_DIR>
```

ou (forma longa equivalente):

```text
iped --yara-only --output <CASE_OUTPUT_DIR>
```

Combinações com flags de processamento de caso (`-i`, `-dname`, `-profile`) **DEVEM** ser rejeitadas com erro claro: rerun YARA não aceita nova evidência. As únicas flags permitidas em conjunto são:

| Flag | Permitida com `--yara-only`? | Notas |
|---|---|---|
| `-d` / `--output` | **Obrigatória** | Diretório do caso já processado. |
| `--profile <name>` | Sim | Pode trocar o perfil (e portanto o catálogo de regras) para a reaplicação. |
| `--Xmx <size>` / `--Xms <size>` | Sim | Mesmas regras de Bootstrap. |
| `-i` / `-evidence` | **Não** | Erro: "--yara-only does not accept new evidence input". |
| `-dname` | Não | Erro: "--yara-only does not accept new evidence input". |
| `--portable` | Sim | Atualiza o caso portátil também. |
| `--append` | **Não** | Erro: rerun substitui matches, não anexa. |

---

## Execution contract

1. `Bootstrap` reconhece a flag, valida combinação, propaga via `CmdLineArgs.setYaraOnlyMode(true)`.
2. JVM filha (`processing.Main`) recebe a flag; instancia o `Manager` em **modo de reaplicação**:
   - Pula `DataSourceReader` (não ingere nova evidência).
   - Abre o índice Lucene existente em **leitura-escrita**.
   - Itera sobre o índice (em ordem do reader, paralelizada entre workers via `ProcessingQueues` adaptada para queue de doc IDs).
   - Para cada `IItem` reconstruído (via `IndexItem.getItem(doc)`): executa **apenas** `YaraScanTask.process(item)`.
   - Atualiza o documento Lucene com `IndexWriter.updateDocument(idTerm, newDoc)`, **substituindo integralmente** os campos `yara:*`.
3. Métricas adicionais no log final: `yara.rerun.itemsProcessed`, `yara.rerun.itemsSkipped`, `yara.rerun.totalSeconds`.

---

## Exit codes

| Code | Significado |
|---|---|
| 0 | Rerun concluído com sucesso (mesmo que zero itens tenham casado). |
| 1 | Erro de validação de flags (combinação inválida, caso inexistente). |
| 2 | YARA engine indisponível (libyara ausente) ou catálogo vazio. Log explica. |
| 3 | Erro fatal de I/O no índice Lucene (e.g., lock contendido). |
| ≥ 10 | Erros propagados do `Manager`. |

---

## Backwards compatibility

- Comportamento default (sem `--yara-only`): inalterado.
- Ausência da flag em versões antigas do IPED: o caso permanece compatível; ao reabrir em versão nova, o índice continua válido (Princípio I).
- Casos processados em versões antigas (sem YARA) podem ser submetidos a `--yara-only` em versões novas, desde que o esquema do índice seja compatível (é — só **adicionamos** campos).
