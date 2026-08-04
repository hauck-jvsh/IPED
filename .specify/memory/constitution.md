<!--
SYNC IMPACT REPORT
==================
Version change: (não ratificada) → 1.0.0

Esta é a primeira ratificação. O arquivo anterior era o template de origem,
com todos os placeholders por preencher.

Princípios adicionados:
  I.   Integridade da evidência é inviolável
  II.  Caso processado é contrato permanente
  III. Estender antes de modificar
  IV.  Comportamento configurável vive em configuração
  V.   Nada implícito no que varia por ambiente

Seções adicionadas:
  - Restrições da plataforma
  - Fluxo de desenvolvimento
  - Governança

Seções removidas: nenhuma (template vazio).

Follow-up TODOs: nenhum. Todos os placeholders foram preenchidos.

Consequência a tratar fora deste comando:
  specs/001-iped-llm-integration/plan.md registra "Constitution Check:
  NÃO AVALIÁVEL — sem princípios ratificados". Com esta ratificação o
  portão passa a existir e aquele bloco precisa ser reavaliado.
-->

# Constituição do IPED — branch 4.3.1

Esta constituição governa **este branch**, derivado da tag `4.3.1` do upstream. O tronco de
desenvolvimento do fork (`master`) evolui sob restrições diferentes — Java 21, YARA-X, Neo4j
fora de processo — e não é governado por este documento. Ao portar mudanças entre branches,
revalide cada princípio antes de assumir que ele se aplica dos dois lados.

## Core Principles

### I. Integridade da evidência é inviolável

Nenhum componente MUST modificar arquivos de evidência original, em nenhum modo de operação e
sob nenhuma circunstância. Componentes que acessam evidência MUST abrir em somente-leitura.

Operações que alteram o estado de um caso — marcadores, seleção, índice — MUST ser explícitas,
distinguíveis de leitura, e MUST registrar o estado anterior quando forem destrutivas.

**Rationale**: o IPED produz material que sustenta decisão judicial. Uma evidência alterada não
é uma evidência degradada — é uma evidência inutilizável, e a perda é irreversível. Todo o resto
nesta constituição é negociável em algum grau; isto não é.

### II. Caso processado é contrato permanente

Os seguintes elementos MUST ser tratados como interface pública imutável:

- Nomes de campo do índice Lucene (`BasicProps`, `IndexItem` e demais constantes que viram chave)
- Configuração do `AppAnalyzer` — fold, lowercase, ASCII, tokenização
- Métodos existentes das interfaces de `iped-api`

Renomear, remover ou alterar semântica desses elementos MUST NOT acontecer. Acréscimos são
permitidos; alterações não.

**Rationale**: casos processados são consultados anos depois de criados e podem ser reabertos por
determinação judicial. Uma mudança de nome de campo não quebra o build — quebra silenciosamente a
busca em acervos que ninguém vai reprocessar. Falha que só aparece em produção, tarde, e sobre
material que importa.

### III. Estender antes de modificar

Ao acrescentar capacidade, o caminho preferencial MUST ser aditivo:

- Nova `AbstractTask` com seu `Configurable`, em vez de alterar task existente
- Novo parser, viewer ou carver, em vez de mudar comportamento de um existente
- Nova propriedade em `ExtraProperties`, em vez de reinterpretar uma existente

Alterações em `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter`, `SleuthkitClient` ou no
Aho-Corasick MUST ser justificadas explicitamente e MUST vir acompanhadas de revisão das
invariantes de concorrência afetadas.

**Rationale**: o pipeline é paralelo, com estado por worker e ordem de tasks significativa. O
custo de um módulo a mais é conhecido e local; o custo de uma regressão de concorrência é difuso,
intermitente e caro de diagnosticar.

### IV. Comportamento configurável vive em configuração

Todo componente externamente configurável MUST implementar `Configurable<T>` e MUST ler seus
parâmetros de `conf/` ou de um profile, nunca de constantes no código.

Habilitar, desabilitar ou reordenar capacidade MUST ser possível editando configuração — nunca
exigindo recompilação.

