# Phase 0 — Research

**Feature**: YARA Rules Engine para IPED
**Date**: 2026-05-19

Este documento consolida as decisões técnicas que destravam o Plan e elimina todos os "NEEDS CLARIFICATION" residuais identificados em Technical Context. Cada decisão segue o formato **Decision / Rationale / Alternatives considered**.

---

## R-01 — Forma de embarcar a engine YARA (**revisada — YARA-X**)

**Decision (2026-05-19, revisão)**: Carregar **YARA-X 1.x** (reescrita Rust do YARA por Victor M. Alvarez) via `libyara-x-capi` como biblioteca nativa **in-process**, via JNA. Os binários (`yara_x_capi.dll` para Windows x64; `libyara_x_capi.so` para Linux x64) ficam em `tools/yara-x/<os>/` e são consumidos por bindings JNA escritos neste repositório.

**Por que mudou** (a v1 anterior elegia libyara 4.x):
- O upstream do YARA clássico (`VirusTotal/yara`) entrou em **modo manutenção** — novas features e melhorias passaram a sair só no YARA-X.
- O release oficial do YARA clássico **não publica `libyara.dll` pré-compilada** para Windows; só os executáveis estáticos. Forçaria o IPED a manter um build próprio da DLL ou a depender de MSYS2. O YARA-X, em contraste, publica `yara_x_capi.dll` self-contained nos releases oficiais — alinhado com o padrão de outras ferramentas do `tools/` (Sleuthkit, Tesseract).
- A linguagem de regras é **~99% retrocompatível** com catálogos clássicos; o flag `YRX_RELAXED_RE_SYNTAX` cobre o gap residual de regex (catálogos legados que usam construções regex aceitas pelo clássico mas estritas pelo Rust regex engine).
- A C API (`yrx_*`) é estável e expõe **mais informação ao chamador** (callbacks separados para patterns/matches/metadata) do que a libyara 4.x, o que simplifica a extração de match detail no futuro.

**Rationale (parte que continua valendo da decisão original)**:
- **Performance (SC-001)**: scan em-processo é mandatório. Spawn por item está fora de cogitação.
- **Stream do `IItem`**: `yrx_scanner_scan(scanner, data, len)` opera sobre buffer — combina com `IItem.getBufferedInputStream()`. Não precisamos materializar carved items ou subitens em arquivos temporários.
- **Mantém padrão IPED**: in-process para componente de risco baixo (leitor de patterns, não parser de conteúdo hostil).

**Alternatives considered**:

| Opção | Por que rejeitada |
|---|---|
| **Continuar com libyara 4.x (decisão anterior)** | Upstream em manutenção; Windows DLL exige build manual; YARA-X é o caminho oficial daqui em diante. Mais detalhes em "Por que mudou" acima. |
| **Spawn da CLI `yara-x`** | Mesmos problemas do clássico: overhead por item, subitens/carved sem path no host. |
| **Aguardar binding Java oficial do YARA-X** | Não existe e não está no roadmap. Bindings finos via JNA contra a C API é o caminho idiomático no ecossistema Java. |
| **JNI handwritten** | Custo de build cross-platform alto demais para ganho marginal sobre JNA em chamadas raras. |

**Notas operacionais**:
- Versão pinned: **YARA-X 1.x** (release mais recente compatível com a feature, congelada por release do IPED). CI Linux faz download do release oficial em `tools/yara-x/linux64/`. Build Windows idem em `tools/yara-x/win64/`.
- Módulos `pe`, `elf`, `math`, `hash`, `magic`, `dotnet`, `time` vêm habilitados no release oficial.
- `cuckoo` é **banido em runtime** via `yrx_compiler_ban_module(compiler, "cuckoo", title, msg)` — regras com `import "cuckoo"` produzem erro isolado captado pelo JSON de `yrx_compiler_errors_json`, a regra é descartada e o caso prossegue (FR-005, R-09).

---

## R-02 — Bindings: superfície mínima da C API do YARA-X usada

**Decision**: Bindings JNA do `YaraEngine` expõem **apenas**:

