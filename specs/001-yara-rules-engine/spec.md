# Feature Specification: YARA Rules Engine para IPED

**Feature Branch**: `001-yara-rules-engine`

**Created**: 2026-05-19

**Status**: Draft

**Input**: User description: "Vamos adicionar ao IPED capacidade de rodar regras YARA nos artefatos por ele analisados."

## Clarifications

### Session 2026-05-19

- Q: Qual engine YARA e quais módulos a integração deve suportar? → A: **YARA-X** (1.x — reescrita oficial em Rust de Victor M. Alvarez, sucessora do YARA clássico em modo manutenção) com os módulos padrão `pe`, `elf`, `math`, `hash`, `magic`, `dotnet` e `time`. O módulo `cuckoo` é explicitamente banido na compilação. Compatibilidade ~99% com catálogos YARA clássicos; o flag `YRX_RELAXED_RE_SYNTAX` é ativado para aceitar regex de catálogos legados. (Decisão atualizada em 2026-05-19; substitui a opção original de libyara 4.x — ver `research.md` R-01.)
- Q: Quais itens do caso o IPED escaneia por default com YARA? → A: Apenas itens com content stream binário acessível (arquivos, subitens de containers e carved items); itens "puramente metadados" (registry entries, linhas de SQLite, contatos isolados) são pulados por default e podem ser incluídos via override de configuração.
- Q: A engine deve aceitar regras pré-compiladas (`.yarc`) além de source? → A: **Não, na v1.** YARA-X compila o catálogo inteiro em milissegundos por regra, então o ganho do formato pré-compilado é marginal; além disso, o formato serializado do YARA-X (`yrx_rules_serialize`/`deserialize`) é diferente do `.yarc` do YARA clássico e ainda está estabilizando. O catálogo aceita só `.yar` e `.yara` (fontes). (Decisão atualizada em 2026-05-19; substitui a aceitação anterior de `.yarc` — ver `research.md` R-01.)
- Q: O que persistir por match (rule id, tags, bytes, offsets)? → A: Persistir tudo — identificador da regra, tags, strings que casaram (bytes) e offsets — em todos os itens, refletindo no índice e no relatório HTML.
- Q: Qual a granularidade do "rerun YARA only" sobre um caso já processado? → A: Rerun total apenas — aplica o catálogo atual a todos os itens elegíveis do caso. Rerun sobre subconjunto/bookmark/seleção fica fora de escopo na v1.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Aplicar regras YARA durante o processamento de um caso (Priority: P1)

Um perito carrega um conjunto de regras YARA (próprias ou de bases públicas, como as do Neo23x0/Florian Roth) na configuração do IPED antes de processar um caso. Durante o processamento, o IPED aplica essas regras a cada artefato (arquivos, subitens, carved items). Toda vez que uma regra casa com um item, o identificador da regra (namespace + nome) e suas tags ficam registrados no item e indexados para busca e filtragem posterior.

**Why this priority**: É a integração mínima que entrega valor real ao perito — permite identificar malware conhecido, indicadores de comprometimento (IOCs), padrões textuais e binários customizados em um único passe junto com tudo o que o IPED já faz. Sem isso, o examinador precisa rodar YARA em ferramentas externas (mais lento, fora do contexto do caso e sem correlação com bookmarks/filtros existentes).

**Independent Test**: Configurar um diretório com 2–3 arquivos `.yar` (uma regra casando por string conhecida, uma por hex pattern, uma com tags), processar uma evidência sintética contendo arquivos que deveriam casar; verificar que (a) cada item casado expõe os identificadores das regras em uma propriedade indexada e pesquisável e (b) itens que não deveriam casar não recebem marcação.

**Acceptance Scenarios**:

1. **Given** o perito definiu o caminho de um diretório com regras YARA válidas no perfil do caso, **When** o caso é processado, **Then** cada item que satisfaz alguma regra recebe uma propriedade multi-valorada com os identificadores das regras casadas e essa propriedade é indexada e pesquisável.
2. **Given** uma das regras YARA do conjunto possui erro de sintaxe, **When** o caso é processado, **Then** o IPED registra o erro no log, descarta apenas a regra defeituosa, continua o processamento com as demais e não aborta o caso.
3. **Given** o item é um subitem (anexo de e-mail, entrada de zip, item recuperado por carving), **When** as regras são aplicadas, **Then** o subitem é escaneado da mesma forma que itens regulares e os matches ficam associados ao subitem (e não apenas ao item-pai).

---

### User Story 2 - Visualizar, filtrar e marcar artefatos pelas regras YARA casadas (Priority: P2)

