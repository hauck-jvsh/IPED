package iped.mcp.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;

import iped.data.IBookmarks;
import iped.engine.data.IPEDSource;
import iped.mcp.protocol.McpError;
import iped.mcp.session.OpenCase;
import iped.properties.BasicProps;

/**
 * Counts by dimension without materializing items (FR-016).
 *
 * <p>
 * This is how the agent answers "how many of each type?" without inspecting a single item, and how
 * it decides whether to narrow before listing. Answering that question by paging through results
 * would cost the whole collection.
 *
 * <p>
 * Implemented over {@code SortedSetDocValues} / {@code SortedDocValues}, following the pattern
 * already used by {@code TimelineResults}. There is no {@code lucene-facet} in this tree, and cases
 * processed years ago carry no {@code FacetField} — introducing facets would help nothing that
 * already exists (R4).
 *
 * <p>
 * <b>Cost characteristic to keep in view:</b> a DocValues aggregation costs in proportion to the
 * collection, not to the result. That is exactly what SC-015 measures.
 */
public class Aggregator {

    /** Dimensions the contract exposes. */
    public enum Dimension {
        category(BasicProps.CATEGORY), contentType(BasicProps.CONTENTTYPE), period(BasicProps.MODIFIED),
        evidence(BasicProps.EVIDENCE_UUID), bookmark(null);

        final String field;

        Dimension(String field) {
            this.field = field;
        }
    }

    private final PagedSearcher pagedSearcher;

    public Aggregator(PagedSearcher pagedSearcher) {
        this.pagedSearcher = pagedSearcher;
    }

