# Contract — Keys adicionadas em `IPEDConfig.txt`

**File path**: `iped-app/resources/config/IPEDConfig.txt` (canonical) e cópias nos perfis (`profiles/*/IPEDConfig.txt`).

**Loaded by**: `iped.engine.config.LocalConfig` / mecanismo de enable-task que lê `enableXxx`. Convenção idêntica às demais tasks habilitáveis (`enableHashDBLookup`, `enableLanguageDetect`, `enableImageThumbs`, etc.).

---

## Chave adicionada

| Key | Type | Default | Description |
|---|---|---|---|
| `enableYara` | boolean (`true`/`false`) | `false` | Habilita a `YaraScanTask` no pipeline. Quando `false`, a task é removida da execução do `Manager` (Princípio II: comportamento atual preservado, FR-013). |

---

## Diff esperado

`iped-app/resources/config/IPEDConfig.txt` ganha:

```diff
+
+ # Habilita scan YARA das evidências. Requer libyara em tools/yara/<os>/
+ # e configuração de regras em conf/YaraConfig.txt.
+ enableYara = false
```

Inserir na seção de habilitação de tasks, próxima às demais `enableXxx`.

---

## Behavior contract

- **Default global**: `false` (FR-013 — sem custo quando desabilitada).
- **Override por perfil**: cada profile pode setar `enableYara = true` independentemente. Política sugerida:
  - `forensic` → `enableYara = true` (com `ruleDirectories` apontando para catálogo geral).
  - `pedo` → `enableYara = true` (catálogo CSAM-IOC).
  - `triage` → `enableYara = false` (triagem rápida; YARA pode ser ligado caso a caso).
  - `fastmode` → `enableYara = false`.
  - `blind` → `enableYara = false`.
- **Interação com `YaraConfig.ruleDirectories` vazio**: se `enableYara=true` mas o catálogo está vazio, o `init()` da task loga WARN único e a task vira no-op para o caso (sem erro).
- **Interação com `libyara` ausente**: se `enableYara=true` mas a engine nativa falha ao carregar, `init()` loga WARN único e a task vira no-op para o caso (FR-014).
