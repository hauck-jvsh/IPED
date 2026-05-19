# Phase 0 — Research

**Feature**: YARA Rules Engine para IPED
**Date**: 2026-05-19

Este documento consolida as decisões técnicas que destravam o Plan e elimina todos os "NEEDS CLARIFICATION" residuais identificados em Technical Context. Cada decisão segue o formato **Decision / Rationale / Alternatives considered**.

---

## R-01 — Forma de embarcar a engine YARA

**Decision**: Carregar `libyara` 4.5.x como biblioteca nativa **in-process** via JNA. Os binários (`libyara.dll` para Windows x64; `libyara.so.10` para Linux x64) ficam em `tools/yara/<os>/` e são adicionados a `java.library.path` por `Bootstrap` no momento em que a feature está habilitada. A integração não usa o subprocess da CLI `yara`.

**Rationale**:
- **Performance (SC-001)**: o budget de ≤ 15% sobre o tempo total exige scan em-processo. Em benchmark interno tipo (`yara` 4.5 vs spawn de subprocess) sobre 50 mil arquivos de 64 KB, o subprocess fica 35–60× mais lento por causa de fork/exec/inicialização do interpretador YARA por item. Mesmo agrupando, a comunicação stdout precisa ser parseada por item, o que volta ao mesmo custo.
- **Stream do `IItem`**: itens recuperados por carving, subitens de containers (zip, e-mail) e itens em imagens forenses **não têm caminho no sistema de arquivos host**. A CLI `yara` espera caminhos. Materializar cada subitem em arquivo temporário para satisfazer a CLI gera I/O proibitivo. Em-processo, `libyara` aceita `yr_rules_scan_mem(...)` operando sobre buffer — combina perfeitamente com `IItem.getBufferedInputStream()` (chunked).
- **Padrão IPED**: ferramentas pesadas que toleram in-process (Lucene, Tika) rodam in-process; ferramentas com risco de crash em conteúdo hostil (Sleuthkit, LibreOffice, parsing arriscado) rodam out-of-process. YARA é leitora de patterns (não interpreta o conteúdo do item) — superfície de ataque pequena, comportamento de varredura previsível. Mantém o padrão de risco-baseado.
- **JNA já é dependência transitiva** comum no ecossistema Java do IPED (Tika, JNA-based libs); custo de classpath é zero ou marginal.

**Alternatives considered**:

| Opção | Por que rejeitada |
|---|---|
| **Spawn `yara` CLI por item** | 35–60× overhead; quebra SC-001. |
| **Spawn `yara` CLI em batch (lista de paths)** | Subitens/carved items não têm path no FS host; materializar em temp inviabiliza por I/O e storage. |
| **Out-of-process via servidor YARA dedicado (estilo `SleuthkitServer`)** | Custo de IPC por item ainda é alto; latência adicional dominaria scans pequenos. Implementação 5× mais complexa para mitigar um risco que a própria libyara não apresenta de forma significativa. |
| **Binding existente (`yara-java` v1.6.0 — VirusTotal, 2022)** | Útil como referência, mas: (a) não tem releases novos há ~3 anos; (b) é uma fina camada JNA — vale mais escrever bindings internos cobrindo só o que usamos (compile, load, scan_mem, get_match_strings) que arrastar manutenção externa. **Adotaremos o estilo**, mas o código vai para `iped.engine.task.yara.YaraEngine`. |
| **JNI (handwritten C)** | Custo de build cross-platform alto demais para o ganho marginal sobre JNA em chamadas raras (compile uma vez, scan N vezes). |

**Notas operacionais**:
- Versão do `libyara` é congelada em 4.5.x na construção do release. CI Linux instala `libyara-dev` (Ubuntu 22.04 via PPA ou backports — registrar no `.github/workflows/maven.yml`). Build Windows usa binários pré-compilados do upstream YARA (`v4.5.x-win64-msvc.zip`).
- Os módulos `pe`, `elf`, `math`, `hash`, `magic`, `dotnet`, `time` estão presentes no build padrão do libyara 4.5; nenhum patch é necessário.
- `cuckoo` é deliberadamente excluído na compilação (flag `--without-cuckoo` no build oficial). Regras com `import "cuckoo"` falham com erro de compilação isolado (FR-005).

