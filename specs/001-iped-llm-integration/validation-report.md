# Validation report — quickstart.md run

**Feature**: 001-iped-llm-integration | **Data**: 2026-08-04, atualizado em 2026-08-06 | Task T081

Resultado da execução de [quickstart.md](./quickstart.md) contra a implementação. O objetivo desta
página é registrar **o que ficou de fora e por quê**, não celebrar o que passou.

## Resumo

| | Cenários |
|---|---|
| Validados automaticamente | 1, 3 (caso grande), 6 (parcial), 8 (passos 1–5), 13 |
| Cobertos por suíte que **pula** sem o caso de referência | 2, 3 (caso pequeno), 4, 5, 7, 9, 12 |
| Não executáveis nesta bancada | 10, 11 |

**A lacuna dominante é uma só: o caso de referência não foi construído.** Ela sozinha responde por
47 dos 151 testes do módulo, que hoje pulam. Um teste pulado não é um teste que passou.

> **Atualização de 2026-08-06 — o caso grande foi executado.** T028 rodou sobre um caso real de
> **15.061.999 itens** e SC-002 e SC-015 passaram com folga. Isso remove a lacuna que este relatório
> marcava como a mais séria; o Cenário 3 contra o caso grande deixa de ser hipótese. Ver a seção do
> Cenário 3 abaixo, reescrita com as medições.

> **Encerramento em 2026-08-06.** A feature foi encerrada por decisão do perito com **T006, T073 e
> T079 dispensadas e não executadas**. Este relatório existe para registrar o que ficou de fora, e
> é isso que fica: o caso de referência nunca foi construído, então **47 dos 151 testes seguem
> pulando** e os Cenários 2, 4, 5, 7, 9 e 12 nunca rodaram sob automação — foram exercitados à mão,
> em campo, sobre casos reais de 781 mil e 15 milhões de itens. A diferença importa para o futuro:
> o que foi verificado à mão não impede regressão, porque uma alteração que quebre qualquer uma
> dessas garantias passa no `mvn test`. Os Cenários 10 e 11 continuam sem execução, deixando SC-010
> e FR-065 não verificados.

## Situação por cenário

### Cenário 1 — Handshake do protocolo ✅

`HandshakeTest`, 9 testes, verde. Cobre `initialize` declarando versão e capacidade, `tools/list`
com esquema válido para toda ferramenta, ferramenta inexistente devolvendo erro JSON-RPC bem formado,
método desconhecido, notificação sem resposta, e ida e volta em UTF-8 explícito.

`ToolSchemaTest` verifica além disso que a superfície bate **exatamente** com
[contracts/mcp-tools.md](./contracts/mcp-tools.md), nos dois sentidos: nada faltando e nada a mais.

### Cenário 2 — Abertura e panorama ⏸

`CaseOpenTest` escrito e pulando. Os caminhos de recusa que não precisam de caso —
`NOT_A_CASE`, `CASE_INCOMPLETE`, `CASE_IN_PROCESSING`, `VERSION_UNSUPPORTED`, `CASE_INACCESSIBLE` —
estão cobertos e verdes em `DiagnosticsTest` e `VersionRangeTest`, sobre casos sintéticos.

O passo 6 (cronometragem no caso grande) depende do caso de 10 M.

### Cenário 3 — Paginação e contagem ⏸ (caso pequeno) / ✅ (caso grande)

`PaginationTest` escrito e pulando sobre o caso pequeno.

**Contra o caso grande foi executado em 2026-08-06** (T028), sobre um caso real de **15.061.999
itens**, índice de 68,6 GB. `ScalePerformanceTest`, 5 testes, verde:

