package iped.mcp.item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;

import iped.data.IBookmarks;
import iped.engine.data.IPEDSource;
import iped.engine.task.index.IndexItem;
import iped.mcp.protocol.McpError;
import iped.mcp.query.FieldNames;
import iped.mcp.query.FieldVocabulary;
import iped.properties.BasicProps;
import iped.utils.DateUtil;

/**
 * A caller-chosen set of fields, read from a batch of items in one pass.
 *
 * <p>
 * {@link ItemView} answers "what is this item", with a fixed set of essential properties. This
 * answers "what does this case hold in these fields, for these items" — the projection an examiner
 * needs once the interesting field is a case-specific one: {@code ufed:UserID} over a page of chat
 * messages, {@code p2p:fileType} over a set of shared files, two EXIF fields over three hundred
 * photos. Without it the only way to reach those was one {@code iped_item_fields} call per item,
 * which is the round-trip cost this module exists to remove.
 *
 * <p>
 * <b>A name this case does not have is refused, never dropped.</b> Answering with items that simply
 * lack the misspelled field is indistinguishable from items that genuinely do not have it, and an
 * agent reading that back concludes the evidence is absent. So resolution happens once, before any
 * document is read, and the whole call fails with the near names attached (FR-047).
 *
 * <p>
 * The names accepted are the ones the index holds — the authority is {@link FieldVocabulary}, not
 * documentation — plus every key this server publishes inside an item, so that anything an agent
 * received in a result can be handed straight back in a request. {@code content_type} is answered by
 * {@code contentType}, and the result says so in {@code resolved_fields}.
 */
public final class FieldSelection {

    /** Answered from case state rather than from the item's document. */
    private static final String CASE_ID = "case_id";
    private static final String BOOKMARKS = "bookmarks";
    private static final String SELECTED = "selected";
    private static final String SNIPPET = "snippet";

    /** Keys {@link ItemView} publishes whose index name differs from the key. */
    private static final Map<String, String> ALIASES = aliases();

    /**
     * Fields the index writes as {@code "true"}/{@code "false"} and that this server publishes as
     * JSON booleans, so that a projection types them the way the default view does.
     */
    private static final Set<String> BOOLEAN_FIELDS = new LinkedHashSet<>(Arrays.asList(BasicProps.DELETED,
            BasicProps.CARVED, BasicProps.SUBITEM, BasicProps.ISDIR, BasicProps.ISROOT, BasicProps.HASCHILD));

    /**
     * Timestamps of the item itself. The case type map covers metadata dates but not these, which
     * {@code IndexItem} writes directly.
     */
    private static final Set<String> DATE_FIELDS = new LinkedHashSet<>(Arrays.asList(BasicProps.CREATED,
            BasicProps.MODIFIED, BasicProps.ACCESSED, BasicProps.CHANGED));

    private final List<String> asked;
    private final Map<String, String> indexFields;
    private final Set<String> computed;
    private final Map<String, String> renamed;
    private final Set<String> storedFields;

    private FieldSelection(List<String> asked, Map<String, String> indexFields, Set<String> computed,
            Map<String, String> renamed) {
        this.asked = Collections.unmodifiableList(asked);
        this.indexFields = indexFields;
        this.computed = computed;
        this.renamed = Collections.unmodifiableMap(renamed);
        this.storedFields = Collections.unmodifiableSet(new LinkedHashSet<>(indexFields.values()));
    }

    /**
     * Resolves every asked-for name against this case, before a single item is read.
     *
     * @throws McpError
     *             {@code UNKNOWN_FIELD} naming every name this case does not have, with near names
     *             for each — one refusal for the whole list, so that correcting a ten-field
     *             projection does not cost ten round trips
     */
    public static FieldSelection resolve(FieldVocabulary vocabulary, List<String> asked) {
        Map<String, String> lowercased = new LinkedHashMap<>();
        for (String field : vocabulary.getFields()) {
            lowercased.putIfAbsent(field.toLowerCase(Locale.ROOT), field);
        }

        Map<String, String> indexFields = new LinkedHashMap<>();
        Set<String> computed = new LinkedHashSet<>();
        Map<String, String> renamed = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        List<String> ordered = new ArrayList<>();

        for (String name : asked) {
            if (ordered.contains(name)) {
                continue;
            }
            ordered.add(name);
            if (BasicProps.CONTENT.equals(name)) {
                throw contentIsNotStored();
            }
            if (SNIPPET.equals(name)) {
                throw snippetIsNotAField();
            }
            if (CASE_ID.equals(name) || BOOKMARKS.equals(name) || SELECTED.equals(name)) {
                computed.add(name);
                continue;
            }
            String resolved = resolveOne(vocabulary, lowercased, name);
            if (resolved == null) {
                unknown.add(name);
                continue;
            }
            indexFields.put(name, resolved);
            if (!resolved.equals(name)) {
                renamed.put(name, resolved);
            }
        }

        if (!unknown.isEmpty()) {
            throw unknownFields(vocabulary, ordered, unknown);
        }
        return new FieldSelection(ordered, indexFields, computed, renamed);
    }

