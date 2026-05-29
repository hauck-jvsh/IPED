# Contract — `conf/YaraConfig.txt`

**File path (release)**: `iped-app/resources/config/conf/YaraConfig.txt` (canonical) e `profiles/*/conf/YaraConfig.txt` (override por perfil).

**Format**: `UTF8Properties` (mesmo padrão dos demais `*.txt` em `conf/`), uma chave por linha, `key = value`, comentários iniciados por `#`. Charset **sempre** UTF-8 (Princípio IV).

**Loaded by**: `iped.engine.config.YaraConfig` (Configurable), via `ConfigurationManager.findObject(YaraConfig.class)`.

---

## Keys

| Key | Type | Default | Validation | Description |
|---|---|---|---|---|
| `ruleDirectories` | path list (`;` separator no Windows, `:` no Linux — mesmo padrão de outros configs) | *(vazio)* | Cada path existe e é diretório legível. | Diretórios varridos recursivamente em busca de `.yar` e `.yara` (regras-fonte). Formatos pré-compilados (`.yarc` do YARA clássico ou serialização própria do YARA-X) **não são aceitos na v1**. Vazio efetivamente desliga a feature (mesmo com `enabled=true` em `IPEDConfig.txt`). Cada path aceita 3 formas: **(a)** caminho absoluto (`C:\rules\yara`, `/opt/iped/rules`); **(b)** token `${IPED_HOME}` no início, expandido em runtime para a raiz da instalação do IPED (recomendado para portabilidade entre máquinas/usuários); **(c)** caminho relativo bare (`yara-rules`), resolvido a partir do diretório de trabalho do JVM — funciona quando o IPED é iniciado pela pasta de instalação, mas é frágil em outros cenários — prefira `${IPED_HOME}/...`. |
| `maxFileSizeBytes` | integer com sufixo (`K`, `M`, `G`) | `250M` | > 0 | Itens com `length` acima são pulados; contabilizados em "skipped". |
| `perItemTimeoutMs` | integer | `30000` | ≥ 100 | Scan que exceda o timeout é interrompido; item marcado como skipped (sem propagar exception). |
| `scanAllItems` | boolean (`true`/`false`) | `false` | — | `true` força tentativa em todos os `IItem` (inclusive sem stream binário). `false` mantém default seletivo (R-06). |
| `matchHexMaxBytes` | integer | `256` | > 0; ≤ 65536 | Quantidade máxima de bytes brutos persistidos por matched-string. Excesso é truncado e marcado (`truncated=true`). |
| `engineLibraryHint` | optional path | *(vazio)* | Path para arquivo `.dll`/`.so` se presente | Caminho explícito para `libyara-x-capi` quando o autodetect em `tools/yara-x/<os>/` precisa ser sobrescrito (debug/dev). Em produção fica vazio. |

---

## Example (`YaraConfig.txt`)

```properties
# YARA Rules Engine — IPED (engine: YARA-X 1.x)
#
# Diretórios contendo .yar / .yara (regras-fonte).
# Aceita vários paths separados por path-separator do SO.
ruleDirectories = ${IPED_HOME}/yara-rules;${IPED_HOME}/yara-rules-vendor

# Limites operacionais
maxFileSizeBytes = 250M
perItemTimeoutMs = 30000

# Comportamento de scan
scanAllItems = false

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
