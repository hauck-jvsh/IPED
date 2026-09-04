package iped.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.query.Aggregator;
import iped.mcp.query.PagedSearcher;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;

/**
 * Query tools: {@code iped_search} and {@code iped_aggregate}.
 *
 * <p>
 * {@code iped_search} always reports an exact {@code total_matches} while returning at most a page
 * of items (FR-012, FR-013). Those two facts together are what let an agent tell "this query is too
 * broad" from "this query found little", which is the difference between narrowing and concluding.
 *
 * <p>
 * {@code iped_aggregate} answers "how many of each" without inspecting a single item (FR-016). It
 * is the cheap way to decide whether to refine before listing.
 */
public class QueryTools {

    private final Session session;
    private final PagedSearcher pagedSearcher;
    private final Aggregator aggregator;

    public QueryTools(Session session, PagedSearcher pagedSearcher, Aggregator aggregator) {
        this.session = session;
        this.pagedSearcher = pagedSearcher;
        this.aggregator = aggregator;
    }

    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> tools = new ArrayList<>();

        tools.add(new ToolDescriptor("iped_search",
                "Searches a case with IPED query syntax and returns one page of enriched items plus the exact "
                        + "total number of matches. total_matches is always exact and independent of how many "
                        + "items came back, so a large total means narrow the query rather than page through "
                        + "it. Optionally restrict the result to one bookmark. Page with next_cursor. Ordering "
                        + "is deterministic: the same query and bookmark return the same page in the same order.",
                arguments -> search(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .optional("query", "string",
                                "IPED query expression. A bare term searches name and content. Use field:value "
                                        + "for a restriction, quotes for phrases, AND/OR/NOT to combine, and "
                                        + "field:[a TO b] for ranges. Call iped_list_fields for the field names "
                                        + "this case has. Omit it when you pass a bookmark and want the whole "
                                        + "bookmark. To ask for every item write *:*, never a bare *: they mean "
                                        + "the same to you and not to the parser.")
                        .optional("bookmark", "string",
                                "Restrict results to items in this bookmark. Pass the exact name returned by "
                                        + "iped_list_bookmarks. The bookmark is combined with query as a filter, "
                                        + "and on its own — with no query — it lists the whole bookmark.")
                        .optional("page_size", "integer",
                                "Items per page. Defaults to the server default and is capped by its ceiling.")
                        .optional("cursor", "string", "next_cursor from the previous page. Omit for the first page.")
                        .optional("timeout_ms", "integer",
                                "Time budget for the scan. On exhaustion the result comes back with "
                                        + "partial=true, total_matches as a floor and no cursor, rather than "
                                        + "silently short. It bounds the scan, not the parsing: an expression "
                                        + "that is itself expensive to expand — a bare * over the text of every "
                                        + "item — spends its time before the clock is ever consulted.")
                        .optional("include_snippets", "boolean",
                                "Include a text excerpt showing why each item matched. Defaults to true. Set "
                                        + "false when paging quickly through a large set: snippets cost a text "
                                        + "extraction per item.")
                        .returnsContent("text"));

        tools.add(new ToolDescriptor("iped_aggregate",
                "Counts items by dimension without inspecting any of them. Use it to see the shape of a result "
                        + "set before listing it: which categories, which media types, which periods, which "
                        + "evidences, which bookmarks.",
                arguments -> aggregate(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("dimension", "string",
                                "One of: category, contentType, period, evidence, bookmark.")
                        .optional("query", "string",
                                "Optional IPED query restricting what is counted. Omit to count the whole case.")
                        .optional("max_buckets", "integer", "Largest number of buckets to return. Defaults to 50."));

        return tools;
    }

    private Object search(JsonNode arguments) {
        OpenCase openCase = session.getCaseRegistry().require(Args.requiredString(arguments, "case_id",
                "Pass the case_id returned by iped_open_case."));
        String query = Args.optionalString(arguments, "query", null);
        String bookmark = Args.optionalString(arguments, "bookmark", null);
        // A bookmark on its own is a complete request: it is what clicking a bookmark in the IPED UI
        // does, and there the operation is a membership filter rather than a query. Requiring a query
        // here forced the agent to invent an expression meaning "everything", and the one it reaches
        // for is a bare *, whose cost is the whole term dictionary. The tool laid that trap; this
        // removes it.
        boolean wholeBookmark = query == null || query.trim().isEmpty();
        if (wholeBookmark) {
            if (bookmark == null || bookmark.trim().isEmpty()) {
                throw new McpError(McpError.INVALID_ARGUMENT,
                        "Neither 'query' nor 'bookmark' was given, so there is nothing to look for.",
                        "Pass a query expression, or a bookmark name to list one whole bookmark. To page through "
                                + "the entire case, pass query as *:* — and prefer iped_case_overview or "
                                + "iped_aggregate when what you want is the shape of the collection rather than "
                                + "its items.").with("parameter", "query");
            }
            query = PagedSearcher.MATCH_ALL;
        }

        Map<String, Object> result = pagedSearcher.search(openCase, query, bookmark,
                Args.optionalInt(arguments, "page_size"), Args.optionalString(arguments, "cursor", null),
                Args.optionalLong(arguments, "timeout_ms"), Args.optionalBoolean(arguments, "include_snippets", true));
        if (wholeBookmark) {
            result.put("query_note", "No query was given, so this lists every item in the bookmark — the query "
                    + "shown is the match-all the server supplied. total_matches is the size of the bookmark.");
        }
        return result;
    }

    private Object aggregate(JsonNode arguments) {
        OpenCase openCase = session.getCaseRegistry().require(Args.requiredString(arguments, "case_id",
                "Pass the case_id returned by iped_open_case."));
        String dimension = Args.requiredString(arguments, "dimension",
                "Pass one of: category, contentType, period, evidence, bookmark.");
        Integer maxBuckets = Args.optionalInt(arguments, "max_buckets");
        return aggregator.aggregate(openCase, dimension, Args.optionalString(arguments, "query", null),
                maxBuckets == null || maxBuckets <= 0 ? 50 : maxBuckets);
    }
}
