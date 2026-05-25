# Specification Quality Checklist: YARA Rules Engine para IPED

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-05-19
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

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`.
- The spec preserves IPED-native vocabulary (perito, perfil, subitem, carved item, bookmark) without leaking implementation details (no class names, no library names, no module paths).
- FR-013 and SC-005 enforce that the feature is fully opt-in and reversible via existing configuration, which is consistent with the IPED CLAUDE.md guidance ("prefer adding a task with its Configurable to modifying existing ones").
- `/speckit-clarify` Session 2026-05-19 closed 5 open decisions (YARA version & modules, item-scope default, `.yarc` support, match persistence depth, rerun granularity). All recorded in `spec.md` → Clarifications.
- One residual concern (Outstanding, low-impact): localization of new UI strings (pt-BR/EN). Convention to follow [iped-app/resources/localization/](../../../iped-app/resources/localization/) — can be enforced at `/speckit-plan` time without further clarification.

## Manual validation log (post-implementation)

### 2026-05-22 — caso `F:\yara-test` (438.708 itens)

**US1 (processamento) ✓**

```
INFO  YaraScanTask scan summary:
  itemsScanned    : 438708
  itemsWithMatches: 11694
  matchesTotal    : 16271
  itemsSkipped    : 58 (size=58, no-stream=0, error=0)
```

Métricas saudáveis: 2.66% match rate, 1.39 matches/item médio, **zero crashes nativos** por item (`error=0`), só 58 itens pulados por exceder `maxFileSizeBytes` (250 MB).

**US2 (UI: faceta + filtro + bookmark) ✓**

Screenshot anexado no roteiro manual `manual-tests/us2-ui-filter.md`. Validado:
- Grupo "YARA matches" aparece no combobox de propriedades.
- `yara:rule` lista regras casadas com contagens (`suspicious_strings/browsers (71)`, `crypto_signatures/big_numbers1 (43)`, etc.).
- Filtro reduz galeria/tabela aos itens da regra selecionada.
- `yara:tag = AntiVM` visível no painel Metadata do item selecionado.
- Bookmark "Yara browsers" criado a partir da seleção filtrada; itens persistidos corretamente.

Limitação conhecida confirmada na prática: `yara:matches` aparece como JSON cru no painel de detalhes (viewer pretty-print fica para próxima iteração).

**US3 (`--yara-only` rev-2) ✓**

Validado após o pivot arquitetural (ver research.md §R-08). Pipeline rodou até o fim, `IndexTask.updateDocuments` reescreveu os docs commitados sem schema conflict Lucene, a regra nova adicionada ao catálogo entre os runs apareceu na faceta UI após reabrir o caso. A v1 standalone (`YaraRerunRunner`) tinha falhado com NPE em `Item.setName` + schema mismatch `SORTED` vs `SORTED_SET`; foi removida.

**Deferidos** (não bloqueantes para v1):
- T046 (benchmark formal SC-001/SC-006): exige hardware/janela dedicado; validação funcional já cobre o que importa.
- T047 §5 (HTML report) e §7 (paridade com `yara-x` CLI): polish ortogonal; reabrir em rodada futura.
