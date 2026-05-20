package iped.engine.task.yara;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link YaraMatchSerializer} — JSON round-trip, ordering and
 * hex truncation.
 */
public class YaraMatchSerializerTest {

    private final YaraMatchSerializer ser = new YaraMatchSerializer(256);

    @Test
    public void empty_input_returns_null() throws Exception {
        assertNull(ser.toJson(null, "yara-x-1.16.0", 0));
        assertNull(ser.toJson(Collections.emptyList(), "yara-x-1.16.0", 0));
    }

    @Test
    public void single_match_roundtrip_preserves_identifier_and_tags() throws Exception {
        YaraMatch m = new YaraMatch(
                "apt28",
                "loader_dropper",
                Arrays.asList("apt", "windows"),
                meta("author", "Florian Roth", "severity", "high"),
                Arrays.asList(new MatchedString("$s1", 4096, "4d5a9000", false)));

        String json = ser.toJson(Arrays.asList(m), "yara-x-1.16.0", 32768);
        assertNotNull(json);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        assertEquals("yara-x-1.16.0", root.get("engineVersion").asText());
        assertEquals(32768, root.get("scannedBytes").asInt());
        JsonNode items = root.get("items");
        assertNotNull(items);
        assertEquals(1, items.size());
        JsonNode item = items.get(0);
        assertEquals("loader_dropper", item.get("rule").asText());
        assertEquals("apt28", item.get("namespace").asText());
        assertEquals("apt", item.get("tags").get(0).asText());
        assertEquals("windows", item.get("tags").get(1).asText());
        assertEquals("Florian Roth", item.get("meta").get("author").asText());
        assertEquals("high", item.get("meta").get("severity").asText());
        JsonNode strings = item.get("strings");
        assertEquals(1, strings.size());
        assertEquals("$s1", strings.get(0).get("id").asText());
        assertEquals(4096, strings.get(0).get("offset").asInt());
        assertEquals("4d5a9000", strings.get(0).get("hex").asText());
        assertFalse(strings.get(0).get("truncated").asBoolean());
    }

    @Test
    public void roundtrip_via_fromJson_returns_equivalent_objects() throws Exception {
        YaraMatch m = new YaraMatch(
                "apt28",
                "loader_dropper",
                Arrays.asList("apt", "windows"),
                meta("author", "Florian Roth"),
                Arrays.asList(new MatchedString("$s1", 4096, "4d5a", false),
                              new MatchedString("$re1", 8192, "554e495f4944", false)));

        String json = ser.toJson(Arrays.asList(m), "yara-x-1.16.0", 12345);
        List<YaraMatch> recovered = ser.fromJson(json);

        assertEquals(1, recovered.size());
        YaraMatch r = recovered.get(0);
        assertEquals("apt28", r.getNamespace());
        assertEquals("loader_dropper", r.getName());
        assertEquals(Arrays.asList("apt", "windows"), r.getTags());
        assertEquals("Florian Roth", r.getMeta().get("author"));
        assertEquals(2, r.getStrings().size());
        // strings vêm ordenadas por (id, offset).
        assertEquals("$re1", r.getStrings().get(0).getId());
        assertEquals("$s1", r.getStrings().get(1).getId());
    }

    @Test
    public void matches_ordered_by_namespace_then_rule() throws Exception {
        YaraMatch a = match("zzz_ns", "a_rule");
        YaraMatch b = match("aaa_ns", "z_rule");
        YaraMatch c = match("aaa_ns", "a_rule");
        // Insertion order intentionally scrambled.
        String json = ser.toJson(Arrays.asList(a, b, c), "yara-x-1.16.0", 0);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode items = mapper.readTree(json).get("items");
        assertEquals(3, items.size());
        // Expected: aaa_ns/a_rule, aaa_ns/z_rule, zzz_ns/a_rule
        assertEquals("aaa_ns", items.get(0).get("namespace").asText());
        assertEquals("a_rule", items.get(0).get("rule").asText());
        assertEquals("aaa_ns", items.get(1).get("namespace").asText());
        assertEquals("z_rule", items.get(1).get("rule").asText());
        assertEquals("zzz_ns", items.get(2).get("namespace").asText());
        assertEquals("a_rule", items.get(2).get("rule").asText());
    }