Após processar o caso, o perito abre a interface de análise e quer rapidamente ver quais artefatos casaram com quais regras YARA — para priorizar a análise, criar bookmarks/tags e exportar/relatar somente o que interessa.

**Why this priority**: A informação só vira ação se for fácil de explorar. Sem painel/filtro dedicado, o perito teria que pesquisar manualmente por nome de regra. Mas a coleta de matches (P1) é o que viabiliza este passo, por isso vem depois — e pode ser implementada/entregue em iteração separada.

**Independent Test**: Em um caso já processado com regras aplicadas, abrir a UI e (a) localizar a seção de regras YARA no painel de filtros/categorias, (b) clicar em uma regra e ver apenas os itens correspondentes na galeria/tabela, (c) marcar o conjunto resultante como bookmark.

**Acceptance Scenarios**:

1. **Given** um caso processado com matches YARA, **When** o perito abre o painel de filtros/categorias, **Then** existe uma seção dedicada a regras YARA listando cada regra casada e a contagem de itens correspondentes.
2. **Given** o perito clica em uma regra na seção YARA, **When** o filtro é aplicado, **Then** a tabela/galeria mostra apenas os itens que casaram com aquela regra.
3. **Given** o perito tem uma seleção de itens filtrados por uma regra YARA, **When** ele cria um bookmark a partir da seleção, **Then** o bookmark é criado normalmente e pode ser exportado/incluído no relatório.
4. **Given** o perito faceta `yara:rule` ou `yara:tag` no painel de metadados/filtros e abre um item correspondente, **When** o conteúdo é renderizado pelo viewer de texto, **Then** os bytes que casaram a regra/tag selecionada são destacados — mesmo comportamento já oferecido para `Regex:*` e `NER:*`. Fragmentos não-imprimíveis (binários puros) são pulados pelo highlight e permanecem inspecionáveis via `yara:matches` JSON / viewer dedicado.

---

### User Story 3 - Catálogo de regras por perfil e matches no relatório (Priority: P3)

Equipes de perícia mantêm catálogos de regras separados por tipo de caso (malware, CSAM, fraude financeira, etc.). O perito espera poder atrelar conjuntos de regras a um perfil (`forensic`, `pedo`, `triage`, ...) e que, ao gerar o relatório HTML do caso, as regras casadas apareçam na descrição de cada item.

**Why this priority**: Ganho de produtividade e padronização, mas não bloqueia o uso — o perito pode apontar manualmente o diretório de regras a cada caso. Depende de P1 e se beneficia de P2 estar pronto.

**Independent Test**: Configurar dois perfis com diretórios de regras distintos, processar dois casos com perfis diferentes e verificar (a) que cada caso usou apenas as regras do seu perfil e (b) que o relatório HTML lista os matches YARA por item.

**Acceptance Scenarios**:

1. **Given** o perfil `forensic` aponta para um diretório de regras X e o perfil `pedo` aponta para um diretório Y, **When** dois casos são processados com perfis distintos, **Then** cada caso aplica somente o conjunto de regras do seu perfil.
2. **Given** um caso com matches YARA, **When** o relatório HTML é gerado, **Then** cada item com matches lista as regras casadas em sua página/detalhe.

---

### Edge Cases

