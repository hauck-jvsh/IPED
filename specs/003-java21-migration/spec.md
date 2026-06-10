# Feature Specification: Migração do IPED para Java 21 LTS

**Feature Branch**: `003-java21-migration`

**Created**: 2026-05-29

**Status**: Draft

**Input**: User description: "Vamos migrar o IPED para Java 21 LTS."

## Visão geral

O IPED roda hoje sobre Java 11 (com JavaFX, via Liberica Full JDK), runtime cujo suporte gratuito está em fim de ciclo e que cada vez mais bloqueia atualizações de bibliotecas. Esta feature eleva a plataforma de execução e build do projeto para **Java 21 LTS**, de forma **preservadora de comportamento**: o objetivo é paridade forense e suportabilidade, não novas funcionalidades.

A migração é um **cut-over total** — após sua conclusão, Java 21 é o único runtime suportado; Java 11 deixa de ser alvo. No Windows o IPED distribui seu próprio runtime embarcado (o usuário não precisa instalar Java); no Linux o runtime Java 21 é fornecido pelo sistema, mantendo o modelo de distribuição atual.

## Clarifications

### Session 2026-05-29

- Q: Como o runtime Java 21 deve ser empacotado por plataforma? → A: Só o Windows embarca o runtime; no Linux a aplicação usa o Java 21 (Full, com JavaFX) instalado no sistema (modelo atual mantido).
- Q: O que conta como "equivalente" na validação de paridade Java 11 vs Java 21? → A: Igualdade semântica no conjunto definido de campos forenses (hashes, assinatura/MIME, texto extraído, contagem/categorização, itens carved, matches YARA, timeline), ignorando saídas cosméticas/não-determinísticas (timestamps de relatório, ordem multi-thread, bytes de thumbnail).
- Q: Como o JavaFX deve ser provido no runtime Java 21? → A: Full JDK com JavaFX embutido (modelo Liberica Full JDK 21), não OpenJDK base + OpenJFX separado.

### Session 2026-06-01

- Q: O release novo de visualização precisa abrir bancos de grafo (e casos portáteis) gerados antes da migração? → A: **Não.** Cada caso processado é distribuído **autocontido** — acompanha a JRE e as bibliotecas usadas no seu processamento (`<caso>/iped/jre` + `<caso>/iped/lib`, incl. `lib/neo4j`). A análise de um caso usa sempre o runtime/libs empacotados com ele, não o aplicativo novo sobre um caso antigo. Em consequência, **FR-005** (abrir casos portáteis antigos), **FR-006** (enquadramento "grafo funciona para casos pós-migração" — a garantia de que o grafo renderiza já é coberta por **FR-011**) e **FR-007** (não crashar com graph store Neo4j 4.x) deixam de ser necessários e foram **retirados**. A guarda de degradação correspondente (T043) também foi descartada.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Processar evidências no Java 21 sem regressão (Priority: P1)

Um perito processa uma imagem forense (E01/DD/UFDR/AD1/pasta lógica) usando o release do IPED sobre Java 21 e obtém um caso indexado com o **mesmo resultado forense** que obteria no release atual sobre Java 11: mesmos hashes, mesma detecção de assinatura, mesmo texto extraído, mesmas categorias, mesmos subitens/itens recuperados por carving, mesmos matches YARA e mesma timeline.

**Why this priority**: É a função central da ferramenta. Se o pipeline de processamento não produzir resultados equivalentes, a migração é inaceitável — em perícia, divergência de resultado compromete a validade da prova.

**Independent Test**: Processar um conjunto de dados de referência no build Java 11 (baseline) e no build Java 21 e comparar as saídas (hashes, contagem de itens, texto, categorias, carved, YARA, timeline). Entrega valor mesmo isolada: comprova que a ferramenta processa corretamente no novo runtime.

**Acceptance Scenarios**:

