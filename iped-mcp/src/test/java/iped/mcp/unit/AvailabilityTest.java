package iped.mcp.unit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.integration.McpSessionRule;

/**
 * Absence is declared with its reason, never returned as empty (FR-022).
 *
 * <p>
 * This is the invariant that separates "this item has no extracted text" from "this item's text is
 * blank". They look identical to an agent that gets an empty string back, and they support opposite
 * conclusions.
 */
public class AvailabilityTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void itemsWithoutTextDeclareItRatherThanReturningNothing() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        int checked = 0;
        for (JsonNode item : page(caseId, "*:*", 60)) {
            JsonNode text = session.call("iped_item_text", "case_id", caseId, "item_id",
                    item.path("item_id").asInt());
            if (!text.path("available").asBoolean()) {
                assertFalse("an unavailable text must say why", text.path("reason").asText().isEmpty());
                assertFalse("and point at what else to try", text.path("remedy").asText().isEmpty());
                assertFalse("an unavailable text must not carry an empty string as if it were content",
                        text.has("text"));
                checked++;
            }
        }
        // A representative case has directories and binary items, so this should find some. If it
        // finds none, the case is not exercising the requirement and the assertion below says so.
        assertTrue("the reference case should contain items without extractable text; found none in 60 items",
                checked > 0);
    }

    @Test
    public void theReasonSaysSomethingTrueAboutThisItemRatherThanListingHypotheses() {
        // The message this replaces offered three at once — binary, unparsed, or encrypted — and for a
        // decoded chat message all three were wrong while its text sat in Message-Body. A reason that
        // covers every case by naming none of them cannot be acted on, and an agent that believes it
        // reports the messages as empty.
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        int checked = 0;
        for (JsonNode item : page(caseId, "*:*", 60)) {
            JsonNode text = session.call("iped_item_text", "case_id", caseId, "item_id",
                    item.path("item_id").asInt());
            if (text.path("available").asBoolean()) {
                continue;
            }
            String reason = text.path("reason").asText();
            String type = item.path("content_type").asText("");
            boolean namesThisItem = reason.contains("decoded data") || reason.contains("directory")
                    || reason.contains("timed out") || reason.contains("metadata")
                    || (!type.isEmpty() && reason.contains(type));
            assertTrue("the reason must name what is true of item " + item.path("item_id") + " (" + type
                    + "), got: " + reason, namesThisItem);
            checked++;
        }
        assertTrue("no item without text was found in 60; this case does not exercise the requirement",
                checked > 0);
    }

    @Test
    public void itemsWithoutThumbnailsDeclareIt() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        int checked = 0;
        for (JsonNode item : page(caseId, "*:*", 60)) {
            JsonNode thumb = session.call("iped_item_thumbnail", "case_id", caseId, "item_id",
                    item.path("item_id").asInt());
            if (!thumb.path("available").asBoolean()) {
                assertFalse("an absent thumbnail must say why", thumb.path("reason").asText().isEmpty());
                assertFalse("an absent thumbnail must not carry data", thumb.has("data"));
                checked++;
            }
        }
        assertTrue("the reference case should contain items without thumbnails", checked > 0);
    }

    @Test
    public void missingPropertiesAreListedWithTheirReason() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        for (JsonNode item : page(caseId, "*:*", 30)) {
            JsonNode unavailable = item.path("unavailable");
            assertTrue("every item view must carry the unavailable list, even when empty",
                    unavailable.isArray());
            for (JsonNode entry : unavailable) {
                assertFalse("an unavailable entry must name the field", entry.path("field").asText().isEmpty());
                assertFalse("and give the reason", entry.path("reason").asText().isEmpty());
                assertFalse("a field reported unavailable must not also be present: "
                        + entry.path("field").asText(), item.has(entry.path("field").asText()));
            }
        }
    }

    @Test
    public void directoriesSayTheyHaveNoContentOfTheirOwn() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode dirs = session.call("iped_search", "case_id", caseId, "query", "isDir:true", "page_size", 1,
                "include_snippets", false).path("items");
        org.junit.Assume.assumeTrue("the reference case has no directories", dirs.size() > 0);

        int itemId = dirs.get(0).path("item_id").asInt();
        JsonNode content = session.call("iped_item_content", "case_id", caseId, "item_id", itemId);

        assertFalse(content.path("available").asBoolean());
        assertTrue("a directory must be told apart from an unreadable item",
                content.path("reason").asText().contains("directory"));
        assertTrue("and pointed at the tool that does answer", content.path("remedy").asText()
                .contains("iped_item_tree"));
    }

    @Test
    public void truncationIsAlwaysSignalledWithTheRealSize() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode large = session.call("iped_search", "case_id", caseId, "query", "size:[100000 TO *]", "page_size",
                1, "include_snippets", false).path("items");
        org.junit.Assume.assumeTrue("the reference case has no item above 100 KB", large.size() > 0);

        int itemId = large.get(0).path("item_id").asInt();
        JsonNode content = session.call("iped_item_content", "case_id", caseId, "item_id", itemId, "max_bytes",
                1024);

        if (content.path("available").asBoolean()) {
            assertTrue("truncation must be declared", content.path("truncated").asBoolean());
            assertTrue("the real size must be reported alongside", content.path("real_size").asLong() > 1024);
            assertFalse("and the note must explain the consequence",
                    content.path("truncation_note").asText().isEmpty());
        }
    }

    private JsonNode page(String caseId, String query, int size) {
        return session.call("iped_search", "case_id", caseId, "query", query, "page_size", size,
                "include_snippets", false).path("items");
    }
}
