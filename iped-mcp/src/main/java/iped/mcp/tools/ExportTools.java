package iped.mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IBookmarks;
import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.ContentClass;
import iped.mcp.export.ArtifactWriter;
import iped.mcp.export.EvidenceFileName;
import iped.mcp.export.ItemFileWriter;
import iped.mcp.export.ItemFileWriter.Written;
import iped.mcp.export.PathConfinement;
import iped.mcp.export.PathConfinement.ResolvedDestination;
import iped.mcp.item.ContentAccess;
import iped.mcp.item.ContentAccess.ExtractedText;
import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.query.PagedSearcher;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;
import iped.mcp.transport.Transport;

/**
 * What leaves this server as a file rather than as an answer.
 *
 * <ul>
 * <li>{@code iped_export_artifact} turns a bookmark, a query or an explicit list into a spreadsheet,
 * CSV or JSON file — a listing of items, never their content.</li>
 * <li>{@code iped_export_item} writes one item: its own bytes, or the text extracted from it.</li>
 * </ul>
 *
 * <p>
 * Both exist for the same reason. The complete set goes to the file; the conversation gets a count,
 * a sample and a path (FR-067). Five thousand rows in a report are useful, five thousand rows in a
 * conversation are a context window spent on data nobody reads — and the same holds for a thirty
 * megabyte PDF, which is why the ceilings on {@code iped_item_content} and {@code iped_item_text} do
 * not apply here: those ceilings protect the conversation, and a file on disk is not one.
 *
 * <p>
 * Every destination is confined to a declared write root, and refused inside the case folder by
 * default (FR-068). A deliverable written into the case would be indistinguishable, later, from
 * something the case itself produced.
 */