    /**
     * The index name behind one asked-for name.
     *
     * @return the name to read from the document, or {@code null} when this case has nothing by that
     *         name
     */
    private static String resolveOne(FieldVocabulary vocabulary, Map<String, String> lowercased, String name) {
        if (vocabulary.exists(name)) {
            return name;
        }
        // A name written the way a query needs it: the agent that just escaped a colon for iped_search
        // pastes the same spelling here, and that escape is query punctuation, not part of the name.
        String plain = FieldNames.fromQueryForm(name);
        if (plain != null && !plain.equals(name) && vocabulary.exists(plain)) {
            return plain;
        }
        String alias = ALIASES.get(name);
        if (alias != null && vocabulary.exists(alias)) {
            return alias;
        }
        return lowercased.get(name.toLowerCase(Locale.ROOT));
    }

    /** Index names to load from each document. Nothing else is read. */
    public Set<String> storedFields() {
        return storedFields;
    }

    /** The names asked for, deduplicated, in the order given. */
    public List<String> askedFields() {
        return asked;
    }

    /** Asked-for name to index name, only where the two differ. Empty when nothing was rewritten. */
    public Map<String, String> renamedFields() {
        return renamed;
    }

    /**
     * The projected view of one item: a {@code fields} map keyed by the names asked for, and the
     * declared absences.
     *
     * <p>
     * Keyed by what the caller asked rather than by what the index calls it, so that a lookup of the
     * name sent always finds the value. {@code resolved_fields} in the result carries the
     * correspondence.
     */
    public Map<String, Object> project(IPEDSource source, String caseId, int luceneId, Document doc) {
        int itemId = source.getId(luceneId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("case_id", caseId);
        view.put("item_id", itemId);

        Map<String, Object> values = new LinkedHashMap<>();
        List<Map<String, String>> unavailable = new ArrayList<>();
        for (String name : asked) {
            if (computed.contains(name)) {
                values.put(name, computedValue(source, caseId, itemId, name));
                continue;
            }
            String field = indexFields.get(name);
            IndexableField[] stored = doc.getFields(field);
            if (stored.length == 0) {
                if (BOOLEAN_FIELDS.contains(field)) {
                    // The index writes isRoot only for a root item, so its absence is the value false
                    // rather than an unknown. Declaring it unavailable would invite an agent to treat
                    // every non-root item as undetermined.
                    values.put(name, Boolean.FALSE);
                } else {
                    unavailable.add(reason(name, "item " + itemId + " has no value for " + field
                            + ". The field exists in this case, so this is an absence in this item rather than a "
                            + "missing field; other items may have it."));
                }
                continue;
            }
            List<Object> converted = new ArrayList<>(stored.length);
            for (IndexableField value : stored) {
                Object typed = valueOf(field, value);
                if (typed != null) {
                    converted.add(typed);
                }
            }
            if (converted.isEmpty()) {
                unavailable.add(reason(name, field + " holds binary data for item " + itemId
                        + ", and a projection returns text, numbers and booleans only. Call "
                        + (BasicProps.THUMB.equals(field) ? "iped_item_thumbnail" : "iped_item_content")
                        + " for the bytes."));
                continue;
            }
            values.put(name, converted.size() == 1 ? converted.get(0) : converted);
        }

        view.put("fields", values);
        view.put("unavailable", unavailable);
        return view;
    }

    private Object computedValue(IPEDSource source, String caseId, int itemId, String name) {
        if (CASE_ID.equals(name)) {
            return caseId;
        }
        IBookmarks bookmarks = source.getBookmarks();
        if (SELECTED.equals(name)) {
            return bookmarks != null && bookmarks.isChecked(itemId);
        }
        List<String> names = bookmarks == null ? null : bookmarks.getBookmarkList(itemId);
        return names == null ? new ArrayList<String>() : names;
    }

    /**
     * One stored value, typed the way the rest of the surface types it.
     *
     * @return {@code null} when the value is binary and so cannot be projected
     */
    private static Object valueOf(String field, IndexableField value) {
        if (BOOLEAN_FIELDS.contains(field)) {
            return Boolean.valueOf(Boolean.parseBoolean(value.stringValue()));
        }
        Number number = value.numericValue();
        if (number != null) {
            return number;
        }
        String text = value.stringValue();
        if (text == null) {
            return null;
        }
        if (isDate(field)) {
            try {
                return DateUtil.stringToDate(text).toInstant().toString();
            } catch (Exception e) {
                // A timestamp the index holds in a shape we cannot parse comes back as stored, never
                // dropped: the examiner has to be able to see it.
                return text;
            }
        }
        return text;
    }

    /**
     * Whether this field holds a timestamp.
     *
     * <p>
     * The case type map is engine-global state loaded when a case is opened, so with more than one
     * case open it describes the last one opened. That only ever costs a metadata date its
     * normalization: the value still comes back, in the form the index holds it.
     */
    private static boolean isDate(String field) {
        return DATE_FIELDS.contains(field) || Date.class.equals(IndexItem.getMetadataTypes().get(field));
    }

    private static Map<String, String> reason(String field, String why) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("reason", why);
        return entry;
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("item_id", BasicProps.ID);
        aliases.put("content_type", BasicProps.CONTENTTYPE);
        aliases.put("parent_id", BasicProps.PARENTID);
        aliases.put("is_dir", BasicProps.ISDIR);
        aliases.put("is_root", BasicProps.ISROOT);
        aliases.put("has_children", BasicProps.HASCHILD);
        aliases.put("evidence_uuid", BasicProps.EVIDENCE_UUID);
        return Collections.unmodifiableMap(aliases);
    }

