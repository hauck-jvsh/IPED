package iped.mcp.query;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.Test;

import iped.data.IBookmarks;
import iped.engine.task.index.IndexItem;

public class BookmarkQueryTest {

    @Test
    public void matchesCurrentMembershipWithoutCachingMutableBookmarkState() throws Exception {
        Set<Integer> selected = new HashSet<>(Arrays.asList(10, 30));
        IBookmarks bookmarks = bookmarksBackedBy(selected, 7);

        try (Directory directory = new ByteBuffersDirectory();
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            addItem(writer, 10);
            addItem(writer, 20);
            addItem(writer, 30);
            writer.addDocument(new Document()); // Content fragments and scaffolding have no item id.
            writer.commit();

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                BookmarkQuery query = new BookmarkQuery(bookmarks, 7, "Selected");

                assertEquals(2, searcher.count(query));

                selected.remove(10);
                selected.add(20);
                selected.remove(30);
                assertEquals("an open searcher must see bookmark edits rather than a cached bit set", 1,
                        searcher.count(query));
            }
        }
    }

    private static void addItem(IndexWriter writer, int itemId) throws Exception {
        Document document = new Document();
        document.add(new NumericDocValuesField(IndexItem.ID, itemId));
        writer.addDocument(document);
    }

    private static IBookmarks bookmarksBackedBy(Set<Integer> selected, int expectedBookmarkId) {
        return (IBookmarks) Proxy.newProxyInstance(IBookmarks.class.getClassLoader(), new Class<?>[] { IBookmarks.class },
                (proxy, method, arguments) -> {
                    if ("hasBookmark".equals(method.getName()) && arguments != null && arguments.length == 2
                            && arguments[0] instanceof Integer && arguments[1] instanceof Integer) {
                        return ((Integer) arguments[1]) == expectedBookmarkId
                                && selected.contains((Integer) arguments[0]);
                    }
                    if ("toString".equals(method.getName())) {
                        return "test-bookmarks";
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
    }
}