1. **Given** o conjunto de dados de referência e o caso-baseline gerado no Java 11, **When** o mesmo conjunto é processado pelo release Java 21 com o mesmo profile, **Then** as saídas forenses (hashes, contagem de itens, texto extraído, categorias, itens carved, matches YARA, eventos de timeline) são idênticas às do baseline.
2. **Given** o pipeline completo habilitado (hash, parsing, carving, YARA, OCR, regex, NER, thumbs, índice, relatório), **When** o processamento conclui no Java 21, **Then** não ocorre erro atribuível a incompatibilidade de JDK (encapsulamento forte, API removida) e o caso abre normalmente.
3. **Given** tarefas customizadas em JavaScript e Python configuradas no pipeline, **When** o processamento roda no Java 21, **Then** os scripts executam e produzem os mesmos efeitos do baseline.

---

### ~~User Story 2 - Abrir e analisar casos pré-existentes sem regressão~~ — **Removida (Session 2026-06-01)**

> **Removida por completo.** Esta história tratava exclusivamente de abrir, no release novo, casos processados por releases anteriores. Como cada caso é distribuído **autocontido** (acompanha a JRE + libs do seu processamento e é analisado com elas — Clarifications 2026-06-01), o release novo **não** abre casos antigos. Em consequência, FR-004/005/006/007 foram retirados e esta história deixa de existir.
>
> A validação de **renderização de viewers/UI no Java 21** (FR-011 — HTML, imagem, áudio, hex, Office, e-mail, mapa, timeline, grafo), que antes vivia aqui, passa a ser exercitada sobre o **caso recém-processado** no fluxo da **User Story 1** (o caso é aberto e analisado na UI logo após o processamento). FR-011 permanece como requisito.

---

### User Story 3 - Buildar e testar em uma toolchain suportada (Priority: P3)

Um mantenedor/desenvolvedor clona o repositório, builda com JDK 21 e roda a suíte de testes; a integração contínua (CI) valida o projeto em Java 21.

**Why this priority**: Sem build e CI sobre Java 21, a migração não é sustentável nem verificável; novas contribuições não teriam garantia de compatibilidade.

**Independent Test**: Executar o build completo e a suíte de testes com um JDK 21 localmente e no CI; confirmar sucesso. Entrega valor isolada: habilita desenvolvimento contínuo no novo alvo.

**Acceptance Scenarios**:

1. **Given** um JDK 21 (Full, com JavaFX) configurado, **When** o build completo é executado, **Then** todos os módulos compilam no nível de linguagem 21 e o release é gerado.
2. **Given** a suíte de testes automatizados, **When** executada sobre Java 21, **Then** 100% dos testes existentes passam.
3. **Given** o pipeline de CI, **When** uma alteração é submetida, **Then** o CI builda e roda os testes sobre Java 21 (substituindo a matriz Java 11/14 atual).

---

### User Story 4 - Distribuir release com runtime Java 21 (Windows e Linux) (Priority: P3)

Um perito recebe um pacote de release do IPED que já embarca o runtime Java 21 (com a stack gráfica), executa o instalador/launcher em Windows e em Linux e a aplicação inicia e processa evidências de ponta a ponta sem instalar Java manualmente.

**Why this priority**: O IPED é distribuído com runtime embarcado; sem o runtime 21 empacotado e validado nas plataformas suportadas, o usuário final não consegue executar a nova versão.

**Independent Test**: Gerar o release nas plataformas suportadas, instalar em máquinas limpas (sem Java) Windows e Linux, e processar um caso pequeno de ponta a ponta. Entrega valor isolada: comprova distribuibilidade.

**Acceptance Scenarios**:

1. **Given** uma máquina Windows sem Java instalado, **When** o release Java 21 é executado, **Then** a aplicação inicia usando o runtime embarcado e processa um caso de teste de ponta a ponta.
2. **Given** uma máquina Linux suportada com Java 21 (Full, com JavaFX) instalado no sistema, **When** o release Java 21 é executado, **Then** a aplicação inicia e todas as ferramentas nativas integradas (Sleuthkit out-of-process, OCR via Python, ImageMagick, LibreOffice, RegRipper) funcionam.
3. **Given** o release rodando no runtime embarcado Java 21, **When** a aplicação verifica a versão do Java, **Then** o Java 21 é reconhecido como suportado e nenhum aviso falso de "versão não testada" é exibido.

---

### Edge Cases

