# Contract — CLI flag `--yara-only`

**Component**: `iped.app.bootstrap.Bootstrap` (entry point) → `iped.app.processing.Main` (delegate) → `iped.engine.core.Manager` (pipeline).

**Purpose**: implementar FR-011 (re-aplicar regras YARA-X sobre um caso já processado, sem reprocessamento total do pipeline-pesado, mas mantendo o pipeline normal de geração de `Document` para evitar round-trip de schema).

> **História da v1**: a primeira implementação tentou um caminho standalone (`YaraRerunRunner`) que bypassava o `Manager` e iterava o índice Lucene reconstruindo `IItem` via `IndexItem.getItem(...)`. Esse caminho colidiu na prática com (a) `NPE` em `Item.setName` para docs sem campo `BasicProps.NAME` (fragmentos de itens grandes); e (b) conflito de schema Lucene (`SORTED` vs `SORTED_SET` em metadados multi-valorados como `language:all_detected`) porque o ciclo `Document → IItem → Document` não é round-trip-safe. A v1 atual pivotou para o caminho do Manager descrito abaixo — preserva o schema porque o `Document` é gerado uma vez, pela mesma rota da primeira ingestão.

---

## Sintaxe

```text
iped --yara-only -d <DATASOURCE> -o <CASE_OUTPUT_DIR>
```

- `-d` aponta para a(s) evidência(s) **original(is)** do caso — IPED precisa re-abrir o stream binário de cada item para passar pela `YaraScanTask`. Sem o datasource não há como ler bytes para o scan.
- `-o` aponta para o **diretório do caso já processado** (o que contém o subdiretório `iped/`). A validação em `CmdLineArgsImpl` rejeita o flag se `<CASE_OUTPUT_DIR>/iped/` não existir.

| Flag | Permitida com `--yara-only`? | Notas |
|---|---|---|
| `-d` / `-data` | **Obrigatória** | Datasource(s) original(is); itens são re-lidos para que YARA escaneie bytes. |
| `-o` / `--output` | **Obrigatória** | Diretório do caso já processado (contendo `iped/`). |
| `-profile <name>` | Sim | Pode trocar o perfil (e portanto o catálogo de regras) para a reaplicação. |
| `-dname` | Sim | Mesmo significado de sempre, só uma observação cosmética. |
| `--Xmx <size>` / `--Xms <size>` | Sim | Mesmas regras de Bootstrap. |
| `--portable` | Sim | Mantém o flag para o caso portátil. |
| `--nogui` | Sim | Sem GUI; igual ao processamento normal. |
| `--continue` | **Não (redundante)** | `--yara-only` já implica `--continue` automaticamente. Erro claro se passado explicitamente, evitando confusão de intenção. |
| `--append` | **Não** | Semântica conflita: `--append` adiciona evidência nova como segundo caso; `--yara-only` refresca docs existentes. |
| `--restart` | **Não** | `--restart` apaga estado parcial; `--yara-only` precisa do índice existente. |
| `-remove` | **Não** | Remove evidência por nome; ortogonal a `--yara-only`. |

---

## Execution contract

1. **CLI parsing**: `Bootstrap` carrega a JVM filha; `CmdLineArgsImpl` reconhece `--yara-only` via JCommander e valida combinações em `handleSpecificArgs()`. Falhas saem com exit code 1.
2. **Implied --continue**: `CmdLineArgsImpl.isContinue()` passa a retornar `true` sempre que `yaraOnly` está ligado. `SkipCommitedTask.init()` carrega os `trackID`s já commitados do índice existente para que o passo de identificação de docs funcione no caso re-ingerido.
3. **Pre-check YARA enabled**: `Main.startManager()` valida `ConfigurationManager.findObject(YaraConfig.class).isEnabled() == true` antes de criar o `Manager`. Caso contrário, falha com `IPEDException` clara antes de tocar o índice — evita o cenário destrutivo onde `updateDocuments` apagaria `yara:*` ao reindexar com `YaraScanTask` desligada.
4. **Manager normal**: `Main.startManager()` instancia `new Manager(dataSource, output, keywords)` exatamente como em qualquer run. Nenhum bypass; nenhum classe `*RerunRunner` paralela.
5. **DataSourceReader**: lê os itens do `-d` novamente como em qualquer `--continue`. Itens cujo `trackID` já está no índice são identificados pelo `SkipCommitedTask`.
6. **SkipCommitedTask**: em modo `--yara-only`, marca itens commitados com o atributo temporário `IS_COMMITTED=true` **mas NÃO chama `setToIgnore(true)`**. O item segue para frente no pipeline (o caminho normal de `--continue` ignoraria esses itens).
7. **Pipeline completo**: todas as tasks habilitadas (`Signature`, `Hash`, `Parsing`, `YaraScanTask`, `IndexTask`, etc.) processam o item commitado também. `YaraScanTask` recalcula `yara:rule`/`yara:tag`/`yara:matches` com o catálogo atual.
8. **IndexTask (decisão add vs update)**: ao final do pipeline, `IndexTask.process(IItem)`:
   - Se `SkipCommitedTask.isAlreadyCommited(evidence) == true && cmdArgs.isYaraOnly() == true`:
     `worker.writer.updateDocuments(new Term(IndexItem.TRACK_ID, Util.getTrackID(evidence)), new DocumentsIterable(evidence, fragReader))`. O `updateDocuments(Term, Iterable<Documents>)` do Lucene apaga **todos** os docs com aquele `trackId` (parent + fragmentos de conteúdo, se houver) e adiciona o bloco novo, atomicamente.
   - Caso contrário: `worker.writer.addDocuments(...)` como sempre.
9. **Trade-off explícito**: o pipeline completo roda também para itens já commitados. Isso é mais lento que a abordagem standalone rejeitada, mas é o único caminho que preserva schema do índice (mesma rota de geração de `Document` da primeira ingestão), evitando o `IllegalArgumentException: cannot change field … doc values type` que afetou a v1. Tasks pesadas que o perito não queira re-rodar devem ser desabilitadas em `IPEDConfig.txt` antes do `--yara-only`.

---

## Exit codes

| Code | Significado |
|---|---|
| 0 | Run concluído com sucesso. |
| 1 | Erro de validação de flags (combinação inválida, datasource ausente, caso inexistente, `enableYara=false`, etc.) — `IPEDException`/`ParameterException` lançado em `CmdLineArgsImpl.handleSpecificArgs()` ou em `Main.startManager()`. |
| 1 | Falha no `Manager` (engine YARA-X indisponível, índice corrompido, etc.). Captura via o `catch (Throwable)` existente em `Main.startManager()`. |

---

## Backwards compatibility

- Comportamento default (sem `--yara-only`): inalterado.
- Casos processados em versões antigas (sem YARA) podem ser submetidos a `--yara-only` em versões novas — `yara:*` apenas **adiciona** campos ao schema; Lucene aceita.
- Casos processados em versões mais novas que apresentem novos campos podem reabrir em versões antigas; o IPED ignora os campos que não conhece (Princípio I).
