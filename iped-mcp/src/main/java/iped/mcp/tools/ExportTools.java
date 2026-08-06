package iped.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import iped.data.IBookmarks;
import iped.engine.data.IPEDSource;
import iped.mcp.export.ArtifactWriter;
import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.query.PagedSearcher;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;

/**
 * {@code iped_export_artifact}: turns a bookmark, a query or an explicit list into a spreadsheet,
 * CSV or JSON file.
 *
 * <p>
 * The complete set goes to the file; the conversation gets a count, a sample and a path (FR-067).
 * That is the whole design: five thousand rows in a report are useful, five thousand rows in a
 * conversation are a context window spent on data nobody reads.
 *
 * <p>
 * The destination is refused inside the case folder by default (FR-068). A deliverable written into
 * the case would be indistinguishable, later, from something the case itself produced.
 */
public class ExportTools {

    /** Hard ceiling on how many items one artifact may carry, as a guard against a runaway query. */
    private static final int MAX_ITEMS = 1_000_000;

    private final Session session;
    private final PagedSearcher pagedSearcher;
    private final ArtifactWriter artifactWriter;

    public ExportTools(Session session, PagedSearcher pagedSearcher, ArtifactWriter artifactWriter) {
        this.session = session;
        this.pagedSearcher = pagedSearcher;
        this.artifactWriter = artifactWriter;
    }

    public List<ToolDescriptor> descriptors() {
        return Collections.singletonList(new ToolDescriptor("iped_export_artifact",
                "Writes the complete set defined by a bookmark, a query or an explicit id list to a file, in "
                        + "xlsx, csv or json. Nothing is paginated and nothing is truncated. You get back the "
                        + "count, a small sample and the path — the file is the deliverable, not this "
                        + "conversation.",
                arguments -> export(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("format", "string", "One of: xlsx, csv, json.")
                        .required("destination", "string",
                                "Absolute path of the file to write. Must be outside the case folder.")
                        .optional("bookmark", "string", "Export every item in this bookmark.")
                        .optional("query", "string", "Export every item matching this IPED query.")
                        .optional("item_ids", "integer[]", "Export exactly these items.")
                        .optional("group_by_conversation", "boolean",
                                "Group messages by conversation, in chronological order, with sender and "
                                        + "recipient identified. Use for chat exports.")
                        .returnsContent("metadata"));
    }

    private Map<String, Object> export(JsonNode arguments) {
        OpenCase openCase = session.getCaseRegistry().require(Args.requiredString(arguments, "case_id",
                "Pass the case_id returned by iped_open_case."));
        String format = Args.requiredString(arguments, "format", "Pass one of: xlsx, csv, json.");
        File destination = new File(Args.requiredString(arguments, "destination",
                "Pass an absolute file path outside the case folder."));
        checkDestination(openCase, destination);

        ItemSet set = resolveSet(openCase, arguments);
        Map<String, Object> result = artifactWriter.write(openCase, set.ids, format, destination,
                Args.optionalBoolean(arguments, "group_by_conversation", false));
        if (set.plan != null) {
            // An artifact outlives the conversation, so a repaired expression has to be legible
            // from the artifact's own result: it is what the examiner will cite.
            PagedSearcher.declareNormalization(result, set.plan);
        }
        return result;
    }

    /** The items to export, and the query plan that produced them when a query defined the set. */
    private static final class ItemSet {

        private final List<Integer> ids;
        private final PagedSearcher.QueryPlan plan;

        ItemSet(List<Integer> ids, PagedSearcher.QueryPlan plan) {
            this.ids = ids;
            this.plan = plan;
        }
    }

    /** Exactly one of bookmark, query or item_ids defines the set. */
    private ItemSet resolveSet(OpenCase openCase, JsonNode arguments) {
        String bookmark = Args.optionalString(arguments, "bookmark", null);
        String query = Args.optionalString(arguments, "query", null);
        JsonNode ids = arguments.get("item_ids");
        int given = (bookmark != null ? 1 : 0) + (query != null ? 1 : 0) + (ids != null && ids.isArray() ? 1 : 0);
        if (given != 1) {
            throw new McpError(McpError.INVALID_ARGUMENT,
                    "Exactly one of 'bookmark', 'query' or 'item_ids' defines what to export; " + given
                            + " were given.",
                    "Pass one of them. Use 'bookmark' for curated findings, 'query' for a result set, "
                            + "'item_ids' for a list you assembled yourself.");
        }
        if (bookmark != null) {
            return new ItemSet(fromBookmark(openCase, bookmark), null);
        }
        if (query != null) {
            PagedSearcher.QueryPlan plan = pagedSearcher.plan(openCase, query);
            return new ItemSet(pagedSearcher.collectAllIds(openCase,
                    pagedSearcher.forItems(openCase, plan.getQuery()), MAX_ITEMS), plan);
        }
        List<Integer> explicit = new ArrayList<>();
        for (JsonNode entry : ids) {
            if (entry.canConvertToInt()) {
                explicit.add(entry.asInt());
            }
        }
        return new ItemSet(explicit, null);
    }

    private List<Integer> fromBookmark(OpenCase openCase, String name) {
        IPEDSource source = openCase.getSource();
        IBookmarks bookmarks = source.getBookmarks();
        int bookmarkId = bookmarks == null ? -1 : bookmarks.getBookmarkId(name);
        if (bookmarkId == -1) {
            throw new McpError(McpError.BOOKMARK_NOT_FOUND, "There is no bookmark named '" + name + "'.",
                    "Call iped_list_bookmarks to see the names this case has.").with("name", name);
        }
        List<Integer> itemIds = new ArrayList<>();
        for (int id = 0; id <= source.getLastId(); id++) {
            if (source.getLuceneId(id) >= 0 && bookmarks.hasBookmark(id, bookmarkId)) {
                itemIds.add(id);
            }
        }
        return itemIds;
    }

    /**
     * Refuses a destination inside the case folder unless the configuration explicitly allows it
     * (FR-068).
     */
    private void checkDestination(OpenCase openCase, File destination) {
        if (session.getConfig().isAllowExportIntoCaseFolder()) {
            return;
        }
        try {
            String casePath = openCase.getCasePath().getCanonicalPath();
            File parent = destination.getCanonicalFile().getParentFile();
            String target = parent == null ? destination.getCanonicalPath() : parent.getCanonicalPath();
            if (target.equals(casePath) || target.startsWith(casePath + File.separator)) {
                throw new McpError(McpError.DESTINATION_REFUSED,
                        "The destination is inside the case folder, which is refused by default.",
                        "Write the artifact somewhere outside the case — a working folder or the report "
                                + "folder. An artifact written into the case becomes indistinguishable later "
                                + "from something the case itself produced.").with("destination",
                                        destination.getAbsolutePath()).with("casePath", casePath);
            }
        } catch (IOException e) {
            throw new McpError(McpError.DESTINATION_REFUSED,
                    "The destination path could not be resolved: " + e.getMessage(),
                    "Pass an absolute path whose parent folder exists.").with("destination",
                            destination.getAbsolutePath());
        }
    }
}
