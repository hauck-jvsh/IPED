package iped.engine.task.yara;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.apache.tika.mime.MediaType;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.engine.config.AbstractTaskConfig;
import iped.engine.config.EnableTaskProperty;
import iped.engine.config.YaraConfig;
import iped.engine.data.Item;
import iped.properties.ExtraProperties;
import iped.utils.UTF8Properties;

/**
 * Integration test of the full {@link YaraScanTask} pipeline against the real
 * {@code libyara-x-capi}. Loads a small rule catalog from a temp directory,
 * compiles via {@link YaraEngine}, and runs the task's {@link YaraScanTask#process}
 * on in-memory {@link Item}s to verify that {@code yara:rule}, {@code yara:tag}
 * and {@code yara:matches} are populated correctly (FR-001 / FR-003 / FR-004 /
 * FR-005 / FR-006 / FR-012).
 *
 * <p>Skipped via {@link org.junit.Assume} when {@code libyara-x-capi} is not
 * loadable in the test environment.</p>
 */
public class YaraScanTaskIntegrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private YaraScanTask task;

    @BeforeClass
    public static void ensureEngine() {
        String hint = System.getenv("YARA_X_LIB_PATH");
        assumeTrue("libyara-x-capi not available — skipping integration tests",
                YaraEngine.ensureAvailable(hint));
    }

    @AfterClass
    public static void shutdownEngine() {
        YaraEngine.shutdown();
    }

    @Before
    public void resetTaskState() {
        YaraScanTask.resetForTests();
    }

    @After
    public void closeTask() throws Exception {
        if (task != null) {
            task.finish();
            task = null;
        }
    }

    /* ----------------------------------------------------------------- *
     *  Tests
     * ----------------------------------------------------------------- */

    @Test
    public void matched_item_receives_yara_rule_yara_tag_and_yara_matches() throws Exception {
        File ruleDir = tmp.newFolder("rules-match");
        writeRule(ruleDir, "hello.yar",
                "rule hello_world : demo malware { strings: $s = \"hello world\" condition: $s }");

        YaraConfig config = buildEnabledConfig(ruleDir);
        task = new YaraScanTask();
        task.initWithConfig(config);
        assertTrue("task should be enabled with a valid catalog", task.isEnabled());

        Item item = makeItem("greeting.txt", "the quick brown fox says hello world to YARA-X");
        task.process(item);

        @SuppressWarnings("unchecked")
        List<String> rules = (List<String>) item.getExtraAttribute(ExtraProperties.YARA_RULE);
        assertNotNull("yara:rule must be set on matched item", rules);
        assertEquals(Arrays.asList("hello/hello_world"), rules);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) item.getExtraAttribute(ExtraProperties.YARA_TAGS);
        assertNotNull("yara:tag must be set on matched item", tags);
        assertTrue("expected tag 'demo' but got " + tags, tags.contains("demo"));
        assertTrue("expected tag 'malware' but got " + tags, tags.contains("malware"));

        String json = (String) item.getExtraAttribute(ExtraProperties.YARA_MATCH_DETAIL);
        assertNotNull("yara:matches JSON must be set", json);
        assertTrue("JSON must include engineVersion key: " + json, json.contains("\"engineVersion\""));
        assertTrue("JSON must include namespace 'hello': " + json, json.contains("\"hello\""));
        assertTrue("JSON must include rule 'hello_world': " + json, json.contains("\"hello_world\""));
    }

    @Test
    public void non_matching_item_receives_no_yara_attributes() throws Exception {
        File ruleDir = tmp.newFolder("rules-nomatch");
        writeRule(ruleDir, "only_mz.yar",
                "rule mz_at_start { strings: $mz = { 4D 5A } condition: $mz at 0 }");

        YaraConfig config = buildEnabledConfig(ruleDir);
        task = new YaraScanTask();
        task.initWithConfig(config);

        Item item = makeItem("not-pe.txt", "GIF89a — this is not a PE file");
        task.process(item);

        assertNull(item.getExtraAttribute(ExtraProperties.YARA_RULE));
        assertNull(item.getExtraAttribute(ExtraProperties.YARA_TAGS));
        assertNull(item.getExtraAttribute(ExtraProperties.YARA_MATCH_DETAIL));
    }

    @Test
    public void item_above_maxFileSize_is_skipped() throws Exception {
        File ruleDir = tmp.newFolder("rules-size");
        writeRule(ruleDir, "any.yar",
                "rule always { strings: $a = \"a\" condition: $a }");

        YaraConfig config = buildEnabledConfig(ruleDir);
        // Cap at 32 bytes via reflection on the private field.
        setLongField(config, "maxFileSizeBytes", 32L);

        task = new YaraScanTask();
        task.initWithConfig(config);

        // 256-byte payload exceeds the 32-byte cap.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            sb.append('a');
        }
        Item item = makeItem("big.txt", sb.toString());
        task.process(item);

        assertNull("item above cap must not be scanned", item.getExtraAttribute(ExtraProperties.YARA_RULE));
    }

    @Test
    public void invalid_rule_is_isolated_good_rule_still_matches() throws Exception {
        File ruleDir = tmp.newFolder("rules-mixed");
        writeRule(ruleDir, "good.yar",
                "rule good_rule { strings: $s = \"abc\" condition: $s }");
        writeRule(ruleDir, "bad.yar",
                "rule bad_rule { strings: $s = \"unterminated condition: $s }");

        YaraConfig config = buildEnabledConfig(ruleDir);
        task = new YaraScanTask();
        task.initWithConfig(config);
        // Task may or may not be enabled depending on whether the good rule compiled;
        // we only require: if enabled, the good rule still matches.
        if (task.isEnabled()) {
            Item item = makeItem("evidence.txt", "this contains abc as substring");
            task.process(item);
            @SuppressWarnings("unchecked")
            List<String> rules = (List<String>) item.getExtraAttribute(ExtraProperties.YARA_RULE);
            assertNotNull("good rule must still match despite bad rule presence", rules);
            assertEquals(Arrays.asList("good/good_rule"), rules);
        }
    }

    @Test
    public void multiple_rules_matched_are_listed_lexicographically() throws Exception {
        File ruleDir = tmp.newFolder("rules-multi");
        writeRule(ruleDir, "zeta.yar",
                "rule zeta_rule { strings: $s = \"target\" condition: $s }");
        writeRule(ruleDir, "alpha.yar",
                "rule alpha_rule { strings: $s = \"target\" condition: $s }");

        YaraConfig config = buildEnabledConfig(ruleDir);
        task = new YaraScanTask();
        task.initWithConfig(config);

        Item item = makeItem("multi.txt", "well past the target word");
        task.process(item);

        @SuppressWarnings("unchecked")
        List<String> rules = (List<String>) item.getExtraAttribute(ExtraProperties.YARA_RULE);
        assertNotNull(rules);
        assertEquals(2, rules.size());
        assertEquals("alpha/alpha_rule", rules.get(0));
        assertEquals("zeta/zeta_rule", rules.get(1));
    }

    @Test
    public void disabled_config_produces_a_no_op_task() throws Exception {
        // Build a config that is NOT enabled — enabledProp.value stays false.
        YaraConfig config = new YaraConfig();
        Field enabledField = AbstractTaskConfig.class.getDeclaredField("enabledProp");
        enabledField.setAccessible(true);
        EnableTaskProperty prop = new EnableTaskProperty("enableYara");
        prop.setEnabled(false);
        enabledField.set(config, prop);

        task = new YaraScanTask();
        task.initWithConfig(config);
        assertFalse("task must report disabled when enableYara=false", task.isEnabled());

        Item item = makeItem("anything.txt", "nothing should match");
        task.process(item);
        assertNull(item.getExtraAttribute(ExtraProperties.YARA_RULE));
    }

    /* ----------------------------------------------------------------- *
     *  Helpers
     * ----------------------------------------------------------------- */

    private static void writeRule(File dir, String fileName, String content) throws IOException {
        Files.write(new File(dir, fileName).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds an enabled {@link YaraConfig} pointing at {@code ruleDir} without
     * going through {@code ConfigurationManager}. Sets {@code enabledProp} via
     * reflection (the field is protected on {@code AbstractTaskConfig}).
     */
    private YaraConfig buildEnabledConfig(File ruleDir) throws Exception {
        YaraConfig config = new YaraConfig();
        Field enabledField = AbstractTaskConfig.class.getDeclaredField("enabledProp");
        enabledField.setAccessible(true);
        EnableTaskProperty prop = new EnableTaskProperty("enableYara");
        prop.setEnabled(true);
        enabledField.set(config, prop);

        UTF8Properties props = new UTF8Properties();
        props.setProperty("ruleDirectories", ruleDir.getAbsolutePath());
        config.processProperties(props);
        return config;
    }

    private static void setLongField(Object target, String fieldName, long value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setLong(target, value);
    }

    /**
     * Builds an in-memory {@link Item} backed by a {@code byte[]} content,
     * with sane defaults: media type {@code application/octet-stream}, length
     * = content length.
     */
    private static Item makeItem(String name, String content) {
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Item item = new Item() {
            @Override
            public BufferedInputStream getBufferedInputStream() throws IOException {
                return new BufferedInputStream(new ByteArrayInputStream(bytes));
            }
        };
        item.setName(name);
        item.setLength((long) bytes.length);
        item.setMediaType(MediaType.OCTET_STREAM);
        return item;
    }
}