| Função `libyara-x-capi` | Uso |
|---|---|
| `yrx_compiler_create(flags, &compiler)` | Por catálogo, no `init()` da `YaraScanTask`. Usa flag `YRX_RELAXED_RE_SYNTAX` para aceitar regex de catálogos clássicos. |
| `yrx_compiler_destroy(compiler)` | Pareado com o create. |
| `yrx_compiler_ban_module(compiler, "cuckoo", ...)` | Garante que regras com `import "cuckoo"` sejam rejeitadas com mensagem clara (R-09). |
| `yrx_compiler_new_namespace(compiler, ns)` | Antes de cada `add_source_with_origin` — define o namespace = basename do arquivo. |
| `yrx_compiler_add_source_with_origin(compiler, src, origin)` | Adiciona o conteúdo de cada `.yar`/`.yara` (lido em UTF-8 pelo lado Java). `origin` = caminho absoluto, usado nas mensagens de erro. |
| `yrx_compiler_errors_json(compiler, &buf)` | Após `add_source_with_origin` retornar erro: lê o JSON com a lista de erros e popula o `CompileErrorSink`. |
| `yrx_compiler_build(compiler)` | Materializa `YRX_RULES*`. Retorna NULL se nenhuma regra compilou. |
| `yrx_rules_destroy(rules)` | No `finish()` do `Manager`. |
| `yrx_scanner_create(rules, &scanner)` | Por worker, uma vez. |
| `yrx_scanner_destroy(scanner)` | No `finish()` do worker. |
| `yrx_scanner_set_timeout(scanner, seconds)` | Aplica `YaraConfig.perItemTimeoutMs` convertido para segundos. |
| `yrx_scanner_on_matching_rule(scanner, callback, user_data)` | Instala o callback `YRX_RULE_CALLBACK` que popula `YaraMatch`. |
| `yrx_scanner_scan(scanner, data, len)` | Hot path — uma chamada por item. |
| `yrx_rule_identifier(rule, &ident, &len)` | Lê o nome da regra dentro do callback. |
| `yrx_rule_namespace(rule, &ns, &len)` | Lê o namespace dentro do callback. |
| `yrx_rule_iter_tags(rule, tag_callback, user_data)` | Lê as tags da regra (cada tag é `const char*`). |
| `yrx_buffer_destroy(buf)` | Libera buffers retornados por `errors_json`. |
| `yrx_last_error()` | Para mensagens de diagnóstico quando uma função retorna falha sem JSON. |

**Rationale**: cobre 100% do que `YaraScanTask` precisa para satisfazer FR-001 a FR-005, FR-012 e FR-014, sem expor superfície adicional que aumentaria a manutenção do binding. Funções de profiling, módulos customizados, variáveis globais e serialização de rulesets ficam **deliberadamente fora** da v1.

**Mudança importante de semântica em relação à libyara clássica**:
- A libyara clássica entregava match strings via `yr_rules_scan_mem` callback com mensagens `CALLBACK_MSG_RULE_MATCHING`. Para extrair os bytes/offsets era preciso caminhar pelas estruturas `YR_STRING`/`YR_MATCH` (opacas e fragéis).
- A C API do YARA-X expõe iteradores explícitos: `yrx_rule_iter_patterns(rule, cb, user)` → para cada pattern, `yrx_pattern_iter_matches(pattern, match_cb, user)` → `YRX_MATCH { offset, length }`. Os bytes do match não são entregues diretamente; lado Java faz `Arrays.copyOfRange(buffer, offset, offset+length)` sobre o buffer original (que controlamos integralmente).
- Resultado: extração de matched-string detail é **mais simples e estável** com YARA-X do que com libyara clássica. A v1 pode (e deve) implementar a coleta completa. A v1 do binding **atual** (entregue no slice "Foundation + JNA") ainda popula `strings: []`; a captura completa fica para a próxima iteração — limitação documentada no Javadoc da classe.

**Alternatives considered**: expor toda a API `yrx_*` — rejeitado por princípio de mínima superfície.

---

