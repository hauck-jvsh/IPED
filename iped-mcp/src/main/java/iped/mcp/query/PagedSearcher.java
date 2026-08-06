package iped.mcp.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TimeLimitingCollector;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldCollector;

import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.engine.search.QueryBuilder;
import iped.engine.task.index.IndexItem;
import iped.mcp.config.McpServerConfig;
import iped.mcp.item.ContentAccess;
import iped.mcp.item.ItemView;
import iped.mcp.protocol.McpError;
import iped.mcp.session.OpenCase;

/**
 * Paged query over a case, with an exact total.
 *
 * <p>
 * <b>This class deliberately does not use {@code IPEDSearcher}.</b> Its {@code searchAll()}
 * materializes every matching document — it collects over {@code maxDoc()} and, when scoring,
 * iterates {@code searchAfter} in blocks until the set is exhausted. On a ten-million-item case with
 * a broad query that is the whole collection in memory, which is what FR-013 and SC-002 exist to
 * prevent. The finding is documented in R3; the fix lives here rather than in
 * {@code IPEDSearcher}, which is central API used by the UI.
 *
 * <p>
 * What is reused from the engine is what carries IPED semantics and cannot be reimplemented:
 * {@link QueryBuilder#getQuery(String)} for the query syntax, the second rewrite pass that maps
 * content matches onto their parent item documents, and the tree-node exclusion.
 *
 * <p>
 * Ordering is deterministic: score descending, ties broken by document order, so the same query
 * returns the same page in the same order (FR-019).
 */
public class PagedSearcher {

    /** Score first, document order as the stable tiebreaker. Owned by {@link Cursor}. */
    private static final Sort DETERMINISTIC_SORT = Cursor.SORT;

    private final McpServerConfig config;
    private final ContentAccess contentAccess;
    private final SnippetBuilder snippetBuilder;

    public PagedSearcher(McpServerConfig config, ContentAccess contentAccess, SnippetBuilder snippetBuilder) {
        this.config = config;
        this.contentAccess = contentAccess;
        this.snippetBuilder = snippetBuilder;
    }

    /**
     * A parsed query together with the expression that actually produced it.
     *
     * <p>
     * The two differ only when the server repaired an unescaped field name and
     * {@code autoEscapeFieldNames} is in force. Keeping both is what lets the result declare the
     * repair instead of quietly answering a question nobody asked.
     */
    public static final class QueryPlan {

        private final Query query;
        private final String expression;
        private final String requestedExpression;

        QueryPlan(Query query, String expression, String requestedExpression) {
            this.query = query;
            this.expression = expression;
            this.requestedExpression = requestedExpression;
        }

        public Query getQuery() {
            return query;
        }

        /** The expression that was parsed. */
        public String getExpression() {
            return expression;
        }

        /** The expression as the caller wrote it. */
        public String getRequestedExpression() {
            return requestedExpression;
        }

        public boolean isNormalized() {
            return !expression.equals(requestedExpression);
        }
    }

    /**
     * Parses a query expression, producing an actionable diagnostic when it cannot.
     *
     * @throws McpError
     *             {@code QUERY_SYNTAX} with the position of the problem, or {@code UNKNOWN_FIELD}
     *             with near field names
     */
    public Query parse(OpenCase openCase, String expression) {
        return plan(openCase, expression).getQuery();
    }

    /**
     * Parses a query expression and, when it fails, checks whether escaping this case's own field
     * names repairs it.
     *
     * <p>
     * A repair that is proven to work is never withheld: it comes back either applied, when
     * {@code autoEscapeFieldNames} is enabled, or named in {@code details.suggested_query} so the
     * next attempt succeeds. What must not happen — and what did happen — is a failure whose remedy
     * sends the agent back to the spelling that just failed.
     */
    public QueryPlan plan(OpenCase openCase, String expression) {
        String requested = expression == null ? "" : expression;
        try {
            return new QueryPlan(build(openCase, requested), requested, requested);
        } catch (McpError failure) {
            if (!isRepairable(failure)) {
                throw failure;
            }
            String repaired = FieldNames.escapeKnownFieldNames(requested, openCase.getVocabulary());
            if (repaired.equals(requested)) {
                throw failure;
            }
            Query query;
            try {
                query = build(openCase, repaired);
            } catch (McpError stillFailing) {
                // The escaping was not what stood in the way; the original diagnostic is the one
                // that describes what the agent actually asked.
                throw failure;
            }
            if (config.isAutoEscapeFieldNames()) {
                return new QueryPlan(query, repaired, requested);
            }
            throw failure.with("suggested_query", repaired)
                    .withRemedy("Retry with this expression, which this server verified against the case: "
                            + repaired + " — field names in this index carry colons, and a colon inside a name "
                            + "has to be escaped as \\: so the parser does not read it as the separator between "
                            + "field and value. In JSON the backslash itself is escaped, so the argument reads "
                            + "\\\\:. Quoting the name instead does not work.");
        }
    }