    /**
     * @param dimension
     *            one of {@link Dimension}
     * @param expression
     *            optional restriction; when absent the whole case is counted
     */
    public Map<String, Object> aggregate(OpenCase openCase, String dimension, String expression, int maxBuckets) {
        Dimension dim;
        try {
            dim = Dimension.valueOf(dimension);
        } catch (IllegalArgumentException | NullPointerException e) {
            List<String> valid = new ArrayList<>();
            for (Dimension value : Dimension.values()) {
                valid.add(value.name());
            }
            throw new McpError(McpError.INVALID_ARGUMENT, "There is no aggregation dimension named '" + dimension
                    + "'.", "Use one of the dimensions in details.valid.").with("requested", dimension).with("valid",
                            valid);
        }

        IPEDSource source = openCase.getSource();
        BitSet matching = null;
        PagedSearcher.QueryPlan plan = null;
        if (expression != null && !expression.trim().isEmpty()) {
            plan = pagedSearcher.plan(openCase, expression);
            matching = matchingDocs(openCase, pagedSearcher.forItems(openCase, plan.getQuery()));
        }

        Map<String, Long> counts = dim == Dimension.bookmark ? countBookmarks(source, matching)
                : countDocValues(source, dim, matching);

        List<Map<String, Object>> buckets = new ArrayList<>();
        long total = 0;
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, Long> entry : entries) {
            total += entry.getValue();
            if (buckets.size() < maxBuckets) {
                Map<String, Object> bucket = new LinkedHashMap<>();
                bucket.put("value", entry.getKey());
                bucket.put("count", entry.getValue());
                buckets.add(bucket);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("dimension", dim.name());
        result.put("query", expression);
        if (plan != null) {
            PagedSearcher.declareNormalization(result, plan);
        }
        result.put("buckets", buckets);
        result.put("distinct_values", counts.size());
        result.put("summed_count", total);
        if (counts.size() > buckets.size()) {
            result.put("buckets_truncated", true);
            result.put("buckets_note", "Only the " + maxBuckets + " largest of " + counts.size()
                    + " values are listed. summed_count covers all of them, so it will exceed the sum of the "
                    + "buckets shown.");
        }
        return result;
    }

    /** Bitset of item documents matching a restriction, so counting can skip the rest. */
    private BitSet matchingDocs(OpenCase openCase, Query query) {
        IPEDSource source = openCase.getSource();
        FixedBitSet bits = new FixedBitSet(source.getReader().maxDoc());
        try {
            source.getSearcher().search(query, new org.apache.lucene.search.SimpleCollector() {
                private int docBase;

                @Override
                protected void doSetNextReader(org.apache.lucene.index.LeafReaderContext context) {
                    this.docBase = context.docBase;
                }

                @Override
                public void collect(int doc) {
                    bits.set(docBase + doc);
                }

                @Override
                public org.apache.lucene.search.ScoreMode scoreMode() {
                    return org.apache.lucene.search.ScoreMode.COMPLETE_NO_SCORES;
                }
            });
        } catch (IOException e) {
            throw new McpError(McpError.INTERNAL_ERROR, "The aggregation restriction could not be applied: "
                    + e.getMessage(), "Report this with the server log attached.", e);
        }
        return bits;
    }

    private Map<String, Long> countDocValues(IPEDSource source, Dimension dimension, BitSet matching) {
        Map<String, Long> counts = new HashMap<>();
        LeafReader reader = source.getAtomicReader();
        try {
            SortedSetDocValues multi = reader.getSortedSetDocValues(dimension.field);
            if (multi != null) {
                int doc;
                while ((doc = multi.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (matching != null && !matching.get(doc)) {
                        continue;
                    }
                    long ord;
                    while ((ord = multi.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
                        counts.merge(bucketValue(dimension, multi.lookupOrd(ord).utf8ToString()), 1L, Long::sum);
                    }
                }
                return counts;
            }
            SortedDocValues single = reader.getSortedDocValues(dimension.field);
            if (single != null) {
                int doc;
                while ((doc = single.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (matching != null && !matching.get(doc)) {
                        continue;
                    }
                    counts.merge(bucketValue(dimension, single.lookupOrd(single.ordValue()).utf8ToString()), 1L,
                            Long::sum);
                }
                return counts;
            }
        } catch (IOException e) {
            throw new McpError(McpError.INTERNAL_ERROR, "The aggregation could not be read: " + e.getMessage(),
                    "Report this with the server log attached.", e);
        }
        throw new McpError(McpError.INVALID_ARGUMENT,
                "This case's index has no aggregatable values for the dimension '" + dimension.name() + "'.",
                "The field '" + dimension.field + "' carries no doc values in this case, which happens with "
                        + "older or partially configured indexes. Use iped_search with a restrictive query and "
                        + "read total_matches instead.").with("dimension", dimension.name()).with("field",
                                dimension.field);
    }

    /** Period buckets are the year and month of the stored timestamp. */
    private static String bucketValue(Dimension dimension, String raw) {
        if (dimension != Dimension.period) {
            return raw;
        }
        return raw.length() >= 7 ? raw.substring(0, 7) : raw;
    }

    private Map<String, Long> countBookmarks(IPEDSource source, BitSet matching) {
        Map<String, Long> counts = new HashMap<>();
        IBookmarks bookmarks = source.getBookmarks();
        if (bookmarks == null) {
            return counts;
        }
        if (matching == null) {
            for (Map.Entry<Integer, String> entry : bookmarks.getBookmarkMap().entrySet()) {
                counts.put(entry.getValue(), (long) bookmarks.getBookmarkCount(entry.getKey()));
            }
            return counts;
        }
        // With a restriction in force, walk the matching documents once and attribute each to the
        // bookmarks it belongs to. One pass over the matches beats one pass per bookmark.
        for (Map.Entry<Integer, String> entry : bookmarks.getBookmarkMap().entrySet()) {
            counts.put(entry.getValue(), 0L);
        }
        for (int doc = 0; doc < matching.length(); doc++) {
            if (!matching.get(doc)) {
                continue;
            }
            int id = source.getId(doc);
            if (id < 0) {
                continue;
            }
            for (Map.Entry<Integer, String> entry : bookmarks.getBookmarkMap().entrySet()) {
                if (bookmarks.hasBookmark(id, entry.getKey())) {
                    counts.merge(entry.getValue(), 1L, Long::sum);
                }
            }
        }
        return counts;
    }
}
