# Quickstart — YARA Rules Engine

**Audience**: peritos forenses e desenvolvedores integrando a feature em um caso de teste.

**Pré-requisitos**: release do IPED construído contendo `tools/yara-x/<os>/` (gerado por `mvn clean install`). Ver [iped-app/CLAUDE.md](../../iped-app/CLAUDE.md) para o layout do release.

---

## 1. Habilitar a feature

Edite (no diretório do release ou no checkout-source):

`resources/config/IPEDConfig.txt` — adicionar/editar:

```properties
enableYara = true
```

Validar que `resources/config/conf/YaraConfig.txt` existe (criado pelo build). Schema completo: [contracts/YaraConfig.txt.contract.md](contracts/YaraConfig.txt.contract.md).

---

## 2. Apontar para um catálogo de regras

Edite `resources/config/conf/YaraConfig.txt`:

```properties
# Onde estão seus .yar / .yara (YARA-X 1.x — pré-compilados fora de escopo na v1)
ruleDirectories = ${IPED_HOME}/yara-rules

# Limites operacionais (defaults razoáveis)
maxFileSizeBytes = 250M
perItemTimeoutMs = 30000

# Comportamento
scanAllItems = false
matchHexMaxBytes = 256
```

Coloque seus arquivos `.yar`/`.yara` em `${IPED_HOME}/yara-rules/`. Recomendado começar com uma regra sintética simples para validar:

```yara
// arquivo: hello.yar
rule hello_world : demo
{
    meta:
        author = "QA"
        severity = "info"
    strings:
        $s1 = "hello world" ascii
    condition:
        $s1
}
```

---

## 3. Processar um caso pequeno

```powershell
# Windows
.\iped.exe -d C:\cases\case-yara-demo -i .\test-evidence
```

```bash
# Linux
./iped -d /cases/case-yara-demo -i ./test-evidence
```

Durante o processamento, o log deve conter linhas como:

```text
INFO  YaraScanTask - Loaded ruleset: 23 rules from 4 files in 312 ms
INFO  YaraScanTask - Engine version: yara-x-1.16.0
INFO  YaraScanTask - Initialized scanner per worker (×8)
...
INFO  YaraScanTask - Scan summary:
  itemsScanned   : 41203
  itemsSkipped   : 18 (size>250M=12, timeout=4, no-stream=2)
  matchesTotal   : 297
  topRules:
    apt28/loader_dropper            : 84
    formbook/packer_v3              : 51
    hello/hello_world               : 162
```

---

## 4. Visualizar os matches na UI

```powershell
.\iped.exe -d C:\cases\case-yara-demo
```

1. **Painel de filtros** → seção **YARA** lista cada regra casada com contagem (FR-008).
2. Clicar em uma regra filtra a galeria/tabela (FR-008).
3. Selecionar itens → **Criar bookmark** → exportar (FR-009).
4. Abrir um item casado → painel de detalhes mostra `yara:rule`, `yara:tag` e — na aba de propriedades estendidas — a estrutura completa `yara:matches` (offsets, hex).

---

## 5. Verificar o relatório HTML

Gere o relatório (mesmo fluxo de sempre — `enableHtmlReport=true` em `IPEDConfig.txt`). Cada item com matches recebe uma seção "YARA matches" listando regra, tags, offsets e hex truncado (FR-010).

---

## 6. Re-rodar YARA sobre o caso (sem ingerir nova evidência)

Atualize `yara-rules/` com novas regras. Depois:

```powershell
.\iped.exe --yara-only -d D:\evidence\image.E01 -o C:\cases\case-yara-demo
```

Comportamento (FR-011):
- `--yara-only` requer **tanto `-d` (datasource original) quanto `-o` (caso já processado)** — IPED precisa re-abrir o stream binário de cada item para a `YaraScanTask` poder escanear.
- Implica `--continue` automaticamente: `SkipCommitedTask` carrega os `trackID`s já indexados, mas em modo `--yara-only` esses itens **continuam fluindo** pelo pipeline (não são marcados como `toIgnore`).
- O pipeline completo roda também para itens commitados (necessário para que `IndexItem.Document(...)` gere docs com o mesmo schema da primeira ingestão; veja "histórico da v1" no [contracts/cli-yara-only.contract.md](contracts/cli-yara-only.contract.md)).
- `IndexTask` detecta itens commitados em modo `--yara-only` e usa `IndexWriter.updateDocuments(Term(trackId), docs)` em vez de `addDocuments(...)` — apaga atomicamente todos os docs com aquele `trackId` (parent + fragmentos) e adiciona o bloco novo.
- O resultado é que `yara:rule`/`yara:tag`/`yara:matches` refletem exclusivamente o catálogo atual (sem mescla com runs anteriores).
- Log final é o resumo padrão do `Manager` (itens processados, indexados, etc.) + o `YaraScanTask scan summary:` rodando exatamente como num run normal.

