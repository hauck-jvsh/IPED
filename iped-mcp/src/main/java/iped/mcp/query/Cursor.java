package iped.mcp.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;

import iped.mcp.protocol.McpError;

/**
 * The continuation between two pages of a query.
 *
 * <p>
 * A cursor carries the position the next page starts after: the document, and the value the sort
 * compared to place it there. Both are needed — the sort value alone repeats across ties, and the
 * document alone says nothing about where it sat in the ordering.
 *
 * <p>
 * <b>The sort value comes from {@link FieldDoc#fields}, never from {@link ScoreDoc#score}.</b>
 * {@code TopFieldCollector} does not populate {@code score} in Lucene 9 — it leaves {@code NaN}
 * there and fills scores only on demand, through {@code populateScores}. What it did compare is in
 * {@code fields[0]}, because {@link #SORT} leads with {@link SortField#FIELD_SCORE}. Encoding
 * {@code score} produced a cursor carrying {@code NaN}; every {@code searchAfter} comparison against
 * {@code NaN} is false, so the following page came back as the first one. A paging loop never
 * advanced and never ended, and nothing said so — the defect was found in the field, not by a test.
 */
public final class Cursor {

    /**
     * Score first, document order as the stable tiebreaker (FR-019).
     *
     * <p>
     * The shape of an encoded cursor follows from this sort, so the two change together.
     */
    public static final Sort SORT = new Sort(SortField.FIELD_SCORE, SortField.FIELD_DOC);

    private Cursor() {
    }

    /**
     * The cursor that resumes after the given hit.
     *
     * @return the encoded cursor, or {@code null} when this hit cannot produce a usable one — an
     *         unusable cursor is worse than none, because the caller loops on it in silence
     */
    public static String encode(ScoreDoc last) {
        if (last == null) {
            return null;
        }
        float sortValue = sortValueOf(last);
        if (Float.isNaN(sortValue) || Float.isInfinite(sortValue)) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((last.doc + ":" + sortValue).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The position a cursor resumes from, shaped for {@code TopFieldCollector} under {@link #SORT}.
     *
     * @param cursor
     *            a cursor this server issued, or {@code null}/empty to start from the first page
     * @return the position, or {@code null} for the first page
     * @throws McpError
     *             {@code INVALID_CURSOR} when the value is not one this server can resume from
     */
    public static FieldDoc decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        int doc;
        float sortValue;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(':');
            doc = Integer.parseInt(decoded.substring(0, separator));
            sortValue = Float.parseFloat(decoded.substring(separator + 1));
        } catch (RuntimeException e) {
            throw refuse(cursor, "The cursor is not one this server produced.");
        }
        if (Float.isNaN(sortValue) || Float.isInfinite(sortValue)) {
            // Cursors issued before this was fixed carry NaN. Resuming from one silently restarts
            // at the first page, which is the failure being removed; refusing says so out loud.
            throw refuse(cursor, "This cursor carries no usable sort position, so it cannot be resumed from. It "
                    + "was issued by an older version of this server.");
        }
        // The array must mirror SORT field by field, or the collector cannot compare against it.
        return new FieldDoc(doc, sortValue, new Object[] { sortValue, doc });
    }

    private static McpError refuse(String cursor, String message) {
        return new McpError(McpError.INVALID_CURSOR, message,
                "Use the next_cursor value from the previous page verbatim, or omit it to start over from the "
                        + "first page.").with("cursor", cursor);
    }

    /**
     * The value the collector sorted by, read from where the collector actually put it.
     *
     * @return {@code fields[0]} as a float, falling back to {@code score} for a hit that carries no
     *         sort fields
     */
    private static float sortValueOf(ScoreDoc scoreDoc) {
        if (scoreDoc instanceof FieldDoc) {
            Object[] fields = ((FieldDoc) scoreDoc).fields;
            if (fields != null && fields.length > 0 && fields[0] instanceof Number) {
                return ((Number) fields[0]).floatValue();
            }
        }
        return scoreDoc.score;
    }
}
