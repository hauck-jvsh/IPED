package iped.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.lucene.document.Document;

import iped.mcp.item.ContentAccess;
import iped.mcp.item.ItemView;
import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;

/**
 * Item inspection tools: {@code iped_get_items}, {@code iped_item_metadata}, {@code iped_item_text},
 * {@code iped_item_thumbnail}, {@code iped_item_content} and {@code iped_item_tree}.
 *
 * <p>
 * {@code iped_get_items} takes a batch in one call (FR-024). It is what keeps an agent from making
 * one call per item after a search — the pattern that makes a fifty-item result set cost fifty
 * round trips.
 *
 * <p>
 * Each content tool declares its content class, so the egress policy applies at the dispatcher
 * boundary rather than depending on this class remembering to ask.
 */
public class ItemTools {

    private final Session session;
    private final ContentAccess contentAccess;

    public ItemTools(Session session, ContentAccess contentAccess) {
        this.session = session;
        this.contentAccess = contentAccess;
    }

    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> tools = new ArrayList<>();

        tools.add(new ToolDescriptor("iped_get_items",
                "Essential properties of a batch of items in one call. Use this instead of calling a per-item "
                        + "tool in a loop.",
                arguments -> getItems(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("item_ids", "integer[]",
                                "Item identifiers, local to this case. Capped by the server batch ceiling.")
                        .returnsContent("metadata"));

        tools.add(new ToolDescriptor("iped_item_metadata",
                "Everything the parsers extracted for one item: EXIF, GPS coordinates, mail headers, codec "
                        + "details, and the extra attributes assigned during processing.",
                arguments -> contentAccess.metadata(caseOf(arguments), itemOf(arguments)))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("item_id", "integer", "Item identifier, local to this case.")
                        .returnsContent("metadata"));

        tools.add(new ToolDescriptor("iped_item_text",
                "Extracted text of one item, under a size ceiling. Truncation is always signalled. When there "
                        + "is no text, says so with the reason and points at what else to try — an empty answer "
                        + "here never means an empty item.",
                arguments -> contentAccess.text(caseOf(arguments), itemOf(arguments),
                        Args.optionalInt(arguments, "max_chars")))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("item_id", "integer", "Item identifier, local to this case.")
                                .optional("max_chars", "integer",
                                        "Characters to return, capped by the server ceiling.")
                                .returnsContent("text"));

        tools.add(new ToolDescriptor("iped_item_thumbnail",
                "Thumbnail of one item, base64-encoded. Absence is declared: an item with no thumbnail says so "
                        + "and why.",
                arguments -> contentAccess.thumbnail(caseOf(arguments), itemOf(arguments)))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("item_id", "integer", "Item identifier, local to this case.")
                        .returnsContent("thumbnail"));

        tools.add(new ToolDescriptor("iped_item_content",
                "Raw bytes of one item, base64-encoded, under a conservative ceiling. Truncation is signalled "
                        + "and the real size reported.",
                arguments -> contentAccess.content(caseOf(arguments), itemOf(arguments),
                        Args.optionalInt(arguments, "max_bytes")))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("item_id", "integer", "Item identifier, local to this case.")
                                .optional("max_bytes", "integer",
                                        "Bytes to return, capped by the server ceiling.")
                                .returnsContent("binary"));

        tools.add(new ToolDescriptor("iped_item_tree",
                "The container an item sits in and the items it contains — a mail and its attachments, an "
                        + "archive and its entries — without building the query by hand.",
                arguments -> {
                    Integer max = Args.optionalInt(arguments, "max_children");
                    return contentAccess.tree(caseOf(arguments), itemOf(arguments),
                            max == null || max <= 0 ? 100 : max);
                }).required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("item_id", "integer", "Item identifier, local to this case.")
                        .optional("max_children", "integer", "Contained items to list. Defaults to 100.")
                        .returnsContent("metadata"));

        return tools;
    }

    private OpenCase caseOf(JsonNode arguments) {
        return session.getCaseRegistry().require(Args.requiredString(arguments, "case_id",
                "Pass the case_id returned by iped_open_case."));
    }

    private int itemOf(JsonNode arguments) {
        return Args.requiredInt(arguments, "item_id",
                "Pass an item_id from a result of iped_search on this same case. Ids are local to a case and "
                        + "collide between cases.");
    }

    private Map<String, Object> getItems(JsonNode arguments) {
        OpenCase openCase = caseOf(arguments);
        List<Integer> itemIds = Args.requiredIntList(arguments, "item_ids",
                "Pass the ids as an array of integers, taken from a result of iped_search on this same case.",
                session.getConfig().getMaxBatchSize());

        List<Map<String, Object>> items = new ArrayList<>(itemIds.size());
        List<Integer> notFound = new ArrayList<>();
        for (Integer itemId : itemIds) {
            int luceneId = itemId == null || itemId < 0 || itemId > openCase.getSource().getLastId() ? -1
                    : openCase.getSource().getLuceneId(itemId);
            if (luceneId < 0) {
                notFound.add(itemId);
                continue;
            }
            try {
                Document doc = openCase.getSource().getSearcher().doc(luceneId, ItemView.storedFields());
                Map<String, Object> view = ItemView.of(openCase.getSource(), luceneId, doc, null);
                view.put("case_id", openCase.getCaseId());
                items.add(view);
            } catch (Exception e) {
                throw new McpError(McpError.INTERNAL_ERROR,
                        "Item " + itemId + " could not be read: " + e.getMessage(),
                        "Report this with the server log attached.", e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("items", items);
        result.put("requested", itemIds.size());
        if (!notFound.isEmpty()) {
            result.put("not_found", notFound);
            result.put("not_found_reason", "These ids are not in case " + openCase.getCaseId()
                    + ". Item ids are local to a case and collide between cases; the highest id here is "
                    + openCase.getSource().getLastId() + ".");
        }
        return result;
    }
}
