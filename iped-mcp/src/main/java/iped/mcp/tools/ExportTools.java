package iped.mcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import iped.data.IBookmarks;
import iped.engine.data.IPEDSource;
import iped.mcp.config.McpServerConfig;
import iped.mcp.export.ArtifactWriter;
import iped.mcp.export.PathConfinement;
import iped.mcp.export.PathConfinement.ResolvedDestination;
import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.query.PagedSearcher;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;
import iped.mcp.transport.Transport;

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
        String requested = Args.requiredString(arguments, "destination",
                "Pass an absolute file path inside one of the folders this server may write to.");
        // Before the set is materialized, and before anything touches the filesystem: a refused
        // destination must cost nothing and leave nothing (FR-002).
        ResolvedDestination destination = checkDestination(openCase, requested);

        ItemSet set = resolveSet(openCase, arguments);
        Map<String, Object> result = artifactWriter.write(openCase, set.ids, format, destination.getResolved(),
                Args.optionalBoolean(arguments, "group_by_conversation", false));
        if (session.getTransport() == Transport.Kind.SOCKET) {
            // The examiner is on another machine and cannot tell, from the path alone, which
            // filesystem produced it. Saying so beats letting them go looking on the wrong one.
            result.put("destination_filesystem", "server");
            result.put("destination_note", "This path is on the machine running the MCP server, not on the machine "
                    + "running this conversation. Retrieve the file there.");
        }
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
     * Approves a destination against the declared write roots, or refuses it (FR-001, FR-004).
     *
     * <p>
     * This is a <b>list of what is permitted</b>, not a list of what is forbidden. The previous rule
     * refused the case folder and allowed the rest of the filesystem the account could reach, which
     * protected the case and did nothing for the workstation. A deny list leaves everything nobody
     * anticipated open, and what nobody anticipated is where the defect lives.
     *
     * <p>
     * {@code allowExportIntoCaseFolder} still exists and still means what it says, but it now means
     * only that: it suppresses the case-folder refusal and does not turn the rest of the disk back
     * on. Anyone who was using it to write to an arbitrary folder will start getting refused, and
     * that is the point of the change.
     */
    private ResolvedDestination checkDestination(OpenCase openCase, String requested) {
        McpServerConfig config = session.getConfig();
        ResolvedDestination destination = PathConfinement.resolve(requested, config.getResolvedExportRoots(),
                openCase.getCasePath(), config.isAllowExportIntoCaseFolder());
        if (destination.isAllowed()) {
            return destination;
        }
        throw refusal(destination, openCase);
    }

    /**
     * Turns a refusal into a diagnostic the agent can act on without the examiner stepping in
     * (FR-008), and that the trail can record with the rule that applied (FR-007).
     */
    private static McpError refusal(ResolvedDestination destination, OpenCase openCase) {
        String permitted = describeRoots(destination.getRoots());
        McpError error;
        switch (destination.getVerdict()) {
            case INSIDE_CASE:
                error = new McpError(McpError.DESTINATION_REFUSED,
                        "The destination is inside the case folder, which is refused.",
                        "Write the artifact somewhere outside the case — a working folder or the report "
                                + "folder. An artifact written into the case becomes indistinguishable later "
                                + "from something the case itself produced. Permitted: " + permitted + ".")
                                        .with("casePath", openCase.getCasePath().getAbsolutePath());
                break;
            case UNRESOLVABLE:
                error = new McpError(McpError.DESTINATION_REFUSED,
                        "The destination is not a path this system can name: " + destination.getReason() + ".",
                        "Pass an absolute path to a plain file — no alternate data stream after a colon, no "
                                + "trailing space or dot — under one of: " + permitted + ".");
                break;
            case OUTSIDE_ROOTS:
            default:
                error = new McpError(McpError.DESTINATION_REFUSED,
                        "The destination is outside every folder this server may write to.",
                        "Write the artifact under one of: " + permitted + ". These are declared in "
                                + "conf/McpServerConfig.txt and cannot be changed from this conversation.");
                break;
        }
        error.with("destination", destination.getRequested()).with("rule", destination.getVerdict().name())
                .with("permittedRoots", describeRootList(destination.getRoots()));
        if (destination.getResolved() != null) {
            // Where it would really have landed. For a junction or a short name this is the whole
            // explanation, and without it the refusal looks arbitrary to whoever asked.
            error.with("resolvedTo", destination.getResolved().toString());
        }
        return error;
    }

    private static String describeRoots(List<Path> roots) {
        if (roots.isEmpty()) {
            return "(no writable folder is configured — see exportRoots in conf/McpServerConfig.txt)";
        }
        List<String> quoted = new ArrayList<>(roots.size());
        for (Path root : roots) {
            quoted.add(root.toString());
        }
        return String.join(", ", quoted);
    }

    private static List<String> describeRootList(List<Path> roots) {
        List<String> list = new ArrayList<>(roots.size());
        for (Path root : roots) {
            list.add(root.toString());
        }
        return list;
    }
}