    private static McpError contentIsNotStored() {
        return new McpError(McpError.UNKNOWN_FIELD,
                "The field content is the extracted text of an item. The index searches it but does not store it, "
                        + "so no projection can return it.",
                "Call iped_item_text for the text of one item, or iped_search with include_snippets for the "
                        + "excerpt around a match.").with("field", BasicProps.CONTENT);
    }

    private static McpError snippetIsNotAField() {
        return new McpError(McpError.UNKNOWN_FIELD,
                "There is no snippet field in the index. A snippet is the excerpt built around a query match, so "
                        + "it exists only in the result of a search.",
                "Call iped_search with include_snippets=true to get it. There is no snippet without a query: "
                        + "nothing would say what the excerpt is an excerpt of.").with("field", SNIPPET);
    }

    private static McpError unknownFields(FieldVocabulary vocabulary, List<String> asked, List<String> unknown) {
        Map<String, List<String>> similar = new LinkedHashMap<>();
        Map<String, List<String>> namespaced = new LinkedHashMap<>();
        for (String name : unknown) {
            // A name that exists *under* the asked-for one is not a near miss, exactly as in a query:
            // it is a namespaced name cut at its colon, and the remedy is the full name.
            List<String> under = vocabulary.namesUnder(name);
            if (!under.isEmpty()) {
                namespaced.put(name, new ArrayList<>(under.subList(0, Math.min(under.size(), 12))));
            } else {
                similar.put(name, vocabulary.similar(name, 5));
            }
        }
        List<String> recognized = new ArrayList<>(asked);
        recognized.removeAll(unknown);

        String first = unknown.get(0);
        StringBuilder remedy = new StringBuilder();
        if (namespaced.containsKey(first)) {
            remedy.append("Retry with ").append(namespaced.get(first).get(0))
                    .append(", a full name of this case that begins with ").append(first).append(". ");
        } else if (!similar.get(first).isEmpty()) {
            remedy.append("Retry with ").append(similar.get(first).get(0))
                    .append(", the closest name this case actually has; details.similar carries the near names of "
                            + "each rejected one. ");
        } else {
            remedy.append("Call iped_list_fields for the vocabulary of this case, or iped_item_fields on one item "
                    + "to see what it carries. ");
        }
        remedy.append("Names here are the plain ones those tools return: a colon inside a name is written as it "
                + "is, because this parameter is a list of names and not a query expression. Nothing was read — a "
                + "projection is refused whole rather than answered with items missing the field, because a field "
                + "never read looks exactly like a field the items do not have.");

        McpError error = new McpError(McpError.UNKNOWN_FIELD,
                unknown.size() + " of the " + asked.size()
                        + " names asked for in fields do not exist in this case index: " + String.join(", ", unknown)
                        + ".",
                remedy.toString()).with("unknown_fields", unknown).with("recognized_fields", recognized)
                        .with("fields_searched", vocabulary.getFields().size());
        if (!similar.isEmpty()) {
            error.with("similar", similar);
        }
        if (!namespaced.isEmpty()) {
            error.with("namespaced_fields", namespaced);
        }
        return error;
    }
}
