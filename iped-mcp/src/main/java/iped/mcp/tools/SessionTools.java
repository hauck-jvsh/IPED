package iped.mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import iped.engine.data.Category;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.query.Aggregator;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;

/**
 * Session and case tools: {@code iped_session_info}, {@code iped_open_case},
 * {@code iped_case_overview} and {@code iped_close_case}.
 *
 * <p>
 * {@code iped_case_overview} exists so the agent can orient itself in <b>one</b> call (FR-006).
 * Without it the first thing any investigation does is fire a dozen exploratory queries just to
 * learn the shape of the collection — which is expensive, and worse, is where an agent forms a
 * wrong picture of what is in the case.
 */
public class SessionTools {

    private final Session session;
    private final Aggregator aggregator;

    public SessionTools(Session session, Aggregator aggregator) {
        this.session = session;
        this.aggregator = aggregator;
    }

    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> tools = new ArrayList<>();

        tools.add(new ToolDescriptor("iped_session_info",
                "State of this session: operator, access mode, egress policy in force, open cases, and the audit "
                        + "trail's location and synchronization state. Call it first if you need to know whether "
                        + "writing is enabled or what content may be transmitted.",
                arguments -> session.describe()));

        tools.add(new ToolDescriptor("iped_open_case",
                "Opens a processed IPED case for querying. Validates the case before accepting it and reports "
                        + "exactly what is wrong when it cannot. Reopening an already open case succeeds and "
                        + "returns the same case_id. Returns case_id, total_items, iped_version and evidences.",
                arguments -> openCase(Args.requiredString(arguments, "case_path",
                        "Pass the absolute path of the IPED output folder — the one that contains the 'iped' "
                                + "subfolder, not the 'iped' subfolder itself.")))
                                        .required("case_path", "string",
                                                "Absolute path of the IPED output folder, the one containing the "
                                                        + "'iped' subfolder."));

        tools.add(new ToolDescriptor("iped_case_overview",
                "The shape of a case in one call: total items, evidences, categories with counts and bookmarks "
                        + "with counts. This is the first call to make after opening a case — it is what lets "
                        + "you narrow deliberately instead of guessing.",
                arguments -> overview(Args.requiredString(arguments, "case_id",
                        "Pass the case_id returned by iped_open_case."))).required("case_id", "string",
                                "Case identifier returned by iped_open_case."));

        tools.add(new ToolDescriptor("iped_close_case",
                "Closes a case and releases its resources, leaving no lock on the folder. The audit trail is "
                        + "synchronized into the case folder as part of closing.",
                arguments -> closeCase(Args.requiredString(arguments, "case_id",
                        "Pass the case_id returned by iped_open_case."))).required("case_id", "string",
                                "Case identifier returned by iped_open_case."));

        return tools;
    }

    private Map<String, Object> openCase(String casePath) {
        OpenCase openCase = session.getCaseRegistry().open(new File(casePath));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("case_path", openCase.getCasePath().getAbsolutePath());
        result.put("iped_version", openCase.getIpedVersion());
        result.put("total_items", openCase.getTotalItems());
        result.put("evidences", openCase.describeEvidences());
        result.put("field_count", openCase.getVocabulary().getFields().size());
        result.put("warnings", openCase.getWarnings());
        result.put("next_step", "Call iped_case_overview with this case_id to see what the case contains before "
                + "querying it.");
        return result;
    }

    private Map<String, Object> overview(String caseId) {
        OpenCase openCase = session.getCaseRegistry().require(caseId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", caseId);
        result.put("case_path", openCase.getCasePath().getAbsolutePath());
        result.put("iped_version", openCase.getIpedVersion());
        result.put("total_items", openCase.getTotalItems());
        result.put("evidences", openCase.describeEvidences());
        result.put("categories", categories(openCase));
        result.put("bookmarks", aggregator.aggregate(openCase, "bookmark", null, 200).get("buckets"));
        result.put("field_count", openCase.getVocabulary().getFields().size());
        result.put("note", "Category counts come from the case's own category tree and include descendants. "
                + "Call iped_list_fields before writing a field-restricted query: field names vary between "
                + "cases and the index is the only authority on them.");
        return result;
    }

    /** Category tree flattened into counted buckets, from the case's own tree. */
    private List<Map<String, Object>> categories(OpenCase openCase) {
        List<Map<String, Object>> categories = new ArrayList<>();
        Category root = openCase.getSource().getCategoryTree();
        if (root != null) {
            collect(root, categories, 0);
        }
        return categories;
    }

    private void collect(Category category, List<Map<String, Object>> out, int depth) {
        if (depth > 0) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", category.getName());
            entry.put("count", category.getNumItems());
            entry.put("depth", depth);
            out.add(entry);
        }
        for (Category child : category.getChildren()) {
            collect(child, out, depth + 1);
        }
    }

    private Map<String, Object> closeCase(String caseId) {
        session.getCaseRegistry().close(caseId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", caseId);
        result.put("closed", true);
        return result;
    }
}