**Rationale**: o mesmo binário atende triagem rápida, perícia completa e detecção de CSAM, em
instalações que não podem recompilar. Comportamento que só muda no código é comportamento que,
na prática, não muda.

### V. Nada implícito no que varia por ambiente

Padrões herdados da plataforma MUST NOT ser usados onde o valor varia por máquina, locale ou
origem do dado:

- Charset MUST ser sempre explícito — UTF-8 por padrão; ISO-8859-1 onde o dado exigir
- Logging MUST usar SLF4J; `System.out` e `System.err` MUST NOT aparecer em código de produção
- Acesso a Sleuthkit MUST passar por `SleuthkitClient`, nunca por `SleuthkitJNI` diretamente
- Trabalho de UI MUST ocorrer na EDT (Swing) ou via `Platform.runLater` (JavaFX)
- Código novo MUST ser comentado e documentado em inglês; texto visível ao usuário MUST ser
  localizado em `iped-app/resources/localization/`, no mínimo PT-BR e EN

**Rationale**: a ferramenta roda em máquinas que não controlamos e lê dados produzidos por
sistemas que não controlamos. Um charset herdado do sistema operacional é um bug que só aparece
na evidência de outra pessoa, e normalmente como texto corrompido que ninguém percebe ser corrupção.

## Restrições da plataforma

**Java 11 é restrição de runtime, não apenas de compilação.** O release embarca um JRE 11
(`iped-app/pom.xml`, artefato `java:jre:zip`). Toda dependência nova MUST executar nesse runtime.
Uma biblioteca cujo baseline seja superior MUST NOT ser adotada, ainda que o build a aceite —
o artefato compilaria e falharia em campo.

**Estrutura Maven multi-módulo.** Capacidade nova de escopo próprio SHOULD nascer como módulo,
não como pacote dentro de um módulo existente, quando puder consumir apenas APIs públicas dos
demais. Isso mantém a fronteira explícita e isola dependências novas.

**Ferramentas externas** (Sleuthkit, Tesseract, ImageMagick, LibreOffice) são distribuídas em
`tools/`. Dependência nativa nova MUST ser documentada ali e no `ThirdParty.txt`.

## Fluxo de desenvolvimento

Antes de qualquer commit que altere código:

1. `mvn -pl <módulo> -am install` no módulo afetado MUST passar
2. `mvn test` MUST passar onde houver cobertura relevante
3. O `CLAUDE.md` do módulo MUST ser atualizado se contratos ou dependências mudaram

Toda task nova MUST ter `Configurable` correspondente, MUST estar registrada em
`TaskInstaller.xml` na posição correta, e MUST respeitar `isEnabled()` em `process()`.

Trabalho conduzido sob Spec Kit MUST passar pelo Constitution Check do `plan.md`. Um plano que
registre o portão como não avaliável MUST NOT prosseguir para implementação sem que esta
constituição seja consultada.

## Governança

Esta constituição prevalece sobre convenções não escritas e sobre preferência individual. Onde
ela conflitar com um `CLAUDE.md` de módulo, a constituição vence e o `CLAUDE.md` MUST ser corrigido.

**Emenda** exige: a alteração proposta em texto, a justificativa, e a avaliação do impacto sobre
casos já processados. Emendas que afetem o Princípio I ou II MUST demonstrar que nenhum acervo
existente é invalidado.

**Versionamento** segue semântica:

- **MAJOR** — remoção ou redefinição incompatível de princípio
- **MINOR** — princípio ou seção acrescentada, ou orientação materialmente expandida
- **PATCH** — esclarecimento, redação, correção sem efeito semântico

**Conformidade** é verificada no Constitution Check de cada `plan.md` e na revisão de PR. Uma
violação MUST ser justificada explicitamente na seção Complexity Tracking do plano, com a
alternativa simples que foi rejeitada e o motivo. Violação não justificada bloqueia a entrega.

**Version**: 1.0.0 | **Ratified**: 2026-08-04 | **Last Amended**: 2026-08-04