## R-03 — Distribuição da `libyara-x-capi`

**Decision**:
- **Windows x64**: shipa `yara_x_capi.dll` em `tools/yara-x/win64/`. O upstream do YARA-X publica esse binário pré-compilado nos releases oficiais (`libyara-x-capi-vX.Y.Z-x86_64-pc-windows-msvc.zip`) — sem etapa de build manual.
- **Linux x64**: shipa `libyara_x_capi.so` em `tools/yara-x/linux64/`. Idem — release oficial fornece (`libyara-x-capi-vX.Y.Z-x86_64-unknown-linux-gnu.tar.gz`).
- **macOS / outros**: ausente. Em runtime, se `Native.load("yara_x_capi", ...)` falhar e nenhum binário em `tools/yara-x/<os>/` existir, a feature loga `WARN` único e fica desabilitada (FR-014). O resto do IPED continua.

**Vantagens em relação ao YARA clássico**:
- O upstream do YARA clássico **não publica `libyara.dll`** — só os executáveis estáticos `yara64.exe`/`yarac64.exe`. Forçaria o IPED a manter um build próprio. YARA-X resolve isso publicando os artefatos `*-capi-*.zip`/`tar.gz`.
- O binário é estático e self-contained (sem dependências dinâmicas extras), o que simplifica `tools/yara-x/<os>/` — não precisa shipar OpenSSL/libcrypto/libgcc juntos.

**Rationale**:
- Constituição "Restrições de Build, Ferramentas e Distribuição" exige que ferramentas externas sejam **distribuídas em `tools/`** e **não** dependam de PATH do sistema. YARA-X segue o mesmo padrão de Sleuthkit (`tools/sleuthkit/`) e Tesseract.
- Determinismo: o release define a versão exata da engine, evitando que casos rodados em hosts diferentes produzam matches divergentes por causa de versão de `libyara-x-capi` local.

**Alternatives considered**:
- **Exigir `libyara-x-capi` no PATH do host** — descartado por contrariar a constituição e por reprodutibilidade.
- **Empacotar como JAR com extração temporária** (estilo `sqlite-jdbc`) — mais limpo, mas exige manter um JAR de "yara-x-natives". Pode ser uma evolução futura; v1 usa o mesmo padrão dos demais binários nativos do IPED.

---

## R-04 — Threading e ciclo de vida do scanner

**Decision**:
- YARA-X **não tem `yr_initialize`/`yr_finalize`** análogos à libyara clássica — não há lifecycle de processo a gerenciar. (Existe `yrx_finalize()` para liberar recursos globais em shutdown, mas é opcional.)
- Compilação do catálogo ocorre **uma única vez** no primeiro `init()` chamado por qualquer worker (lock estático `AtomicBoolean`). O `YRX_RULES*` resultante é **compartilhado read-only** entre todos os workers.
- Cada worker cria seu próprio `YRX_SCANNER*` via `yrx_scanner_create(rules, &scanner)` — o C API do YARA-X é projetado para um scanner por thread (`YRX_SCANNER` não é thread-safe; `YRX_RULES` é, para uso read-only). O scanner mantém callback dedicado e o estado de match acumulado.
- Cada `YaraScanTask` (uma instância por worker) é dona do seu `YRX_SCANNER` e o destrói em `finish()`.
- `yrx_rules_destroy(rules)` no `finish()` do último worker.

**Rationale**: alinhado ao Princípio V (uma instância por worker; estado global em campos estáticos com lifecycle controlado); evita recompilar 500 regras por worker, o que dominaria o startup do caso.

**Alternatives considered**:
- Compilar por worker — descartado (custo de startup × N workers).
- Compartilhar um único `YRX_SCANNER` entre workers — descartado (não é thread-safe no YARA-X).

---

## R-05 — Schema do match persistido

**Decision**: para cada item com pelo menos um match, persistir em três campos Lucene:

