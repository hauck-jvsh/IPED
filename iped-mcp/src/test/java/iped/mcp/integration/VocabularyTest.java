package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.protocol.McpError;

/**
 * Vocabulary self-correction end to end (Scenario 4, SC-006).
 *
 * <p>
 * The loop this protects: an agent restricts on a field this index does not have, gets the right
 * name back in the error, retries, and finds the evidence. Without the loop the same agent reports
 * "no evidence found" — a confident, wrong, negative finding, and the most dangerous failure mode
 * this feature has.
 */
public class VocabularyTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void listFieldsReturnsWhatTheIndexActuallyHas() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode result = session.call("iped_list_fields", "case_id", caseId);

        List<String> fields = new ArrayList<>();
        result.path("fields").forEach(node -> fields.add(node.asText()));
        assertTrue("a 4.x index always carries these", fields.contains("name"));
        assertTrue(fields.contains("path"));
        assertTrue(fields.contains("category"));
        assertFalse("'content' is the text itself, not a listable property", fields.contains("content"));
        assertEquals(fields.size(), result.path("field_count").asInt());
    }

    @Test
    public void anUnknownFieldInAQueryComesBackWithTheRightNameAttached() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        // 'mediaType' is what another product calls it. This index calls it contentType.
        JsonNode error = session.expectError(McpError.UNKNOWN_FIELD, "iped_search", "case_id", caseId, "query",
                "mediaType:image/jpeg");

        JsonNode similar = error.path("details").path("similar");
        assertTrue("near names must be offered", similar.isArray() && similar.size() > 0);
        List<String> suggestions = new ArrayList<>();
        similar.forEach(node -> suggestions.add(node.asText()));
        assertTrue("contentType must be among the suggestions, got " + suggestions,
                suggestions.contains("contentType"));
    }

    @Test
    public void theSuggestedFieldActuallyWorks() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode error = session.expectError(McpError.UNKNOWN_FIELD, "iped_search", "case_id", caseId, "query",
                "mediaType:*");
        String suggested = error.path("details").path("similar").get(0).asText();

        // The loop has to close: the suggestion must produce a query that runs.
        JsonNode retried = session.call("iped_search", "case_id", caseId, "query", suggested + ":*", "page_size", 5,
                "include_snippets", false);
        assertTrue("the suggested field must yield a working query", retried.path("total_matches").asLong() > 0);
    }

    @Test
    public void checkFieldConfirmsWhatExistsAndSuggestsWhatDoesNot() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        assertTrue(session.call("iped_check_field", "case_id", caseId, "field", "name").path("exists").asBoolean());

        JsonNode missing = session.call("iped_check_field", "case_id", caseId, "field", "filename");
        assertFalse(missing.path("exists").asBoolean());
        assertTrue("a missing field must come with near names", missing.path("similar").size() > 0);
        assertFalse("and with a remedy", missing.path("remedy").asText().isEmpty());
    }

    @Test
    public void itemFieldsShowsTheVocabularyThroughAConcreteExample() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int itemId = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 1,
                "include_snippets", false).path("items").get(0).path("item_id").asInt();

        JsonNode result = session.call("iped_item_fields", "case_id", caseId, "item_id", itemId);

        assertTrue("an item's own fields must be listed", result.path("fields").size() > 0);
        assertTrue(result.path("fields").has("name"));
    }

    @Test
    public void aFieldThatExistsIsNeverRejected() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode fields = session.call("iped_list_fields", "case_id", caseId).path("fields");

        // Every name the vocabulary reports must be usable in a query. A false rejection here
        // would be worse than no validation at all.
        int checked = 0;
        for (JsonNode field : fields) {
            String name = field.asText();
            if (name.contains(":") || name.contains(" ")) {
                continue;
            }
            session.call("iped_search", "case_id", caseId, "query", name + ":*", "page_size", 1,
                    "include_snippets", false);
            if (++checked >= 25) {
                break;
            }
        }
        assertTrue("the vocabulary must expose usable field names", checked > 0);
    }
}
