package iped.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.lucene.document.Document;

import iped.mcp.item.ContentAccess;
import iped.mcp.item.FieldSelection;
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
 * Its {@code fields} parameter closes the other half of that gap. The default projection is the
 * essential properties, which are the same for every case; the fields that decide an investigation
 * are often case-specific — {@code ufed:UserID}, {@code p2p:fileType}, an EXIF tag — and reaching
 * those used to mean one {@code iped_item_fields} call per item. {@link FieldSelection} resolves the
 * names against this case before anything is read, so a misspelled one fails the call instead of
 * coming back as an absence in every item.
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
                "Properties of a batch of items in one call. Use this instead of calling a per-item tool in a "
                        + "loop. Returns the essential properties by default, or exactly the fields named in "
                        + "'fields' — which is how you read a case-specific field, such as a chat sender or an "
                        + "EXIF tag, across a whole result set.",
                arguments -> getItems(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("item_ids", "integer[]",
                                "Item identifiers, local to this case. Capped by the server batch ceiling.")
                        .optional("fields", "string[]",
                                "Return only these fields, instead of the default essential properties. Names as "
                                        + "iped_list_fields and iped_item_fields give them: plain, with no "
                                        + "backslash before a colon, because this is a list of names and not a "
                                        + "query expression. The keys this server publishes in an item are "
                                        + "accepted too (content_type, parent_id, is_dir, bookmarks, selected). A "
                                        + "name this case does not have is refused with the near names attached, "
                                        + "never silently dropped.")
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
        List<String> askedFields = Args.optionalStringList(arguments, "fields",
                "Pass the field names as an array of strings, as iped_list_fields returns them. Omit the "
                        + "parameter to get the essential properties.",
                session.getConfig().getMaxBatchSize());
        // Resolved once, against this case, before any document is read: a name this index does not
        // have has to fail the call rather than come back as an absence in every item.
        FieldSelection selection = askedFields == null ? null
                : FieldSelection.resolve(openCase.getVocabulary(), askedFields);
        Set<String> storedFields = selection == null ? ItemView.storedFields()
                : selection.storedFields();

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
                Document doc = openCase.getSource().getSearcher().doc(luceneId, storedFields);
                if (selection != null) {
                    items.add(selection.project(openCase.getSource(), openCase.getCaseId(), luceneId, doc));
                    continue;
                }
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
        if (selection != null) {
            // What was read has to be readable from the answer alone. Without this an agent can mistake
            // a narrow projection for the whole of what the items carry, and report a field as absent
            // when it was simply never asked for.
            result.put("projection", selection.askedFields());
            result.put("projection_note", "Only the fields in 'projection' were read. A field missing from an "
                    + "item's 'fields' is declared in its 'unavailable' with the reason; a field missing from "
                    + "'projection' was never asked for and says nothing about the items. Call iped_item_fields "
                    + "for everything one item carries.");
            if (!selection.renamedFields().isEmpty()) {
                result.put("resolved_fields", selection.renamedFields());
                result.put("resolved_fields_note", "These names were answered by the index field named beside "
                        + "them. The keys of each item's 'fields' are the names you asked for.");
            }
        }
        if (!notFound.isEmpty()) {
            result.put("not_found", notFound);
            result.put("not_found_reason", "These ids are not in case " + openCase.getCaseId()
                    + ". Item ids are local to a case and collide between cases; the highest id here is "
                    + openCase.getSource().getLastId() + ".");
        }
        return result;
    }
}