- **Flags de JVM removidas**: configurações ou launchers que passem flags de JVM removidas em versões intermediárias não podem impedir a inicialização (devem ser ignoradas com segurança).
- **Bibliotecas reflexivas**: dependências que acessam internals do JDK por reflexão não podem falhar em runtime sob o encapsulamento forte (default a partir do Java 16).
- **Subprocessos nativos**: o modelo out-of-process (Sleuthkit, parsing isolado) e os processos nativos (Tesseract/JEP, MPlayer, GraphViz) precisam funcionar com a JVM filha em Java 21.
- **Arquitetura/ambiente**: detecção de runtime 32 bits e mensagens de versão continuam corretas.
- **Coexistência durante a transição**: um caso aberto/processado parcialmente por uma versão e continuado por outra não deve corromper o índice (cenário de `--continue`/`--append`).
- **Plugins externos restritos**: PhotoDNA, jars TSK customizados e demais drop-ins de `plugins/` continuam a ser carregados sem recompilação obrigatória.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O projeto MUST compilar no nível de linguagem Java 21 e executar sobre um runtime Java 21 LTS em todos os módulos.
- **FR-002**: 100% da suíte de testes automatizados existente MUST passar sobre Java 21.
- **FR-003**: Para o conjunto de dados de referência, o pipeline completo de processamento MUST produzir resultados **forensemente equivalentes** ao baseline Java 11. "Equivalente" significa igualdade **semântica** no conjunto definido de campos forenses — hashes dos itens, detecção de assinatura/MIME, texto extraído, contagem e categorização de itens, itens recuperados por carving, matches YARA e eventos de timeline —, desconsiderando saídas cosméticas ou não-determinísticas (ex.: timestamps de geração de relatório, ordem de itens dependente de multi-threading, reencode binário de thumbnails por bibliotecas atualizadas).
- ~~**FR-004**: Casos criados por releases Java 11 (índice Lucene, bookmarks, storage, thumbnails) MUST abrir e ser pesquisáveis/analisáveis no release Java 21, sem reindexação por parte do usuário.~~ — **Removido (Session 2026-06-01)**: casos são autocontidos (acompanham a JRE + libs do seu processamento e são analisados com elas); o release novo não abre casos antigos.
- ~~**FR-005**: Casos portáteis gerados em releases anteriores MUST permanecer abríveis no release Java 21.~~ — **Removido (Session 2026-06-01)**: idem (casos autocontidos).
- ~~**FR-006**: A análise de grafo MUST funcionar no Java 21 para casos processados após a migração.~~ — **Removido (Session 2026-06-01)**: a renderização do grafo na UI já é exigida por **FR-011** (lista de viewers inclui "grafo"); o enquadramento "antes/depois da migração" deixa de fazer sentido com casos autocontidos.
- ~~**FR-007**: Não é requerido preservar bancos de grafo de casos processados antes da migração; abrir tal caso MUST NOT causar falha — a funcionalidade de grafo degrada de forma controlada.~~ — **Removido (Session 2026-06-01)**: o release novo não abre casos/graph stores antigos, então não há cenário de store 4.x a degradar.

> Os identificadores FR-004/005/006/007 ficam **vagos** (não reaproveitados) para preservar a numeração de FR-008…FR-020, referenciada nos demais artefatos. A migração continua **preservando o formato de índice Lucene** (linha 9.x, `backward-codecs`, chaves de campo e `AppAnalyzer` congelados — Princípio I): isso garante que o caso aberto **com suas próprias libs** funcione, e evita churn desnecessário; apenas deixou de ser um requisito que o **release novo** abra um caso **antigo**.