public class ExportTools {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportTools.class);

    /** Hard ceiling on how many items one artifact may carry, as a guard against a runaway query. */
    private static final int MAX_ITEMS = 1_000_000;

    /** Marks an export that holds the text of an item rather than the item. */
    private static final String TEXT_SUFFIX = ".txt";

    private final Session session;
    private final PagedSearcher pagedSearcher;
    private final ArtifactWriter artifactWriter;
    private final ContentAccess contentAccess;
    private final ItemFileWriter itemFileWriter;

    public ExportTools(Session session, PagedSearcher pagedSearcher, ArtifactWriter artifactWriter,
            ContentAccess contentAccess, ItemFileWriter itemFileWriter) {
        this.session = session;
        this.pagedSearcher = pagedSearcher;
        this.artifactWriter = artifactWriter;
        this.contentAccess = contentAccess;
        this.itemFileWriter = itemFileWriter;
    }

    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> descriptors = new ArrayList<>(2);
        descriptors.add(exportItemDescriptor());
        descriptors.add(exportArtifactDescriptor());
        return descriptors;
    }

    /**
     * Declares {@code iped_export_item}, deliberately <b>without</b> a content class.
     *
     * <p>
     * {@code returnsContent} blocks the whole tool when the class it names is not allowed, and this
     * tool has no single class — {@code text_only} decides it per call. Declaring {@code binary}
     * would refuse the text export in an installation that permits text and withholds files, which
     * is the configuration most likely to want exactly that. The policy is applied inside
     * {@link #exportItem}, against the class the arguments actually ask for.
     */
    private ToolDescriptor exportItemDescriptor() {
        return new ToolDescriptor("iped_export_item",
                "Writes one item to a file in the server's configured export folder, and returns the path "
                        + "with the digests of what was written. By default the item's own bytes are "
                        + "exported and verified against the hash the case recorded for it; with "
                        + "text_only, the extracted text is written as UTF-8 instead. Nothing is truncated "
                        + "— the ceilings that apply to iped_item_content and iped_item_text exist to "
                        + "protect this conversation, and a file on disk is not this conversation.",
                arguments -> exportItem(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("item_id", "integer", "Item identifier, local to this case.")
                        .optional("text_only", "boolean",
                                "False (the default) exports the item's own bytes — the file as it exists in "
                                        + "the evidence. True exports the text extracted from it, as a UTF-8 "
                                        + ".txt file. Use text_only for reading and citing; use the default "
                                        + "when the file itself is the deliverable.");
    }

    private ToolDescriptor exportArtifactDescriptor() {
        return new ToolDescriptor("iped_export_artifact",
                "Writes the complete set defined by a bookmark, a query or an explicit id list to a file, in "
                        + "xlsx, csv or json. Nothing is paginated and nothing is truncated. You get back the "
                        + "count, a small sample and the path — the file is the deliverable, not this "
                        + "conversation.",
                arguments -> export(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("format", "string", "One of: xlsx, csv, json.")
                        .required("destination", "string",
                                "Absolute path of the file to write. Must be outside the case folder.")
                        .optional("bookmark", "string", "Export every item in this bookmark.")
                        .optional("query", "string", "Export every item matching this IPED query.")
                        .optional("item_ids", "integer[]", "Export exactly these items.")
                        .optional("group_by_conversation", "boolean",
                                "Group messages by conversation, in chronological order, with sender and "
                                        + "recipient identified. Use for chat exports.")
                        .returnsContent("metadata");
    }

    /**
     * {@code iped_export_item}: one item, one file, in the folder the configuration declares.
     *
     * <p>
     * The destination is not a parameter. Everywhere else the agent names the file it wants; here it
     * does not, because the caller has no reason to and every reason not to — the name comes from
     * the evidence, and a name from seized material is the input this server trusts least
     * ({@link EvidenceFileName}). What the agent gets back is the path, so it can tell the examiner
     * where the file is.
     *
     * <p>
     * The text branch calls the same {@code extractText} that {@code iped_item_text} calls, on
     * purpose: two extractions that could drift would mean the text an examiner reads in the
     * conversation is not the text in the file they file away.
     */
    private Map<String, Object> exportItem(JsonNode arguments) {
        OpenCase openCase = session.getCaseRegistry().require(Args.requiredString(arguments, "case_id",
                "Pass the case_id returned by iped_open_case."));
        int itemId = Args.requiredInt(arguments, "item_id", "Pass the item_id of the item to export.");
        boolean textOnly = Args.optionalBoolean(arguments, "text_only", false);

        IItem item = contentAccess.requireItem(openCase, itemId);
        // The class of content differs by branch and so does the policy that governs it: an
        // installation may well allow text out and keep the files in.
        session.getEgressPolicy().enforce(textOnly ? ContentClass.text : ContentClass.binary, item);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("item_id", itemId);
        result.put("name", item.getName());
        result.put("content_type", item.getMediaType() == null ? null : item.getMediaType().toString());
        result.put("exported", textOnly ? "text" : "evidence_file");

        if (item.isDir()) {
            return unavailable(result, "this item is a directory and has no content of its own to export",
                    "Use iped_item_tree to list what it contains, then export the items themselves.");
        }
        return textOnly ? exportText(openCase, item, result) : exportContent(openCase, item, result);
    }

    /** The item's own bytes, verified against what the case recorded for them. */
    private Map<String, Object> exportContent(OpenCase openCase, IItem item, Map<String, Object> result) {
        Long length = item.getLength();
        Path destination = destinationFor(openCase, item, null);
        Written written;
        try {
            written = itemFileWriter.writeContent(item, destination);
        } catch (IOException e) {
            return unavailable(result,
                    "the content of this item could not be read: " + e.getMessage()
                            + "; in a portable case this usually means the evidence file is not present",
                    "Metadata stays available through iped_item_metadata, and text may still be available "
                            + "through this tool with text_only.");
        }
        if (written.getBytes() == 0) {
            // A zero-byte export is indistinguishable from a failed one once it is on disk.
            deleteQuietly(destination);
            return unavailable(result, "this item has zero bytes of content, so there was nothing to write",
                    "That is a fact about the item, not a failure to read it. Its length in the index is "
                            + (length == null ? "not recorded" : length + " bytes") + ".");
        }
        describeWrite(result, written);
        result.put("real_size", length);
        verifyAgainstCase(result, item, written);
        if (length == null && item.hasPreview()) {
            // No stretch of the evidence is this item; what exists is what IPED rendered for it.
            result.put("source_note", "This item has no file behind it — it was decoded from a container. What "
                    + "was written is the preview IPED generated for it during processing, not a file that "
                    + "existed in the evidence. Do not present it as one.");
        }
        return finish(openCase, result);
    }

    /** The extracted text, through the same path {@code iped_item_text} uses. */
    private Map<String, Object> exportText(OpenCase openCase, IItem item, Map<String, Object> result) {
        // No ceiling: the ceiling on iped_item_text protects the conversation, and this is a file.
        ExtractedText extracted = contentAccess.extractText(openCase, item, ContentAccess.NO_LIMIT);
        if (extracted.failure != null) {
            return unavailable(result, extracted.failure, extracted.remedy);
        }
        if (extracted.text.isEmpty()) {
            return unavailable(result, "no text could be extracted from this item, so there was nothing to write",
                    "Call iped_item_text for the reason, which is derived from this item: it names where the "
                            + "content is when the content is elsewhere.");
        }
        Path destination = destinationFor(openCase, item, TEXT_SUFFIX);
        Written written;
        try {
            written = itemFileWriter.writeText(extracted.text, destination);
        } catch (IOException e) {
            return unavailable(result, "the extracted text could not be written: " + e.getMessage(),
                    "Check that the export folder exists and is writable; it is declared in "
                            + "conf/McpServerConfig.txt.");
        }
        describeWrite(result, written);
        result.put("characters", extracted.text.length());
        result.put("charset", "UTF-8");
        if (extracted.parsedBy != null) {
            result.put("extracted_by", extracted.parsedBy);
        }
        // The digests are of the text file, and there is nothing in the case to check them against:
        // the recorded hash is of the item's bytes. Saying so keeps them from being read as
        // verification that did not happen.
        result.put("hash_verified", false);
        result.put("hash_note", "The digests above are of the text file written now. They are not comparable "
                + "with the hash the case recorded, which is of the item's own bytes. Export without "
                + "text_only to obtain a file that can be verified against the case.");
        return finish(openCase, result);
    }

    /** Where an item's export goes: the first configured root, a folder per case, the item id in the name. */
    private Path destinationFor(OpenCase openCase, IItem item, String suffix) {
        McpServerConfig config = session.getConfig();
        List<Path> roots = config.getResolvedExportRoots();
        if (roots.isEmpty()) {
            throw new McpError(McpError.DESTINATION_REFUSED,
                    "This server has no folder it may write to, so nothing can be exported.",
                    "Declare exportRoots in conf/McpServerConfig.txt. This cannot be set from this "
                            + "conversation.");
        }
        // A folder per case, because item ids are local to a case and collide between them: two
        // exports named 4711-photo.jpg from two cases would silently be one file.
        String fileName = EvidenceFileName.forItem(item.getId(), item.getName(), suffix);
        Path proposed = roots.get(0).resolve(openCase.getCaseId()).resolve(fileName);
        // Built here rather than by the caller, and checked anyway. The name came from the evidence,
        // and the whole point of a confinement rule is that it does not depend on the code that
        // builds the path having been careful.
        ResolvedDestination destination = PathConfinement.resolve(proposed.toString(), roots,
                openCase.getCasePath(), session.getConfig().isAllowExportIntoCaseFolder());
        if (!destination.isAllowed()) {
            throw refusal(destination, openCase);
        }
        return destination.getResolved();
    }

    private static void describeWrite(Map<String, Object> result, Written written) {
        result.put("available", true);
        result.put("path", written.getPath().toString());
        result.put("bytes_written", written.getBytes());
        result.put("md5", written.getMd5());
        result.put("sha256", written.getSha256());
    }

    /**
     * Compares what was written against what the case recorded, when the case recorded anything.
     *
     * <p>
     * This is the reason an export tool is worth more than a copy: the file on disk either hashes to
     * the value the case holds or it does not, and both answers belong in the result. Hashing may
     * have been disabled during processing, and an item may have no hash of its own — those are
     * declared, not passed off as agreement.
     */
    private static void verifyAgainstCase(Map<String, Object> result, IItem item, Written written) {
        String recordedSha256 = extraAttribute(item, "sha-256");
        String recordedMd5 = extraAttribute(item, "md5");
        String algorithm = recordedSha256 != null ? "sha-256" : recordedMd5 != null ? "md5" : null;
        if (algorithm == null) {
            result.put("hash_verified", false);
            result.put("hash_note", "The case recorded no hash for this item that could be compared — hashing "
                    + "may have been disabled during processing, or the item may have had no content to hash. "
                    + "The digests above were computed from the bytes just written.");
            return;
        }
        String recorded = recordedSha256 != null ? recordedSha256 : recordedMd5;
        String computed = recordedSha256 != null ? written.getSha256() : written.getMd5();
        boolean matches = recorded.equalsIgnoreCase(computed);
        result.put("hash_verified", matches);
        result.put("hash_verified_against", algorithm);
        result.put("hash_recorded_in_case", recorded);
        if (!matches) {
            result.put("hash_note", "The file written does not hash to the value the case recorded for this "
                    + "item. Do not treat this file as the item until that is explained: the evidence may "
                    + "have changed since processing, or the export may be incomplete.");
        }
    }

    private static String extraAttribute(IItem item, String name) {
        Object value = item.getExtraAttribute(name);
        String text = value == null ? null : value.toString().trim();
        return text == null || text.isEmpty() ? null : text;
    }

    private Map<String, Object> finish(OpenCase openCase, Map<String, Object> result) {
        if (session.getTransport() == Transport.Kind.SOCKET) {
            result.put("destination_filesystem", "server");
            result.put("destination_note", "This path is on the machine running the MCP server, not on the "
                    + "machine running this conversation. Retrieve the file there.");
        }
        return result;
    }

    private static Map<String, Object> unavailable(Map<String, Object> result, String reason, String remedy) {
        result.put("available", false);
        result.put("reason", reason);
        result.put("remedy", remedy);
        return result;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("An empty export could not be removed: {}", path, e);
        }
    }

    private Map<String, Object> export(JsonNode arguments) {
        OpenCase openCase = session.getCaseRegistry().require(Args.requiredString(arguments, "case_id",
                "Pass the case_id returned by iped_open_case."));
        String format = Args.requiredString(arguments, "format", "Pass one of: xlsx, csv, json.");
        String requested = Args.requiredString(arguments, "destination",
                "Pass an absolute file path inside one of the folders this server may write to.");
        // Before the set is materialized, and before anything touches the filesystem: a refused
        // destination must cost nothing and leave nothing (FR-002).
        ResolvedDestination destination = checkDestination(openCase, requested);

        ItemSet set = resolveSet(openCase, arguments);
        Map<String, Object> result = artifactWriter.write(openCase, set.ids, format, destination.getResolved(),
                Args.optionalBoolean(arguments, "group_by_conversation", false));
        if (session.getTransport() == Transport.Kind.SOCKET) {
            // The examiner is on another machine and cannot tell, from the path alone, which
            // filesystem produced it. Saying so beats letting them go looking on the wrong one.
            result.put("destination_filesystem", "server");
            result.put("destination_note", "This path is on the machine running the MCP server, not on the machine "
                    + "running this conversation. Retrieve the file there.");
        }
        if (set.plan != null) {
            // An artifact outlives the conversation, so a repaired expression has to be legible
            // from the artifact's own result: it is what the examiner will cite.
            PagedSearcher.declareNormalization(result, set.plan);
        }
        return result;
    }

    /** The items to export, and the query plan that produced them when a query defined the set. */
    private static final class ItemSet {

        private final List<Integer> ids;
        private final PagedSearcher.QueryPlan plan;

        ItemSet(List<Integer> ids, PagedSearcher.QueryPlan plan) {
            this.ids = ids;
            this.plan = plan;
        }
    }

    /** Exactly one of bookmark, query or item_ids defines the set. */
    private ItemSet resolveSet(OpenCase openCase, JsonNode arguments) {
        String bookmark = Args.optionalString(arguments, "bookmark", null);
        String query = Args.optionalString(arguments, "query", null);
        JsonNode ids = arguments.get("item_ids");
        int given = (bookmark != null ? 1 : 0) + (query != null ? 1 : 0) + (ids != null && ids.isArray() ? 1 : 0);
        if (given != 1) {
            throw new McpError(McpError.INVALID_ARGUMENT,
                    "Exactly one of 'bookmark', 'query' or 'item_ids' defines what to export; " + given
                            + " were given.",
                    "Pass one of them. Use 'bookmark' for curated findings, 'query' for a result set, "
                            + "'item_ids' for a list you assembled yourself.");
        }
        if (bookmark != null) {
            return new ItemSet(fromBookmark(openCase, bookmark), null);
        }
        if (query != null) {
            PagedSearcher.QueryPlan plan = pagedSearcher.plan(openCase, query);
            return new ItemSet(pagedSearcher.collectAllIds(openCase,
                    pagedSearcher.forItems(openCase, plan.getQuery()), MAX_ITEMS), plan);
        }
        List<Integer> explicit = new ArrayList<>();
        for (JsonNode entry : ids) {
            if (entry.canConvertToInt()) {
                explicit.add(entry.asInt());
            }
        }
        return new ItemSet(explicit, null);
    }

    private List<Integer> fromBookmark(OpenCase openCase, String name) {
        IPEDSource source = openCase.getSource();
        IBookmarks bookmarks = source.getBookmarks();
        int bookmarkId = bookmarks == null ? -1 : bookmarks.getBookmarkId(name);
        if (bookmarkId == -1) {
            throw new McpError(McpError.BOOKMARK_NOT_FOUND, "There is no bookmark named '" + name + "'.",
                    "Call iped_list_bookmarks to see the names this case has.").with("name", name);
        }
        List<Integer> itemIds = new ArrayList<>();
        for (int id = 0; id <= source.getLastId(); id++) {
            if (source.getLuceneId(id) >= 0 && bookmarks.hasBookmark(id, bookmarkId)) {
                itemIds.add(id);
            }
        }
        return itemIds;
    }

    /**
     * Approves a destination against the declared write roots, or refuses it (FR-001, FR-004).
     *
     * <p>
     * This is a <b>list of what is permitted</b>, not a list of what is forbidden. The previous rule
     * refused the case folder and allowed the rest of the filesystem the account could reach, which
     * protected the case and did nothing for the workstation. A deny list leaves everything nobody
     * anticipated open, and what nobody anticipated is where the defect lives.
     *
     * <p>
     * {@code allowExportIntoCaseFolder} still exists and still means what it says, but it now means
     * only that: it suppresses the case-folder refusal and does not turn the rest of the disk back
     * on. Anyone who was using it to write to an arbitrary folder will start getting refused, and
     * that is the point of the change.
     */
    private ResolvedDestination checkDestination(OpenCase openCase, String requested) {
        McpServerConfig config = session.getConfig();
        ResolvedDestination destination = PathConfinement.resolve(requested, config.getResolvedExportRoots(),
                openCase.getCasePath(), config.isAllowExportIntoCaseFolder());
        if (destination.isAllowed()) {
            return destination;
        }
        throw refusal(destination, openCase);
    }

    /**
     * Turns a refusal into a diagnostic the agent can act on without the examiner stepping in
     * (FR-008), and that the trail can record with the rule that applied (FR-007).
     */
    private static McpError refusal(ResolvedDestination destination, OpenCase openCase) {
        String permitted = describeRoots(destination.getRoots());
        McpError error;
        switch (destination.getVerdict()) {
            case INSIDE_CASE:
                error = new McpError(McpError.DESTINATION_REFUSED,
                        "The destination is inside the case folder, which is refused.",
                        "Write the artifact somewhere outside the case — a working folder or the report "
                                + "folder. An artifact written into the case becomes indistinguishable later "
                                + "from something the case itself produced. Permitted: " + permitted + ".")
                                        .with("casePath", openCase.getCasePath().getAbsolutePath());
                break;
            case UNRESOLVABLE:
                error = new McpError(McpError.DESTINATION_REFUSED,
                        "The destination is not a path this system can name: " + destination.getReason() + ".",
                        "Pass an absolute path to a plain file — no alternate data stream after a colon, no "
                                + "trailing space or dot — under one of: " + permitted + ".");
                break;
            case OUTSIDE_ROOTS:
            default:
                error = new McpError(McpError.DESTINATION_REFUSED,
                        "The destination is outside every folder this server may write to.",
                        "Write the artifact under one of: " + permitted + ". These are declared in "
                                + "conf/McpServerConfig.txt and cannot be changed from this conversation.");
                break;
        }
        error.with("destination", destination.getRequested()).with("rule", destination.getVerdict().name())
                .with("permittedRoots", describeRootList(destination.getRoots()));
        if (destination.getResolved() != null) {
            // Where it would really have landed. For a junction or a short name this is the whole
            // explanation, and without it the refusal looks arbitrary to whoever asked.
            error.with("resolvedTo", destination.getResolved().toString());
        }
        return error;
    }

    private static String describeRoots(List<Path> roots) {
        if (roots.isEmpty()) {
            return "(no writable folder is configured — see exportRoots in conf/McpServerConfig.txt)";
        }
        List<String> quoted = new ArrayList<>(roots.size());
        for (Path root : roots) {
            quoted.add(root.toString());
        }
        return String.join(", ", quoted);
    }

    private static List<String> describeRootList(List<Path> roots) {
        List<String> list = new ArrayList<>(roots.size());
        for (Path root : roots) {
            list.add(root.toString());
        }
        return list;
    }
}