    /** Only a syntax or unknown-field failure can be a missing escape. */
    private static boolean isRepairable(McpError failure) {
        return McpError.QUERY_SYNTAX.equals(failure.getCode()) || McpError.UNKNOWN_FIELD.equals(failure.getCode());
    }

    private Query build(OpenCase openCase, String expression) {
        Query query;
        try {
            query = new QueryBuilder(openCase.getSource()).getQuery(expression);
        } catch (Exception e) {
            throw new McpError(McpError.QUERY_SYNTAX, "The query could not be parsed: " + rootMessage(e),
                    "Fix the expression and retry. Quote phrases with double quotes, escape the special "
                            + "characters + - && || ! ( ) { } [ ] ^ \" ~ * ? : \\ with a backslash, and use "
                            + "field:value for a field restriction. If the field name itself contains a colon "
                            + "— p2p:fileType, ufed:UserID, dc:title — that colon must be escaped too: write "
                            + "p2p\\:fileType:\"mp3\". Call iped_check_field to get the exact spelling.")
                                    .with("query", expression).with("position", positionOf(e));
        }
        checkFields(openCase, query, expression);
        return query;
    }

    /** Applies the engine's own item-document semantics on top of a parsed query. */
    public Query forItems(OpenCase openCase, Query query) {
        IPEDSource source = openCase.getSource();
        Query itemQuery = query instanceof MatchAllDocsQuery ? QueryBuilder.getMatchAllItemsQuery()
                : new QueryBuilder(source, true).rewriteQuery(query);
        // Tree nodes are index scaffolding, not items an examiner would cite.
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(itemQuery, Occur.MUST);
        builder.add(new TermQuery(new Term(IndexItem.TREENODE, "true")), Occur.MUST_NOT);
        return builder.build();
    }

