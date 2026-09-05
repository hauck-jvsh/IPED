package iped.mcp.item;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.IntPoint;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDSource;
import iped.engine.task.ParsingTask;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.ContentClass;
import iped.mcp.egress.EgressPolicy;
import iped.mcp.protocol.McpError;
import iped.mcp.session.OpenCase;
import iped.parsers.standard.StandardParser;
import iped.properties.BasicProps;

/**
 * Access to an item's text, thumbnail, raw content, metadata and place in the hierarchy, under
 * volume ceilings.
 *
 * <p>
 * Two rules govern everything here:
 *
 * <ul>
 * <li><b>Truncation is always signalled and the real size always reported</b> (FR-021). An agent
 * that reads a truncated text and does not know it is truncated will conclude that something is
 * absent from the evidence when it is merely past the ceiling.</li>
 * <li><b>Unavailability is declared with its reason</b> (FR-022). An item with no extracted text, no
 * thumbnail, encrypted content, or whose evidence file is missing from a portable case, says so and
 * points at what else can be tried. It never comes back as an empty string.</li>
 * </ul>
 */
public class ContentAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentAccess.class);

    private final McpServerConfig config;
    private final EgressPolicy egressPolicy;

    public ContentAccess(McpServerConfig config, EgressPolicy egressPolicy) {
        this.config = config;
        this.egressPolicy = egressPolicy;
    }

    /**
     * @throws McpError
     *             with {@link McpError#ITEM_NOT_FOUND} when the id is not in this case
     */
    public IItem requireItem(OpenCase openCase, int itemId) {
        IPEDSource source = openCase.getSource();
        if (itemId < 0 || itemId > source.getLastId() || source.getLuceneId(itemId) < 0) {
            throw new McpError(McpError.ITEM_NOT_FOUND,
                    "There is no item " + itemId + " in case " + openCase.getCaseId() + ".",
                    "Item ids are local to a case and collide between cases. Take the id from a result of "
                            + "iped_search on this same case; the highest id here is " + source.getLastId() + ".")
                                    .with("caseId", openCase.getCaseId()).with("itemId", itemId)
                                    .with("lastId", source.getLastId());
        }
        IItem item = source.getItemByID(itemId);
        if (item == null) {
            throw new McpError(McpError.ITEM_NOT_FOUND,
                    "Item " + itemId + " could not be read from the index of case " + openCase.getCaseId() + ".",
                    "The id is within range but the document is unreadable. The index may be damaged; try "
                            + "reopening the case in the IPED UI to confirm.").with("itemId", itemId);
        }
        return item;
    }

    /** Extracted text, under the configured ceiling (FR-021, FR-022). */
    public Map<String, Object> text(OpenCase openCase, int itemId, Integer maxBytes) {
        IItem item = requireItem(openCase, itemId);
        egressPolicy.enforce(ContentClass.text, item);

        int limit = clamp(maxBytes, config.getMaxTextBytes(), config.getMaxTextBytes());
        Map<String, Object> result = base(openCase, itemId);

        if (item.isDir()) {
            return unavailable(result, "this item is a directory and has no text of its own",
                    "Use iped_item_tree to list what it contains.");
        }
        ExtractedText extracted = extractText(openCase, item, limit);
        if (extracted.failure != null) {
            return unavailable(result, extracted.failure, extracted.remedy);
        }
        if (extracted.text.isEmpty()) {
            return noText(result, item);
        }
        result.put("available", true);
        result.put("text", extracted.text);
        result.put("truncated", extracted.truncated);
        result.put("returned_chars", extracted.text.length());
        if (extracted.parsedBy != null) {
            result.put("extracted_by", extracted.parsedBy);
        }
        if (extracted.typeDetected && extracted.parsedAs != null && item.getMediaType() != null
                && !extracted.parsedAs.equals(item.getMediaType().toString())) {
            // The type that parsed is not the type the item has, and both belong in the answer. An
            // examiner who reads only the parsing type would record the wrong media type for the item.
            result.put("parsed_as", extracted.parsedAs);
            result.put("parsed_as_note", "This item's own media type is " + item.getMediaType()
                    + ", which no parser handles directly — it is a type IPED assigned during processing. "
                    + "The text above was extracted after detecting " + extracted.parsedAs
                    + " from the bytes. Cite the item's type, not this one.");
        }
        if (extracted.truncated) {
            result.put("truncation_note", "Text stops at the ceiling of " + limit
                    + " characters. What follows was not read, and absence of a term in this excerpt is not "
                    + "evidence of its absence from the item.");
        }
        return result;
    }

    /**
     * Why this item has no text, said from what the index actually records about it.
     *
     * <p>
     * The message this replaces offered three hypotheses at once — binary, unparsed, or encrypted —
     * and for the commonest case in a phone extraction all three were wrong. A decoded message is not
     * a file: it is a record a parser built from a container, with no bytes of its own to re-parse,
     * and its content is carried in the metadata the parser wrote. An agent told "no text could be
     * extracted" concludes the messages are empty, which is the confident, wrong, negative finding
     * this server exists to prevent (FR-022, FR-047).
     */
    private Map<String, Object> noText(Map<String, Object> result, IItem item) {
        String type = item.getMediaType() == null ? null : item.getMediaType().toString();
        boolean decoded = Boolean.parseBoolean(String.valueOf(item.getExtraAttribute("isDecodedData")));
        List<String> carriers = textBearingMetadata(item);

        if (decoded || (type != null && type.startsWith("message/"))) {
            String reason = "this item is decoded data" + (type == null ? "" : " (" + type + ")")
                    + ": a record a parser produced from a container, not a file with bytes of its own, so there "
                    + "is nothing to re-parse for text.";
            if (!carriers.isEmpty()) {
                return unavailable(result,
                        reason + " Its content is carried in metadata: " + String.join(", ", carriers) + ".",
                        "Call iped_item_metadata on this item — the fields named above hold what it says. For the "
                                + "conversation around it, iped_item_tree gives the container, whose own text reads "
                                + "as a whole.");
            }
            return unavailable(result, reason,
                    "Call iped_item_metadata for what the parser recorded, and iped_item_tree for the container "
                            + "this record belongs to.");
        }
        if (item.isTimedOut()) {
            return unavailable(result,
                    "parsing this item timed out during processing, so no text was extracted for it then and "
                            + "re-parsing it now stops the same way.",
                    "Call iped_item_content for the raw bytes. Anything this item would have contributed to a "
                            + "search is missing from the index too — do not read a zero on this item as absence.");
        }
        if (!carriers.isEmpty()) {
            return unavailable(result,
                    "no text was extracted from this item's content, but its parser did record content in "
                            + "metadata: " + String.join(", ", carriers) + ".",
                    "Call iped_item_metadata — the fields named above hold what was recorded.");
        }
        return unavailable(result,
                "no text was extracted from this item"
                        + (type == null ? "" : ", whose media type is " + type)
                        + "; for most binary formats that is expected rather than a failure",
                "Call iped_item_metadata for what the parser did record — EXIF, headers, codec details — or "
                        + "iped_item_content for the raw bytes.");
    }

    /**
     * Metadata keys of this item that carry content rather than describe it.
     *
     * <p>
     * Taken from the item's own metadata rather than from a fixed list of parsers: a case may carry
     * chats from any decoder, and naming the fields this item actually has is what makes the remedy
     * something the agent can act on instead of a guess.
     */
    private static List<String> textBearingMetadata(IItem item) {
        List<String> carriers = new ArrayList<>();
        Metadata metadata = item.getMetadata();
        if (metadata == null) {
            return carriers;
        }
        for (String name : metadata.names()) {
            String value = metadata.get(name);
            if (value == null || value.trim().isEmpty() || name.startsWith("X-TIKA")) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith("body") || lower.endsWith("message") || lower.contains("subject")
                    || lower.contains("transcript") || lower.contains("comment") || lower.endsWith("title")) {
                carriers.add(name);
            }
        }
        return carriers;
    }

    /** Result of a bounded text extraction. */
    public static class ExtractedText {
        public String text = "";
        public boolean truncated;
        public String failure;
        public String remedy;
        /** Whether the item's own media type had to be set aside and the type detected from the bytes. */
        public boolean typeDetected;
        /** The parser that actually ran, and the type it ran as — both declared in the result. */
        public String parsedBy;
        public String parsedAs;
    }

    /**
     * Passed as the limit to extract everything the item has.
     *
     * <p>
     * Tika's own convention for {@code BodyContentHandler}, kept rather than invented so the value
     * means here what it means there. The ceilings elsewhere exist to protect the conversation; a
     * caller writing to a file is not the conversation, and a text export cut at the ceiling of a
     * chat window would be an exhibit missing its end.
     */
    public static final int NO_LIMIT = -1;

    /**
     * Extracts an item's text by reparsing it, bounded by a character ceiling.
     *
     * <p>
     * The indexed {@code content} field is indexed but not stored, so text cannot be read back from
     * the index. Reparsing is the available path, which is why every caller passes a budget.
     *
     * @param limit
     *            the ceiling in characters, or {@link #NO_LIMIT} for everything
     */
    public ExtractedText extractText(OpenCase openCase, IItem item, int limit) {
        ExtractedText result = new ExtractedText();
        StandardParser parser = new StandardParser();
        parser.setPrintMetadata(false);
        Metadata metadata = new Metadata();
        ParsingTask.fillMetadata(item, metadata);
        if (!parser.hasSpecificParser(metadata)) {
            // Second line of defence, and it should almost never fire. IPED's media types are declared
            // in conf/CustomSignatures.xml, many as subtypes of something Tika parses
            // (application/x-whatsapp-chat is a subtype of text/html), and McpServerMain registers them
            // at startup so the type resolves through that hierarchy exactly as it does in the IPED UI.
            //
            // What remains for this branch is a type this installation does not know — a case processed
            // by a newer IPED, with a decoder whose type the server's CustomSignatures.xml predates.
            // There, pinning selects nothing and StandardParser falls through to the raw-string parser,
            // which never fails: it returns the printable bytes, so the text of a decoded chat comes back
            // as its own markup with nothing to say anything went wrong. Clearing the pin lets the
            // detector read the bytes instead. The result declares that it did.
            metadata.remove(StandardParser.INDEXER_CONTENT_TYPE);
            metadata.remove(Metadata.CONTENT_TYPE);
            result.typeDetected = true;
        }
        BodyContentHandler handler = new BodyContentHandler(limit);
        try {
            ParsingTask expander = new ParsingTask(item, parser);
            expander.init(ConfigurationManager.get());
            ParseContext context = expander.getTikaContext(openCase.getSource());
            expander.setExtractEmbedded(false);
            try (InputStream is = item.getTikaStream()) {
                parser.parse(is, handler, metadata, context);
            }
            result.text = handler.toString();
        } catch (org.xml.sax.SAXException e) {
            // BodyContentHandler stops the parse when the write limit is reached; that is the
            // ceiling doing its job, not a failure.
            result.text = handler.toString();
            result.truncated = true;
        } catch (IOException e) {
            result.failure = "the content of this item could not be read: " + e.getMessage()
                    + "; in a portable case this usually means the evidence file is not present";
            result.remedy = "Metadata stays available through iped_item_metadata. To read content, attach the "
                    + "original evidence to this case.";
        } catch (McpError e) {
            throw e;
        } catch (RuntimeException e) {
            // Not a failure to read this item: a failure of the plumbing that reads items — an
            // initialization the server owes, a resource it never opened. "This item could not be
            // parsed" would describe the evidence, and the evidence is not what failed; a server
            // that never opened the case's preview database once told an agent that a message with
            // content had none. Nor is the raw-bytes reader an alternative here: it reaches the item
            // through the same plumbing and fails the same way.
            LOGGER.error("Text extraction failed for item {} for a server-side reason", item.getId(), e);
            result.failure = "the server could not read this item: " + e.getMessage()
                    + "; this is a fault in the server, not a property of the item";
            result.remedy = "Report this with the server log attached. iped_item_metadata answers from the index "
                    + "and is unaffected by it.";
        } catch (Exception e) {
            LOGGER.debug("Text extraction failed for item {}", item.getId(), e);
            result.failure = "this item could not be parsed for text: " + e.getMessage();
            result.remedy = "Try iped_item_metadata, or iped_item_content for the raw bytes.";
        }
        // What ran, taken from the metadata the parse filled in. Reported rather than inferred: the
        // fallback parser succeeds on anything, so "there is text" says nothing on its own about
        // whether the item was understood.
        result.parsedBy = simpleName(metadata.get("X-TIKA:Parsed-By"));
        result.parsedAs = metadata.get(StandardParser.INDEXER_CONTENT_TYPE);
        if (limit > 0 && result.text.length() >= limit) {
            result.truncated = true;
        }
        return result;
    }

    /** The parser's class name without its package, which is what a reader of the result needs. */
    private static String simpleName(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? className : className.substring(lastDot + 1);
    }

    /** Thumbnail bytes, base64-encoded, or a declared absence. */
    public Map<String, Object> thumbnail(OpenCase openCase, int itemId) {
        IItem item = requireItem(openCase, itemId);
        egressPolicy.enforce(ContentClass.thumbnail, item);

        Map<String, Object> result = base(openCase, itemId);
        byte[] thumb = item.getThumb();
        if (thumb == null || thumb.length == 0) {
            return unavailable(result,
                    "this item has no thumbnail; thumbnails exist for images, videos and documents that were "
                            + "rendered during processing",
                    "Check content_type through iped_item_metadata: for a non-visual item there is nothing to "
                            + "render.");
        }
        if (thumb.length > config.getMaxThumbnailBytes()) {
            return unavailable(result,
                    "the thumbnail is " + thumb.length + " bytes, past the ceiling of "
                            + config.getMaxThumbnailBytes(),
                    "Raise maxThumbnailBytes in conf/McpServerConfig.txt if this is expected.");
        }
        result.put("available", true);
        result.put("encoding", "base64");
        result.put("media_type", "image/jpeg");
        result.put("bytes", thumb.length);
        result.put("data", Base64.getEncoder().encodeToString(thumb));
        return result;
    }

    /** Raw content, under the configured ceiling, truncation signalled (FR-021). */
    public Map<String, Object> content(OpenCase openCase, int itemId, Integer maxBytes) {
        IItem item = requireItem(openCase, itemId);
        egressPolicy.enforce(ContentClass.binary, item);

        int limit = clamp(maxBytes, config.getMaxContentBytes(), config.getMaxContentBytes());
        Map<String, Object> result = base(openCase, itemId);
        Long length = item.getLength();
        if (item.isDir()) {
            return unavailable(result, "this item is a directory and has no content of its own",
                    "Use iped_item_tree to list what it contains.");
        }
        byte[] buffer = new byte[limit];
        int read = 0;
        try (InputStream is = item.getBufferedInputStream()) {
            int n;
            while (read < limit && (n = is.read(buffer, read, limit - read)) > 0) {
                read += n;
            }
        } catch (IOException e) {
            return unavailable(result,
                    "the content of this item could not be read: " + e.getMessage()
                            + "; in a portable case this usually means the evidence file is not present",
                    "Metadata stays available through iped_item_metadata.");
        }
        if (read == 0) {
            return unavailable(result, "this item has zero bytes of content",
                    "That is a fact about the item, not a failure to read it. Its length in the index is "
                            + (length == null ? "not recorded" : length + " bytes") + ".");
        }
        result.put("available", true);
        result.put("encoding", "base64");
        result.put("returned_bytes", read);
        result.put("real_size", length);
        boolean truncated = length != null && length > read;
        result.put("truncated", truncated);
        if (truncated) {
            result.put("truncation_note", "Only the first " + read + " of " + length
                    + " bytes were returned. Raise max_bytes, within the server ceiling of "
                    + config.getMaxContentBytes() + ", to see more.");
        }
        byte[] exact = new byte[read];
        System.arraycopy(buffer, 0, exact, 0, read);
        result.put("data", Base64.getEncoder().encodeToString(exact));
        return result;
    }

    /** Extracted metadata: EXIF, GPS, headers, codec, and everything else the parsers produced. */
    public Map<String, Object> metadata(OpenCase openCase, int itemId) {
        IItem item = requireItem(openCase, itemId);
        egressPolicy.enforce(ContentClass.metadata, item);

        Map<String, Object> result = base(openCase, itemId);
        Map<String, Object> values = new LinkedHashMap<>();
        Metadata metadata = item.getMetadata();
        if (metadata != null) {
            for (String name : metadata.names()) {
                String[] all = metadata.getValues(name);
                values.put(name, all.length == 1 ? all[0] : new ArrayList<>(java.util.Arrays.asList(all)));
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : item.getExtraAttributeMap().entrySet()) {
            extra.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().toString());
        }
        result.put("available", !values.isEmpty() || !extra.isEmpty());
        result.put("metadata", values);
        result.put("extra_attributes", extra);
        if (values.isEmpty() && extra.isEmpty()) {
            result.put("reason", "no parser produced metadata for this item");
        }
        return result;
    }

    /**
     * Parent container and contained items, so the agent does not have to build the query by hand
     * (FR-023).
     */
    public Map<String, Object> tree(OpenCase openCase, int itemId, int maxChildren) {
        IItem item = requireItem(openCase, itemId);
        IPEDSource source = openCase.getSource();

        Map<String, Object> result = base(openCase, itemId);
        result.put("name", item.getName());
        result.put("path", item.getPath());

        Integer parentId = item.getParentId();
        if (parentId != null && parentId >= 0 && parentId <= source.getLastId()
                && source.getLuceneId(parentId) >= 0) {
            IItem parent = source.getItemByID(parentId);
            Map<String, Object> parentView = new LinkedHashMap<>();
            parentView.put("item_id", parentId);
            parentView.put("name", parent == null ? null : parent.getName());
            parentView.put("path", parent == null ? null : parent.getPath());
            result.put("parent", parentView);
        } else {
            result.put("parent", null);
            result.put("parent_reason", "this is a root item; it is not contained by anything in this case");
        }

        List<Map<String, Object>> children = new ArrayList<>();
        try {
            IndexSearcher searcher = source.getSearcher();
            Query query = IntPoint.newExactQuery(BasicProps.PARENTID, itemId);
            Sort sort = new Sort(SortField.FIELD_DOC);
            TopDocs top = searcher.search(query, Math.max(1, maxChildren), sort);
            for (ScoreDoc scoreDoc : top.scoreDocs) {
                org.apache.lucene.document.Document doc = searcher.doc(scoreDoc.doc, ItemView.storedFields());
                Map<String, Object> child = new LinkedHashMap<>();
                child.put("item_id", source.getId(scoreDoc.doc));
                child.put("name", doc.get(BasicProps.NAME));
                child.put("content_type", doc.get(BasicProps.CONTENTTYPE));
                child.put("size", doc.get(BasicProps.LENGTH));
                children.add(child);
            }
            result.put("children_total", top.totalHits.value);
            result.put("children_truncated", top.totalHits.value > children.size());
        } catch (IOException e) {
            throw new McpError(McpError.INTERNAL_ERROR, "The item hierarchy could not be read: " + e.getMessage(),
                    "Report this with the server log attached.", e);
        }
        result.put("children", children);
        return result;
    }

    private static Map<String, Object> base(OpenCase openCase, int itemId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("item_id", itemId);
        return result;
    }

    private static Map<String, Object> unavailable(Map<String, Object> result, String reason, String remedy) {
        result.put("available", false);
        result.put("reason", reason);
        result.put("remedy", remedy);
        return result;
    }

    private static int clamp(Integer requested, int fallback, int ceiling) {
        if (requested == null || requested <= 0) {
            return fallback;
        }
        return Math.min(requested, ceiling);
    }
}
