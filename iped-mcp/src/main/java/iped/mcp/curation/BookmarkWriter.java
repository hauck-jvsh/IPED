package iped.mcp.curation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IBookmarks;
import iped.engine.data.IPEDSource;
import iped.mcp.protocol.McpError;
import iped.mcp.session.OpenCase;

/**
 * Bookmark and selection changes, written into the case through the engine's own
 * {@code Bookmarks}/{@code saveState} path so that the IPED UI sees them on reopen (FR-030).
 *
 * <p>
 * Every state save is synchronous. The asynchronous path exists for a UI where a background write
 * is invisible to the user; here the tool call has to be able to report that the change is on disk,
 * and the audit record has to describe something that actually happened.
 *
 * <p>
 * Deleting or renaming a pre-existing bookmark captures the prior state before it is applied
 * (FR-033). Recovering a bookmark the examiner did not mean to lose depends entirely on that
 * capture existing, and it has to be written to the trail <i>before</i> the operation, not after.
 */
public class BookmarkWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookmarkWriter.class);

    /** Lists bookmarks with their counts. Reading, so allowed in any access mode. */
    public List<Map<String, Object>> list(OpenCase openCase) {
        IBookmarks bookmarks = openCase.getSource().getBookmarks();
        List<Map<String, Object>> result = new ArrayList<>();
        if (bookmarks == null) {
            return result;
        }
        for (Map.Entry<Integer, String> entry : bookmarks.getBookmarkMap().entrySet()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("bookmark_id", entry.getKey());
            view.put("name", entry.getValue());
            view.put("item_count", bookmarks.getBookmarkCount(entry.getKey()));
            view.put("comment", bookmarks.getBookmarkComment(entry.getKey()));
            view.put("in_report", bookmarks.isInReport(entry.getKey()));
            result.add(view);
        }
        return result;
    }

    public Map<String, Object> create(OpenCase openCase, String name) {
        IBookmarks bookmarks = require(openCase);
        requireName(name);
        if (bookmarks.getBookmarkId(name) != -1) {
            throw new McpError(McpError.BOOKMARK_EXISTS, "A bookmark named '" + name + "' already exists.",
                    "Use iped_add_to_bookmark to add items to it, or pick a different name.").with("name", name);
        }
        int id = bookmarks.newBookmark(name);
        bookmarks.saveState(true);
        LOGGER.info("Created bookmark '{}' (id {}) in case {}", name, id, openCase.getCaseId());
        return describe(openCase, bookmarks, id);
    }

    public Map<String, Object> rename(OpenCase openCase, String oldName, String newName) {
        IBookmarks bookmarks = require(openCase);
        requireName(newName);
        int id = requireBookmark(bookmarks, oldName);
        if (bookmarks.getBookmarkId(newName) != -1) {
            throw new McpError(McpError.BOOKMARK_EXISTS, "A bookmark named '" + newName + "' already exists.",
                    "Pick a different name, or delete the existing one first if that is what you meant.")
                            .with("name", newName);
        }
        bookmarks.renameBookmark(id, newName);
        bookmarks.saveState(true);
        LOGGER.info("Renamed bookmark '{}' to '{}' in case {}", oldName, newName, openCase.getCaseId());
        return describe(openCase, bookmarks, id);
    }

    public Map<String, Object> delete(OpenCase openCase, String name) {
        IBookmarks bookmarks = require(openCase);
        int id = requireBookmark(bookmarks, name);
        int count = bookmarks.getBookmarkCount(id);
        bookmarks.delBookmark(id);
        bookmarks.saveState(true);
        LOGGER.info("Deleted bookmark '{}' ({} items) in case {}", name, count, openCase.getCaseId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("deleted", name);
        result.put("item_count", count);
        return result;
    }

    public Map<String, Object> addItems(OpenCase openCase, String name, List<Integer> itemIds) {
        IBookmarks bookmarks = require(openCase);
        int id = requireBookmark(bookmarks, name);
        List<Integer> valid = validate(openCase, itemIds);
        bookmarks.addBookmark(valid, id);
        bookmarks.saveState(true);
        Map<String, Object> result = describe(openCase, bookmarks, id);
        result.put("items_added", valid.size());
        return result;
    }

    public Map<String, Object> removeItems(OpenCase openCase, String name, List<Integer> itemIds) {
        IBookmarks bookmarks = require(openCase);
        int id = requireBookmark(bookmarks, name);
        List<Integer> valid = validate(openCase, itemIds);
        bookmarks.removeBookmark(valid, id);
        bookmarks.saveState(true);
        Map<String, Object> result = describe(openCase, bookmarks, id);
        result.put("items_removed", valid.size());
        return result;
    }

    /** The examiner's selection, which the IPED UI shows as checked items (FR-027). */
    public Map<String, Object> getSelection(OpenCase openCase, int limit) {
        IBookmarks bookmarks = require(openCase);
        List<Integer> selected = new ArrayList<>();
        IPEDSource source = openCase.getSource();
        for (int id = 0; id <= source.getLastId() && selected.size() < limit; id++) {
            if (source.getLuceneId(id) >= 0 && bookmarks.isChecked(id)) {
                selected.add(id);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("total_selected", bookmarks.getTotalChecked());
        result.put("item_ids", selected);
        result.put("truncated", bookmarks.getTotalChecked() > selected.size());
        return result;
    }

    public Map<String, Object> setSelection(OpenCase openCase, List<Integer> itemIds, boolean selected) {
        IBookmarks bookmarks = require(openCase);
        List<Integer> valid = validate(openCase, itemIds);
        for (int id : valid) {
            bookmarks.setChecked(selected, id);
        }
        bookmarks.saveState(true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("changed", valid.size());
        result.put("selected", selected);
        result.put("total_selected", bookmarks.getTotalChecked());
        return result;
    }

    /**
     * Captures what a destructive operation is about to overwrite, for the write-ahead audit record
     * (FR-033).
     */
    public Map<String, Object> captureBookmarkState(OpenCase openCase, String name) {
        IBookmarks bookmarks = openCase.getSource().getBookmarks();
        if (bookmarks == null || name == null) {
            return null;
        }
        int id = bookmarks.getBookmarkId(name);
        if (id == -1) {
            return null;
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("bookmark_id", id);
        state.put("name", name);
        state.put("item_count", bookmarks.getBookmarkCount(id));
        state.put("comment", bookmarks.getBookmarkComment(id));
        state.put("in_report", bookmarks.isInReport(id));
        // The member ids are what makes the prior state usable for recovery. Capturing them costs
        // one pass over the id space, which is the price of being able to undo.
        List<Integer> members = new ArrayList<>();
        IPEDSource source = openCase.getSource();
        for (int itemId = 0; itemId <= source.getLastId(); itemId++) {
            if (source.getLuceneId(itemId) >= 0 && bookmarks.hasBookmark(itemId, id)) {
                members.add(itemId);
            }
        }
        state.put("item_ids", members);
        return state;
    }

    private static IBookmarks require(OpenCase openCase) {
        IBookmarks bookmarks = openCase.getSource().getBookmarks();
        if (bookmarks == null) {
            throw new McpError(McpError.INTERNAL_ERROR, "This case has no bookmark state to write to.",
                    "Open the case once in the IPED UI to initialize its bookmark state, then retry.");
        }
        return bookmarks;
    }

    private static void requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new McpError(McpError.INVALID_ARGUMENT, "A bookmark name is required and cannot be blank.",
                    "Pass a non-empty 'name'. Name it after the finding, not after the query that found it.");
        }
    }

    private static int requireBookmark(IBookmarks bookmarks, String name) {
        int id = name == null ? -1 : bookmarks.getBookmarkId(name);
        if (id == -1) {
            throw new McpError(McpError.BOOKMARK_NOT_FOUND, "There is no bookmark named '" + name + "'.",
                    "Call iped_list_bookmarks to see the names that exist in this case.").with("name", name)
                            .with("existing", new ArrayList<>(bookmarks.getBookmarkMap().values()));
        }
        return id;
    }

    /**
     * @throws McpError
     *             when an id is not in this case; a partial write on a bad batch would leave the
     *             case in a state the audit record does not describe
     */
    private static List<Integer> validate(OpenCase openCase, List<Integer> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new McpError(McpError.INVALID_ARGUMENT, "No item ids were given.",
                    "Pass the ids in 'item_ids'. Take them from a result of iped_search on this same case.");
        }
        IPEDSource source = openCase.getSource();
        List<Integer> invalid = new ArrayList<>();
        for (Integer id : itemIds) {
            if (id == null || id < 0 || id > source.getLastId() || source.getLuceneId(id) < 0) {
                invalid.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            throw new McpError(McpError.ITEM_NOT_FOUND,
                    invalid.size() + " of the " + itemIds.size() + " ids are not in case " + openCase.getCaseId()
                            + ", so nothing was written.",
                    "Item ids are local to a case and collide between cases. Take them from a result of "
                            + "iped_search on this same case; the highest id here is " + source.getLastId() + ".")
                                    .with("invalidIds", invalid).with("lastId", source.getLastId());
        }
        return new ArrayList<>(itemIds);
    }

    private static Map<String, Object> describe(OpenCase openCase, IBookmarks bookmarks, int id) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("case_id", openCase.getCaseId());
        view.put("bookmark_id", id);
        view.put("name", bookmarks.getBookmarkName(id));
        view.put("item_count", bookmarks.getBookmarkCount(id));
        return view;
    }
}
