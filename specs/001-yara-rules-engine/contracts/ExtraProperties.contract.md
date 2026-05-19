# Contract — Constantes adicionadas em `iped.properties.ExtraProperties`

**File path**: `iped-api/src/main/java/iped/properties/ExtraProperties.java`.

**Princípio I (NÃO-NEGOCIÁVEL)**: somente **adições**. Constantes existentes (`GLOBAL_ID`, `TIKA_PARSER_USED`, `DATASOURCE_READER`, `EMBEDDED_FOLDER`, etc.) **não podem** ser renomeadas, removidas ou ter seus valores alterados.

---

## Constantes novas

| Constante (símbolo Java) | Literal | Uso |
|---|---|---|
| `ExtraProperties.YARA_RULE` | `"yara:rule"` | Nome do campo Lucene multi-valor + indexado que carrega cada identificador de regra casada (`namespace/rule_name`). |
| `ExtraProperties.YARA_TAGS` | `"yara:tag"` | Nome do campo Lucene multi-valor + indexado com a união das tags YARA dos matches do item. |
| `ExtraProperties.YARA_MATCH_DETAIL` | `"yara:matches"` | Nome do campo Lucene `String` (stored, **não** indexado) com o JSON do detalhe dos matches do item. |

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
+    /** Multi-valued field with the YARA rule identifier (namespace/name) for each match. */
+    public static final String YARA_RULE = "yara:rule"; //$NON-NLS-1$
+
+    /** Multi-valued field with the union of YARA tags inherited from matched rules. */
+    public static final String YARA_TAGS = "yara:tag"; //$NON-NLS-1$
+
+    /** Stored, non-indexed JSON payload with per-item match detail (strings, offsets, hex). */
+    public static final String YARA_MATCH_DETAIL = "yara:matches"; //$NON-NLS-1$
```

---

## Stability contract

- Os **valores literais** (`"yara:rule"`, `"yara:tag"`, `"yara:matches"`) viram chave de campo Lucene e portanto entram no escopo do Princípio I a partir do release que os introduz. Após o release, **NÃO PODEM** ser renomeados sem ciclo de deprecação documentado em `ReleaseNotes.txt`.
- Adições adicionais nessa família (ex.: `yara:engineVersion`) são permitidas no mesmo molde — adição pura, sem remoção das anteriores.
