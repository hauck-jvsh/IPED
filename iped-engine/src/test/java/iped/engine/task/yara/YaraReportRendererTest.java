package iped.engine.task.yara;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Cobre a parte testável de T033/T039 sem depender de um caso IPED real ou de
 * Lucene index: a renderização HTML de matches YARA pelo {@link YaraReportRenderer}
 * que é consumido pelo {@code HTMLReportTask}.
 *
 * <p>Validar o relatório HTML completo end-to-end (gerar um report de um caso
 * pequeno e abrir o arquivo) fica como roteiro manual — fora do escopo deste
 * unit test.</p>
 */
public class YaraReportRendererTest {

    /* htmlEscape ------------------------------------------------------- */

    @Test
    public void htmlEscape_handles_all_five_critical_chars() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&#39;f", YaraReportRenderer.htmlEscape("a&b<c>d\"e'f"));
    }

    @Test
    public void htmlEscape_null_and_empty_safe() {
        assertEquals("", YaraReportRenderer.htmlEscape(null));
        assertEquals("", YaraReportRenderer.htmlEscape(""));
    }

    @Test
    public void htmlEscape_passes_through_safe_chars() {
        assertEquals("hello world 42", YaraReportRenderer.htmlEscape("hello world 42"));
    }

    /* renderHtml ------------------------------------------------------- */

    @Test
    public void render_null_or_empty_returns_null() {
        assertNull(YaraReportRenderer.renderHtml(null));
        assertNull(YaraReportRenderer.renderHtml(""));
    }

    @Test
    public void render_invalid_json_returns_null() {
        assertNull(YaraReportRenderer.renderHtml("{not valid json"));
    }

    @Test
    public void render_empty_matches_returns_null() {
        String json = "{\"engineVersion\":\"yara-x-1.16.0\",\"scannedBytes\":0,\"items\":[]}";
        assertNull(YaraReportRenderer.renderHtml(json));
    }

    @Test
    public void render_basic_match_includes_identifier_and_tags() throws Exception {
        YaraMatch m = new YaraMatch(
                "apt28",
                "loader_dropper",
                Arrays.asList("apt", "windows"),
                Collections.emptyMap(),
                Arrays.asList(new MatchedString("$s1", 4096L, "4d5a9000", false)));
        String json = new YaraMatchSerializer(256).toJson(Arrays.asList(m), "yara-x-1.16.0", 32768);

        String html = YaraReportRenderer.renderHtml(json);

        assertNotNull(html);
        assertTrue("must mention namespace/name identifier: " + html, html.contains("apt28/loader_dropper"));
        assertTrue("must mention joined tags: " + html, html.contains("apt, windows"));
        assertTrue("must include matched string id: " + html, html.contains("$s1"));
        assertTrue("must include offset: " + html, html.contains("4096"));
        assertTrue("must include hex bytes: " + html, html.contains("4d5a9000"));
    }

    @Test
    public void render_escapes_hostile_values_safely() throws Exception {
        // Force a hostile namespace value (paranoid but defensible — defense in depth).
        YaraMatch m = new YaraMatch(
                "<script>alert(1)</script>",
                "x",
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyList());
        String json = new YaraMatchSerializer(256).toJson(Arrays.asList(m), "yara-x-1.16.0", 0);

        String html = YaraReportRenderer.renderHtml(json);
        assertNotNull(html);
        assertFalse("raw <script> must not appear: " + html, html.contains("<script>"));
        assertTrue("escaped form must appear: " + html, html.contains("&lt;script&gt;"));
    }

    @Test
    public void render_truncated_string_shows_ellipsis() throws Exception {
        YaraMatch m = new YaraMatch(
                "ns",
                "rule",
                Collections.emptyList(),
                Collections.emptyMap(),
                Arrays.asList(new MatchedString("$big", 0L, "aabbccdd", true)));
        String json = new YaraMatchSerializer(256).toJson(Arrays.asList(m), "yara-x-1.16.0", 0);

        String html = YaraReportRenderer.renderHtml(json);
        assertNotNull(html);
        assertTrue("truncated string must render ellipsis: " + html, html.contains("&hellip;"));
    }

    @Test
    public void render_two_matches_produce_two_blocks() throws Exception {
        YaraMatch a = new YaraMatch(
                "apt28", "rule_a", Arrays.asList("apt"),
                Collections.emptyMap(), Collections.emptyList());
        YaraMatch b = new YaraMatch(
                "formbook", "rule_b", Arrays.asList("packer"),
                Collections.emptyMap(), Collections.emptyList());
        String json = new YaraMatchSerializer(256).toJson(Arrays.asList(a, b), "yara-x-1.16.0", 0);

        String html = YaraReportRenderer.renderHtml(json);
        assertNotNull(html);
        assertTrue(html.contains("apt28/rule_a"));
        assertTrue(html.contains("formbook/rule_b"));
        // Two outer <div> blocks expected (one per match).
        int divCount = 0, idx = 0;
        while ((idx = html.indexOf("<div", idx)) != -1) {
            divCount++;
            idx += 4;
        }
        assertEquals("expected exactly two top-level <div> blocks", 2, divCount);
    }
}