    /**
     * Runs one page.
     *
     * @param cursor
     *            continuation from a previous page, or {@code null} for the first
     * @return {@code total_matches}, {@code items}, {@code next_cursor} and {@code partial}
     */
    public Map<String, Object> search(OpenCase openCase, String expression, Integer pageSize, String cursor,
            Long timeoutMs, boolean includeSnippets) {
        IPEDSource source = openCase.getSource();
        IndexSearcher searcher = source.getSearcher();

        QueryPlan plan = plan(openCase, expression);
        Query parsed = plan.getQuery();
        Query query = forItems(openCase, parsed);
        int size = clampPageSize(pageSize);
        FieldDoc after = Cursor.decode(cursor);
        long budget = timeoutMs == null || timeoutMs <= 0 ? config.getQueryTimeoutMs() : timeoutMs;

        long totalMatches;
        TopDocs top;
        boolean partial = false;
        try {
            // Exact total, independent of how many items are returned (FR-012). count() never
            // materializes the match set.
            totalMatches = searcher.count(query);

            TopFieldCollector collector = TopFieldCollector.create(DETERMINISTIC_SORT, size, after,
                    Integer.MAX_VALUE);
            // The global counter ticks in milliseconds, so the budget passes through unchanged.
            TimeLimitingCollector limited = new TimeLimitingCollector(collector,
                    TimeLimitingCollector.getGlobalCounter(), Math.max(1, budget));
            try {
                searcher.search(query, limited);
            } catch (TimeLimitingCollector.TimeExceededException e) {
                partial = true;
            }
            top = collector.topDocs();
        } catch (IOException e) {
            throw new McpError(McpError.INTERNAL_ERROR, "The query could not be run: " + e.getMessage(),
                    "Report this with the server log attached.", e);
        }

        SnippetBuilder.Budget snippetBudget = snippetBuilder.newBudget();
        List<Map<String, Object>> items = new ArrayList<>(top.scoreDocs.length);
        for (ScoreDoc scoreDoc : top.scoreDocs) {
            Document doc;
            try {
                doc = searcher.doc(scoreDoc.doc, ItemView.storedFields());
            } catch (IOException e) {
                continue;
            }
            String snippet = null;
            if (includeSnippets) {
                int itemId = source.getId(scoreDoc.doc);
                if (itemId >= 0) {
                    IItem item = source.getItemByID(itemId);
                    snippet = snippetBuilder.build(openCase, item, parsed, source.getAnalyzer(), snippetBudget);
                }
            }
            Map<String, Object> view = ItemView.of(source, scoreDoc.doc, doc, snippet);
            view.put("case_id", openCase.getCaseId());
            items.add(view);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("query", expression);
        declareNormalization(result, plan);
        result.put("total_matches", totalMatches);
        result.put("page_size", size);
        result.put("items", items);
        result.put("partial", partial);
        if (partial) {
            result.put("partial_note", "The time budget of " + budget
                    + " ms ran out before the whole index was scanned. total_matches is exact but this page "
                    + "may be missing hits. Narrow the query, or raise timeout_ms.");
        }
        String next = nextCursor(top, size);
        if (next != null) {
            result.put("next_cursor", next);
        }
        return result;
    }

    /** Exact match count without collecting anything. */
    public long count(OpenCase openCase, String expression) {
        try {
            return openCase.getSource().getSearcher().count(forItems(openCase, parse(openCase, expression)));
        } catch (IOException e) {
            throw new McpError(McpError.INTERNAL_ERROR, "The query could not be counted: " + e.getMessage(),
                    "Report this with the server log attached.", e);
        }
    }

    /**
     * Collects every matching item id, in deterministic order. Used only by artifact export, where
     * the complete set is the point and it is written to a file rather than to the conversation
     * (FR-066, FR-067).
     */
    public List<Integer> collectAllIds(OpenCase openCase, Query query, int hardLimit) {
        IPEDSource source = openCase.getSource();
        List<Integer> ids = new ArrayList<>();
        try {
            IndexSearcher searcher = source.getSearcher();
            Sort byDoc = new Sort(SortField.FIELD_DOC);
            ScoreDoc last = null;
            int batch = 10000;
            while (ids.size() < hardLimit) {
                TopDocs page = searcher.searchAfter(last, query, batch, byDoc, false);
                if (page.scoreDocs.length == 0) {
                    break;
                }
                for (ScoreDoc scoreDoc : page.scoreDocs) {
                    int id = source.getId(scoreDoc.doc);
                    if (id >= 0) {
                        ids.add(id);
                    }
                }
                last = page.scoreDocs[page.scoreDocs.length - 1];
            }
        } catch (IOException e) {
            throw new McpError(McpError.INTERNAL_ERROR, "The result set could not be collected: " + e.getMessage(),
                    "Report this with the server log attached.", e);
        }
        return ids;
    }

    /**
     * Rejects field names the index does not have, with near names attached (FR-008).
     *
     * <p>
     * This is the check that keeps an agent from reporting "no evidence found" when what actually
     * happened is that it asked for a field this case calls something else.
     */
    private void checkFields(OpenCase openCase, Query query, String expression) {
        Set<String> fields = new LinkedHashSet<>();
        query.visit(new QueryVisitor() {
            @Override
            public boolean acceptField(String field) {
                if (field != null) {
                    fields.add(field);
                }
                return true;
            }

            @Override
            public QueryVisitor getSubVisitor(Occur occur, Query parent) {
                return this;
            }
        });
        FieldVocabulary vocabulary = openCase.getVocabulary();
        for (String field : fields) {
            // 'content' is indexed but never listed: LoadIndexFields excludes it because it is not
            // a property an examiner filters on, it is the full text itself.
            if (IndexItem.CONTENT.equals(field) || IndexItem.TREENODE.equals(field) || vocabulary.exists(field)) {
                continue;
            }
            // A name that exists *under* the asked-for one is not a near miss: it is proof that the
            // parser ate the colon of a namespaced field name as its own separator. Saying
            // "unknown field p2p" and offering "p2p:fileType" as a near name is what sent agents
            // into a loop — they retried with a spelling that cannot parse.
            List<String> namespaced = vocabulary.namesUnder(field);
            if (!namespaced.isEmpty()) {
                List<String> queryForms = new ArrayList<>();
                for (String name : namespaced.subList(0, Math.min(namespaced.size(), 12))) {
                    queryForms.add(FieldNames.toQueryForm(name));
                }
                throw new McpError(McpError.UNKNOWN_FIELD,
                        "There is no field named '" + field + "' in this case, but " + namespaced.size()
                                + " field name(s) begin with '" + field + ":'. The colon inside the name was read "
                                + "as the separator between field and value.",
                        "Escape the colon that belongs to the name: write " + FieldNames.toQueryForm(namespaced.get(0))
                                + ":value, not " + namespaced.get(0) + ":value. In JSON the backslash is itself "
                                + "escaped, so the argument carries \\\\:. The spellings ready to paste are in "
                                + "details.query_form.").with("field", field).with("namespaced_fields", namespaced)
                                        .with("query_form", queryForms).with("query", expression);
            }
            List<String> similar = vocabulary.similar(field, 8);
            throw new McpError(McpError.UNKNOWN_FIELD,
                    "The field '" + field + "' does not exist in this case's index.",
                    similar.isEmpty()
                            ? "Call iped_list_fields to see the field names this case actually has. Field names "
                                    + "vary between cases and versions; the index is the source of truth."
                            : "Retry with one of the near names in details.similar — write it as '"
                                    + FieldNames.toQueryForm(similar.get(0))
                                    + "', which is the closest. Call iped_list_fields for the full vocabulary of "
                                    + "this case.").with("field", field).with("similar", similar)
                                            .with("similar_query_form", queryForms(similar)).with("query",
                                                    expression);
        }
    }

    /**
     * States, in the result itself, that the expression run was not the expression asked for.
     *
     * <p>
     * A repaired query is answered, never silently: what was counted has to be readable from the
     * answer alone, because the answer is what ends up in a report.
     */
    public static void declareNormalization(Map<String, Object> result, QueryPlan plan) {
        if (!plan.isNormalized()) {
            return;
        }
        result.put("query_normalized", plan.getExpression());
        result.put("query_normalized_note", "The expression asked for could not be parsed as written: a colon "
                + "belonging to a field name was read as the separator between field and value. The server "
                + "escaped the field names this case actually has and ran the result shown in "
                + "query_normalized. This is what autoEscapeFieldNames does; cite the normalized expression "
                + "when reporting these counts.");
    }

    /** The same names, spelled the way an expression needs them. */
    private static List<String> queryForms(List<String> names) {
        List<String> forms = new ArrayList<>(names.size());
        for (String name : names) {
            forms.add(FieldNames.toQueryForm(name));
        }
        return forms;
    }

    private int clampPageSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return config.getDefaultPageSize();
        }
        return Math.min(requested, config.getMaxPageSize());
    }

    /**
     * A cursor is issued only when the page came back full. A short page is the last one, so no
     * cursor is offered and the caller cannot walk past the end.
     */
    private static String nextCursor(TopDocs top, int size) {
        if (top.scoreDocs.length < size) {
            return null;
        }
        return Cursor.encode(top.scoreDocs[top.scoreDocs.length - 1]);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    /** Best-effort extraction of the error position from the parser's message. */
    private static Integer positionOf(Throwable e) {
        String message = rootMessage(e);
        if (message == null) {
            return null;
        }
        int at = message.indexOf("at position ");
        if (at < 0) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = at + "at position ".length(); i < message.length() && Character.isDigit(message.charAt(i)); i++) {
            digits.append(message.charAt(i));
        }
        return digits.length() == 0 ? null : Integer.parseInt(digits.toString());
    }

    ContentAccess getContentAccess() {
        return contentAccess;
    }
}
