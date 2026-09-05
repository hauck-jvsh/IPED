package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;

/**
 * {@code iped_export_item}: one item, one file, in the folder the configuration declares (FR-087).
 *
 * <p>
 * The tool exists because the ceilings on {@code iped_item_content} and {@code iped_item_text}
 * protect the conversation, and a file the examiner keeps is not the conversation. What has to hold
 * is that the file on disk <i>is</i> the item: these tests read the bytes back and hash them, rather
 * than trusting the digests the answer reports about itself.
 */
public class ItemExportTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    private File exportRoot;

    @Before
    public void pointTheServerAtATemporaryExportFolder() throws Exception {
        exportRoot = temp.newFolder("exports");
        session.config().setExportRoots(Collections.singletonList(exportRoot.getAbsolutePath()));
    }

    @Test
    public void theFileWrittenIsTheItem() throws Exception {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode item = anItemWithBytesAndAHash(caseId);
        Assume.assumeTrue("this case has no hashed item with content", item != null);
        int itemId = item.path("item_id").asInt();

        JsonNode result = session.call("iped_export_item", "case_id", caseId, "item_id", itemId);
        assertTrue("the export must have happened: " + result.path("reason").asText(),
                result.path("available").asBoolean());

        Path written = Paths.get(result.path("path").asText());
        assertTrue("the answer names a file that must exist: " + written, Files.exists(written));
        assertTrue("the file must be inside the configured export folder: " + written,
                written.toRealPath().startsWith(exportRoot.getCanonicalFile().toPath()));

        // Nothing is truncated: this is the whole point of exporting instead of reading.
        assertEquals("the file must hold every byte the index recorded", item.path("size").asLong(),
                Files.size(written));
        assertEquals("bytes_written must describe the file, not the intent", Files.size(written),
                result.path("bytes_written").asLong());

        // The digest the answer reports is checked against the file, not taken on faith.
        assertEquals("the reported sha256 must be the sha256 of the file on disk", sha256(written),
                result.path("sha256").asText());
        // And the file is what the case says the item is.
        assertTrue("the export must verify against the hash the case recorded: "
                + result.path("hash_note").asText(), result.path("hash_verified").asBoolean());
    }

    @Test
    public void theTextExportIsTheSameTextTheReadingToolReturns() throws Exception {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        // Which item does not matter; that it has text does. Asking each candidate is what keeps
        // this from skipping on the luck of what the first page happened to contain.
        int itemId = -1;
        JsonNode read = null;
        for (JsonNode candidate : itemsWithBytesAndAHash(caseId)) {
            JsonNode text = session.call("iped_item_text", "case_id", caseId, "item_id",
                    candidate.path("item_id").asInt(), "max_chars", 2000);
            if (text.path("available").asBoolean() && !text.path("text").asText().isEmpty()) {
                itemId = candidate.path("item_id").asInt();
                read = text;
                break;
            }
        }
        Assume.assumeTrue("no item on the first page of this case has extractable text", read != null);

        JsonNode result = session.call("iped_export_item", "case_id", caseId, "item_id", itemId, "text_only", true);
        assertTrue("the text export must have happened: " + result.path("reason").asText(),
                result.path("available").asBoolean());
        assertEquals("text", result.path("exported").asText());

        Path written = Paths.get(result.path("path").asText());
        assertTrue("a text export must be recognizable as one: " + written,
                written.getFileName().toString().endsWith(".txt"));
        String fromFile = new String(Files.readAllBytes(written), StandardCharsets.UTF_8);

        // Same extraction, so the text in the exhibit is the text in the conversation. If these ever
        // diverge, an examiner is citing something the file does not say.
        String fromTool = read.path("text").asText();
        String comparable = fromFile.length() > fromTool.length() ? fromFile.substring(0, fromTool.length())
                : fromFile;
        assertTrue("the exported text must begin with what iped_item_text returned", fromTool.startsWith(comparable));
        // The reading tool stops at its ceiling; the file does not. That difference is the feature.
        assertTrue("the file must not be cut at the ceiling of the reading tool",
                fromFile.length() >= fromTool.length());

        // Nothing here was verified against the case, and the answer has to say so rather than
        // leaving a digest that looks like verification.
        assertFalse("a text file cannot verify against a hash taken of the item's bytes",
                result.path("hash_verified").asBoolean());
        assertTrue("and it must say why", result.path("hash_note").asText().contains("not comparable"));
    }

    @Test
    public void aDirectoryIsDeclaredRatherThanWrittenAsAnEmptyFile() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode directory = session.call("iped_search", "case_id", caseId, "query", "isDir:true", "page_size", 1,
                "include_snippets", false).path("items");
        Assume.assumeTrue("this case has no directories", directory.size() > 0);

        JsonNode result = session.call("iped_export_item", "case_id", caseId, "item_id",
                directory.get(0).path("item_id").asInt());
        assertFalse("a directory has no content of its own", result.path("available").asBoolean());
        assertTrue("and the reason must say so: " + result.path("reason").asText(),
                result.path("reason").asText().contains("directory"));
        // An empty file carrying an item's name would later be indistinguishable from an item that
        // really is empty.
        assertEquals("nothing may be written for it", 0, filesUnder(exportRoot));
    }

    /**
     * Items with bytes of their own and a hash recorded for them.
     *
     * <p>
     * Both halves matter: without content there is nothing to write, and without a recorded hash the
     * verification this tool exists to provide cannot be checked.
     *
     * <p>
     * The set is taken from every item and narrowed here rather than by a range on {@code size},
     * which is what the first version did and which selected nothing usable — a range clause on that
     * field does not restrict what comes back, so the page was arbitrary and the tests skipped
     * depending on what landed in it. Reading the item view is the honest way to ask "does this item
     * have bytes and a hash", because those are exactly the fields the answer carries.
     */
    private List<JsonNode> itemsWithBytesAndAHash(String caseId) {
        JsonNode items = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 50,
                "include_snippets", false).path("items");
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.path("hash").asText("").isEmpty() && item.path("size").asLong(0) > 0
                    && !item.path("is_dir").asBoolean(false)) {
                usable.add(item);
            }
        }
        return usable;
    }

    private JsonNode anItemWithBytesAndAHash(String caseId) {
        List<JsonNode> usable = itemsWithBytesAndAHash(caseId);
        return usable.isEmpty() ? null : usable.get(0);
    }

    private static int filesUnder(File folder) {
        File[] entries = folder.listFiles();
        if (entries == null) {
            return 0;
        }
        int count = 0;
        for (File entry : entries) {
            count += entry.isDirectory() ? filesUnder(entry) : 1;
        }
        return count;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
