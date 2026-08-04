package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;

/**
 * Pagination, exact totals and deterministic ordering (Scenario 3; FR-012, FR-013, FR-019).
 *
 * <p>
 * This is the suite that separates the delivery from the proof of concept. Note the caveat in
 * quickstart.md though: these assertions pass on the small case even against an implementation that
 * materializes the whole result set. The cost characteristic is what {@code ScalePerformanceTest}
 * measures, and it only shows up on the large case.
 */
public class PaginationTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void totalIsExactWhileItemsStayWithinThePage() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode page = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 5,
                "include_snippets", false);

        long total = page.path("total_matches").asLong();
        assertTrue("a broad query must report a real total", total > 0);
        assertTrue("the page must respect page_size regardless of the total", page.path("items").size() <= 5);
        assertEquals(5, page.path("page_size").asInt());
        if (total > 5) {
            assertTrue("a cursor must be offered while there is more", page.has("next_cursor"));
        }
    }

    @Test
    public void aBroadQueryNeverReturnsTheWholeSet() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        // FR-013: even asking for more than the ceiling must not produce the whole collection.
        JsonNode page = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 100000,
                "include_snippets", false);

        int ceiling = session.config().getMaxPageSize();
        assertTrue("the server ceiling must apply: got " + page.path("items").size(),
                page.path("items").size() <= ceiling);
        assertEquals(ceiling, page.path("page_size").asInt());
    }

    @Test
    public void pagingCoversEverythingExactlyOnce() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        Set<Integer> seen = new HashSet<>();
        List<Integer> order = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        long total;
        do {
            JsonNode page = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 20,
                    "cursor", cursor, "include_snippets", false);
            total = page.path("total_matches").asLong();
            for (JsonNode item : page.path("items")) {
                int itemId = item.path("item_id").asInt();
                assertTrue("item " + itemId + " came back on two different pages", seen.add(itemId));
                order.add(itemId);
            }
            cursor = page.has("next_cursor") ? page.path("next_cursor").asText() : null;
        } while (cursor != null && ++pages < 5000);

        assertEquals("paging must cover the whole set, with no gap and no repetition", total, seen.size());
    }

    @Test
    public void theSameQueryReturnsTheSamePageInTheSameOrder() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        List<Integer> first = idsOf(session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 20,
                "include_snippets", false));
        List<Integer> second = idsOf(session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 20,
                "include_snippets", false));

        // FR-019: without this, a second examiner reproducing the trail gets a different set.
        assertEquals("ordering must be deterministic across identical queries", first, second);
    }

    @Test
    public void anInvalidCursorIsDiagnosedRatherThanSilentlyIgnored() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode error = session.expectError("INVALID_CURSOR", "iped_search", "case_id", caseId, "query", "*:*",
                "cursor", "not-a-cursor-we-issued");

        assertTrue("the remedy must say how to recover",
                error.path("remedy").asText().contains("previous page"));
    }

    @Test
    public void aQueryWithNoMatchesReportsZeroRatherThanFailing() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode page = session.call("iped_search", "case_id", caseId, "query",
                "\"zqxjkvwmpblrfhtn nothing matches this\"", "include_snippets", false);

        assertEquals(0, page.path("total_matches").asLong());
        assertEquals(0, page.path("items").size());
        assertFalse("no cursor may be offered for an empty result", page.has("next_cursor"));
    }

    @Test
    public void aSyntaxErrorReportsWhereTheProblemIs() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode error = session.expectError("QUERY_SYNTAX", "iped_search", "case_id", caseId, "query",
                "name:(unclosed AND");

        assertTrue("the remedy must explain the syntax", error.path("remedy").asText().contains("escape"));
    }

    private static List<Integer> idsOf(JsonNode page) {
        List<Integer> ids = new ArrayList<>();
        for (JsonNode item : page.path("items")) {
            ids.add(item.path("item_id").asInt());
        }
        return ids;
    }

    /** Kept for symmetry with the other suites that resolve the case path directly. */
    static File referenceCase() {
        return McpTestSupport.requireReferenceCase();
    }
}
