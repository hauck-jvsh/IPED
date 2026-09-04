package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import iped.mcp.audit.AuditRecord;
import iped.mcp.audit.AuditTrail;
import iped.mcp.config.McpServerConfig.AccessMode;
import iped.mcp.protocol.McpError;

/**
 * The full curation cycle with writes enabled (Scenario 7, FR-030).
 *
 * <p>
 * Persistence is checked by <b>reopening the case</b> rather than by reading back through the same
 * handle. Reading back in-process would pass against an implementation that never flushed anything
 * to disk, which is exactly the failure that matters: a bookmark the examiner cannot see in the
 * IPED UI was not created.
 */
public class BookmarkWriteTest {

    private static final String BOOKMARK = "MCP Test Findings";
    private static final String RENAMED = "MCP Test Findings (renamed)";

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp, AccessMode.READ_WRITE);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void createAssociateRenameRemoveSurvivesReopening() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        cleanUp(caseId);

        List<Integer> itemIds = firstItemIds(caseId, 5);
        int[] ids = itemIds.stream().mapToInt(Integer::intValue).toArray();

        session.call("iped_create_bookmark", "case_id", caseId, "name", BOOKMARK);
        JsonNode added = session.call("iped_add_to_bookmark", "case_id", caseId, "name", BOOKMARK, "item_ids", ids);
        assertEquals(ids.length, added.path("item_count").asInt());

        session.call("iped_rename_bookmark", "case_id", caseId, "old_name", BOOKMARK, "new_name", RENAMED);

        int[] toRemove = new int[] { ids[0], ids[1] };
        JsonNode removed = session.call("iped_remove_from_bookmark", "case_id", caseId, "name", RENAMED, "item_ids",
                toRemove);
        assertEquals(ids.length - toRemove.length, removed.path("item_count").asInt());

        // Reopen: this is the only check that proves the IPED UI would see it.
        session.call("iped_close_case", "case_id", caseId);
        String reopened = session.openCase(caseDir);
        JsonNode bookmarks = session.call("iped_list_bookmarks", "case_id", reopened).path("bookmarks");

        JsonNode found = null;
        for (JsonNode bookmark : bookmarks) {
            if (RENAMED.equals(bookmark.path("name").asText())) {
                found = bookmark;
            }
            assertFalse("the old name must be gone after the rename", BOOKMARK.equals(bookmark.path("name")
                    .asText()));
        }
        assertNotNull("the bookmark must survive reopening the case", found);
        assertEquals(ids.length - toRemove.length, found.path("item_count").asInt());

        cleanUp(reopened);
    }

    @Test
    public void deletingRecordsThePriorStateBeforeItRuns() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        cleanUp(caseId);

        int[] ids = firstItemIds(caseId, 3).stream().mapToInt(Integer::intValue).toArray();
        session.call("iped_create_bookmark", "case_id", caseId, "name", BOOKMARK);
        session.call("iped_add_to_bookmark", "case_id", caseId, "name", BOOKMARK, "item_ids", ids);

        session.call("iped_delete_bookmark", "case_id", caseId, "name", BOOKMARK);

        List<AuditRecord> records = AuditTrail.read(session.server().getSession().getAuditTrail().getFile());
        AuditRecord deletion = null;
        for (AuditRecord record : records) {
            if ("iped_delete_bookmark".equals(record.getOperation())
                    && record.getOutcome() == AuditRecord.Outcome.STARTED) {
                deletion = record;
            }
        }
        assertNotNull("the deletion must be recorded", deletion);
        assertNotNull("a destructive operation must carry its prior state (FR-033)", deletion.getPriorState());
        assertEquals(BOOKMARK, deletion.getPriorState().get("name"));
        assertEquals("the member ids are what make the prior state usable for recovery", ids.length,
                ((List<?>) deletion.getPriorState().get("item_ids")).size());
    }

    @Test
    public void selectionChangesPersist() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);

        int[] ids = firstItemIds(caseId, 3).stream().mapToInt(Integer::intValue).toArray();
        session.call("iped_set_selection", "case_id", caseId, "item_ids", ids, "selected", true);

        session.call("iped_close_case", "case_id", caseId);
        String reopened = session.openCase(caseDir);
        JsonNode selection = session.call("iped_get_selection", "case_id", reopened);

        assertTrue("the selection must survive reopening", selection.path("total_selected").asInt() >= ids.length);

        session.call("iped_set_selection", "case_id", reopened, "item_ids", ids, "selected", false);
    }

    @Test
    public void aBadIdInABatchWritesNothing() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        cleanUp(caseId);

        int[] ids = firstItemIds(caseId, 2).stream().mapToInt(Integer::intValue).toArray();
        session.call("iped_create_bookmark", "case_id", caseId, "name", BOOKMARK);

        JsonNode error = session.expectError(McpError.ITEM_NOT_FOUND, "iped_add_to_bookmark", "case_id", caseId,
                "name", BOOKMARK, "item_ids", new int[] { ids[0], Integer.MAX_VALUE - 1 });
        assertTrue("a partial write would leave a state the audit record does not describe",
                error.path("message").asText().contains("nothing was written"));

        assertEquals("the valid id in the batch must not have been written either", 0,
                bookmarkCount(caseId, BOOKMARK));

        cleanUp(caseId);
    }

    @Test
    public void creatingADuplicateNameIsRefused() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        cleanUp(caseId);

        session.call("iped_create_bookmark", "case_id", caseId, "name", BOOKMARK);
        JsonNode error = session.expectError(McpError.BOOKMARK_EXISTS, "iped_create_bookmark", "case_id", caseId,
                "name", BOOKMARK);
        assertTrue("the remedy must point at the tool that does what was probably meant",
                error.path("remedy").asText().contains("iped_add_to_bookmark"));

        cleanUp(caseId);
    }

    @Test
    public void searchCanPageAndIntersectOneBookmark() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        cleanUp(caseId);

        List<Integer> candidates = firstItemIds(caseId, 4);
        int[] bookmarked = candidates.subList(0, 3).stream().mapToInt(Integer::intValue).toArray();
        session.call("iped_create_bookmark", "case_id", caseId, "name", BOOKMARK);
        session.call("iped_add_to_bookmark", "case_id", caseId, "name", BOOKMARK, "item_ids", bookmarked);

        Set<Integer> found = new HashSet<>();
        String cursor = null;
        long total;
        do {
            JsonNode page = session.call("iped_search", "case_id", caseId, "query", "*:*", "bookmark", BOOKMARK,
                    "page_size", 2, "cursor", cursor, "include_snippets", false);
            assertEquals(BOOKMARK, page.path("bookmark").asText());
            total = page.path("total_matches").asLong();
            for (JsonNode item : page.path("items")) {
                found.add(item.path("item_id").asInt());
            }
            cursor = page.has("next_cursor") ? page.path("next_cursor").asText() : null;
        } while (cursor != null);

        Set<Integer> expected = new HashSet<>();
        for (int itemId : bookmarked) {
            expected.add(itemId);
        }
        assertEquals(bookmarked.length, total);
        assertEquals(expected, found);

        int outside = candidates.get(3);
        JsonNode intersection = session.call("iped_search", "case_id", caseId, "query", "id:" + outside,
                "bookmark", BOOKMARK, "include_snippets", false);
        assertEquals("query and bookmark must be intersected", 0, intersection.path("total_matches").asLong());

        cleanUp(caseId);
    }

    @Test
    public void searchDiagnosesAnUnknownBookmark() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode error = session.expectError(McpError.BOOKMARK_NOT_FOUND, "iped_search", "case_id", caseId,
                "query", "*:*", "bookmark", "bookmark-that-does-not-exist");

        assertTrue(error.path("remedy").asText().contains("iped_list_bookmarks"));
    }

    private int bookmarkCount(String caseId, String name) {
        for (JsonNode bookmark : session.call("iped_list_bookmarks", "case_id", caseId).path("bookmarks")) {
            if (name.equals(bookmark.path("name").asText())) {
                return bookmark.path("item_count").asInt();
            }
        }
        return 0;
    }

    private List<Integer> firstItemIds(String caseId, int count) {
        List<Integer> ids = new ArrayList<>();
        for (JsonNode item : session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", count,
                "include_snippets", false).path("items")) {
            ids.add(item.path("item_id").asInt());
        }
        return ids;
    }

    /** Leaves the reference case as it was found; these suites must be repeatable. */
    private void cleanUp(String caseId) {
        for (String name : new String[] { BOOKMARK, RENAMED }) {
            if (bookmarkExists(caseId, name)) {
                session.raw("iped_delete_bookmark", "case_id", caseId, "name", name);
            }
        }
    }

    private boolean bookmarkExists(String caseId, String name) {
        for (JsonNode bookmark : session.call("iped_list_bookmarks", "case_id", caseId).path("bookmarks")) {
            if (name.equals(bookmark.path("name").asText())) {
                return true;
            }
        }
        return false;
    }
}
