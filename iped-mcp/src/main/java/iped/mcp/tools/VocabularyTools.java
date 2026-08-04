package iped.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;

import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.query.FieldVocabulary;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;

/**
 * Field vocabulary tools: {@code iped_list_fields}, {@code iped_check_field} and
 * {@code iped_item_fields}.
 *
 * <p>
 * These exist because the biggest silent failure in querying a forensic case is asking for a field
 * that this index does not have. The query is valid, it runs, it returns nothing, and an agent that
 * does not check reports "no evidence found" — which is a wrong conclusion, not an empty result.
 *
 * <p>
 * The index is the authority. Any documentation about a canonical vocabulary is subordinate to what
 * these tools return (FR-050).
 */
public class VocabularyTools {

    private final Session session;

    public VocabularyTools(Session session) {
        this.session = session;
    }

    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> tools = new ArrayList<>();

        tools.add(new ToolDescriptor("iped_list_fields",
                "The field names actually present in this case's index. This is the source of truth: field names "
                        + "vary between cases and IPED versions, and this list wins over any documentation. "
                        + "Call it before writing a field-restricted query.",
                arguments -> listFields(caseOf(arguments))).required("case_id", "string",
                        "Case identifier returned by iped_open_case."));

        tools.add(new ToolDescriptor("iped_check_field",
                "Checks whether one field name exists in this case, and when it does not, returns the near names "
                        + "that do. Use it the moment a field-restricted query returns zero, before concluding "
                        + "anything from that zero.",
                arguments -> checkField(caseOf(arguments), Args.requiredString(arguments, "field",
                        "Pass the field name you want to check, exactly as you would write it in a query.")))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("field", "string", "Field name to check."));

        tools.add(new ToolDescriptor("iped_item_fields",
                "Every indexed field of one item, with its values. Vocabulary discovery from a concrete example: "
                        + "when you do not know what a case calls something, look at an item that has it.",
                arguments -> itemFields(caseOf(arguments), Args.requiredInt(arguments, "item_id",
                        "Pass an item_id from a result of iped_search on this same case.")))
                                .required("case_id", "string", "Case identifier returned by iped_open_case.")
                                .required("item_id", "integer", "Item identifier, local to this case."));

        return tools;
    }

    private OpenCase caseOf(com.fasterxml.jackson.databind.JsonNode arguments) {
        return session.getCaseRegistry().require(Args.requiredString(arguments, "case_id",
                "Pass the case_id returned by iped_open_case."));
    }

    private Map<String, Object> listFields(OpenCase openCase) {
        FieldVocabulary vocabulary = openCase.getVocabulary();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("fields", vocabulary.getFields());
        result.put("field_count", vocabulary.getFields().size());
        result.put("note", "'content' is the full text of items. It is searchable but is not listed here, "
                + "because it is the text itself rather than a property to filter on. A bare term with no "
                + "field prefix searches name and content.");
        return result;
    }

    private Map<String, Object> checkField(OpenCase openCase, String field) {
        FieldVocabulary vocabulary = openCase.getVocabulary();
        boolean exists = vocabulary.exists(field);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("field", field);
        result.put("exists", exists);
        if (!exists) {
            List<String> similar = vocabulary.similar(field, 8);
            result.put("similar", similar);
            result.put("remedy", similar.isEmpty()
                    ? "No near name was found. Call iped_list_fields for the full vocabulary of this case."
                    : "Retry with '" + similar.get(0) + "', the closest name this case actually has.");
        }
        return result;
    }

    private Map<String, Object> itemFields(OpenCase openCase, int itemId) {
        int luceneId = openCase.getSource().getLuceneId(itemId);
        if (itemId < 0 || itemId > openCase.getSource().getLastId() || luceneId < 0) {
            throw new McpError(McpError.ITEM_NOT_FOUND,
                    "There is no item " + itemId + " in case " + openCase.getCaseId() + ".",
                    "Item ids are local to a case. Take one from a result of iped_search on this same case; the "
                            + "highest id here is " + openCase.getSource().getLastId() + ".").with("itemId", itemId);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        try {
            Document doc = openCase.getSource().getSearcher().doc(luceneId);
            for (IndexableField field : doc.getFields()) {
                String value = field.stringValue();
                if (value == null || value.isEmpty()) {
                    continue;
                }
                Object existing = fields.get(field.name());
                if (existing == null) {
                    fields.put(field.name(), value);
                } else if (existing instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) existing;
                    list.add(value);
                } else {
                    List<Object> list = new ArrayList<>();
                    list.add(existing);
                    list.add(value);
                    fields.put(field.name(), list);
                }
            }
        } catch (Exception e) {
            throw new McpError(McpError.INTERNAL_ERROR, "The item's fields could not be read: " + e.getMessage(),
                    "Report this with the server log attached.", e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("item_id", itemId);
        result.put("fields", fields);
        result.put("note", "These are the stored fields of this one item. A field absent here may still exist "
                + "in the index for other items — call iped_list_fields for the case-wide vocabulary.");
        return result;
    }
}