| Medição | Resultado | Teto | Critério |
|---|---|---|---|
| `iped_open_case` + `iped_case_overview` | 3.050 ms | 30.000 ms | SC-015 |
| Primeira página de `*:*` (50 itens) | 655 ms | 5.000 ms | SC-002 |
| Total exato devolvendo 1 item | 817 ms | 5.000 ms | FR-012 |
| 10 páginas seguidas, pior caso | 836 ms — página 1: 836 ms, página 10: **479 ms** | 5.000 ms | SC-002 |
| `iped_aggregate` por categoria (41 valores) | 4.490 ms | 15.000 ms | SC-015 |

A linha decisiva é a da paginação profunda: **a página 10 é mais rápida que a primeira**. O custo
acompanha a página, não a profundidade — que é exatamente a diferença entre `PagedSearcher` e uma
implementação sobre `IPEDSearcher.searchAll()`, cujo custo cresce com a profundidade até derrubar a
sessão. O desenho que R3 prescreve deixa de ser argumento e passa a ser medição.

Ressalva honesta sobre o número de itens: 15 M está acima do alvo de ~10 M da clarificação, mas
`*:*` sobre esse caso é o pior caso de amplitude, não o pior caso de custo por item — uma consulta
textual com trechos ativados custa mais por página. `snippetBudgetMs` existe para isso e não foi
exercitado nesta escala.

### Cenário 4 — Autocorreção de vocabulário ⏸

`VocabularyTest` escrito e pulando. O núcleo do ranqueamento está coberto e verde em
`VocabularySuggestionTest`, sobre um vocabulário sintético com a forma de um índice 4.x — incluindo
os erros clássicos (`mediaType` → `contentType`, `filename` → `name`, `isDeleted` → `deleted`) e um
typo próximo.

Esse teste encontrou um defeito real durante a implementação: a regra de substring devolvia `type`
para `mediaType`, que existe, significa outra coisa e levaria a investigação para o lugar errado. O
ranqueamento foi reescrito em torno disso.

### Cenário 5 — Agregação ⏸

`AggregationTest` escrito e pulando. Cronometragem depende do caso grande.

### Cenário 6 — Somente-leitura é real ⚠️

`ReadOnlyInvariantTest` escrito e pulando — o hash recursivo precisa de um caso real.

O que **está** verde: o portão de escrita no dispatcher (`AuditFailClosedTest`), que recusa toda
ferramenta de curadoria com `WRITE_NOT_ENABLED` antes de ler qualquer argumento, e registra a recusa
na trilha.

### Cenário 7 — Escrita, confirmação e estado anterior ⏸

`BookmarkWriteTest` e `ConcurrentAccessTest` escritos e pulando. A captura de estado anterior está
coberta e verde em `AuditChainTest`, sobre a trilha diretamente.

O passo 2 (abrir na UI do IPED e conferir o marcador) é manual por natureza.

### Cenário 8 — Auditoria ✅ passos 1–5, ⏸ 6–9

Verde e executado:

- Passos 1–3: `seq` monotônico sem lacunas, cadeia íntegra, adulteração e remoção de registro
  detectadas — `AuditChainTest`, 8 testes.
- Passo 4: área não gravável recusa **antes** de executar — `AuditFailClosedTest`.
- Passo 5: **processo morto no meio da sessão** — `AuditDurabilityTest` sobe uma JVM filha, deixa-a
  gravar 25 operações e a mata com `destroyForcibly`, sem shutdown hook. Tudo que estava registrado
  sobrevive e a cadeia verifica. É o teste que valida a decisão de R7.
- Passos 6–8: co-localização automática, degradação em mídia não gravável e detecção de trilha órfã
  — `AuditSyncTest` e `AuditOrphanTest`, verdes sobre pastas sintéticas.

Passo 9 (segundo examinador reproduz a sequência) é manual.

### Cenário 9 — Artefatos de saída ⏸

`ArtifactExportTest` escrito e pulando. As duas guardas que não dependem de caso — conjunto vazio
não gera arquivo, formato inválido recusado antes de tocar o caso — estão verdes em
`ArtifactGuardTest`.

O teste do formato encontrou um defeito real: a validação acontecia depois de materializar o
conjunto.