1. **`yara:rule`** (texto, multi-valorado, indexado, armazenado) — uma entrada por regra casada, formato `namespace/rule_name`. Ex.: `apt28/apt28_loader_dropper`. Indexado para filtro por igualdade na UI (FR-008) e para busca textual.
2. **`yara:tag`** (texto, multi-valorado, indexado, armazenado) — união (set) das tags das regras casadas para o item. Ex.: `apt`, `malware`, `windows`.
3. **`yara:matches`** (texto, **stored only**, não indexado) — JSON serializado com a estrutura completa do match, **um objeto por item**:

   ```json
   {
     "items": [
       {
         "rule": "apt28_loader_dropper",
         "namespace": "apt28",
         "tags": ["apt", "windows"],
         "meta": { "author": "Florian Roth", "severity": "high" },
         "strings": [
           { "id": "$s1", "offset": 4096, "hex": "4d5a90000300..." },
           { "id": "$re1", "offset": 8192, "hex": "554e495f4944..." }
         ]
       }
     ],
     "engineVersion": "yara-x-1.16.0",
     "scannedBytes": 32768
   }
   ```

**Rationale**:
- **Princípio I (estabilidade)**: novos campos, sem renomeação de existentes. Prefixo `yara:` evita colisão com qualquer namespace presente.
- **Performance de UI**: `yara:rule` e `yara:tag` indexados permitem facetar e filtrar sem desserializar JSON. O JSON só é lido na visualização de detalhe do item.
- **Auditoria forense**: `engineVersion` (formato `yara-x-<versão>` — o C API do YARA-X não expõe a versão programaticamente, então é montada a partir da versão pinned no `tools/yara-x/README.md`) e `scannedBytes` registram contexto reprodutível.
- **Tamanho**: hex truncado para no máximo 256 bytes por string (configurável em `YaraConfig.matchHexMaxBytes`, default 256). String maior é prefixada e marcada com `"truncated": true` no JSON.

**Alternatives considered**:
- Persistir matched bytes como blob binário separado — adiciona um storage paralelo; rejeitado por contrariar "sem dependência de novo armazenamento" (Assumptions).
- Indexar `yara:matches` JSON — desperdício; ninguém vai fazer full-text search dentro do JSON serializado.

---

## R-06 — Detecção de item elegível ("scan tudo" vs default seletivo)

**Decision**:
- Default: scan se e somente se `IItem.getMediaType() != null` e `IItem.getLength() > 0` e `IItem.getBufferedInputStream()` retorna stream não-nulo. Cobre arquivos, subitens com payload, carved items. Exclui registros do Windows Registry, células SQLite isoladas, contatos sem corpo, etc.
- Override: `YaraConfig.scanAllItems = true` força tentativa em todos os itens (subindo `IItem.getLength()` for null/0 fallback para buffer vazio que retorna 0 matches sem custo de yara).

**Rationale**: o spec (Q2 = A) define exatamente isso. A condição é barata (sem `instanceof`, sem reflection); é só inspecionar atributos já materializados.

**Alternatives considered**:
- Filtrar por categoria (`IItem.getCategorySet()`) — frágil porque depende de `SetCategoryTask` ter rodado antes; e o usuário pode introduzir categorias customizadas que invalidam o filtro.

---

## R-07 — Estratégia de leitura do stream (chunked vs full)

**Decision**: ler o item inteiro em memória **se** `IItem.getLength() ≤ YaraConfig.maxFileSizeBytes` (default 250 MB). YARA opera melhor sobre buffer contíguo — `yr_rules_scan_mem` faz uma única varredura linear, e regras com `pe`/`elf` precisam do buffer completo para parse de headers. Itens acima do limite são pulados (FR-006) e contados em "skipped" (FR-012).

**Rationale**:
- Buffer contíguo é o caminho oficial recomendado pela libyara.
- 250 MB é compatível com hardware típico (workers do IPED rodam com `-Xmx32g`) e cobre >99% do material de interesse (executáveis, documentos, dumps de memória pequenos).
- Casos com necessidade de scan em arquivos maiores (imagens de disco inteiras, dumps de RAM grandes) ficam fora — esses normalmente são scaneados depois que o IPED já carveou o conteúdo interessante.

