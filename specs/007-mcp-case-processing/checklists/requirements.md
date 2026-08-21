# Specification Quality Checklist: Criação de caso pelo servidor MCP — processar a evidência

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

### Iteração 1 — 2026-08-21

Correções aplicadas antes desta avaliação:

- **Vazamento de implementação removido.** A redação inicial citava nomes de ferramentas MCP
  (`iped_search`, `iped_export_artifact`), a classe `Bootstrap` e o valor concreto de heap
  (32 GB). Substituídos por descrição funcional ("a busca e a exportação", "orçamento de memória
  dimensionado em dezenas de gigabytes"). Referências a requisitos de specs anteriores
  (FR-036 de 006) permanecem: são rastreabilidade entre documentos de requisito, não detalhe
  técnico.
- **"fechar o stdin" → "fechar a entrada padrão"** no contexto, pela mesma razão.

### Iteração 2 — 2026-08-21 (após clarificação)

Os três marcadores [NEEDS CLARIFICATION] foram resolvidos com o usuário e registrados na seção
Clarifications da spec. Nenhum permanece.

| Marcador | Requisito | Resolução | Requisito derivado |
|---|---|---|---|
| Modelo de autorização | FR-003 | Concessão por configuração; sem aprovação por pedido | FR-038 — a trilha registra a postura vigente na abertura de sessão, já que a autorização é anterior ao pedido e não deixaria rastro próprio |
| Confinamento de origem | FR-006 | Lista de permissão de áreas de leitura declaradas, sobre o caminho real | FR-039 — áreas resolvidas no momento do pedido, não na inicialização, para não exigir reinício após montar mídia |
| Escopo da mutação de caso | FR-030 | Caso novo e retomada; acréscimo a caso concluído fora de escopo | FR-040 — recusa de acréscimo com diagnóstico distinto do de destino ocupado, para que a fronteira de escopo não seja lida como defeito |

Acréscimos decorrentes: cenário 8 na US2 (retomada), cenário 6 da US3 reescrito para distinguir os
dois diagnósticos, SC-016 e SC-017, e duas premissas novas na seção Assumptions.

### Iteração 3 — 2026-08-21 (`/speckit-clarify`)

Cinco perguntas adicionais, todas respondidas e integradas. Nenhum item deste checklist mudou de
estado: a spec já passava em 16/16 antes e continua passando depois. O que a sessão produziu foi
**cobertura**, não correção — cinco categorias da taxonomia saíram de Partial para Resolved.

| Pergunta | Resposta | Requisitos derivados |
|---|---|---|
| Quem pode cancelar um trabalho alheio | Qualquer sessão autorizada; a trilha registra quem pediu | FR-023 estendido; edge case que dizia "precisa haver regra declarada" resolvido |
| O trabalho sobrevive ao fim do servidor | Não — sobrevive à sessão, não ao processo; retomada por FR-030 | FR-024 explicitado, FR-041 |
| O agente alcança a causa técnica de uma falha | Trecho diagnóstico limitado, como conteúdo derivado de evidência sob a política de egresso | FR-042, FR-043, SC-018 |
| Preflight de espaço em disco | Adverte e registra, nunca recusa; a decisão fica com quem pede | FR-017 delimitado, FR-044, SC-019, edge case reescrito |
| Retenção do registro de trabalho | Indefinida, junto da área de auditoria | FR-045, SC-020, edge case explicitado |

Um TODO disfarçado foi eliminado: o edge case de cancelamento por terceiro dizia "precisa haver
regra declarada" sem declarar regra alguma.

### Estado final

Contagem verificada: **45 requisitos funcionais**, **20 critérios de sucesso**, **8 entradas de
clarificação** (3 de `/speckit-specify` + 5 de `/speckit-clarify`), sem duplicatas de numeração,
sem marcadores pendentes, todas as seções obrigatórias preenchidas.

Todos os 16 itens deste checklist passam. A spec está pronta para `/speckit-plan`.

### Observação para o Constitution Check do plano

A constituição deste branch exige que o `plan.md` avalie explicitamente cada princípio. Três pontos
já são visíveis daqui e devem ser tratados lá, não aqui:

- **Princípio I** — processar lê evidência e escreve caso novo; FR-031 e SC-015 fixam a abertura em
  somente-leitura e a identidade bit a bit da origem.
- **Princípio II** — o caso produzido precisa ser indistinguível, para consulta, de um criado pela
  linha de comando; FR-027 e SC-008 cobrem, e o escopo exclui mexer em caso concluído (FR-040).
- **Princípio III** — a feature é aditiva por construção (capacidade nova, desabilitada por padrão),
  mas o planejamento precisa demonstrar que ela não altera `Manager`, `Worker` nem
  `ProcessingQueues`.
