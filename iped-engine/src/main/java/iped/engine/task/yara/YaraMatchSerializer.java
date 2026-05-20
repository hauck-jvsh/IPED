package iped.engine.task.yara;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Serializa/desserializa a lista de {@link YaraMatch} de um item para o formato JSON
 * persistido no campo Lucene {@code yara:matches}.
 *
 * <p>Schema: {@code specs/001-yara-rules-engine/contracts/lucene-fields.contract.md}.</p>
 *
 * <p>Ordenação determinística:</p>
 * <ul>
 *   <li>matches por {@code (namespace asc, rule asc)};</li>
 *   <li>strings dentro de cada match por {@code (id asc, offset asc)}.</li>
 * </ul>
 */
public final class YaraMatchSerializer {

    private static final Comparator<YaraMatch> MATCH_ORDER = Comparator
            .comparing(YaraMatch::getNamespace)
            .thenComparing(YaraMatch::getName);

    private static final Comparator<MatchedString> STRING_ORDER = Comparator
            .comparing(MatchedString::getId)
            .thenComparingLong(MatchedString::getOffset);

    private final ObjectMapper mapper;
    private final int matchHexMaxBytes;

    public YaraMatchSerializer(int matchHexMaxBytes) {
        if (matchHexMaxBytes <= 0) {
            throw new IllegalArgumentException("matchHexMaxBytes must be > 0");
        }
        this.matchHexMaxBytes = matchHexMaxBytes;
        this.mapper = new ObjectMapper();
    }

    /**
     * Serializa os matches do item para JSON pronto para gravar no campo {@code yara:matches}.
     *
     * @param matches lista de matches (não-nula; vazia produz {@code null} pois o contrato manda
     *                não gravar o campo quando não há matches)
     * @param engineVersion versão do engine YARA usado, no formato {@code yara-X.Y.Z}
     * @param scannedBytes total de bytes do stream do item efetivamente escaneados
     * @return string JSON, ou {@code null} se {@code matches} estiver vazio
     */
    public String toJson(List<YaraMatch> matches, String engineVersion, long scannedBytes) throws IOException {
        if (matches == null || matches.isEmpty()) {
            return null;
        }

        List<YaraMatch> ordered = new ArrayList<>(matches);
        ordered.sort(MATCH_ORDER);

        ObjectNode root = mapper.createObjectNode();
        root.put("engineVersion", engineVersion == null ? "" : engineVersion);
        root.put("scannedBytes", scannedBytes);

        ArrayNode items = root.putArray("items");
        for (YaraMatch m : ordered) {
            ObjectNode item = items.addObject();
            item.put("rule", m.getName());
            item.put("namespace", m.getNamespace());
            ArrayNode tags = item.putArray("tags");
            for (String t : m.getTags()) {
                tags.add(t);
            }
            ObjectNode meta = item.putObject("meta");
            for (Map.Entry<String, String> e : m.getMeta().entrySet()) {
                meta.put(e.getKey(), e.getValue());
            }
            ArrayNode strings = item.putArray("strings");
            List<MatchedString> sortedStrings = new ArrayList<>(m.getStrings());
            sortedStrings.sort(STRING_ORDER);
            for (MatchedString s : sortedStrings) {
                ObjectNode sn = strings.addObject();
                sn.put("id", s.getId());
                sn.put("offset", s.getOffset());
                sn.put("hex", clampHex(s.getHex()));
                sn.put("truncated", s.isTruncated() || hexExceedsLimit(s.getHex()));
            }
        }
        return mapper.writeValueAsString(root);
    }

    /**
     * Desserializa de volta para uma lista de {@link YaraMatch}. Útil para a UI e o
     * relatório HTML. Tolera chaves desconhecidas (forward-compat).
     */
    public List<YaraMatch> fromJson(String json) throws IOException {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        JsonNode root = mapper.readTree(json);
        JsonNode items = root.get("items");
        if (items == null || !items.isArray() || items.size() == 0) {
            return Collections.emptyList();
        }
        List<YaraMatch> out = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            String namespace = textOrEmpty(item, "namespace");
            String name = textOrEmpty(item, "rule");
            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = item.get("tags");
            if (tagsNode != null && tagsNode.isArray()) {
                for (JsonNode t : tagsNode) {
                    tags.add(t.asText());
                }
            }
            Map<String, String> meta = new LinkedHashMap<>();
            JsonNode metaNode = item.get("meta");
            if (metaNode != null && metaNode.isObject()) {
                metaNode.fields().forEachRemaining(e -> meta.put(e.getKey(), e.getValue().asText()));
            }
            List<MatchedString> strings = new ArrayList<>();
            JsonNode stringsNode = item.get("strings");
            if (stringsNode != null && stringsNode.isArray()) {
                for (JsonNode s : stringsNode) {
                    strings.add(new MatchedString(
                            textOrEmpty(s, "id"),
                            s.path("offset").asLong(0),
                            textOrEmpty(s, "hex"),
                            s.path("truncated").asBoolean(false)));
                }
            }
            out.add(new YaraMatch(namespace, name, tags, meta, strings));
        }
        return out;
    }

    private static String textOrEmpty(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private boolean hexExceedsLimit(String hex) {
        return hex != null && hex.length() > matchHexMaxBytes * 2;
    }

    private String clampHex(String hex) {
        if (hex == null) {
            return "";
        }
        int maxChars = matchHexMaxBytes * 2;
        if (hex.length() <= maxChars) {
            return hex;
        }
        return hex.substring(0, maxChars);
    }

    public int getMatchHexMaxBytes() {
        return matchHexMaxBytes;
    }
}
