package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import iped.mcp.protocol.McpError;
import iped.mcp.query.Cursor;

/**
 * Cursor pagination actually advances (FR-013, FR-019).
 *
 * <p>
 * Written after a field test found that it did not. {@code iped_search} returned a
 * {@code next_cursor}, and resuming from it returned the same page with the same cursor — a paging
 * loop that never ended and never got past the first page, with no error to notice. The cause was
 * {@code ScoreDoc.score}, which {@code TopFieldCollector} leaves as {@code NaN}: every
 * {@code searchAfter} comparison against {@code NaN} is false, so collection restarted from the
 * top.
 *
 * <p>
 * The suite runs over a real in-memory Lucene index rather than a mock, because the property being
 * protected belongs to the collector: asserting that a cursor round-trips as a string would have
 * passed throughout the defect.
 */
public class CursorPaginationTest {

    private static final int PAGE = 3;
    private static final int DOCUMENTS = 12;

    private static Directory directory;
    private static DirectoryReader reader;
    private static IndexSearcher searcher;
    private static TermQuery query;

    @BeforeClass
    public static void buildIndex() throws Exception {
        directory = new ByteBuffersDirectory();
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            for (int i = 0; i < DOCUMENTS; i++) {
                Document doc = new Document();
                // Varying term frequency gives distinct scores, and repeating it gives ties — the
                // tiebreaker on document order is what makes paging deterministic.
                StringBuilder text = new StringBuilder("radxa");
                for (int repeat = 0; repeat < i % 3; repeat++) {
                    text.append(" radxa");
                }
                doc.add(new TextField("content", text.toString(), Field.Store.NO));
                writer.addDocument(doc);
            }
        }
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
        query = new TermQuery(new Term("content", "radxa"));
    }

    @AfterClass
    public static void closeIndex() throws Exception {
        if (reader != null) {
            reader.close();
        }
        if (directory != null) {
            directory.close();
        }
    }

    @Test
    public void theSecondPageIsNotTheFirstOneAgain() {
        TopDocs first = page(null);
        TopDocs second = page(Cursor.decode(Cursor.encode(last(first))));

        List<Integer> firstIds = idsOf(first);
        List<Integer> secondIds = idsOf(second);
        assertEquals(PAGE, firstIds.size());
        assertEquals(PAGE, secondIds.size());
        assertFalse("the cursor must advance, not restart: page 1 " + firstIds + ", page 2 " + secondIds,
                firstIds.equals(secondIds));
        for (Integer id : secondIds) {
            assertFalse("document " + id + " was already returned on page 1 " + firstIds, firstIds.contains(id));
        }
    }

    @Test
    public void walkingEveryPageVisitsEveryDocumentExactlyOnce() {
        // The property the examiner actually needs: a paging loop terminates and enumerates the
        // result set. Under the defect this ran forever.
        Set<Integer> seen = new LinkedHashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            TopDocs page = page(Cursor.decode(cursor));
            for (Integer id : idsOf(page)) {
                assertTrue("document " + id + " came back on more than one page", seen.add(id));
            }
            cursor = page.scoreDocs.length < PAGE ? null : Cursor.encode(last(page));
            assertTrue("a paging loop must terminate", ++pages <= DOCUMENTS);
        } while (cursor != null);

        assertEquals("every matching document must be visited", DOCUMENTS, seen.size());
    }

    @Test
    public void theCollectorAlreadyKnowsTheWholeTotalOnEveryPage() {
        // The assumption that lets a page cost one query instead of two. totalHitsThreshold is
        // Integer.MAX_VALUE, which forbids early termination, so totalHits is the size of the whole
        // result set — not of the page, and not of what remains after the cursor. If a Lucene upgrade
        // ever changed that, total_matches would silently start meaning something else, and this is
        // where it has to fail.
        TopDocs first = page(null);
        assertEquals("page 1 must report the whole total", DOCUMENTS, first.totalHits.value);
        assertEquals(TotalHits.Relation.EQUAL_TO, first.totalHits.relation);
        assertEquals("and it must not be the page size", PAGE, first.scoreDocs.length);

        String cursor = Cursor.encode(last(first));
        int pages = 1;
        while (cursor != null) {
            TopDocs next = page(Cursor.decode(cursor));
            if (next.scoreDocs.length == 0) {
                break;
            }
            assertEquals("page " + (++pages) + " must report the same total, cursor or no cursor", DOCUMENTS,
                    next.totalHits.value);
            cursor = next.scoreDocs.length < PAGE ? null : Cursor.encode(last(next));
        }
        assertTrue("the walk must have covered more than one page", pages > 1);
    }

    @Test
    public void aCursorCarriesTheSortValueTheCollectorCompared() {
        // The regression itself, stated directly: the encoded position must not be NaN, whatever
        // TopFieldCollector chooses to leave in ScoreDoc.score.
        ScoreDoc last = last(page(null));
        String cursor = Cursor.encode(last);
        assertNotNull("a full page must yield a cursor", cursor);
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        assertFalse("a cursor carrying NaN cannot be resumed from: " + decoded, decoded.contains("NaN"));

        FieldDoc position = Cursor.decode(cursor);
        assertEquals(last.doc, position.doc);
        assertFalse(Float.isNaN(((Number) position.fields[0]).floatValue()));
        assertEquals("the sort fields must mirror Cursor.SORT", 2, position.fields.length);
    }

    @Test
    public void noCursorIsIssuedForAHitWithNoUsablePosition() {
        // Better none than one that silently restarts — the failure mode being removed.
        assertNull(Cursor.encode(new ScoreDoc(7, Float.NaN)));
        assertNull(Cursor.encode(null));
    }

    @Test
    public void aCursorFromTheOlderBrokenBuildIsRefused() {
        String legacy = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("45253:NaN".getBytes(StandardCharsets.UTF_8));
        try {
            Cursor.decode(legacy);
            fail("a cursor carrying NaN must be refused, not silently restarted");
        } catch (McpError e) {
            assertEquals(McpError.INVALID_CURSOR, e.getCode());
            assertFalse("the refusal must carry a remedy", e.getRemedy().isEmpty());
        }
    }

    @Test
    public void garbageIsRefusedAndNothingIsResumedFromIt() {
        try {
            Cursor.decode("not-a-cursor");
            fail("expected INVALID_CURSOR");
        } catch (McpError e) {
            assertEquals(McpError.INVALID_CURSOR, e.getCode());
        }
    }

    @Test
    public void absentCursorMeansTheFirstPage() {
        assertNull(Cursor.decode(null));
        assertNull(Cursor.decode(""));
    }

    private static TopDocs page(FieldDoc after) {
        try {
            TopFieldCollector collector = TopFieldCollector.create(Cursor.SORT, PAGE, after, Integer.MAX_VALUE);
            searcher.search(query, collector);
            return collector.topDocs();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ScoreDoc last(TopDocs page) {
        return page.scoreDocs[page.scoreDocs.length - 1];
    }

    private static List<Integer> idsOf(TopDocs page) {
        List<Integer> ids = new ArrayList<>(page.scoreDocs.length);
        for (ScoreDoc scoreDoc : page.scoreDocs) {
            ids.add(scoreDoc.doc);
        }
        return ids;
    }
}
