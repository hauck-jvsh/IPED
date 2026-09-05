package iped.mcp.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.engine.data.IPEDSource;
import iped.engine.preview.PreviewRepositoryManager;
import iped.mcp.McpTestSupport;

/**
 * An item whose only content is a stored preview is content the server can read (FR-021, FR-022).
 *
 * <p>
 * Written after a field report: {@code iped_item_text} answered {@code available: false} with
 * "Repository not configured. Call configureWritable/ReadOnly() first for: &lt;path&gt;". The item
 * was fine. When an item read back from the index has no file of its own and no reference into the
 * evidence, {@code IndexItem} gives it a {@code PreviewInputStreamFactory}, so its bytes live in the
 * case's {@code previews.mv.db} — and the server had never opened that database. The engine's
 * complaint surfaced where content is read, so it was reported as a property of the evidence: an
 * item with content was told to have none.
 *
 * <p>
 * These tests assert the two halves separately, because the second one is invisible from the first.
 * Opening the database makes the content readable; closing it is what {@code iped_close_case}
 * promises when it says it leaves no lock on the folder.
 */
public class PreviewBackedContentTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    /** How many items with a stored preview to put through both readers. */
    private static final int SAMPLE = 25;

    @Test
    public void nothingBlamesTheEvidenceForADatabaseTheServerDidNotOpen() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        List<JsonNode> items = previewBackedItems(caseId);
        Assume.assumeTrue("this case has no items with stored previews", items.size() > 0);

        int readable = 0;
        for (JsonNode item : items) {
            int itemId = item.path("item_id").asInt();

            // Both readers reach the item through the same stream factory, so both failed the same
            // way. iped_item_content failed worse: the engine's complaint is unchecked, and content()
            // catches only IOException, so it escaped as a protocol error rather than as an answer.
            // McpSessionRule.call turns that into a failure here, which is the point.
            JsonNode text = session.call("iped_item_text", "case_id", caseId, "item_id", itemId, "max_chars", 500);
            JsonNode content = session.call("iped_item_content", "case_id", caseId, "item_id", itemId, "max_bytes",
                    512);

            assertNotAboutTheServer(itemId, "iped_item_text", text);
            assertNotAboutTheServer(itemId, "iped_item_content", content);
            if (content.path("available").asBoolean()) {
                readable++;
            }
        }
        // Without this the suite would pass on a case where every sampled item happens to be empty,
        // which is the shape a broken reader also has.
        assertTrue("no item with a stored preview returned any content at all, over " + items.size()
                + " sampled; the preview database is not being read", readable > 0);
    }

    @Test
    public void closingACaseReleasesItsPreviewDatabase() throws Exception {
        File casePath = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(casePath);
        List<JsonNode> items = previewBackedItems(caseId);
        Assume.assumeTrue("this case has no items with stored previews", items.size() > 0);

        // Reading is what makes the manager create the connection pool; configuring alone only
        // records how to open it.
        session.call("iped_item_content", "case_id", caseId, "item_id", items.get(0).path("item_id").asInt(),
                "max_bytes", 512);
        session.call("iped_close_case", "case_id", caseId);

        // The manager is keyed by folder and refuses to configure one it already knows, so this
        // succeeding is the observable proof that closing the case gave the database back. If it
        // throws, the server is holding a case file open that no session holds any more.
        File moduleDir = new File(casePath, IPEDSource.MODULE_DIR);
        try {
            PreviewRepositoryManager.configureReadOnly(moduleDir);
        } catch (IllegalStateException e) {
            throw new AssertionError("closing the case left its preview database configured: " + e.getMessage(), e);
        } finally {
            // Leave the process as this test found it: the manager is global, and a later suite in
            // this JVM opens the same case.
            PreviewRepositoryManager.close(moduleDir);
        }
    }

    /**
     * The items of this case whose only content is a stored preview.
     *
     * <p>
     * Getting this selector right is most of the test, and it took two wrong ones. Neither half of
     * what actually makes an item preview-backed — no file of its own, no reference into the evidence
     * — is a field to query. Sampling {@code hasPreview:true} at large finds almost nothing: that set
     * is overwhelmingly ordinary files with a rendered preview, which read their bytes from the
     * evidence and never open the database, and on the reference case it is 1.7 million items, so a
     * sample of a few dozen contained none of the affected ones and passed with the fix removed.
     * Widening the category to conversations at large was worse, not better: adding
     * {@code OR category:"Chats"} returned a different population entirely — property lists and
     * databases belonging to messaging apps — and not one message record in the first seventy-five.
     *
     * <p>
     * What separates the affected items is visible in the item view: a record decoded out of a
     * container has no length of its own, because no stretch of evidence <i>is</i> the item. A
     * preview, and nothing else to read. So the category narrows and the missing length decides, and
     * the test asserts on what it actually found. Selecting by category rather than by media type
     * keeps this from naming a decoder — the discipline {@code ItemTextTest} follows, and for the
     * same reason.
     */
    private List<JsonNode> previewBackedItems(String caseId) {
        JsonNode items = session.call("iped_search", "case_id", caseId, "query",
                "hasPreview:true AND category:\"Instant Messages\"", "page_size", 3 * SAMPLE,
                "include_snippets", false).path("items");
        List<JsonNode> backed = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.path("size").asLong(0) <= 0 && backed.size() < SAMPLE) {
                backed.add(item);
            }
        }
        return backed;
    }

    /**
     * Fails when an answer explains a server-side condition as a fact about the item.
     */
    private static void assertNotAboutTheServer(int itemId, String tool, JsonNode answer) {
        String reason = answer.path("reason").asText("");
        assertFalse(tool + " on item " + itemId + " reported a server condition as a property of the evidence: "
                + reason, reason.contains("Repository not configured"));
        // The message also carried the server's own absolute path and the name of an engine method,
        // neither of which an agent can act on and both of which end up quoted in reports.
        assertFalse(tool + " on item " + itemId + " leaked an engine API name into an agent-facing answer: " + reason,
                reason.contains("configureWritable"));
    }
}