**Alternatives considered**:
- **Streaming chunked**: libyara expõe scan iterativo, mas regras com módulos (especialmente `pe`) precisam de buffer linear para parse de headers; suporte é parcial. Descartado.
- **Cap de 1 GB**: piora SC-001 e estoura memória em workers paralelos.

---

## R-08 — Modo "rerun YARA-only"

**Decision**: nova flag CLI `--yara-only` em `Bootstrap`. Quando presente, `Bootstrap` propaga para `processing.Main` que ajusta `CmdLineArgs` indicando ao `Manager` para:
1. Abrir o caso existente (`-d <case-output>` torna-se obrigatório).
2. Pular `DataSourceReader` (não há nova evidência a ingerir).
3. Iterar sobre os documentos do índice Lucene reabrindo o `IItem` via `IndexItem.getItem(doc)`.
4. Executar apenas a `YaraScanTask` para cada item.
5. Atualizar (sobrescrever) os campos `yara:rule`, `yara:tag`, `yara:matches` do documento Lucene via `IndexWriter.updateDocument(...)` — substituição integral (FR-011).

**Rationale**:
- Idiomático: outras ferramentas forenses expõem rerun como modo, não como config.
- Substituição integral evita matches "fantasma" de catálogos antigos.

**Alternatives considered**:
- Configurable `yaraOnlyRerun` — rejeitado por fragilidade operacional (Complexity Tracking).
- Nova subcommand (estilo `iped yara-rescan`) — possível evolução, mas exige refator maior do `Bootstrap`; v1 fica na flag.

---

## R-09 — Resposta a regras com `import "cuckoo"`

**Decision**: Logo após `yrx_compiler_create`, a `YaraEngine` chama `yrx_compiler_ban_module(compiler, "cuckoo", "Cuckoo module unsupported", "IPED does not bundle the cuckoo module; rules importing it are rejected.")`. Regras com `import "cuckoo"` produzem erro de compilação **dessa regra específica** com a mensagem exata fornecida; o erro é capturado via `yrx_compiler_errors_json(compiler, &buf)`, parseado em Java, logado como WARN e a regra é descartada. As demais do mesmo arquivo continuam.

**Rationale**: Q1 da Clarifications fixa exclusão do `cuckoo`. O YARA-X expõe `ban_module` exatamente para esse caso — não precisamos remover o módulo do build, basta proibir runtime. FR-002 + FR-005 honrados.

**Alternatives considered**:
- Compilar `libyara-x-capi` sem o módulo cuckoo — desnecessário; `ban_module` é a primitiva idiomática.
- Falhar o arquivo inteiro — viola FR-005.

---

## R-10 — Atualização do CI

**Decision**: `.github/workflows/maven.yml` ganha (em ambos os jobs Ubuntu 22.04) um step que baixa o tarball oficial do `libyara-x-capi` do release do GitHub e instala em `/usr/local/lib`:

```yaml
- name: Install libyara-x-capi
  run: |
    YARAX_VERSION="<pinned, ex: 1.x.y>"
    curl -L -o yara-x-capi.tar.gz \
      https://github.com/VirusTotal/yara-x/releases/download/v${YARAX_VERSION}/libyara-x-capi-v${YARAX_VERSION}-x86_64-unknown-linux-gnu.tar.gz
    sudo tar -xzf yara-x-capi.tar.gz -C /usr/local/
    sudo ldconfig
    ldconfig -p | grep yara_x_capi || echo "libyara-x-capi missing — integration tests will be skipped"
```

A versão é pinned em `tools/yara-x/README.md` e atualizada manualmente.

**Rationale**: Constituição §"CI" exige que dependências nativas novas atualizem o workflow no mesmo PR. YARA-X não está nos repos do apt (ainda); o tarball oficial é a forma idiomática de instalar.

**Alternatives considered**:
- `cargo install yara-x` no CI — exige Rust toolchain (~3 min extra), e mesmo assim só constrói o binário CLI, não a C API library. Rejeitado.
- Skipar testes que dependem de `libyara-x-capi` no CI — rejeitado (perde-se a cobertura mais valiosa do SC-004).

