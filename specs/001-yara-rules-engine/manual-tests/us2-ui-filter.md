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
| 1.3 | Selecionar **YARA matches** | O combobox "Propriedade" abaixo passa a listar somente `yara:rule`, `yara:tag` (e `yara:matches` se aparecer como indexado). |
| 1.4 | Selecionar `yara:rule` | Lista lateral mostra cada identificador `namespace/rule_name` que casou, com a contagem de itens em parênteses. |
| 1.5 | Selecionar `yara:tag` | Lista lateral mostra cada tag agregada (union sobre todos os matches do caso) com contagem. |

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

---

### AS-3 / T031 — Criar bookmark a partir da seleção filtrada

| # | Ação | Resultado esperado |
|---|---|---|
| 3.1 | Com a galeria filtrada por uma regra, selecionar todos os itens (Ctrl+A) | Todos itens da view atual ficam destacados. |
| 3.2 | No painel de bookmarks (Marcadores), criar novo bookmark com nome legível (ex.: `YARA:apt28_loader_dropper`) | Bookmark é criado e os itens selecionados ficam atribuídos a ele. |
| 3.3 | Limpar filtros e voltar pelo painel de bookmarks, clicando no novo bookmark | Lista mostra exatamente os mesmos itens marcados — fluxo de bookmark é independente do critério de filtro original (T031). |
| 3.4 | (Opcional) Gerar HTML report do caso ou de uma seleção que inclua o bookmark | (Cobertura está em US3 — T039. Aqui só validar que o bookmark aparece e é exportável.) |

---

## Limitações conhecidas (v1)

- **`yara:matches` JSON aparece como texto cru** na aba de metadados. Um viewer dedicado (extends `iped.viewers.api.AbstractViewer`) que pretty-prints regra/tags/strings/offsets fica como polish para a próxima iteração — ver T030 e research.md R-05. O `iped.engine.task.yara.YaraMatchSerializer.fromJson()` já existe e basta consumi-lo no novo viewer quando for implementado.
- **Outros locales (de_DE, es_AR, fr_FR, it_IT)** recebem `ColumnsManager.Yara = "YARA matches"` (inglês como fallback). Comunidade pode traduzir conforme convenção do projeto.
- **`yara:matches` pode não aparecer como faceta** porque é stored-only (não indexado). Se o caso real confirmar isso, ajustar o roteiro para focar em `yara:rule`/`yara:tag` e atualizar `data-model.md → §5` para refletir.

## Critério de aprovação

- ✓ AS-1, AS-2, AS-3 reproduzem o comportamento descrito em "Resultado esperado" sem stack trace no log (`IPED-SearchApp.log`).
- ✓ Pelo menos um screenshot do passo 1.2 (combobox com YARA matches visível) anexado a este documento ou ao PR.
- ✓ T031 confirmado: bookmark sobre seleção filtrada funciona sem alteração de código.

Em caso de falha, anotar em `## Notas` abaixo + abrir issue.

## Notas

(Preencher durante execução manual.)