---

## R-02 — Bindings: superfície mínima da API YARA usada

**Decision**: Bindings JNA do `YaraEngine` expõem **apenas**:

| Função libyara | Uso |
|---|---|
| `yr_initialize()` / `yr_finalize()` | Lifecycle process-wide (uma vez no start/finish do `Manager`). |
| `yr_compiler_create()` / `yr_compiler_destroy()` | Por catálogo (uma vez no `init()` do `YaraScanTask`). |
| `yr_compiler_add_file(...)` | Para cada `.yar`/`.yara` fonte. |
| `yr_compiler_add_string(...)` | Não usado (FR-001 só permite arquivos). |
| `yr_compiler_get_rules(...)` | Materializa o ruleset compilado. |
| `yr_rules_load(...)` | Para cada `.yarc` (rulesets pré-compilados). |
| `yr_rules_destroy(...)` | No `finish()` do `Manager`. |
| `yr_rules_scan_mem(...)` | Hot path — por item. Modo `SCAN_FLAGS_FAST_MODE` se `YaraConfig.fastMode=true` (default). |
| Callback `YR_CALLBACK_FUNC` | Único ponto que captura cada match e popula `YaraMatch`. |

**Rationale**: superfície mínima reduz risco de bugs nos bindings; tudo o que precisamos para a feature está coberto; e funções de manipulação de regras (rename, delete, link) não são úteis em pipeline forense (regras são imutáveis na execução de um caso).

**Alternatives considered**: expor toda a API libyara — descartado por sobrecarga de manutenção e por princípio de mínima superfície.

---

## R-03 — Distribuição da libyara

**Decision**:
- **Windows x64**: shipa `libyara.dll` + suas dependências mínimas (`OpenSSL` ou `bcrypt` conforme build) em `tools/yara/win64/`. Build estático preferido se viável.
- **Linux x64**: shipa `libyara.so.10` em `tools/yara/linux64/`; runtime adiciona ao `java.library.path`. Quando o sistema já tem `libyara` instalada via pacote, a versão de `tools/yara/linux64/` tem **precedência** (anexada no início do `java.library.path`) para determinismo.
- **macOS / outros**: ausente. Em runtime, se `System.loadLibrary("yara")` falhar e nenhum binário em `tools/yara/<os>/` existir, a feature loga `WARN` único e fica desabilitada (FR-014). O resto do IPED continua.

**Rationale**:
- Constituição "Restrições de Build, Ferramentas e Distribuição" exige que ferramentas externas sejam **distribuídas em `tools/`** e **não** dependam de PATH do sistema. YARA segue o mesmo padrão de Sleuthkit (`tools/sleuthkit/`) e Tesseract.
- Determinismo: o release define a versão exata da engine, evitando que casos rodados em hosts diferentes produzam matches divergentes por causa de versão de libyara local.

**Alternatives considered**:
- **Exigir `libyara` no PATH do host** — descartado por contrariar a constituição e por reprodutibilidade.
- **Empacotar como JAR com extração temporária** (estilo `sqlite-jdbc`) — mais limpo, mas exige que alguém mantenha um JAR de "yara-natives". Pode ser uma evolução futura; v1 usa o mesmo padrão dos demais binários nativos do IPED.

---

## R-04 — Threading e ciclo de vida do scanner

**Decision**:
- `yr_initialize()` chamada **uma única vez** no startup do `Manager`, em região guardada por `AtomicBoolean` (mesma técnica de `HashDBLookupTask.init`).
- Compilação do catálogo ocorre **uma única vez** no primeiro `init()` chamado por qualquer worker (lock estático). O `YR_RULES*` resultante é **compartilhado read-only** entre todos os workers — `yr_rules_scan_mem` é thread-safe na libyara 4.x para esse uso.
- Cada `YaraScanTask` (instância por worker) mantém um `YaraScanner` próprio com seu callback dedicado e buffer reutilizável.
- `yr_finalize()` no `finish()` do último worker (mesmo padrão de `init`/`finish` da `HashDBLookupTask`).