- Arquivos muito grandes (imagens de disco, vídeos de vários GB): o scan respeita um limite de tamanho configurável; ao exceder o limite o item é pulado e o motivo registrado, sem falhar.
- Arquivos cifrados, corrompidos ou sem stream acessível: scan é pulado silenciosamente para o item, contabilizado como "skipped" nas métricas.
- Conjunto de regras vazio, ausente ou diretório inexistente: feature fica desligada para o caso, log explica o motivo e nenhum erro é propagado.
- Regra YARA com erro de compilação: somente a regra defeituosa é descartada (log warn), as demais são compiladas e aplicadas normalmente.
- Regras com modificadores `private`/`global` e variáveis externas: comportamento documentado (privates não geram match exposto na UI; globals afetam todas; externals usam defaults configuráveis).
- Re-processamento parcial de um caso já existente: o perito pode aplicar somente as regras YARA aos itens já indexados, sem reprocessar todo o pipeline.
- Regras com nomes iguais em arquivos diferentes: o namespace é derivado do arquivo de origem (chamada `yrx_compiler_new_namespace` antes de cada `add_source`); o identificador exposto inclui namespace + nome, evitando colisão.
- Itens com conteúdo binário lido via Sleuthkit (out-of-process): o scan consome o stream do `IItem` sem materializar o conteúdo duas vezes em memória.
- Indisponibilidade da engine YARA no SO atual: a feature é silenciosamente desabilitada com um warning único, o restante do IPED continua funcional.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Sistema MUST permitir ao usuário configurar um ou mais diretórios contendo regras YARA em formato fonte (`.yar`/`.yara`) por perfil e por caso, com a feature inteira podendo ser habilitada/desabilitada via configuração. Formatos pré-compilados (`.yarc` do YARA clássico ou serialização própria do YARA-X) ficam fora do escopo na v1.
- **FR-002**: Sistema MUST compilar todas as regras fonte (`.yar`/`.yara`) encontradas antes de iniciar o processamento, usando a engine **YARA-X 1.x** com os módulos padrão `pe`, `elf`, `math`, `hash`, `magic`, `dotnet` e `time` e o flag `YRX_RELAXED_RE_SYNTAX` ativo (para aceitar regex de catálogos YARA clássicos). O módulo `cuckoo` MUST ser banido via `yrx_compiler_ban_module` para que regras com `import "cuckoo"` produzam erro individual e sejam descartadas sem abortar o catálogo. Compilações bem-sucedidas, falhas (com motivo extraído de `yrx_compiler_errors_json`) e o tempo total de compilação MUST ser registrados no log.
- **FR-003**: Sistema MUST aplicar as regras YARA compiladas ao conteúdo binário de cada item que possua content stream acessível, incluindo subitens (anexos, entradas de containers) e carved items. Itens puramente metadados (entradas de registro do Windows, linhas de SQLite, contatos isolados, etc.) são pulados por default e devem poder ser incluídos via opção de configuração ("scan tudo") sem mudanças de código.
- **FR-004**: Sistema MUST, para cada item que casar com uma ou mais regras, registrar (a) os identificadores das regras (namespace + nome) e (b) as tags YARA herdadas em propriedade multi-valorada indexada no item, e (c) para cada match, os detalhes — identificador da string, offset no stream do item e bytes brutos do trecho casado — em estrutura associada ao item, recuperável via UI e relatório.
- **FR-005**: Sistema MUST garantir que erro de compilação, erro de runtime ou timeout em uma regra individual não aborta o processamento do caso nem o de outros itens.
- **FR-006**: Sistema MUST permitir limite de tamanho máximo de arquivo a ser escaneado, configurável por perfil; itens acima do limite são pulados e o motivo é registrado.
- **FR-007**: Sistema MUST permitir limite de tempo máximo de scan por item (timeout), interrompendo a regra após o limite e registrando o evento sem propagar falha.
- **FR-008**: Sistema MUST expor as regras YARA casadas como categoria filtrável na interface de análise, com contagem de itens por regra.
- **FR-008a**: Quando o usuário faceta `yara:rule` ou `yara:tag` no painel de metadados/filtros, o sistema MUST injetar os bytes que casaram (decodificados para texto imprimível a partir do `yara:matches` JSON) no conjunto de termos de highlight consumido pelos viewers de texto, equivalendo ao comportamento existente para `Regex:*` e `NER:*`. Bytes não-imprimíveis MUST ser descartados silenciosamente — o detalhe completo permanece no `yara:matches` e no viewer dedicado (futuro T030).
- **FR-009**: Usuários MUST conseguir criar bookmarks/tags a partir do conjunto de itens filtrado por uma regra YARA, usando o fluxo de bookmark já existente.
- **FR-010**: Sistema MUST incluir os matches YARA na geração de relatório HTML do caso, listados por item, exibindo identificador da regra, tags, e — para cada string que casou — o nome da string, o offset e os bytes do trecho (com renderização segura para HTML, sem permitir injeção a partir do conteúdo casado).
- **FR-011**: Sistema MUST permitir reaplicar o catálogo atual de regras YARA sobre um caso já processado, sem necessidade de reprocessar todo o pipeline ("rerun YARA only"). O modo de rerun aplica-se ao caso inteiro (todos os itens elegíveis); rerun sobre subconjunto (bookmark, filtro, seleção) fica fora de escopo na v1. A operação MUST substituir integralmente os matches anteriores no índice (sem mescla parcial).
- **FR-012**: Sistema MUST registrar métricas básicas do scan no log de processamento: itens escaneados, itens pulados (por tamanho/timeout/erro), tempo total e quantidade de matches por regra.
- **FR-013**: Sistema MUST preservar integralmente o comportamento atual do IPED quando a feature está desabilitada: sem custo perceptível de processamento, sem propriedades novas nos itens e sem novas dependências carregadas em runtime.
- **FR-014**: Sistema MUST funcionar nos sistemas operacionais já suportados pelo IPED (Windows e Linux), consumindo a biblioteca nativa `libyara-x-capi` distribuída em `tools/yara-x/<os>/`. A indisponibilidade da engine no SO atual deve apenas desligar a feature com aviso único, sem impedir o build ou a execução do restante do IPED.

