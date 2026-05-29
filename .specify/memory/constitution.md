<!--
SYNC IMPACT REPORT (mais recente)
==================
Version change: 1.1.0 → 1.2.0
Justificativa do bump: MINOR — atualização material da seção "Restrições
de Build, Ferramentas e Distribuição": a baseline de plataforma muda de
Java 11 para Java 21 LTS (cut-over total) e a regra de compilação passa
de `source/target = 11` para `release = 21`. É atualização material de
orientação de build existente (MINOR), não remoção/redefinição dos
princípios I–V (seria MAJOR) nem mera correção de redação (seria PATCH).

Princípios alterados: nenhum dos 5 princípios principais (I–V) muda.
Seções afetadas: "Restrições de Build, Ferramentas e Distribuição" (1º
bullet reescrito: Java 11 → Java 21 LTS; `release = 21`).
Feature de origem: specs/003-java21-migration (migração para Java 21 LTS).
Follow-up: CLAUDE.md §3/§5 a atualizar na própria feature (tasks T049/T056).

=== HISTÓRICO ANTERIOR ===
SYNC IMPACT REPORT
==================
Version change: 1.0.0 → 1.1.0
Justificativa do bump: MINOR — expansão material da seção "Fluxo de
Desenvolvimento e Gates de Qualidade" (item 2): a regra antiga de
"preservar idioma do arquivo" para Javadoc/comentários foi substituída
por uma política explícita de "inglês para todo código novo". Não é
remoção/redefinição de princípio (seria MAJOR), e ultrapassa correção
de redação (seria PATCH).

Princípios alterados: nenhum dos 5 princípios principais (I–V) muda.
Apenas o fluxo de desenvolvimento que governa contribuições novas
foi atualizado.

