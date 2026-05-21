# Contract — CLI flag `--yara-only`

**Component**: `iped.app.bootstrap.Bootstrap` (entry point) → `iped.app.processing.Main` (delegate).

**Purpose**: implementar FR-011 (rerun YARA sobre caso processado, caso inteiro, sem reprocessar pipeline).

---

## Sintaxe

```text
iped --yara-only -o <CASE_OUTPUT_DIR>
```

ou (forma longa equivalente):

```text
iped --yara-only --output <CASE_OUTPUT_DIR>
```

> O `-o`/`--output` aponta para o **diretório do caso já processado** (o que contém o subdiretório `iped/`). No fluxo normal de processamento o `-o` define onde o caso será **criado**; em `--yara-only` ele indica o caso a ser **reaplicado**. A validação em `CmdLineArgsImpl` rejeita o flag se `<CASE_OUTPUT_DIR>/iped/` não existir.

Combinações com flags de processamento de caso (`-d`, `-dname`, `--append`, `--continue`, `--restart`, `-remove`) **DEVEM** ser rejeitadas com erro claro: rerun YARA não aceita nova evidência nem retoma processamento. As flags permitidas em conjunto são:

| Flag | Permitida com `--yara-only`? | Notas |
|---|---|---|
| `-o` / `--output` | **Obrigatória** | Diretório do caso já processado. |
| `-profile <name>` | Sim | Pode trocar o perfil (e portanto o catálogo de regras) para a reaplicação. |
| `--Xmx <size>` / `--Xms <size>` | Sim | Mesmas regras de Bootstrap. |
| `-d` / `-data` | **Não** | Erro: "--yara-only does not accept new evidence input". |
| `-dname` | **Não** | Erro: "--yara-only is incompatible with -dname". |
| `--append` | **Não** | Erro: "--yara-only is incompatible with --append". |
| `--continue` | **Não** | Erro: "--yara-only is incompatible with --continue". |
| `--restart` | **Não** | Erro: "--yara-only is incompatible with --restart". |
| `-remove` | **Não** | Erro: "--yara-only is incompatible with -remove". |
| `--portable` | Sim | Mantém o flag para o caso portátil. |
| `--nogui` | Sim | Sem GUI; igual ao processamento normal. |

---

## Execution contract

1. `Bootstrap` carrega a JVM filha normalmente; `CmdLineArgsImpl` (no processo filho) reconhece `--yara-only` via JCommander e valida combinações em `handleSpecificArgs()`.
2. `Main.startManager()` detecta `cmdLineParams.isYaraOnly()` e **bypassa o `Manager`** — instancia `iped.engine.task.yara.YaraRerunRunner(caseRoot, ConfigurationManager.get())` diretamente. Princípio II honrado: nenhuma linha de `Manager`/`Worker`/`ProcessingQueues` é alterada.
3. `YaraRerunRunner.run()`:
   - Valida `caseRoot/iped/index` existe.
   - Carrega `YaraConfig`; falha se `enableYara=false` ou catálogo vazio.
   - Compila o catálogo via `YaraEngine.compileSources()` (mesma engine do flow normal).
   - Abre `IndexWriter` direto sobre o `caseRoot/iped/index` em `OpenMode.APPEND`.
   - Constrói uma `IPEDSource(caseRoot, writer)` (writer compartilhado evita conflito de lock).
   - Itera por `LeafReaderContext` do `DirectoryReader.open(writer)` (Lucene NRT).
   - Para cada doc vivo: reconstrói o `IItem` via `IndexItem.getItem(doc, source, false)`.
   - Aplica o pipeline equivalente ao `YaraScanTask.process()` (gate `scanAllItems`, `maxFileSizeBytes`, scan via `YaraScanner`, persiste `yara:*` via `setExtraAttribute`).
   - Apenas itens que **tinham yara:* no doc antigo** OU **ganharam matches no run atual** chamam `IndexWriter.updateDocument(idTerm, newDoc)`. Itens sem yara antes e sem yara depois ficam intocados — economiza escrita no índice.
   - O `idTerm` é `new Term(IndexItem.ID, <ID-do-doc>)` — chave única por source. Substituição integral garante limpeza de matches "fantasma" de catálogos antigos.
4. Métricas no log final via `RerunStats`: `itemsScanned`, `itemsWithMatches`, `itemsUpdated`, `itemsSkipped` (= size + no-stream + error), `totalSeconds`.

---

## Exit codes

| Code | Significado |
|---|---|
| 0 | Rerun concluído com sucesso (mesmo que zero itens tenham casado). |
| 1 | Erro de validação de flags (combinação inválida, caso inexistente, etc.) — `IPEDException` lançado em `CmdLineArgsImpl.handleSpecificArgs()`. |
| 1 | `IPEDException` propagado pelo `YaraRerunRunner.run()`: engine YARA-X indisponível, catálogo vazio, `enableYara=false`, índice corrompido, lock contendido, etc. *(Main.startManager() captura via o catch existente de `Throwable` e seta `success=false`, resultando em `System.exit(1)`.)* |

---

## Backwards compatibility

- Comportamento default (sem `--yara-only`): inalterado.
- Ausência da flag em versões antigas do IPED: o caso permanece compatível; ao reabrir em versão nova, o índice continua válido (Princípio I).
- Casos processados em versões antigas (sem YARA) podem ser submetidos a `--yara-only` em versões novas, desde que o esquema do índice seja compatível (é — só **adicionamos** campos).
