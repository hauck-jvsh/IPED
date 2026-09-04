package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.protocol.McpError;

/**
 * What a page of results costs, and what its total is allowed to claim (FR-012, FR-079, FR-081,
 * FR-082).
 *
 * <p>
 * Written after a field report: one call — a bookmark plus the query {@code "*"} — took minutes. The
 * bookmark filter was not the cause. The bare star was a wildcard over the name and text of every
 * item, and the page ran the whole thing twice, because the exact total came from a second
 * {@code searcher.count()} that no time budget covered.
 *
 * <p>
 * These are the properties that keep that from coming back: everything has a cheap spelling, a
 * bookmark needs no query at all, and a total that could not be counted in full says so instead of
 * pretending.
 */
public class SearchTotalsTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void theTotalIsTheWholeSetAndTheSameOnEveryPage() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode first = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 5,
                "include_snippets", false);
        long total = first.path("total_matches").asLong();

        assertTrue("the case must have more items than one page", total > 5);
        assertTrue("a complete scan reports an exact total", first.path("total_matches_exact").asBoolean());
        assertFalse(first.path("partial").asBoolean());
        assertEquals("the total is the result set, not the page", 5, first.path("items").size());

        String cursor = first.path("next_cursor").asText(null);
        assertTrue("a full page must carry a cursor", cursor != null && !cursor.isEmpty());
        for (int page = 2; page <= 3 && cursor != null && !cursor.isEmpty(); page++) {
            JsonNode next = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 5, "cursor",
                    cursor, "include_snippets", false);
            assertEquals("page " + page + " must report the same total", total, next.path("total_matches").asLong());
            assertTrue(next.path("total_matches_exact").asBoolean());
            cursor = next.path("next_cursor").asText(null);
        }
    }

    @Test
    public void aBareStarMeansEverythingAndIsAnsweredAsSuch() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode canonical = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 1,
                "include_snippets", false);
        JsonNode star = session.call("iped_search", "case_id", caseId, "query", "*", "page_size", 1,
                "include_snippets", false);

        assertEquals("a bare star must count what *:* counts", canonical.path("total_matches").asLong(),
                star.path("total_matches").asLong());
        // Rewritten is not the same as silently rewritten: what ran has to be readable from the answer,
        // because the answer is what ends up in a report.
        assertEquals("*:*", star.path("query_normalized").asText());
        assertTrue(star.path("query_normalized_note").asText().contains("*:*"));
    }

    @Test
    public void aBookmarkOnItsOwnListsTheWholeBookmark() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        String bookmark = aBookmarkOfThisCase(caseId);
        Assume.assumeTrue("this case has no bookmarks", bookmark != null);

        // No query at all: the same request as clicking the bookmark in the IPED UI.
        JsonNode result = session.call("iped_search", "case_id", caseId, "bookmark", bookmark, "page_size", 5,
                "include_snippets", false);

        assertEquals(bookmark, result.path("bookmark").asText());
        assertTrue("the substitution must be declared", result.path("query_note").asText().contains("match-all"));
        assertTrue(result.path("total_matches_exact").asBoolean());

        long withExplicitQuery = session.call("iped_search", "case_id", caseId, "bookmark", bookmark, "query", "*:*",
                "page_size", 1, "include_snippets", false).path("total_matches").asLong();
        assertEquals("omitting the query must count exactly what *:* counts", withExplicitQuery,
                result.path("total_matches").asLong());
    }

    @Test
    public void neitherQueryNorBookmarkIsRefusedWithSomethingToDo() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode error = session.expectError(McpError.INVALID_ARGUMENT, "iped_search", "case_id", caseId,
                "page_size", 5);

        assertTrue("the remedy must name the cheap way to ask for everything",
                error.path("remedy").asText().contains("*:*"));
    }

    @Test
    public void aScanCutShortDeclaresAFloorAndIssuesNoCursor() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode result = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 5,
                "timeout_ms", 1, "include_snippets", false);
        Assume.assumeTrue("this case is small enough to scan inside the smallest budget",
                result.path("partial").asBoolean());

        assertFalse("a total counted up to the cut is not exact", result.path("total_matches_exact").asBoolean());
        assertTrue("and it must be described as a floor", result.path("partial_note").asText().contains("floor"));
        // FR-079: a cursor that cannot be trusted to advance over the whole set is declared absent.
        // One taken from a partial page resumes past hits the scan never reached, and they would be
        // missing from every later page too.
        assertFalse("no cursor may be handed out from a partial page", result.has("next_cursor"));
        assertFalse(result.path("next_cursor_omitted").asText().isEmpty());
    }

    /** Any bookmark this case happens to have — the test is about the filter, not the name. */
    private String aBookmarkOfThisCase(String caseId) {
        for (JsonNode bookmark : session.call("iped_list_bookmarks", "case_id", caseId).path("bookmarks")) {
            // A bookmark with no items would make every comparison below trivially true.
            if (bookmark.path("item_count").asLong(0) > 0) {
                return bookmark.path("name").asText();
            }
        }
        return null;
    }
}
