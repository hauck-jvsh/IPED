package iped.mcp.query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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

    /** Score first, document order as the stable tiebreaker. */
    private static final Sort DETERMINISTIC_SORT = new Sort(SortField.FIELD_SCORE, SortField.FIELD_DOC);

    private final McpServerConfig config;
    private final ContentAccess contentAccess;
    private final SnippetBuilder snippetBuilder;

    public PagedSearcher(McpServerConfig config, ContentAccess contentAccess, SnippetBuilder snippetBuilder) {
        this.config = config;
        this.contentAccess = contentAccess;
        this.snippetBuilder = snippetBuilder;
    }

    /**
     * Parses a query expression, producing an actionable diagnostic when it cannot.
     *
     * @throws McpError
     *             {@code QUERY_SYNTAX} with the position of the problem, or {@code UNKNOWN_FIELD}
     *             with near field names
     */
    public Query parse(OpenCase openCase, String expression) {
        Query query;
        try {
            query = new QueryBuilder(openCase.getSource()).getQuery(expression == null ? "" : expression);
        } catch (Exception e) {
            throw new McpError(McpError.QUERY_SYNTAX, "The query could not be parsed: " + rootMessage(e),
                    "Fix the expression and retry. Quote phrases with double quotes, escape the special "
                            + "characters + - && || ! ( ) { } [ ] ^ \" ~ * ? : \\ with a backslash, and use "
                            + "field:value for a field restriction.").with("query", expression)
                                    .with("position", positionOf(e));
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

        Query parsed = parse(openCase, expression);
        Query query = forItems(openCase, parsed);
        int size = clampPageSize(pageSize);
        FieldDoc after = decodeCursor(cursor);
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
            List<String> similar = vocabulary.similar(field, 8);
            throw new McpError(McpError.UNKNOWN_FIELD,
                    "The field '" + field + "' does not exist in this case's index.",
                    similar.isEmpty()
                            ? "Call iped_list_fields to see the field names this case actually has. Field names "
                                    + "vary between cases and versions; the index is the source of truth."
                            : "Retry with one of the near names in details.similar — '" + similar.get(0)
                                    + "' is the closest. Call iped_list_fields for the full vocabulary of this "
                                    + "case.").with("field", field).with("similar", similar).with("query",
                                            expression);
        }
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
    private String nextCursor(TopDocs top, int size) {
        if (top.scoreDocs.length < size) {
            return null;
        }
        ScoreDoc last = top.scoreDocs[top.scoreDocs.length - 1];
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((last.doc + ":" + last.score).getBytes(StandardCharsets.UTF_8));
    }

    private FieldDoc decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(':');
            int doc = Integer.parseInt(decoded.substring(0, separator));
            float score = Float.parseFloat(decoded.substring(separator + 1));
            return new FieldDoc(doc, score, new Object[] { score, doc });
        } catch (RuntimeException e) {
            throw new McpError(McpError.INVALID_CURSOR, "The cursor is not one this server produced.",
                    "Use the next_cursor value from the previous page verbatim, or omit it to start over from "
                            + "the first page.").with("cursor", cursor);
        }
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
