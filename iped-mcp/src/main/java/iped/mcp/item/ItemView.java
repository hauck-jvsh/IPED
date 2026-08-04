package iped.mcp.item;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;

import iped.data.IBookmarks;
import iped.engine.data.IPEDSource;
import iped.properties.BasicProps;
import iped.utils.DateUtil;

/**
 * Enriched projection of an item, returned inside query results so that the agent does not need one
 * call per item to understand what it found (FR-014).
 *
 * <p>
 * Built from the stored Lucene document rather than from a materialized {@code IItem}: the essential
 * properties are all stored fields, and reading them costs a document fetch instead of an item
 * reconstruction. On a page of 200 items that difference is the whole latency budget.
 *
 * <p>
 * <b>Absent is not empty.</b> A field that the item does not have is omitted, never returned as an
 * empty string (FR-022). {@code unavailable} says which ones were omitted and why, so the agent can
 * tell "this item has no hash" from "this item's hash is blank" — the difference between a sound
 * conclusion and a wrong one.
 */
public class ItemView {

    private ItemView() {
    }

    /**
     * @param snippet
     *            context around the query match, or {@code null} when the item has no indexed text
     *            or the query was not textual
     */
    public static Map<String, Object> of(IPEDSource source, int luceneId, Document doc, String snippet) {
        Map<String, Object> view = new LinkedHashMap<>();
        List<Map<String, String>> unavailable = new ArrayList<>();

        int id = source.getId(luceneId);
        view.put("case_id", null); // filled by the caller, which knows the session's case id
        view.put("item_id", id);

        put(view, unavailable, "name", doc.get(BasicProps.NAME), "the item has no name recorded in the index");
        put(view, unavailable, "path", doc.get(BasicProps.PATH), "the item has no path recorded in the index");
        put(view, unavailable, "ext", doc.get(BasicProps.EXT), "the item has no extension");
        put(view, unavailable, "category", doc.get(BasicProps.CATEGORY), "the item was not categorized");
        put(view, unavailable, "content_type", doc.get(BasicProps.CONTENTTYPE),
                "no media type was detected for this item");
        put(view, unavailable, "type", doc.get(BasicProps.TYPE), "the item has no normalized type");

        String size = doc.get(BasicProps.LENGTH);
        if (size != null && !size.isEmpty()) {
            view.put("size", Long.parseLong(size));
        } else {
            unavailable.add(reason("size", "the item has no length recorded, typical of directories and tree nodes"));
        }

        putDate(view, unavailable, doc, BasicProps.CREATED, "created");
        putDate(view, unavailable, doc, BasicProps.MODIFIED, "modified");
        putDate(view, unavailable, doc, BasicProps.ACCESSED, "accessed");
        putDate(view, unavailable, doc, BasicProps.CHANGED, "changed");

        put(view, unavailable, "hash", doc.get(BasicProps.HASH),
                "no hash was computed; hashing may have been disabled, or the item has no content");

        view.put("deleted", bool(doc.get(BasicProps.DELETED)));
        view.put("carved", bool(doc.get(BasicProps.CARVED)));
        view.put("subitem", bool(doc.get(BasicProps.SUBITEM)));
        view.put("is_dir", bool(doc.get(BasicProps.ISDIR)));
        view.put("is_root", bool(doc.get(BasicProps.ISROOT)));
        view.put("has_children", bool(doc.get(BasicProps.HASCHILD)));

        String parentId = doc.get(BasicProps.PARENTID);
        if (parentId != null && !parentId.isEmpty()) {
            view.put("parent_id", Integer.parseInt(parentId));
        } else {
            unavailable.add(reason("parent_id", "the item is a root item and has no container"));
        }

        put(view, unavailable, "evidence_uuid", doc.get(BasicProps.EVIDENCE_UUID), "the item has no evidence recorded");

        IBookmarks bookmarks = source.getBookmarks();
        List<String> names = bookmarks == null ? new ArrayList<>() : bookmarks.getBookmarkList(id);
        view.put("bookmarks", names == null ? new ArrayList<String>() : names);
        view.put("selected", bookmarks != null && bookmarks.isChecked(id));

        if (snippet != null && !snippet.isEmpty()) {
            view.put("snippet", snippet);
        } else {
            unavailable.add(reason("snippet",
                    "no indexed text matched, or this item has no extracted text; ask for iped_item_text to "
                            + "confirm which"));
        }

        view.put("unavailable", unavailable);
        return view;
    }

    private static void put(Map<String, Object> view, List<Map<String, String>> unavailable, String field, String value,
            String reason) {
        if (value != null && !value.isEmpty()) {
            view.put(field, value);
        } else {
            unavailable.add(reason(field, reason));
        }
    }

    private static void putDate(Map<String, Object> view, List<Map<String, String>> unavailable, Document doc,
            String indexField, String field) {
        String value = doc.get(indexField);
        if (value == null || value.isEmpty()) {
            unavailable.add(reason(field, "the source filesystem did not record this timestamp for the item"));
            return;
        }
        try {
            Date date = DateUtil.stringToDate(value);
            view.put(field, date.toInstant().toString());
        } catch (Exception e) {
            // A timestamp the index holds in a shape we cannot parse is reported as present but
            // unreadable, never silently dropped.
            view.put(field, value);
        }
    }

    private static Map<String, String> reason(String field, String why) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("reason", why);
        return entry;
    }

    private static boolean bool(String value) {
        return value != null && Boolean.parseBoolean(value);
    }

    /** Fields fetched from the stored document; anything else would cost a full reconstruction. */
    public static java.util.Set<String> storedFields() {
        java.util.Set<String> fields = new java.util.LinkedHashSet<>();
        fields.add(BasicProps.ID);
        fields.add(BasicProps.NAME);
        fields.add(BasicProps.PATH);
        fields.add(BasicProps.EXT);
        fields.add(BasicProps.TYPE);
        fields.add(BasicProps.CATEGORY);
        fields.add(BasicProps.CONTENTTYPE);
        fields.add(BasicProps.LENGTH);
        fields.add(BasicProps.CREATED);
        fields.add(BasicProps.MODIFIED);
        fields.add(BasicProps.ACCESSED);
        fields.add(BasicProps.CHANGED);
        fields.add(BasicProps.HASH);
        fields.add(BasicProps.DELETED);
        fields.add(BasicProps.CARVED);
        fields.add(BasicProps.SUBITEM);
        fields.add(BasicProps.ISDIR);
        fields.add(BasicProps.ISROOT);
        fields.add(BasicProps.HASCHILD);
        fields.add(BasicProps.PARENTID);
        fields.add(BasicProps.EVIDENCE_UUID);
        return fields;
    }
}
