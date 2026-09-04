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
 * Reading a chosen set of fields from a batch of ids, end to end through the dispatcher.
 *
 * <p>
 * The workflow this serves: a search returns fifty ids, and the field that decides the question is
 * one this case happens to have — a chat sender, an EXIF tag, a P2P attribute. Before the projection
 * existed the only way to read it was one {@code iped_item_fields} per item, and fifty round trips
 * is how an agent runs out of budget before it runs out of evidence.
 */
public class FieldProjectionTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void aProjectionReturnsTheAskedFieldsAndNothingElse() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int[] ids = firstIds(caseId, 5);

        JsonNode result = session.call("iped_get_items", "case_id", caseId, "item_ids", ids, "fields",
                new String[] { "name", "size", "hash" });

        assertEquals(ids.length, result.path("items").size());
        for (JsonNode item : result.path("items")) {
            // The citation invariant holds whatever the projection asks for: an item always carries
            // the case it belongs to, because ids are local to a case and collide between cases.
            assertEquals(caseId, item.path("case_id").asText());
            assertTrue(item.path("item_id").isInt());
            JsonNode fields = item.path("fields");
            List<String> keys = new ArrayList<>();
            fields.fieldNames().forEachRemaining(keys::add);
            assertTrue("only the asked fields may appear, got " + keys,
                    keys.stream().allMatch(key -> key.equals("name") || key.equals("size") || key.equals("hash")));
            // Every asked field is either answered or declared absent. Silence about one would be
            // indistinguishable from a field that was never read.
            for (String asked : new String[] { "name", "size", "hash" }) {
                assertTrue("neither answered nor declared: " + asked + " on item " + item.path("item_id"),
                        fields.has(asked) || declaresUnavailable(item, asked));
            }
        }
        List<String> projection = new ArrayList<>();
        result.path("projection").forEach(node -> projection.add(node.asText()));
        assertEquals("what was read has to be readable from the answer alone", 3, projection.size());
        assertFalse(result.path("projection_note").asText().isEmpty());
    }

    @Test
    public void theDefaultProjectionIsUnchangedWhenNoFieldsAreAsked() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int[] ids = firstIds(caseId, 3);

        JsonNode result = session.call("iped_get_items", "case_id", caseId, "item_ids", ids);

        assertFalse("the essential-properties shape must stay flat", result.has("projection"));
        JsonNode first = result.path("items").get(0);
        assertTrue("the default view carries the properties directly", first.has("name"));
        assertFalse(first.has("fields"));
    }

    @Test
    public void typesMatchTheDefaultViewFieldForField() {
        // A projection and the default view must not disagree about the same item: an agent that
        // compares sizes across the two shapes would be comparing a number with a string.
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int[] ids = firstIds(caseId, 20);

        JsonNode defaults = session.call("iped_get_items", "case_id", caseId, "item_ids", ids);
        JsonNode projected = session.call("iped_get_items", "case_id", caseId, "item_ids", ids, "fields",
                new String[] { "name", "size", "created", "deleted", "is_root", "content_type" });

        for (int i = 0; i < defaults.path("items").size(); i++) {
            JsonNode expected = defaults.path("items").get(i);
            JsonNode fields = projected.path("items").get(i).path("fields");
            assertEquals(expected.path("item_id").asInt(), projected.path("items").get(i).path("item_id").asInt());
            assertEquals(expected.path("name").asText(), fields.path("name").asText());
            assertEquals(expected.path("size").asLong(), fields.path("size").asLong());
            assertEquals("a size is a number in both shapes", expected.path("size").isNumber(),
                    fields.path("size").isNumber());
            assertEquals("a timestamp is the same instant in both shapes", expected.path("created").asText(),
                    fields.path("created").asText());
            assertEquals("a flag is a boolean in both shapes", expected.path("deleted").asBoolean(),
                    fields.path("deleted").asBoolean());
            assertTrue(fields.path("deleted").isBoolean());
            // isRoot is written to the index only for a root item, so its absence is the value false
            // rather than an unknown — reporting it as undetermined would invite a wrong reading.
            assertEquals(expected.path("is_root").asBoolean(), fields.path("is_root").asBoolean());
            assertTrue(fields.path("is_root").isBoolean());
            assertEquals(expected.path("content_type").asText(), fields.path("content_type").asText());
        }
        assertEquals("contentType", projected.path("resolved_fields").path("content_type").asText());
    }

    @Test
    public void aCaseSpecificFieldIsReadableAcrossAWholeResultSetInOneCall() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        // Whatever this case happens to carry beyond the essentials — the point is that the vocabulary
        // decides, not this test and not the documentation.
        String field = aFieldBeyondTheEssentials(caseId);
        org.junit.Assume.assumeTrue("this case carries nothing beyond the essential properties", field != null);
        int[] ids = firstIds(caseId, 25);

        JsonNode result = session.call("iped_get_items", "case_id", caseId, "item_ids", ids, "fields",
                new String[] { field, "name" });

        assertEquals(ids.length, result.path("items").size());
        for (JsonNode item : result.path("items")) {
            assertTrue("the field is answered or its absence is declared, for every item",
                    item.path("fields").has(field) || declaresUnavailable(item, field));
        }
    }

    @Test
    public void aFieldNameThisCaseDoesNotHaveRefusesTheCallWithTheNearNames() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int[] ids = firstIds(caseId, 3);

        JsonNode error = session.expectError(McpError.UNKNOWN_FIELD, "iped_get_items", "case_id", caseId, "item_ids",
                ids, "fields", new String[] { "name", "notAFieldOfThisCase" });

        JsonNode details = error.path("details");
        assertEquals("notAFieldOfThisCase", details.path("unknown_fields").get(0).asText());
        assertTrue("the names that did resolve are named so the retry is one call",
                details.path("recognized_fields").toString().contains("name"));
        assertTrue("the refusal has to say nothing was read", error.path("remedy").asText().contains("Nothing was read"));
    }

    @Test
    public void anEmptyFieldListIsRefusedRatherThanAnsweredWithNothing() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int[] ids = firstIds(caseId, 2);

        JsonNode error = session.expectError(McpError.INVALID_ARGUMENT, "iped_get_items", "case_id", caseId,
                "item_ids", ids, "fields", new String[0]);

        assertTrue(error.path("remedy").asText().contains("Omit"));
    }

    @Test
    public void idsThatAreNotInTheCaseAreStillReportedUnderAProjection() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int missing = session.call("iped_case_overview", "case_id", caseId).path("total_items").asInt() + 100000;
        int[] ids = firstIds(caseId, 1);

        JsonNode result = session.call("iped_get_items", "case_id", caseId, "item_ids",
                new int[] { ids[0], missing }, "fields", new String[] { "name" });

        assertEquals(1, result.path("items").size());
        assertEquals(missing, result.path("not_found").get(0).asInt());
        assertFalse(result.path("not_found_reason").asText().isEmpty());
    }

    /** The first ids of the case, in index order — any items will do, only the projection is at test. */
    private int[] firstIds(String caseId, int count) {
        JsonNode items = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", count,
                "include_snippets", false).path("items");
        int[] ids = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ids[i] = items.get(i).path("item_id").asInt();
        }
        return ids;
    }

    /** A field of this case that the default view does not already carry, or {@code null}. */
    private String aFieldBeyondTheEssentials(String caseId) {
        for (JsonNode field : session.call("iped_list_fields", "case_id", caseId).path("fields")) {
            String name = field.asText();
            if (name.indexOf(':') > 0) {
                return name;
            }
        }
        return null;
    }

    private static boolean declaresUnavailable(JsonNode item, String field) {
        for (JsonNode entry : item.path("unavailable")) {
            if (field.equals(entry.path("field").asText())) {
                return !entry.path("reason").asText().isEmpty();
            }
        }
        return false;
    }
}