- **FR-008**: Tarefas de scripting em JavaScript e em Python MUST continuar a executar no pipeline sob Java 21.
- **FR-009**: Todas as integrações de ferramentas nativas embarcadas (Sleuthkit, OCR/Tesseract via Python, ImageMagick, LibreOffice, MPlayer, RegRipper, libpff/libesedb/evtx/etc.) MUST funcionar em Windows e Linux sob Java 21.
- **FR-010**: A Web API REST MUST continuar a servir e responder corretamente.
- **FR-011**: Todos os viewers e visualizações da UI (HTML, imagem, áudio, hex, Office, e-mail, mapa, timeline, grafo) MUST renderizar corretamente no Java 21.
- **FR-012**: A verificação de versão de Java da aplicação MUST reconhecer Java 21 como versão suportada (sem aviso falso de "não testada") e continuar a alertar em runtimes não suportados.
- **FR-013**: O CI MUST buildar e executar a suíte de testes sobre Java 21, substituindo a matriz Java 11/14 atual.
- **FR-014**: As dependências de terceiros MUST estar em versões compatíveis com Java 21, sem falhas em runtime por encapsulamento forte ou por APIs removidas/alteradas do JDK.
- **FR-015**: O release distribuído para Windows MUST embarcar um runtime Java 21 que inclua a stack gráfica (JavaFX). No Linux, o release MUST executar sobre um runtime Java 21 (Full, com JavaFX) **fornecido pelo sistema** — sem runtime embarcado —, mantendo o modelo de distribuição atual. Em ambas as plataformas o runtime Java 21 MUST ser um **Full JDK com JavaFX embutido** (modelo Liberica Full JDK 21), e não OpenJDK base com OpenJFX adicionado separadamente.
- **FR-016**: O comportamento visível ao usuário, os arquivos de configuração, os profiles e a localização MUST permanecer inalterados — nenhuma ação de migração é exigida do usuário final além de usar o novo release.
- **FR-017**: Após a migração, Java 11 MUST deixar de ser um runtime suportado (runtime único: Java 21).
- **FR-018**: A migração MUST ser preservadora de comportamento — nenhuma nova funcionalidade visível, mudança de comportamento ou adoção de recursos de linguagem pós-Java 11 é introduzida como parte desta feature.
- **FR-019**: A documentação de build/execução, os avisos de versão e os guias de contribuição MUST ser atualizados para refletir Java 21.
- **FR-020**: Artefatos internamente mantidos com patches (ex.: fork customizado do Tika, bundle nativo de Python embarcado) MUST ser rebaseados sobre versões upstream compatíveis com Java 21, preservando os patches.

### Key Artifacts *(itens manipulados pela migração)*

- **Caso processado**: saída do IPED (índice Lucene, storage, bookmarks, thumbnails, eventual graph DB), distribuído **autocontido** com a JRE e as bibliotecas usadas no seu processamento (`<caso>/iped/jre` + `<caso>/iped/lib`). A análise de um caso usa sempre o runtime/libs empacotados com ele.
- **Conjunto de dados de referência (baseline)**: amostra representativa de fontes (imagem forense, UFDR, pasta lógica) usada para comparar a saída Java 11 vs Java 21.
- **Runtime embarcado**: JRE/JDK Java 21 (com JavaFX) empacotado no release por plataforma.
- **Pacote de release**: árvore distribuível (`iped.jar`, `lib/`, `tools/`, `conf/`, `profiles/`, `jre/`, modelos, plugins) que precisa ser regenerada sobre Java 21.
- **Conjunto de dependências**: catálogo de bibliotecas de terceiros cujas versões precisam ser compatíveis com Java 21.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% dos testes automatizados existentes passam quando executados sobre Java 21.
- **SC-002**: Para o conjunto de dados de referência, a saída de processamento no Java 21 apresenta **zero divergências** em relação ao baseline Java 11 no conjunto definido de campos forenses (hashes dos itens, assinatura/MIME, texto extraído, contagem/categorização de itens, itens carved, matches YARA, eventos de timeline); diferenças cosméticas/não-determinísticas (timestamps de relatório, ordem multi-thread, bytes de thumbnail) são explicitamente excluídas da comparação.
- ~~**SC-003**: 100% dos casos do conjunto de validação abrem e são pesquisáveis no release Java 21 sem erro.~~ — **Removido (Session 2026-06-01)** junto com FR-004: o release novo não abre casos antigos. A pesquisabilidade/navegação de um caso **recém-processado** é coberta pela validação da User Story 1 + FR-011.
- **SC-004**: O release Windows inicia em máquina **sem Java instalado** (via runtime embarcado) e processa um caso de ponta a ponta; o release Linux inicia em máquina **com Java 21 do sistema** instalado e processa um caso de ponta a ponta — ambos com sucesso.
- **SC-005**: O throughput de processamento no Java 21 é igual ou melhor que o baseline Java 11, admitida regressão máxima de 5% para o mesmo dataset e hardware.
- **SC-006**: Zero erros de inicialização ou de runtime atribuíveis a incompatibilidade de JDK (encapsulamento forte, APIs removidas) durante as execuções de validação.
- **SC-007**: Nenhuma alteração de configuração é exigida do usuário final para adotar o novo release — configs e profiles existentes funcionam sem edição.
- **SC-008**: O aviso de versão de Java identifica corretamente Java 21 como suportado e não emite alerta de "versão não testada".