Seções afetadas:
  - Fluxo de Desenvolvimento e Gates de Qualidade → item 2 reescrito
    para mandar inglês em todo código fonte novo (Java, JS/Nashorn,
    Python/Jep, XML/properties), justificativa explícita ("IPED é
    usado por equipes fora do Brasil"), e tratamento de Javadocs/
    comentários PT-BR legados (manter, ou traduzir oportunisticamente
    em PRs adjacentes).

Seções adicionadas: nenhuma.
Seções removidas: nenhuma.

Histórico anterior (1.0.0, 2026-05-19):
  Ratificação inicial — 5 princípios derivados do CLAUDE.md raiz
  (Estabilidade da API Pública, Extensão Modular em vez de Modificação,
  Configuração antes de Código, Integridade Forense e Determinismo,
  Disciplina de Concorrência e Isolamento de Processo) + seções de
  Build/Distribuição, Fluxo de Desenvolvimento e Governance.

Templates / artefatos verificados:
  - .specify/templates/plan-template.md   — ✅ não afetado.
  - .specify/templates/spec-template.md   — ✅ não afetado.
  - .specify/templates/tasks-template.md  — ✅ não afetado.
  - .specify/templates/checklist-template.md — ✅ não afetado.
  - CLAUDE.md (raiz)                      — ✅ atualizado §4
    Convenções globais para refletir a nova regra de idioma.
  - iped-api/CLAUDE.md                    — ✅ atualizado §5
    Convenções + checklist §10 para refletir a nova regra.
  - demais CLAUDE.md por módulo           — ✅ verificados; sem
    referência à convenção de idioma de código (Javadoc legado).

Follow-up TODOs: (nenhum)
-->

# IPED — Constituição do Projeto

> Documento normativo para o fork do **IPED — Indexador e Processador de
> Evidências Digitais**. Define os princípios não-negociáveis que regem
> todas as alterações neste repositório, sejam feitas por humanos ou por
> agentes de IA. Esta constituição **supera** quaisquer outras práticas
> implícitas; em caso de conflito com guias secundários, prevalece este
> documento. O `CLAUDE.md` da raiz e dos módulos é a referência
> operacional (o "como"); esta constituição estabelece o "o quê" e o
> "porquê".

## Core Principles

### I. Estabilidade da API Pública (NÃO-NEGOCIÁVEL)

O módulo `iped-api` e os contratos públicos consumidos por plugins,
forks downstream e casos antigos **NÃO PODEM** sofrer remoções ou
renomeações silenciosas. Em particular:

- Interfaces, classes públicas, enums e constantes em `iped-api` **NÃO
  PODEM** ter métodos removidos ou renomeados sem ciclo de deprecação
  documentado em `ReleaseNotes.txt`.
- Strings literais que viram chave de campo Lucene
  (`BasicProps`, `IndexItem`) são **imutáveis**: alterá-las quebra
  busca em casos antigos.
- `AppAnalyzer` é congelado pelo mesmo motivo (compatibilidade de
  índices).
- A biblioteca `iped-ahocorasick` é tratada como dependência fechada;
  modificações exigem revisão explícita e justificativa em PR.

**Justificativa**: o IPED processa casos forenses que sobrevivem por
anos; quebrar a leitura desses casos compromete a cadeia de custódia
e o valor probatório da evidência.

### II. Extensão Modular em vez de Modificação

Novas funcionalidades **DEVEM** ser adicionadas como artefatos novos
dentro do módulo apropriado, em vez de alterar comportamento existente:

- Suporte a novo formato → novo parser em `iped-parsers/iped-parsers-impl`.
- Novo visualizador → novo `AbstractViewer` em `iped-viewers/iped-viewers-impl`.
- Novo formato a recuperar → novo `Carver` em `iped-carvers/iped-carvers-impl`
  + entrada em `CarverConfig.xml`.
- Nova etapa do pipeline → nova `AbstractTask` em `iped-engine` + entrada
  em `TaskInstaller.xml`.
- Nova fonte de dados → novo `DataSourceReader` em `iped-engine`.
- Novo metadado → nova propriedade em `ExtraProperties`, **não**
  reaproveitar chaves existentes.

Modificações em componentes núcleo (`Manager`, `Worker`,
`ProcessingQueues`, `IndexWriter`, `AppAnalyzer`) **DEVEM** ser
justificadas explicitamente no PR e revisadas quanto a invariantes
de concorrência e compatibilidade de índice.

**Justificativa**: o pipeline é uma chain-of-responsibility com
dezenas de tasks compostas via configuração; alterações cirúrgicas
preservam reprodutibilidade entre versões e perfis (`forensic`, `pedo`,
`triage`, `fastmode`, `blind`).

### III. Configuração antes de Código

Todo comportamento ajustável pelo perito **DEVE** ser exposto via o
padrão `iped.configuration.Configurable<T>` (em `iped-api`) e editável
em `iped-app/resources/config/` ou em um profile, **não** hardcoded:

- Thresholds, timeouts, caminhos, flags de habilitação, listas de
  categorias e similares **DEVEM** residir em
  `IPEDConfig.txt`, `TaskInstaller.xml`, `CarverConfig.xml`,
  `CustomSignatures.xml`, `RegexConfig.txt`, `HashDBLookupConfig.txt`,
  `CategoriesConfig.json` ou no arquivo `.properties`/`.xml`/`.json` do
  Configurable correspondente.
- Profiles (`profiles/{forensic,pedo,triage,fastmode,blind}`) **DEVEM**
  ser usados para variações de pipeline, sem ramificações
  condicionais no código.
- Mensagens visíveis ao usuário **DEVEM** ser internacionalizadas em
  `iped-app/resources/localization/` (PT-BR e EN no mínimo). Strings
  hardcoded só são aceitas em logs internos.

**Justificativa**: o IPED é distribuído com vários profiles e operado
por equipes que customizam pipeline e thresholds por tipo de caso;
prender comportamento em código torna o produto inutilizável fora do
contexto original.

### IV. Integridade Forense e Determinismo

A evidência **NÃO PODE** ser corrompida por ambiguidade de plataforma,
encoding ou logging desestruturado:

- Charset **SEMPRE** explícito. UTF-8 é o default; ISO-8859-1 só
  para nomes legados de NTFS. Construtores `new String(byte[])`,
  `Reader`/`Writer` sem charset, `String.getBytes()` sem argumento e
  similares **NÃO PODEM** ser introduzidos.
- Logging via SLF4J + Log4j 2 (configurado por `Log4j2Configuration*.xml`).
  `System.out` e `System.err` **NÃO PODEM** ser usados em código de
  produção (CLI bootstrap é a única exceção controlada).
- Datas em código **DEVEM** usar `java.time` com zona explícita; nunca
  depender do default da JVM.
- Acesso ao Sleuthkit **DEVE** ser feito via `SleuthkitClient` —
  chamadas diretas a `SleuthkitJNI` são proibidas.
- Hashes, ordenação de itens e geração de IDs **DEVEM** ser
  determinísticos para o mesmo conjunto de entrada e configuração.

**Justificativa**: a saída do IPED é prova em processo judicial;
não-determinismo, dados truncados por encoding incorreto ou logs
perdidos por `System.out` comprometem a cadeia de custódia.

### V. Disciplina de Concorrência e Isolamento de Processo

O modelo de execução do engine tem invariantes rígidas que **DEVEM**
ser preservadas:

- Cada `Worker` executa em sua própria thread; cada `AbstractTask`
  tem **uma instância por worker** — campos de instância podem ser
  usados sem locks; estado global **DEVE** ir em `caseData.objectMap`
  com limpeza em `finish()`.
- Subitens **DEVEM** ser gerados via `EmbeddedDocumentExtractor.parseEmbedded(...)`
  (parsers) ou `IItem.createChildItem()` + `worker.processNewItem(item)`
  (tasks/carvers). Criar `IItem` manualmente fora desses caminhos
  é proibido.
- Threading de UI:
  - Swing → `SwingUtilities.invokeLater` ou já estar na EDT.
  - JavaFX → `Platform.runLater`.
- Componentes propensos a crash (Sleuthkit, parsing de containers
  arriscados, UI principal) **DEVEM** rodar out-of-process via os
  patterns existentes (`SleuthkitClient`/`SleuthkitServer`,
  `ParsingProcess`, `Bootstrap` lançando JVM filha).

**Justificativa**: o IPED processa centenas de milhões de itens por
caso e 400 GB/h; quebrar o modelo de workers introduz contenção que
mata a vazão, e perder isolamento de processo derruba o caso inteiro
quando uma DLL nativa quebra em um único arquivo malformado.

## Restrições de Build, Ferramentas e Distribuição

- **Java 21 LTS com JavaFX** (Liberica/BellSoft Full JDK).
  `maven.compiler.release = 21`. Migrado de Java 11 na feature
  `003-java21-migration` (cut-over total: Java 11 deixou de ser
  suportado). Não introduzir dependência de JavaFX além do que já
  está embarcado via `JFXPanel`.
- **Build**: Maven 3.6+ multi-módulo a partir do `pom.xml` raiz
  (versão atual `4.4.0-SNAPSHOT`). Submódulos herdam a versão; não
  fixar versões divergentes em filhos.
- **Encoding fonte**: UTF-8.
- **Branch padrão**: `master` (instável, dev). Releases em tags.
- **Ferramentas externas** (Sleuthkit, ImageMagick, Tesseract,
  LibreOffice, MPlayer, RegRipper, libpff, libesedb, evtxexport,
  rifiuti2, GraphViz, JRE embarcado) são distribuídas em `tools/`,
  `jre/` e `plugins/` do release; **não** assumir disponibilidade no
  PATH do sistema.
- **Licenciamento**: toda dependência nova **DEVE** ser registrada em
  `ThirdParty.txt` e ter sua licença anexada em `licenses/`.
- **CI** (`.github/workflows/maven.yml`) é a referência para o ambiente
  Linux mínimo; mudanças que exijam novas dependências nativas
  **DEVEM** atualizar o workflow no mesmo PR.

## Fluxo de Desenvolvimento e Gates de Qualidade

1. **Antes de editar**: ler o `CLAUDE.md` do módulo afetado; usar
   `Grep` para localizar implementações e consumidores de qualquer
   símbolo público que será tocado.
2. **Durante a edição** — idioma do código:
   - Todo **código novo** (arquivos `.java`, `.js`, `.py`, `.xml`, `.properties`
     e qualquer outro de implementação) **DEVE** ser comentado e documentado
     em **inglês**: Javadocs, comentários de bloco, comentários de linha,
     identificadores em comentários, mensagens de log, mensagens de exceção
     técnicas e exemplos em docstrings. Aplica-se também a arquivos novos
     adicionados a módulos cujos arquivos legados estão em PT-BR.
   - **Javadocs e comentários em PT-BR pré-existentes** no código legado
     **PODEM permanecer** como estão — não há mandato de tradução em massa
     do legado. Ao editar uma região com comentário PT-BR pré-existente:
     se a edição é trivial (rename, format), preservar o idioma; se a edição
     reescreve substancialmente o bloco ou seu Javadoc, **DEVE-SE** traduzir
     o comentário/Javadoc afetado para inglês no mesmo PR.
   - **Strings visíveis ao usuário final** continuam regidas pelo Princípio
     III §3 (bundles em `iped-app/resources/localization/`, PT-BR + EN no
     mínimo). Esta regra cobre o que o desenvolvedor lê no fonte; aquela,
     o que o perito lê na UI.
   - **Esta constituição e os `CLAUDE.md`** permanecem em PT-BR (idioma
     de trabalho do fork). Decisão de governança, fora do escopo de "código".
   - Demais princípios desta constituição (I–V) continuam aplicáveis sem
     mudança.

   **Justificativa**: IPED é usado por equipes forenses fora do Brasil
   (LEAs internacionais, pesquisadores, contribuidores OSS). Código fonte
   em inglês é a base comum que permite leitura, debug e contribuição sem
   atrito linguístico. O custo de manter PT-BR no código é alto e crescente;
   o custo de traduzir em massa o legado é alto e pouco prioritário — daí a
   convivência (legado preservado, novo em inglês).
3. **Antes de commit**:
   - `mvn -pl <módulo> -am install` no módulo afetado (e em
     `iped-app` se a mudança puder impactar o release).
   - `mvn test` no módulo se houver cobertura relevante.
   - Atualizar o `CLAUDE.md` do módulo **se** contratos, dependências
     ou padrões mudaram.
4. **Pull Request**: descrever impacto em compatibilidade de índice
   (Princípio I), em pipeline (Princípio II) e em concorrência
   (Princípio V) quando aplicável. Mudanças em
   `iped-api`, `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`,
   `AppAnalyzer` ou `iped-ahocorasick` exigem revisão explícita e
   justificativa.
5. **Spec Kit**: features grandes seguem o fluxo
   `/speckit-specify` → `/speckit-clarify` → `/speckit-plan` →
   `/speckit-tasks` → `/speckit-implement`. O bloco
   "Constitution Check" do `plan-template.md` é resolvido a partir
   desta constituição: cada princípio é um gate.

## Governance

- Esta constituição **supera** quaisquer práticas tácitas ou
  preferências individuais. Em caso de conflito com `CLAUDE.md`
  (raiz ou módulo), a constituição prevalece para o "o quê"; o
  `CLAUDE.md` permanece autoritativo para o "como" operacional
  (paths, comandos, convenções de estilo).
- **Emendas**: alterações nesta constituição **DEVEM** ser feitas
  via PR dedicado contendo (a) o diff do arquivo
  `.specify/memory/constitution.md`, (b) o **Sync Impact Report**
  atualizado no cabeçalho HTML, (c) atualização dos templates
  dependentes em `.specify/templates/` quando necessário, e (d)
  bump da versão semântica conforme regra abaixo.
- **Versionamento da constituição** (SemVer):
  - **MAJOR**: remoção ou redefinição incompatível de um princípio
    ou regra de governance.
  - **MINOR**: adição de princípio, seção ou expansão material de
    orientação existente.
  - **PATCH**: clarificações, correções de redação, refinamentos
    não-semânticos.
- **Revisão de conformidade**: revisores de PR **DEVEM** verificar
  conformidade com os cinco princípios antes de aprovar. Qualquer
  desvio justificado deve ser registrado no PR (não na constituição).
- **Guidance file de runtime**: o `CLAUDE.md` da raiz e os
  `CLAUDE.md` de cada módulo fornecem orientação operacional para
  agentes de IA e desenvolvedores. Esta constituição os referencia;
  não os substitui.

**Version**: 1.2.0 | **Ratified**: 2026-05-19 | **Last Amended**: 2026-05-29