### Cenário 10 — Os três harnesses ❌

Não executável aqui: exige três máquinas limpas e uma pessoa que não escreveu os guias. Registro
preparado em [`iped-mcp/src/test/resources/evaluation/install-timings.md`](../../iped-mcp/src/test/resources/evaluation/install-timings.md).

`SkillParityTest` cobre a metade automatizável do passo 4: os três invólucros são byte a byte
idênticos à fonte canônica.

**SC-010 está não verificado.**

### Cenário 11 — Modelo local ❌

Não executável aqui: exige OpenCode com runtime local. Registro preparado em
[`iped-mcp/src/test/resources/evaluation/local-model.md`](../../iped-mcp/src/test/resources/evaluation/local-model.md).

**FR-065 está não verificado.** O desenho o leva a sério — todo erro carrega `remedy`, `UNKNOWN_FIELD`
carrega `details.similar`, `ToolSchemaTest` exige descrição em cada parâmetro — mas nada disso é
medição.

### Cenário 12 — Bateria de investigação ⏸ (metade) / ❌ (metade)

A bateria de 30 perguntas com gabarito existe em
[`questions.md`](../../iped-mcp/src/test/resources/evaluation/questions.md), e
`InvestigationBatteryTest` a executa — pulando sem o caso.

**Mas cobre apenas a metade de recuperação.** SC-008 ("zero falso positivo apresentado como
conclusão") e SC-009 ("100% das conclusões com itens citados") são propriedades do que o agente
**escreve**, não do que a ferramenta devolve. Verificá-las exige rodar um agente ao vivo e ler as
respostas. Isso está dito no arquivo e no javadoc do teste, para que uma bateria verde não seja
confundida com SC-008 satisfeito.

### Cenário 13 — Diagnóstico ✅

`DiagnosticsTest`, 9 testes, verde. Cobre a matriz inteira: IPED não localizado, pasta que não é
release, caso inexistente vs. sem permissão, pasta sem `iped/`, caso em processamento, caso
incompleto, versão fora da faixa, área de auditoria não gravável. Um teste final exige que **toda**
falha carregue um `remedy` não vazio.

O último item da matriz — caso portátil com evidência ausente — está tratado no código
(`CaseRegistry` abre com `askImagePathIfNotFound = false` e `ContentAccess` declara indisponibilidade
com motivo), mas precisa de um caso portátil real para ser exercitado.

## Lacunas, em ordem de gravidade

1. **Caso de referência pequeno não construído.** Bloqueia 52 testes e sete cenários. A receita
   reprodutível está versionada com scripts para os dois sistemas, mas dois itens exigem trabalho
   manual que o script não faz: fotos com EXIF GPS e uma imagem de sistema de arquivos com arquivo
   apagado e arquivo recuperável por carving.
2. **Caso grande não construído.** SC-002 e SC-015 não verificados. É a lacuna que o próprio
   quickstart marca como inegociável, porque é a única que não aparece em bancada.
3. **SC-008 e SC-009 não automatizáveis.** Exigem execução com agente ao vivo. A bateria prepara o
   terreno; a leitura das respostas é humana.
4. **SC-010 e FR-065 não medidos.** Exigem máquinas limpas e um runtime local.
5. **Detecção de concorrência com a UI é best-effort.** A UI do IPED 4.3.1 não trava o caso, então
   ausência de conflito detectado não prova ausência de outro leitor. Documentado em
   `iped-mcp/CLAUDE.md`; não é corrigível deste lado sem alterar a UI, o que está fora de escopo.

## O que a execução parcial já produziu

Três defeitos reais encontrados por testes antes de qualquer uso:

- Sugestão de campo devolvia `type` para `mediaType` — um campo que existe e significa outra coisa.
- Validação de formato de exportação acontecia depois de materializar o conjunto.
- Invólucros de skill obsoletos passavam na verificação de paridade, porque eram gerados depois dos
  testes rodarem. A geração foi movida para `generate-resources`.
