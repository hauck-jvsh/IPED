# Specification Quality Checklist: Confinamento de escrita e transporte de rede para o servidor MCP

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-10
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

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`

### Iteração 1 — 2026-08-10 (redação inicial)

15 de 16 itens aprovados. Reprovado: "No [NEEDS CLARIFICATION] markers remain" — três marcadores
abertos por decisão, todos decorrentes de a decisão **D2 de 001** ("estação de trabalho individual")
ter dispensado autenticação, isolamento entre operadores e controle de concorrência remota. Essa
dispensa só era válida porque nenhuma configuração podia produzir sessão remota; a US2 remove a
premissa e nada no material existente dizia o que a substitui.

### Iteração 2 — 2026-08-10 (pós-clarificação)

**16 de 16 aprovados.** As três perguntas foram respondidas e incorporadas na seção Clarifications.

| Marcador | Resposta | Requisitos gerados |
|---|---|---|
| Autenticação e proteção do canal (FR-013) | Segredo compartilhado em configuração, canal em claro | FR-013 reescrito; FR-026, FR-027, FR-028 |
| Sessões concorrentes (FR-014) | Várias somente-leitura, no máximo uma com escrita por caso | FR-014 reescrito; FR-029, FR-030, FR-031 |
| Identidade do operador remoto (FR-020) | Ambas — servidor autoritativa, cliente como alegação | FR-020 reescrito; FR-032 |

**Uma consequência não antecipada foi encontrada ao incorporar a resposta de concorrência e virou
requisito próprio.** A trilha do módulo é **por sessão, não por caso** — limitação conhecida e
registrada em `iped-mcp/CLAUDE.md`. Com uma sessão por vez ela era inofensiva, porque a sequência de
sessões sobre um caso era total. Com sessões simultâneas, o histórico de um caso passa a estar
repartido entre trilhas paralelas e **FR-037 de 001 deixa de ser satisfeito por uma trilha isolada**.
FR-033 e SC-013 criados para cobrir isso. A quarta entrada da seção Clarifications registra o achado.

### Iteração 3 — 2026-08-10 (pós-pesquisa de Phase 0)

**16 de 16 mantidos.** A pesquisa de Phase 0 encontrou um defeito que o spec não cobria e o perito
autorizou incorporá-lo: **FR-034** e **SC-015**, sobre destino que aceita a escrita sem retê-la.

O achado é instrutivo sobre o limite da própria allow-list. `<raiz>\NUL` **não escapa da raiz** — FR-001
o aprova corretamente — e ainda assim o artefato não existe depois da escrita, com sucesso reportado e
`bytes: 0`. Confinamento de destino e integridade do resultado são garantias diferentes, e a primeira
não implica a segunda. Foi medido, não deduzido: ver [research.md](../research.md) R2.

O item "Edge cases are identified" foi reavaliado e continua aprovado, agora com a borda de dispositivo
reservado listada explicitamente.

### Iteração 4 — 2026-08-11 (pós-teste de campo do transporte)

**16 de 16 mantidos.** O servidor foi exercitado fora da suíte, contra a instalação real, com
transporte de rede ativo. Sete verificações passaram; uma reprovou e virou **FR-035** e **SC-016**.

O achado é sobre o que a suíte não estava olhando. O relay respondia as requisições corretamente e
não terminava quando o stdin acabava — e **fechar o stdin do processo filho é como todo harness
suportado sinaliza encerramento**, então isso não era borda, era o caminho normal de saída. FR-017
governa o servidor e estava satisfeito: a conexão não caía, logo não havia nada a liberar. Ninguém
havia dito que o intermediário precisa propagar o encerramento.

Por que passou despercebido: **um relay pendurado passa em qualquer teste de requisição/resposta**,
porque as respostas estão certas. O que falha é o encerramento, e nada exercitava encerramento.
`RelayShutdownTest` e o cenário Q11 do quickstart passam a exercitar.

Nenhum requisito existente foi relaxado. A numeração vai a FR-001–035 e SC-001–016.

### Verificações transversais

**Vazamento de implementação**: o termo "socket", presente no pedido original, aparece apenas no campo
`Input` (transcrição literal). Requisitos usam "transporte por conexão de rede" e "ponto de escuta";
critérios de sucesso descrevem comportamento observável — porta aberta, recusa de gravação,
equivalência de resultados, bloqueio mútuo entre sessões — sem nomear mecanismo. "Segredo
compartilhado" é modelo de confiança, não tecnologia.

**Numeração**: FR-001 a FR-035 e SC-001 a SC-016, ambos contíguos e sem lacunas. Requisitos de 001
são sempre citados com origem explícita ("FR-068 de 001"), convenção declarada na seção *Relação com
a spec 001*.

**Compatibilidade com 001**: verificada requisito a requisito para FR-028, FR-032, FR-034, FR-036,
FR-037, FR-041, FR-042, FR-043, FR-055, FR-057, FR-068, FR-073, FR-078 e SC-003, SC-005, SC-010,
SC-011, SC-013, SC-015. Nenhum removido ou relaxado. Quatro estendidos, com a extensão nomeada no
requisito correspondente: FR-043 (advertência de abertura), FR-055 (credenciais fora de arquivo
versionado), FR-057 (exposição de rede fora da configuração padrão) e FR-068 (destino do artefato).
A decisão **D2 de 001** é a única que esta feature substitui, e a substituição está escrita.

### Ponto de atenção para o `/speckit-plan`

O **Constitution Check** precisa avaliar, no mínimo, o Princípio V (nada implícito no que varia por
ambiente) contra FR-011, FR-012 e FR-026 — ponto de escuta e segredo declarados, nunca herdados de
padrão de plataforma — e a **restrição de plataforma Java 11** contra qualquer capacidade de rede ou
de resolução de caminho que se pretenda adotar.
