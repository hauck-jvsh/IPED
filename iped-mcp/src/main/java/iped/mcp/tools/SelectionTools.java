package iped.mcp.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import iped.mcp.curation.BookmarkWriter;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;

/**
 * Selection tools: {@code iped_get_selection} and {@code iped_set_selection} (FR-027).
 *
 * <p>
 * The selection is what the IPED UI shows as checked items. It is the examiner's working set, so
 * changing it changes what they see in front of them — which is why setting it is a write operation
 * and reading it is not.
 */
public class SelectionTools {

    private final Session session;
    private final BookmarkWriter writer;

    public SelectionTools(Session session, BookmarkWriter writer) {
        this.session = session;
        this.writer = writer;
    }

    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> tools = new ArrayList<>();

        tools.add(new ToolDescriptor("iped_get_selection",
                "The items currently selected in this case — what the IPED UI shows as checked. Reading, so "
                        + "available in any access mode.",
                arguments -> {
                    Integer limit = Args.optionalInt(arguments, "limit");
                    return writer.getSelection(caseOf(arguments), limit == null || limit <= 0 ? 1000 : limit);
                }).required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .optional("limit", "integer", "Ids to return. Defaults to 1000; the total is always exact."));

        tools.add(new ToolDescriptor("iped_set_selection",
                "Selects or deselects items in the case. This changes what the examiner sees checked in the "
                        + "IPED UI, so say what it will do before doing it.",
                arguments -> writer.setSelection(caseOf(arguments),
                        Args.requiredIntList(arguments, "item_ids",
                                "Pass the ids as an array of integers, taken from a result of iped_search on "
                                        + "this same case.",
                                session.getConfig().getMaxBatchSize()),
                        Args.optionalBoolean(arguments, "selected", true)))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("item_ids", "integer[]", "Item identifiers, local to this case.")
                                .optional("selected", "boolean", "true to select, false to deselect. Defaults true.")
                                .writeOperation());

        return tools;
    }

    private OpenCase caseOf(JsonNode arguments) {
        return session.getCaseRegistry().require(Args.requiredString(arguments, "case_id",
                "Pass the case_id returned by iped_open_case."));
    }
}
