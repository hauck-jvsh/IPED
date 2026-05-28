# Manual test script — US2 (UI filter + bookmark)

**Feature**: YARA Rules Engine para IPED
**User Story**: US2 (P2) — Visualizar, filtrar e marcar artefatos pelas regras YARA casadas
**Spec**: [../spec.md](../spec.md)

## Pré-requisitos

1. Release do IPED 4.4.0 (ou snapshot) construído com a engine YARA-X já habilitada e a libyara-x-capi 1.16.0 disponível.
   - Windows: `tools/yara-x/win64/yara_x_capi.dll` populada (T009 ✓).
   - Linux: ver `tools/yara-x/README.md` para o procedimento de build from source — não há prebuilt no upstream 1.16.0.
2. Caso processado com `enableYara=true` em `IPEDConfig.txt` e um catálogo de regras válido em `conf/YaraConfig.txt → ruleDirectories`. Recomenda-se um caso com:
   - Ao menos uma regra que case (uma string-match simples como `"hello world"` resolve).
   - Ao menos uma regra com tags declaradas (ex.: `rule X : malware apt { ... }`).
   - Itens não-casados também presentes (para validar que a UI separa os dois conjuntos).
3. JDK 11 Full FX rodando o `iped.exe` ou `iped` launcher (não os JARs avulsos).

## Escopo

Os três Acceptance Scenarios da US2 da spec:

1. **AS-1**: caso processado com matches → painel de filtros expõe uma seção dedicada a YARA com contagem.
2. **AS-2**: clicar numa regra na seção YARA → tabela/galeria mostra somente itens casados.
3. **AS-3**: criar bookmark a partir da seleção filtrada → bookmark exportável.

Mais, valida T031 (bookmark sobre seleção filtrada) e T030 (renderização do `yara:matches`).

---

## Roteiro

### AS-1 — Faceta YARA no painel de filtros

| # | Ação | Resultado esperado |
|---|---|---|
| 1.1 | Abrir o IPED contra o caso com matches: `iped.exe -d <CASE_OUTPUT_DIR>` | Aplicação abre, tabela de itens carrega. |
| 1.2 | No painel de metadados/filtros, abrir o combobox "Grupo de Propriedades" (label `ColumnsManager.*`) | Lista de grupos contém uma entrada **YARA matches** (EN) ou **Matches YARA** (PT-BR), entre "Windows Events" e "Outras". |
| 1.3 | Selecionar **YARA matches** | O combobox "Propriedade" abaixo lista exatamente: `yara:tag` (faceta agregada de tags cross-rule) e uma entrada `yara:match:<namespace>/<name>` por regra que casou em pelo menos um item do caso. **Não devem aparecer** `yara:rule` nem `yara:matches` — ambos foram removidos na rev-5 ([T031e](../tasks.md)) por redundância com os campos `yara:match:*`. |
| 1.4 | Selecionar `yara:tag` | Lista lateral mostra cada tag agregada (union sobre todos os matches do caso) com contagem de itens. |
| 1.5 | Selecionar `yara:match:<alguma regra>` (ex.: `yara:match:suspicious_strings/vmware_detection`) | Lista lateral mostra cada **valor distinto casado por essa regra específica** (ex.: `vmware`, `vmxh`, ...), com contagem de itens. Mirror exato do `Regex:CPF` mostrando cada CPF casado. |

**Captura de tela esperada**: combobox de grupos aberto evidenciando "YARA matches"; lista lateral mostrando contagens reais.

---

### AS-2 — Filtrar a galeria/tabela por uma regra

| # | Ação | Resultado esperado |
|---|---|---|
| 2.1 | Com `yara:rule` selecionado na faceta, clicar numa regra da lista | Galeria/tabela é reduzida aos itens que têm aquela regra como valor do campo. |
| 2.2 | Verificar a barra de status / contador de hits | Reflete `count(items matching rule X)` igual ao valor exibido na faceta. |
| 2.3 | Abrir um dos itens filtrados → aba "Metadados" / "Propriedades" | Item exibe `yara:rule = namespace/rule_name`, `yara:tag = [...]` e `yara:matches = {"engineVersion":"yara-x-1.16.0",...}` (JSON cru — pretty-print é polish deferido — ver "Limitação conhecida" abaixo). |
| 2.4 | Retornar à faceta, segurar Ctrl e clicar em duas regras diferentes | A interseção (default) ou união (conforme `FilterManager` configurado) dos itens é mostrada — comportamento mesmo do facet de qualquer outro campo multi-valor em IPED. |
| 2.5 | Limpar o filtro (botão "Limpar Filtros" da App) | Galeria volta ao conjunto completo. |
| 2.6 | Selecionar um `yara:match:<regra>` na faceta lateral, escolher um valor decodificado (ex.: `vmware` para `yara:match:suspicious_strings/vmware_detection`); abrir um dos itens; ir à aba "Texto" do viewer | O valor selecionado aparece destacado, idêntico ao que ocorre quando se faceta um `Regex:CPF` e seleciona um CPF. Para valores hex (binários), o destaque não vai ancorar no texto — esperado, pois o text viewer não consegue casar bytes não-imprimíveis. |
| 2.7 | Inspecionar os campos `yara:match:*` na aba de metadados de um item casado | Para cada regra que casou, existe um campo dedicado com lista de valores decodificados — ex.: `yara:match:suspicious_strings/vmware_detection = [vmware, VMware Tools, ...]`. Estrutura visual idêntica a `Regex:EMAIL = [user@host, ...]`. Mirror per-regra introduzido na rev-4 ([T031d](../tasks.md)). |
| 2.8 | Confirmar ausência dos campos `yara:rule` e `yara:matches` na aba de metadados | Esses campos foram removidos na rev-5 ([T031e](../tasks.md)) — não devem aparecer no panel de metadados nem como entradas selecionáveis no dropdown "Propriedade". O conjunto de regras que casou pode ser inferido olhando os field names com prefixo `yara:match:*`. |