### Key Entities *(include if feature involves data)*

- **Conjunto de regras YARA (Ruleset)**: coleção de regras compiladas a partir de arquivos fonte (`.yar`/`.yara`) descobertos em um ou mais diretórios e construída via `yrx_compiler_build`. Atributos relevantes: diretórios de origem, total de regras compiladas, regras com falha de compilação (e motivo extraído do JSON de erros do YARA-X), namespace por arquivo de origem.
- **Regra YARA**: unidade lógica dentro do ruleset. Atributos relevantes para a UI/relatório: identificador (namespace + nome), tags declaradas, metadados (`meta`) declarados na regra (ex.: autor, descrição, severidade).
- **YARA Match**: resultado da aplicação de uma regra a um item. Atributos persistidos para cada item casado: identificador da regra (namespace + nome), tags herdadas, lista das strings que casaram (com identificador da string, offset relativo ao stream do item e bytes brutos do match). Esses atributos são gravados junto ao item — indexados quando aplicável e disponíveis na UI e no relatório HTML.
- **Item (existente)**: artefato escaneado. Ganha uma propriedade nova multi-valorada com os identificadores das regras casadas; demais propriedades permanecem inalteradas.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em um caso de referência com 1 milhão de itens, habilitar YARA com um conjunto de até 500 regras de baixa/média complexidade NÃO aumenta o tempo total de processamento em mais de 15% comparado ao mesmo caso sem YARA.
- **SC-002**: Para 100% dos itens processados, ou o item é escaneado com sucesso ou existe registro explícito no log indicando o motivo do skip (tamanho, timeout, formato, erro).
- **SC-003**: A partir do momento em que o caso é aberto na UI, o perito consegue chegar à lista de itens casados por uma regra específica em no máximo 3 cliques (painel de categorias → seção YARA → regra).
- **SC-004**: Em uma bateria de teste com 50 regras públicas conhecidas aplicadas a um conjunto de 100 amostras controladas, os matches obtidos pelo IPED coincidem em 100% com os obtidos rodando a CLI oficial do YARA contra os mesmos arquivos.
- **SC-005**: Habilitar/desabilitar a feature e ajustar o catálogo de regras requer apenas alteração em arquivo de configuração já existente do IPED, sem recompilar ou substituir binários.
- **SC-006**: Re-rodar YARA sobre um caso já processado leva menos de 25% do tempo do processamento original do mesmo conjunto de itens.

## Assumptions

- Os usuários (peritos) já estão familiarizados com a sintaxe YARA e gerenciam seus próprios catálogos de regras; o IPED não fornece editor de regras nem catálogo público pré-instalado na v1.
- A engine **YARA-X 1.x** publica binários nativos pré-compilados de `libyara-x-capi` para Windows x64 e Linux x64 (releases oficiais em `https://github.com/VirusTotal/yara-x`); os módulos `pe`, `elf`, `math`, `hash`, `magic`, `dotnet` e `time` vêm habilitados no build padrão. Esses binários são consumidos por bindings JNA escritos neste repositório.
- A feature opera sobre o conteúdo binário de cada item (stream do `IItem`); escaneamento de texto extraído por parsers/OCR está fora do escopo da v1 e pode ser proposto em iteração futura.
- O scan é executado como uma task adicional dentro do pipeline existente, no padrão `AbstractTask` + `TaskInstaller.xml`, sem alterar tasks existentes nem renomear propriedades já indexadas.
- Os limites default (sugestão inicial: tamanho máximo de 250 MB por item e timeout de 30 s por item) são valores de partida e serão validados na fase de plano; usuários podem sobrescrevê-los por perfil.
- Itens acima do limite ou sem stream acessível (cifrado/corrompido) são contabilizados como "skipped" e não causam falha do caso.
- A persistência dos matches reaproveita o índice Lucene já existente para os atributos indexáveis (identificadores de regra e tags) como propriedade multi-valorada; os detalhes de cada match (string, offset, bytes) ficam associados ao item em estrutura recuperável pela UI e relatório, podendo reutilizar mecanismos de "metadata extra" já existentes no IPED (a forma exata é decisão do plano).
- Quando a engine YARA-X estiver indisponível no ambiente (biblioteca nativa `libyara-x-capi` ausente), a feature é silenciosamente desligada com warning único, mantendo o restante do IPED funcional.