**Tasks pesadas que o perito não queira re-rodar** (OCR, NER, transcrição, IA) devem ser desabilitadas em `IPEDConfig.txt` antes do `--yara-only`. Isso é manual: `--yara-only` honra o `enableX = ...` do config atual.

Combinações inválidas (`--append`, `--restart`, `-remove`, ou `--continue` explícito) são rejeitadas com exit code 1.

> ⚠️ **Feche o SearchApp (`iped` UI / `IPED-SearchApp.exe`) antes de rodar `--yara-only` no mesmo caso.** O pipeline grava no `<caso>/iped/storage/storage-*.db` (SQLite) via `ExportFileTask`. Se a UI estiver aberta, ela mantém conexões SHARED de leitura nesses arquivos. O `con.commit()` final do `ExportFileTask.finish()` precisa de lock EXCLUSIVE e fica em busy-wait nativo (`NativeDB.step` RUNNABLE) até a UI fechar — pode parecer hang indefinido. Diagnóstico: `jps -v` mostra um `AppMain ... -Dlog4j.configurationFile=file:/<caso>/iped/conf/...` rodando junto com o `Main` do `--yara-only`; thread dump (`jcmd <pid> Thread.print`) mostra Worker-0 em `ExportFileTask.finish:888 → SQLiteConnection.commit → NativeDB.step`. Resolução: fechar a janela do SearchApp libera os locks; o commit conclui em segundos e o `--yara-only` finaliza limpo (sem reprocessar nada). Pre-check automático fica como melhoria futura.

Contrato completo: [contracts/cli-yara-only.contract.md](contracts/cli-yara-only.contract.md).

---

## 7. Verificação de paridade com a CLI YARA-X (SC-004)

Para validar a integração contra a referência oficial:

```bash
# 1) Rode a CLI oficial yara-x sobre uma amostra
yara-x scan ./yara-rules ./samples/ > yara-x-cli.out

# 2) Exporte os matches do IPED como CSV (Tools → Export → matches)
#    OU rode o helper de teste IT-YaraVsCli (em iped-engine/src/test/...)
mvn -pl iped-engine -Dtest=IT_YaraVsCli verify

# 3) Compare
diff yara-x-cli.normalized yara-iped.normalized
```

Critério de sucesso: zero diferenças por (`item`, `rule`, `offset`).

---

## 8. Troubleshooting rápido

| Sintoma | Diagnóstico | Ação |
|---|---|---|
| Log: `WARN YaraScanTask - libyara-x-capi not loadable, task disabled` | Engine nativa ausente/incompatível | Verifique `tools/yara-x/<os>/`. Em Linux: `ldd tools/yara-x/linux64/libyara_x_capi.so`. |
| Log: `WARN YaraScanTask - ruleDirectories empty, task disabled` | Catálogo vazio | Confira `YaraConfig.ruleDirectories`. |
| Log: `WARN YaraEngine - rule "<name>" failed to compile: unknown module "cuckoo"` | Regra usa módulo excluído | Esperado (R-09). Regra é descartada; demais continuam. |
| Item nunca aparece nos matches | Pode ter sido pulado por tamanho/timeout | Consulte o resumo de scan no log final ou aumente `maxFileSizeBytes`. |
| UI não mostra a seção YARA | Caso processado sem `enableYara=true` | Reabrir caso após processar com a feature ligada ou rode `--yara-only`. |
| Performance pior que o budget (>15%) | Catálogo muito grande / regras com regex pesado | Reduza o catálogo, divida regras complexas ou reduza `maxFileSizeBytes`. |

---

## 9. Benchmark sugerido (SC-001 / SC-006)

Documento de referência para validar performance — não automatizado no CI por custo.

**Setup**:
- Hardware: 16 cores, 64 GB RAM, NVMe.
- Caso A: 1.000.000 itens (mix de arquivos comuns), 0 regras.
- Caso B: mesmo caso, 500 regras de baixa/média complexidade (subset do YARA-Forge).
- Caso C: Caso B já processado, rodar `--yara-only`.

**Critérios**:
- `tempo(B) ≤ 1.15 × tempo(A)` (SC-001).
- `tempo(C) ≤ 0.25 × tempo(A)` (SC-006).

Salve os relatórios em `specs/001-yara-rules-engine/perf-runs/YYYYMMDD/` para histórico.
