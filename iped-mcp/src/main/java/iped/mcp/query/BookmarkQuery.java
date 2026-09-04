package iped.mcp.query;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;

import iped.data.IBookmarks;
import iped.engine.task.index.IndexItem;

/**
 * Lucene filter backed by IPED bookmark membership.
 *
 * <p>
 * Item ids are already NumericDocValues in every IPED item document. Iterating those values keeps
 * memory bounded independently of the bookmark size and also skips content fragments, which do not
 * carry an item id. This query is not cacheable because bookmarks are mutable while the index and
 * its searcher remain open.
 */
final class BookmarkQuery extends Query {

    private final IBookmarks bookmarks;
    private final int bookmarkId;
    private final String bookmarkName;

    BookmarkQuery(IBookmarks bookmarks, int bookmarkId, String bookmarkName) {
        this.bookmarks = bookmarks;
        this.bookmarkId = bookmarkId;
        this.bookmarkName = bookmarkName;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new ConstantScoreWeight(this, boost) {
            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                NumericDocValues itemIds = context.reader().getNumericDocValues(IndexItem.ID);
                if (itemIds == null) {
                    return null;
                }
                TwoPhaseIterator matches = new TwoPhaseIterator(itemIds) {
                    @Override
                    public boolean matches() throws IOException {
                        long itemId = itemIds.longValue();
                        return itemId >= Integer.MIN_VALUE && itemId <= Integer.MAX_VALUE
                                && bookmarks.hasBookmark((int) itemId, bookmarkId);
                    }

                    @Override
                    public float matchCost() {
                        return 5.0f;
                    }
                };
                return new ConstantScoreScorer(this, score(), scoreMode, matches);
            }

            @Override
            public boolean isCacheable(LeafReaderContext context) {
                return false;
            }
        };
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    @Override
    public String toString(String field) {
        return "bookmark:" + bookmarkName;
    }

    @Override
    public boolean equals(Object other) {
        if (!sameClassAs(other)) {
            return false;
        }
        BookmarkQuery that = (BookmarkQuery) other;
        return bookmarks == that.bookmarks && bookmarkId == that.bookmarkId;
    }

    @Override
    public int hashCode() {
        int hash = classHash();
        hash = 31 * hash + System.identityHashCode(bookmarks);
        return 31 * hash + bookmarkId;
    }
}
