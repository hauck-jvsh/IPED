package iped.mcp.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

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
                        + "it. Page with next_cursor. Ordering is deterministic: the same query returns the "
                        + "same page in the same order.",
                arguments -> search(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("query", "string",
                                "IPED query expression. A bare term searches name and content. Use field:value "
                                        + "for a restriction, quotes for phrases, AND/OR/NOT to combine, and "
                                        + "field:[a TO b] for ranges. Call iped_list_fields for the field names "
                                        + "this case has.")
                        .optional("page_size", "integer",
                                "Items per page. Defaults to the server default and is capped by its ceiling.")
                        .optional("cursor", "string", "next_cursor from the previous page. Omit for the first page.")
                        .optional("timeout_ms", "integer",
                                "Time budget for this query. On exhaustion the result comes back with "
                                        + "partial=true rather than silently short.")
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
        String query = Args.requiredString(arguments, "query",
                "Pass a query expression. Use an empty-ish broad query only with iped_aggregate; here it would "
                        + "just return the first page of the whole case.");
        return pagedSearcher.search(openCase, query, Args.optionalInt(arguments, "page_size"),
                Args.optionalString(arguments, "cursor", null), Args.optionalLong(arguments, "timeout_ms"),
                Args.optionalBoolean(arguments, "include_snippets", true));
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
