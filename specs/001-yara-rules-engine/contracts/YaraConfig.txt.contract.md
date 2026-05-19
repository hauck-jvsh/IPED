# Contract — `conf/YaraConfig.txt`

**File path (release)**: `iped-app/resources/config/conf/YaraConfig.txt` (canonical) e `profiles/*/conf/YaraConfig.txt` (override por perfil).

**Format**: `UTF8Properties` (mesmo padrão dos demais `*.txt` em `conf/`), uma chave por linha, `key = value`, comentários iniciados por `#`. Charset **sempre** UTF-8 (Princípio IV).

**Loaded by**: `iped.engine.config.YaraConfig` (Configurable), via `ConfigurationManager.findObject(YaraConfig.class)`.

---

## Keys

| Key | Type | Default | Validation | Description |
|---|---|---|---|---|
| `ruleDirectories` | path list (`;` separator no Windows, `:` no Linux — mesmo padrão de outros configs) | *(vazio)* | Cada path existe e é diretório legível. | Diretórios varridos recursivamente em busca de `.yar`, `.yara`, `.yarc`. Vazio efetivamente desliga a feature (mesmo com `enabled=true` em `IPEDConfig.txt`). |
| `maxFileSizeBytes` | integer com sufixo (`K`, `M`, `G`) | `250M` | > 0 | Itens com `length` acima são pulados; contabilizados em "skipped". |
| `perItemTimeoutMs` | integer | `30000` | ≥ 100 | Scan que exceda o timeout é interrompido; item marcado como skipped (sem propagar exception). |
| `scanAllItems` | boolean (`true`/`false`) | `false` | — | `true` força tentativa em todos os `IItem` (inclusive sem stream binário). `false` mantém default seletivo (R-06). |
| `fastMode` | boolean | `true` | — | Mapeia para `SCAN_FLAGS_FAST_MODE` do libyara. Em `true`, libyara aborta uma regra ao primeiro match (suficiente para nosso caso). `false` produz **todos** os matches por regra (mais detalhe; ~30% mais lento). |
| `matchHexMaxBytes` | integer | `256` | > 0; ≤ 65536 | Quantidade máxima de bytes brutos persistidos por matched-string. Excesso é truncado e marcado (`truncated=true`). |
| `engineLibraryHint` | optional path | *(vazio)* | Path para arquivo `.dll`/`.so` se presente | Caminho explícito para `libyara` quando o autodetect em `tools/yara/<os>/` precisa ser sobrescrito (debug/dev). Em produção fica vazio. |

---

## Example (`YaraConfig.txt`)

```properties
# YARA Rules Engine — IPED
#
# Diretórios contendo .yar, .yara e/ou .yarc.
# Aceita vários paths separados por path-separator do SO.
ruleDirectories = ${IPED_HOME}/yara-rules;${IPED_HOME}/yara-rules-vendor

# Limites operacionais
maxFileSizeBytes = 250M
perItemTimeoutMs = 30000

# Comportamento de scan
scanAllItems = false
fastMode = true

# Detalhe do match persistido
matchHexMaxBytes = 256

# Diagnóstico (deixe vazio em produção)
# engineLibraryHint =
```

---

## Behavior contract

- **Loading**: arquivo lido uma única vez no startup do `Manager`. Mudanças em runtime **não** têm efeito (Princípio III: estado declarado).
- **Validation failures**: chave com tipo inválido produz `ERROR` no log e a feature **fica desabilitada** para esse caso (não aborta o IPED).
- **Missing file**: ausência de `YaraConfig.txt` equivale a "feature desabilitada", mesmo que `IPEDConfig.txt` tenha `enableYara=true`. Log WARN único.
- **Override por perfil**: se `profiles/<X>/conf/YaraConfig.txt` existir, sobrescreve **chaves presentes** e mantém o resto do default canônico (semântica padrão de `UTF8Properties` override).
- **Path resolution**: `${IPED_HOME}` é expandido para a raiz do release. `~` não é expandido (consistente com o resto do IPED).
- **Symlinks**: seguidos (default do `Files.walk`); ciclos detectados via `FileVisitOption.FOLLOW_LINKS` + tracking.