## Assumptions

- O alvo é **Java 21 LTS** especificamente (não 17 nem 25), por ser o LTS atual com amplo suporte do ecossistema; uma futura migração para Java 25 está fora de escopo.
- **Cut-over total**: Java 11 é abandonado; o projeto passa a suportar um único runtime (Java 21). Não há janela de suporte duplo 11/21.
- Migração **preservadora de comportamento**: nenhum recurso novo de linguagem (virtual threads, records, pattern matching, etc.) é adotado e nenhum comportamento funcional muda como parte desta feature.
- **Casos são autocontidos**: cada caso processado é distribuído com a JRE e as bibliotecas usadas no seu processamento; é analisado com esse runtime/libs empacotados, não com um release de visualização posterior. Logo, **não há requisito de o release novo abrir casos antigos** (índice, portáteis ou graph store) — ver Clarifications 2026-06-01.
- No Windows o IPED continua a **embarcar seu próprio runtime** Java 21; no Linux o runtime Java 21 (Full, com JavaFX) é **fornecido pelo sistema** — o ambiente Linux deve dispor de Java 21, como hoje.
- O runtime Java 21 é um **Full JDK com JavaFX embutido** (modelo Liberica Full JDK 21), preservando a estratégia atual de JavaFX-no-JDK; não se adota OpenJFX modular separado.
- O formato de índice de caso (Lucene, linha 9.x) permanece **inalterado** (Princípio I) — não como garantia de o release novo abrir casos antigos (não é mais requisito), mas para evitar churn de formato e assegurar que o caso, aberto com **suas próprias libs**, continue legível.
- As plataformas suportadas permanecem **Windows e Linux**, como hoje.
- O conjunto de dados de referência para a validação de paridade será definido pelos mantenedores (fontes representativas).
- Plugins/artefatos externos restritos (PhotoDNA, jars TSK customizados) estão fora do escopo de modificação direta, mas devem continuar a carregar.

## Out of Scope

- Migração para Java 25 (ou qualquer versão além do 21).
- Suporte simultâneo a Java 11 e Java 21 (janela de compatibilidade dupla).
- Migração de namespace Jakarta EE (`javax.*` → `jakarta.*`), salvo se inevitável para alcançar compatibilidade com Java 21.
- Preservação/migração in-place de bancos de grafo Neo4j de casos antigos.
- Abertura de casos antigos (índice, portáteis, graph store) pelo release novo de visualização — casos são autocontidos e analisados com a JRE/libs empacotadas neles (Clarifications 2026-06-01).
- Adoção de recursos de linguagem do Java 21 ou refatorações de modernização (ex.: virtual threads).
- Novas funcionalidades, redesenho de UI ou qualquer mudança de comportamento visível ao usuário.
- Suporte a novas plataformas (ex.: macOS/ARM) além das já suportadas.

## Dependencies & Constraints

- **Neo4j embarcado** (hoje 4.4.x) não executa em Java 21 e precisa ser elevado a uma release compatível; este é o subsistema de maior risco e a principal restrição técnica. Como casos são autocontidos e o release novo não abre grafos antigos (Clarifications 2026-06-01), a troca de formato de store é aceitável e não exige guarda de compatibilidade.
- Bibliotecas que dependem de reflexão sobre internals do JDK (sensíveis ao encapsulamento forte) precisam ser atualizadas ou substituídas para não falharem em runtime.
- A toolchain de build (plugins Maven de compilação e de teste) precisa estar em versões que suportem Java 21.
- A validação depende da existência de um caso-baseline gerado no Java 11 e de máquinas de teste Windows e Linux.
