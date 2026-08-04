package iped.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import iped.mcp.curation.BookmarkWriter;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;

/**
 * Bookmark tools. Everything except {@code iped_list_bookmarks} is a write operation, refused with
 * {@code WRITE_NOT_ENABLED} in the default read-only mode without the case being touched (FR-025),
 * and refused with {@code CONCURRENT_ACCESS} when another local process holds the case (FR-028).
 *
 * <p>
 * Deleting and renaming a pre-existing bookmark declare a prior-state provider, so the state they
 * are about to overwrite — including the full member list — reaches the audit trail before the
 * operation runs, not after (FR-033).
 */
public class BookmarkTools {

    private final Session session;
    private final BookmarkWriter writer;

    public BookmarkTools(Session session, BookmarkWriter writer) {
        this.session = session;
        this.writer = writer;
    }

    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> tools = new ArrayList<>();

        tools.add(new ToolDescriptor("iped_list_bookmarks",
                "Bookmarks in this case with their item counts. Reading, so available in any access mode.",
                arguments -> {
                    OpenCase openCase = caseOf(arguments);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("case_id", openCase.getCaseId());
                    result.put("bookmarks", writer.list(openCase));
                    return result;
                }).required("case_id", "string", "Case identifier returned by iped_open_case."));

        tools.add(new ToolDescriptor("iped_create_bookmark",
                "Creates an empty bookmark in the case. Name it after the finding it will hold, not after the "
                        + "query that found it: the bookmark outlives the query.",
                arguments -> writer.create(caseOf(arguments), Args.requiredString(arguments, "name",
                        "Pass a non-empty bookmark name.")))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("name", "string", "Name of the bookmark to create.").writeOperation());

        tools.add(new ToolDescriptor("iped_rename_bookmark",
                "Renames an existing bookmark. Destructive on a name an examiner may be relying on, so confirm "
                        + "with them before calling it, and expect the prior state to be recorded.",
                arguments -> writer.rename(caseOf(arguments),
                        Args.requiredString(arguments, "old_name", "Pass the current name of the bookmark."),
                        Args.requiredString(arguments, "new_name", "Pass the new name.")))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("old_name", "string", "Current name.")
                                .required("new_name", "string", "New name.").writeOperation()
                                .capturingPriorState(arguments -> priorState(arguments, "old_name")));

        tools.add(new ToolDescriptor("iped_delete_bookmark",
                "Deletes a bookmark and its membership. This discards curation work; confirm with the examiner "
                        + "before calling it. The prior state, member ids included, is recorded before the "
                        + "deletion runs.",
                arguments -> writer.delete(caseOf(arguments), Args.requiredString(arguments, "name",
                        "Pass the name of the bookmark to delete.")))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("name", "string", "Name of the bookmark to delete.").writeOperation()
                                .capturingPriorState(arguments -> priorState(arguments, "name")));

        tools.add(new ToolDescriptor("iped_add_to_bookmark",
                "Adds items to an existing bookmark. Nothing is written if any id is not in this case.",
                arguments -> writer.addItems(caseOf(arguments),
                        Args.requiredString(arguments, "name", "Pass the bookmark name."),
                        itemIds(arguments)))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("name", "string", "Bookmark to add to.")
                                .required("item_ids", "integer[]", "Item identifiers, local to this case.")
                                .writeOperation());

        tools.add(new ToolDescriptor("iped_remove_from_bookmark",
                "Removes items from a bookmark. The bookmark itself stays.",
                arguments -> writer.removeItems(caseOf(arguments),
                        Args.requiredString(arguments, "name", "Pass the bookmark name."),
                        itemIds(arguments)))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("name", "string", "Bookmark to remove from.")
                                .required("item_ids", "integer[]", "Item identifiers, local to this case.")
                                .writeOperation()
                                .capturingPriorState(arguments -> priorState(arguments, "name")));

        return tools;
    }

    private OpenCase caseOf(JsonNode arguments) {
        return session.getCaseRegistry().require(Args.requiredString(arguments, "case_id",
                "Pass the case_id returned by iped_open_case."));
    }

    private List<Integer> itemIds(JsonNode arguments) {
        return Args.requiredIntList(arguments, "item_ids",
                "Pass the ids as an array of integers, taken from a result of iped_search on this same case.",
                session.getConfig().getMaxBatchSize());
    }

    /**
     * Captures the bookmark about to be overwritten. Runs before the write gate has let anything
     * happen, and returns {@code null} when there is nothing to capture — a bookmark that does not
     * exist yet has no prior state, and the operation will fail on its own with a clearer message.
     */
    private Map<String, Object> priorState(JsonNode arguments, String nameParam) {
        try {
            OpenCase openCase = caseOf(arguments);
            String name = Args.optionalString(arguments, nameParam, null);
            return writer.captureBookmarkState(openCase, name);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
