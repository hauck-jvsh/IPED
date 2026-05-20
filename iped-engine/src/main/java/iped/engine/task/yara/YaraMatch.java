package iped.engine.task.yara;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resultado da aplicação de uma única regra YARA a um item.
 *
 * <p>Imutável após construção; coleções são defensivamente copiadas. Veja
 * {@code specs/001-yara-rules-engine/data-model.md} para o modelo completo e
 * {@code specs/001-yara-rules-engine/contracts/lucene-fields.contract.md} para o
 * formato JSON usado na persistência.</p>
 */
public final class YaraMatch {

    private final String namespace;
    private final String name;
    private final List<String> tags;
    private final Map<String, String> meta;
    private final List<MatchedString> strings;

    public YaraMatch(String namespace, String name, List<String> tags,
            Map<String, String> meta, List<MatchedString> strings) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.name = Objects.requireNonNull(name, "name");
        this.tags = (tags == null) ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tags));
        this.meta = (meta == null) ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
        this.strings = (strings == null) ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(strings));
    }

    /** Identificador de origem da regra (basename do arquivo .yar/.yara/.yarc sem extensão). */
    public String getNamespace() {
        return namespace;
    }

    /** Nome da regra (parte após {@code rule} na declaração). */
    public String getName() {
        return name;
    }

    /** Identificador completo {@code "namespace/name"}, usado como chave no campo Lucene {@code yara:rule}. */
    public String getIdentifier() {
        return namespace + "/" + name;
    }

    public List<String> getTags() {
        return tags;
    }

    public Map<String, String> getMeta() {
        return meta;
    }

    public List<MatchedString> getStrings() {
        return strings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof YaraMatch)) {
            return false;
        }
        YaraMatch other = (YaraMatch) o;
        return namespace.equals(other.namespace)
                && name.equals(other.name)
                && tags.equals(other.tags)
                && meta.equals(other.meta)
                && strings.equals(other.strings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, name, tags, meta, strings);
    }

    @Override
    public String toString() {
        return "YaraMatch[" + getIdentifier() + ", tags=" + tags + ", strings=" + strings.size() + "]";
    }
}
