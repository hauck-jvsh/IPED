# Contract — superfície de ferramentas MCP

**Feature**: [007-mcp-case-processing](../spec.md)

Quatro ferramentas novas e três efeitos sobre as existentes. Todas seguem os contratos já vigentes do
módulo: referência a caso sempre carrega o caso, ausência ≠ vazio, registro precede ação, classe de
conteúdo declarada em `ToolDescriptor.returnsContent`.

**Nenhuma delas aparece na superfície com `processingEnabled = false`** (FR-002).

---

## `iped_process_evidence`

Inicia um processamento. Devolve o identificador de trabalho e retorna — nunca aguarda a conclusão
(FR-018).

**Argumentos**

| Nome | Obrigatório | Notas |
|---|---|---|
| `source_path` | sim | Caminho **no sistema de arquivos do servidor** (FR-036) |
| `destination_path` | sim | Idem |
| `profile` | sim | MUST constar dos perfis declarados (FR-013) |
| `display_name` | não | Nome de exibição da evidência no caso (FR-014) |
| `secret_ref` | não | Nome resolvido do lado do servidor. **Nunca a senha** (FR-015) |

Argumento desconhecido é **recusa**, não é ignorado (FR-016).

**Resultado**: `job_id`, `state`, `case_path`, `paths_are_server_side: true`, `disk_warning` quando
FR-044 advertiu, e `secret_exposure_notice` sempre que `secret_ref` foi usado (FR-050) — a senha vai
ao motor por linha de comando e fica legível a outras contas da máquina enquanto o processo existe.

O aviso não é ornamento: é o que converte uma limitação conhecida em decisão informada de quem
implanta. A alternativa que o plano chegou a prever — fechar a exposição alterando o `iped-app` — foi
recusada por proporção, e o gatilho para revê-la está no [plan.md](../plan.md).

**Recusas**: `PROCESSING_DISABLED`, `SOURCE_NOT_PERMITTED`, `SOURCE_AREA_UNAVAILABLE`,
`DESTINATION_NOT_PERMITTED`, `DESTINATION_HAS_CASE`, `APPEND_NOT_SUPPORTED`, `PROFILE_NOT_PERMITTED`,
`JOB_ALREADY_RUNNING`, `SECRET_UNRESOLVED`.

`DESTINATION_HAS_CASE` e `APPEND_NOT_SUPPORTED` são **distintos de propósito** (FR-040): o primeiro é
destino ocupado, o segundo é fronteira de escopo. Fundi-los faria uma decisão de escopo se ler como
defeito.

---

## `iped_job_status`

Avanço ou desfecho de um trabalho. Funciona em qualquer sessão, não só na que iniciou (FR-022).

**Argumentos**: `job_id` (obrigatório).

**Resultado**: `state`, `progress`, `outcome` quando terminal, `log_path` sempre.

`progress.measurable = false` declara que a fase corrente não produz medida. **Percentual ausente é
ausente, não zero** — a invariante de ausência ≠ vazio vale aqui como em todo o módulo, e é o que
SC-007 mede.

`outcome.diagnostic_excerpt`, em falha, é **conteúdo derivado de evidência**: declarado em
`ToolDescriptor.returnsContent` e sujeito à política de egresso pela mesma fronteira que governa texto
de item (FR-043). Não há caminho próprio — foi o ponto que a clarificação Q3 fixou.

**Recusa**: `UNKNOWN_JOB`, que por FR-045 significa exatamente "nunca existiu nesta instalação", nunca
"existiu e foi descartado".

---

## `iped_cancel_job`

Interrompe um trabalho em andamento.

**Argumentos**: `job_id` (obrigatório).

**Autoridade**: **qualquer sessão autorizada**, independente de ter iniciado o trabalho (FR-023). Não
há posse a exigir — a identidade de sessão de rede é alegação não verificada por FR-032 de 006, e
autoridade construída sobre ela seria de fachada. A defesa é o registro de `cancelled_by`.

**Resultado**: `state: CANCELLED`, `remaining_at_destination`.

**Contrato de execução**: destrói a **árvore** de processos, não o filho (R6). `Bootstrap` gera um
neto, e quem lê evidência é o neto; matar só o filho deixaria o motor lendo evidência depois de o
trabalho ser declarado encerrado. Isso torna a verificação um teste de Princípio I, não só de tempo.

---

## `iped_resume_job`

Retoma um trabalho interrompido, aproveitando o que já foi processado (FR-030).

**Argumentos**: `job_id` (obrigatório).

**Pré-condição**: estado `INTERRUPTED`. `FAILED` só é retomável quando o desfecho marcou
`resumable: true`. Estados terminais recusam com `JOB_NOT_RESUMABLE`.

O `job_id` é **mantido** — dois identificadores para a mesma evidência partiriam a história que FR-034
exige reconstituir.

---

## Efeitos sobre ferramentas existentes

### `iped_session_info` — estendida

Passa a declarar a postura de processamento (FR-004): `processing_enabled`, `source_areas` com
disponibilidade corrente de cada uma, `case_roots`, `allowed_profiles`, `active_job`.

### `iped_open_case` — diagnóstico mais preciso

O mecanismo de recusa **já existe**: `CaseValidator` lança `CASE_IN_PROCESSING` quando o índice está
na pasta temporária, e `CASE_INCOMPLETE` quando falta `iped/index`, `iped/data`, `iped/lib` ou o ponto
de commit (R4). FR-028 está satisfeito hoje.

O que muda é a qualidade do diagnóstico: com `JobStore`, a recusa passa a nomear o trabalho conhecido
para aquele destino, seu desfecho e se a retomada é possível — em vez de dizer apenas "reprocesse o
caso".

### Advertência de abertura de sessão — estendida

Declara que o processamento está habilitado (FR-005), junto do que FR-043 de 001 e a advertência de
transporte da 006 já exigem.

---

## O que a orientação do agente passa a ensinar

`SKILL.md` já tem a seção "Paths belong to the server", escrita na 006 para o caminho do caso. FR-037
estende a lição a uma classe nova de caminho: **evidência e destino**.

A falha a evitar é a mesma diagnosticada na 006, e é silenciosa: o agente lê um caminho do Windows,
percebe que está em Linux, conclui que a evidência não existe e sai procurando no sistema de arquivos
do próprio ambiente isolado. Nenhum passo produz erro — o agente simplesmente nunca chama a ferramenta
que teria funcionado, e o perito vê uma sessão que "não achou a evidência".

A regra a ensinar é a mesma: **a única forma de saber se um caminho de evidência serve é passá-lo à
ferramenta e ler a resposta.**
