# Contract — Constantes adicionadas em `iped.properties.ExtraProperties`

**File path**: `iped-api/src/main/java/iped/properties/ExtraProperties.java`.

**Princípio I (NÃO-NEGOCIÁVEL)**: somente **adições**. Constantes existentes (`GLOBAL_ID`, `TIKA_PARSER_USED`, `DATASOURCE_READER`, `EMBEDDED_FOLDER`, etc.) **não podem** ser renomeadas, removidas ou ter seus valores alterados.

> **Histórico**: a v1.0 da feature introduziu também `YARA_RULE = "yara:rule"` e `YARA_MATCH_DETAIL = "yara:matches"`. Ambas foram **removidas na rev-5 (2026-05-27)**, antes do primeiro release (a feature vive na branch `001-yara-rules-engine` sem nunca ter sido merged em `master`), por terem virado redundantes com a família `yara:match:<rule>` adicionada na rev-4. Como nunca chegaram a um release, removê-las não viola o Princípio I.

---

## Constantes ativas

| Constante (símbolo Java) | Literal | Uso |
|---|---|---|
| `ExtraProperties.YARA_PREFIX` | `"yara:"` | Prefixo de namespace usado pela UI para agrupar todos os campos populados pela `YaraScanTask` sob uma faceta dedicada (`ColumnsManager.updateDinamicFields`). |
| `ExtraProperties.YARA_TAGS` | `"yara:tag"` | Nome do campo Lucene multi-valor + indexado com a união das tags YARA dos matches do item. Faceta cross-rule. |
| `ExtraProperties.YARA_MATCH_PREFIX` | `"yara:match:"` | Prefixo de campo per-regra. A `YaraScanTask` escreve um campo `yara:match:<namespace>/<name>` multi-valorado por regra que casou, com cada valor sendo um matched-string distinto (texto ASCII se decodificável, hex lowercase senão). Mirror exato do `RegexTask` (`Regex:<categoria>`). |

**Convenção de prefixo `yara:`**: escolhida deliberadamente para evitar colisão com qualquer propriedade existente (Tika, IPED, parsers) e para deixar claro na visualização de metadados a origem do dado. O caractere `:` é aceito como nome de campo Lucene.

---

## Diff esperado em `ExtraProperties.java`

```diff
 public class ExtraProperties {

     public static final String GLOBAL_ID = "globalId"; //$NON-NLS-1$
     public static final String TIKA_PARSER_USED = TikaCoreProperties.TIKA_PARSED_BY.getName();
     public static final String DATASOURCE_READER = "X-Reader"; //$NON-NLS-1$
     public static final String EMBEDDED_FOLDER = "IpedEmbeddeFolder"; //$NON-NLS-1$
+
+    /** Prefix grouping all YaraScanTask fields under one facet in the UI. */
+    public static final String YARA_PREFIX = "yara:"; //$NON-NLS-1$
+
+    /** Multi-valued field with the union of YARA tags inherited from matched rules. */
+    public static final String YARA_TAGS = "yara:tag"; //$NON-NLS-1$
+
+    /** Prefix of per-rule match-content fields. Full key: yara:match:<namespace>/<name>. */
+    public static final String YARA_MATCH_PREFIX = "yara:match:"; //$NON-NLS-1$
```

---

## Stability contract

- Os **valores literais** (`"yara:tag"`, `"yara:match:"`) viram chave (ou prefixo de chave) de campo Lucene e portanto entram no escopo do Princípio I a partir do release que os introduz. Após o release, **NÃO PODEM** ser renomeados sem ciclo de deprecação documentado em `ReleaseNotes.txt`.
- Adições adicionais nessa família (ex.: `yara:engineVersion` para auditoria global) são permitidas no mesmo molde — adição pura, sem remoção das anteriores.
- O conjunto de campos `yara:match:*` **é dinâmico**: depende de quais regras casaram em pelo menos um item do caso. Não há uma lista fechada — consumidores que precisam enumerar usam `IndexReader.getFieldInfos()` ou filtram nomes pelo prefixo.