---

### AS-3 / T031 — Criar bookmark a partir da seleção filtrada

| # | Ação | Resultado esperado |
|---|---|---|
| 3.1 | Com a galeria filtrada por uma regra, selecionar todos os itens (Ctrl+A) | Todos itens da view atual ficam destacados. |
| 3.2 | No painel de bookmarks (Marcadores), criar novo bookmark com nome legível (ex.: `YARA:apt28_loader_dropper`) | Bookmark é criado e os itens selecionados ficam atribuídos a ele. |
| 3.3 | Limpar filtros e voltar pelo painel de bookmarks, clicando no novo bookmark | Lista mostra exatamente os mesmos itens marcados — fluxo de bookmark é independente do critério de filtro original (T031). |
| 3.4 | (Opcional) Gerar HTML report do caso ou de uma seleção que inclua o bookmark | (Cobertura está em US3 — T039. Aqui só validar que o bookmark aparece e é exportável.) |

---

## Armadilha operacional: SearchApp aberto durante `--yara-only`

Se você está iterando regras YARA e re-rodando `--yara-only` para refrescar o `yara:matches` do caso, **feche a janela do SearchApp do mesmo caso antes**. Caso contrário, o pipeline trava no fim em `ExportFileTask.finish() → con.commit()` esperando lock EXCLUSIVE no `<caso>/iped/storage/storage-*.db` enquanto a UI mantém SHARED locks de leitura. Sintomas: ProgressFrame mostra "Todos os Workers estão ociosos, esperando trabalho"; `jcmd <pid> Thread.print` revela `Worker-0` em `NativeDB.step` RUNNABLE. Resolução: fechar a UI libera os locks e o commit conclui em segundos (sem reprocessar). Diagnóstico detalhado e contexto no [quickstart.md §6](../quickstart.md). Pre-check automatizado fica como melhoria futura.

## Limitações conhecidas (atualizadas na rev-5)

- **Audit detail fino perdido**: offsets per-string, meta YARA (`author = "..."` etc.) e hex completo de matches binários grandes deixaram de ser persistidos quando `yara:matches` (JSON) foi removido na rev-5. O relatório HTML não tem mais o bloco YARA dedicado — só os campos `yara:tag` e `yara:match:*` como metadata comum. Se essa info for crítica para algum fluxo forense, considerar reintroduzir o JSON via `setExtraAttribute(YARA_MATCH_DETAIL, ...)` no `YaraScanTask` (todo o código consumidor está no histórico do branch).
- **Highlight de bytes binários não funciona**: o passo 2.6 só destaca valores de `yara:match:*` cujo `hex` decodifica para ASCII imprimível (`0x20..0x7E` + tab/LF/CR). Valores binários ficam no índice como hex lowercase (facetáveis), mas o text viewer não tem como casá-los — esperado.
- **Outros locales (de_DE, es_AR, fr_FR, it_IT)** recebem `ColumnsManager.Yara = "YARA matches"` (inglês como fallback). Comunidade pode traduzir conforme convenção do projeto.

## Critério de aprovação

- ✓ AS-1, AS-2, AS-3 reproduzem o comportamento descrito em "Resultado esperado" sem stack trace no log (`IPED-SearchApp.log`).
- ✓ Pelo menos um screenshot do passo 1.2 (combobox com YARA matches visível) anexado a este documento ou ao PR.
- ✓ T031 confirmado: bookmark sobre seleção filtrada funciona sem alteração de código.

Em caso de falha, anotar em `## Notas` abaixo + abrir issue.

## Notas

(Preencher durante execução manual.)