---

## R-11 — Licenciamento e ThirdParty

**Decision**:
- **YARA-X** é licenciado sob **BSD 3-clause** (idêntico ao YARA clássico) — compatível com a base do IPED (LGPL). `licenses/YARA-X.txt` é adicionado contendo o `LICENSE` upstream.
- `ThirdParty.txt` ganha bloco descrevendo: nome (YARA-X), versão (1.x — pinned), URL upstream (`https://github.com/VirusTotal/yara-x`), licença (BSD 3-clause), uso (engine de pattern matching, biblioteca `libyara-x-capi` embutida em `tools/yara-x/<os>/`).
- JNA é declarada explicitamente em `iped-engine/pom.xml` (versão 5.7.0 — alinhada com `iped-parsers-impl`); seu licenciamento é Apache 2.0 / LGPL 2.1 (dual), cobertos pelos `licenses/Apache 2.0.txt` e `licenses/LGPL 2.1.txt` já presentes.

**Rationale**: constituição "Restrições de Build" §Licenciamento exige registro em `ThirdParty.txt` e `licenses/`.

---

## R-12 — Estratégia de teste e validação

**Decision**:
- **Unit**: JUnit 4 em `iped-engine/src/test/java/iped/engine/task/yara/`. Fixtures de regras válidas, inválidas, com `.yarc`, com `import "cuckoo"`.
- **Ground-truth (SC-004)**: script de teste integração `IT-YaraVsCli.java` (anotação JUnit `@Category(Integration.class)`) compara saída de `YaraScanTask` contra `yara` CLI sobre 100 amostras controladas + 50 regras públicas. Falha o build se divergir.
- **Performance (SC-001 / SC-006)**: bench manual documentado em `quickstart.md` (não gate de CI por custo de execução).
- **Rerun (FR-011)**: teste de integração que (1) processa um caso pequeno com regras R1, (2) sobrescreve o catálogo com R2, (3) roda `--yara-only`, (4) verifica que os campos `yara:*` refletem R2 exclusivamente.

**Rationale**: Princípio IV (determinismo) + Princípio II (não tocar core) + SC-004 (paridade com CLI).

---

## R-13 — Localização das strings da UI

**Decision**: chaves novas em `iped-app/resources/localization/messages.properties` (EN) e `messages_pt_BR.properties` (PT-BR):
- `yara.filter.section` (título da seção no painel de filtros)
- `yara.match.rule` (label "Regra")
- `yara.match.tag` (label "Tag")
- `yara.match.offset` (label "Offset")
- `yara.match.bytes` (label "Bytes (hex)")
- `yara.report.section` (título no HTML report)
- `yara.task.name` / `yara.task.description` (label da task em UI de status)

**Rationale**: Princípio III §3 exige internacionalização PT-BR + EN.

---

## Unknowns → resolvidos

| Origem | Pergunta | Resolução |
|---|---|---|
| Plan §Technical Context | Engine YARA a embarcar | **YARA-X 1.x** via `libyara-x-capi` (R-01) — substitui a escolha inicial de libyara 4.x clássica. |
| Plan §Constraints | Manter ou não Princípio V para componente nativo | Justificado in-process com mitigações (Complexity Tracking + R-04). |
| Plan §Storage | Schema dos matches | R-05 (`yara:rule`, `yara:tag`, `yara:matches`). |
| Spec §Outstanding | Localização das novas strings UI | R-13. |
| Spec §FR-011 | Como expor o "rerun YARA-only" | R-08 (flag `--yara-only`). |
| Spec §FR-002 | Comportamento com regras inválidas | R-02 + R-09 + FR-005 (log warn + descarte da regra; cuckoo banido via `yrx_compiler_ban_module`). |
| Spec §FR-001 (revisão) | Aceitar pré-compilados? | **Não na v1** (Clarifications Q3 revisada). YARA-X compila rápido; o formato serializado é diferente do `.yarc` clássico e ainda estabilizando. |

Nenhum NEEDS CLARIFICATION resta para o Phase 1.