    @Test
    public void strings_within_match_ordered_by_id_then_offset() throws Exception {
        YaraMatch m = new YaraMatch(
                "ns",
                "rule",
                Collections.emptyList(),
                Collections.emptyMap(),
                Arrays.asList(
                        new MatchedString("$z", 100, "00", false),
                        new MatchedString("$a", 5000, "01", false),
                        new MatchedString("$a", 50, "02", false)));

        String json = ser.toJson(Arrays.asList(m), "yara-x-1.16.0", 0);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode strings = mapper.readTree(json).get("items").get(0).get("strings");
        assertEquals(3, strings.size());
        // Expected ordering: ($a@50, $a@5000, $z@100)
        assertEquals("$a", strings.get(0).get("id").asText());
        assertEquals(50, strings.get(0).get("offset").asInt());
        assertEquals("$a", strings.get(1).get("id").asText());
        assertEquals(5000, strings.get(1).get("offset").asInt());
        assertEquals("$z", strings.get(2).get("id").asText());
    }

    @Test
    public void hex_truncated_when_exceeds_limit() throws Exception {
        // matchHexMaxBytes=4 means up to 8 hex chars.
        YaraMatchSerializer tiny = new YaraMatchSerializer(4);
        // 10 bytes worth of hex (20 chars) — should be truncated to 8 chars.
        YaraMatch m = new YaraMatch(
                "ns",
                "rule",
                Collections.emptyList(),
                Collections.emptyMap(),
                Arrays.asList(new MatchedString("$s", 0, "aabbccddee11223344ff", false)));
        String json = tiny.toJson(Arrays.asList(m), "yara-x-1.16.0", 0);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode s = mapper.readTree(json).get("items").get(0).get("strings").get(0);
        assertEquals("aabbccdd", s.get("hex").asText());
        assertTrue("truncated flag must be true after clamping", s.get("truncated").asBoolean());
    }

    @Test
    public void hex_within_limit_keeps_truncated_false() throws Exception {
        YaraMatchSerializer ample = new YaraMatchSerializer(64);
        YaraMatch m = new YaraMatch(
                "ns",
                "rule",
                Collections.emptyList(),
                Collections.emptyMap(),
                Arrays.asList(new MatchedString("$s", 0, "deadbeef", false)));
        String json = ample.toJson(Arrays.asList(m), "yara-x-1.16.0", 0);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode s = mapper.readTree(json).get("items").get(0).get("strings").get(0);
        assertEquals("deadbeef", s.get("hex").asText());
        assertFalse(s.get("truncated").asBoolean());
    }

    @Test
    public void fromJson_tolerates_empty_input() throws Exception {
        assertEquals(Collections.emptyList(), ser.fromJson(null));
        assertEquals(Collections.emptyList(), ser.fromJson(""));
    }

    @Test
    public void fromJson_tolerates_missing_optional_fields() throws Exception {
        String json = "{\"engineVersion\":\"yara-x-1.16.0\",\"scannedBytes\":0,"
                + "\"items\":[{\"rule\":\"r\",\"namespace\":\"n\"}]}";
        List<YaraMatch> matches = ser.fromJson(json);
        assertEquals(1, matches.size());
        YaraMatch m = matches.get(0);
        assertEquals("n", m.getNamespace());
        assertEquals("r", m.getName());
        assertTrue(m.getTags().isEmpty());
        assertTrue(m.getMeta().isEmpty());
        assertTrue(m.getStrings().isEmpty());
    }

    @Test
    public void fromJson_tolerates_unknown_keys_forward_compat() throws Exception {
        String json = "{\"engineVersion\":\"yara-x-1.16.0\",\"scannedBytes\":0,"
                + "\"futureKey\":\"ignored\","
                + "\"items\":[{\"rule\":\"r\",\"namespace\":\"n\",\"score\":0.9,\"strings\":[]}]}";
        List<YaraMatch> matches = ser.fromJson(json);
        assertEquals(1, matches.size());
    }

    /* Helpers ------------------------------------------------------------ */

    private static YaraMatch match(String ns, String name) {
        return new YaraMatch(ns, name, Collections.emptyList(),
                Collections.emptyMap(), Collections.emptyList());
    }

    private static Map<String, String> meta(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("meta(...) expects key/value pairs");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