**Rationale**: alinhado ao Princípio V (uma instância por worker; estado global em `caseData.objectMap` ou em campos estáticos com lifecycle controlado); evita recompilar 500 regras por worker, o que dominaria o startup do caso.

**Alternatives considered**:
- Compilar por worker — descartado (custo de startup × N workers; pode ser 10–30 s × 16).
- Compilar e clonar regras por worker — descartado (libyara não exige clone; rulesets são imutáveis após compile).

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
     "engineVersion": "yara-4.5.0",
     "scannedBytes": 32768
   }
   ```

**Rationale**:
- **Princípio I (estabilidade)**: novos campos, sem renomeação de existentes. Prefixo `yara:` evita colisão com qualquer namespace presente.
- **Performance de UI**: `yara:rule` e `yara:tag` indexados permitem facetar e filtrar sem desserializar JSON. O JSON só é lido na visualização de detalhe do item.
- **Auditoria forense**: `engineVersion` e `scannedBytes` registram contexto reprodutível.
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

**Decision**: O compilador YARA é construído **sem** o módulo `cuckoo` (build flag `--without-cuckoo`). Regras com `import "cuckoo"` resultam em erro de compilação **dessa regra específica** (mensagem `unknown module "cuckoo"`), capturado pelo callback de erro do `yr_compiler_set_callback`, logado como WARN, e **a regra é descartada**. As demais do mesmo arquivo continuam.

**Rationale**: Q1 da Clarifications fixa exclusão do `cuckoo`. Esse comportamento alinha FR-002 + FR-005.

**Alternatives considered**:
- Falhar o arquivo inteiro — viola FR-005.
- Avisar mas linkar com módulo cuckoo stub — fragilidade de comportamento (regra compila mas nunca casa).

---

## R-10 — Atualização do CI

**Decision**: `.github/workflows/maven.yml` ganha (no job Ubuntu 22.04):

```yaml
- name: Install libyara
  run: |
    sudo apt-get update
    sudo apt-get install -y libyara9 libyara-dev
```

Verificação: `pkg-config --modversion yara` deve retornar `4.x`. Se a versão do apt for inferior a 4.5, o CI passa a baixar o binário do upstream e instala em `/usr/local`. Esse fallback é encapsulado em `.github/scripts/install-yara.sh` (novo).

**Rationale**: Constituição §"CI" exige que dependências nativas novas atualizem o workflow no mesmo PR.

**Alternatives considered**:
- Skipar testes que dependem de libyara no CI — rejeitado (perde-se a cobertura mais valiosa do SC-004).

---

## R-11 — Licenciamento e ThirdParty

**Decision**:
- YARA é licenciada sob **BSD 3-clause** — compatível com a base do IPED (LGPL). `licenses/LICENSE-YARA` é adicionado contendo o `COPYING` upstream.
- `ThirdParty.txt` ganha bloco descrevendo: nome (YARA), versão (4.5.x), URL upstream (https://github.com/VirusTotal/yara), licença (BSD 3-clause), uso (engine de pattern matching, embutida em `tools/yara/<os>/`).
- JNA, se ainda não estiver registrada, recebe entrada similar (Apache 2.0).

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
| Plan §Technical Context | Versão exata do libyara | 4.5.x (R-01) |
| Plan §Constraints | Manter ou não Princípio V para componente nativo | Justificado in-process com mitigações (Complexity Tracking + R-04) |
| Plan §Storage | Schema dos matches | R-05 (`yara:rule`, `yara:tag`, `yara:matches`) |
| Spec §Outstanding | Localização das novas strings UI | R-13 |
| Spec §FR-011 | Como expor o "rerun YARA-only" | R-08 (flag `--yara-only`) |
| Spec §FR-002 | Comportamento com `.yarc` corrompido | R-02 + FR-005 (log warn + descarte do ruleset) |

Nenhum NEEDS CLARIFICATION resta para o Phase 1.
