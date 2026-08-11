package iped.mcp.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;

import iped.engine.data.IPEDSource;
import iped.mcp.item.ItemView;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpError;
import iped.mcp.session.OpenCase;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;

/**
 * Writes the complete result set to a file, in xlsx, CSV or JSON (FR-066).
 *
 * <p>
 * The whole point is that the set does not travel through the conversation. Five thousand items go
 * to disk; the conversation gets a count, a sample and a path (FR-067). Nothing here paginates and
 * nothing truncates — a report missing rows is worse than no report.
 *
 * <p>
 * Writing is incremental in all three formats. The xlsx path uses POI's streaming workbook so that
 * a large bookmark is never held in memory as a document tree.
 */
public class ArtifactWriter {

    /** Columns written for every item, in this order. */
    private static final String[] COLUMNS = { "item_id", "name", "path", "ext", "category", "content_type", "size",
            "created", "modified", "accessed", "hash", "deleted", "carved", "subitem", "parent_id", "bookmarks" };

    /** Extra columns appended when the set is being grouped as a conversation (FR-069). */
    private static final String[] MESSAGE_COLUMNS = { "conversation", "sender", "recipient", "message_date" };

    private final int sampleSize;

    public ArtifactWriter(int sampleSize) {
        this.sampleSize = sampleSize;
    }

    /**
     * @param format
     *            {@code xlsx}, {@code csv} or {@code json}
     * @param groupByConversation
     *            group messages by conversation, chronologically, with sender and recipient
     *            identified (FR-069)
     * @return count, sample and path — never the rows themselves
     */
    public Map<String, Object> write(OpenCase openCase, List<Integer> itemIds, String format, Path destination,
            boolean groupByConversation) {
        // Validate the format before anything reads the case: an unsupported format is a caller
        // mistake, and discovering it after the set has been materialized wastes the work and
        // produces a worse message.
        String normalized = format == null ? "" : format.toLowerCase();
        if (!Arrays.asList("xlsx", "csv", "json").contains(normalized)) {
            throw new McpError(McpError.INVALID_ARGUMENT, "There is no export format named '" + format + "'.",
                    "Use one of: xlsx, csv, json.").with("requested", format).with("valid",
                            Arrays.asList("xlsx", "csv", "json"));
        }
        if (itemIds == null || itemIds.isEmpty()) {
            throw new McpError(McpError.EMPTY_RESULT_SET,
                    "The set is empty, so no artifact was created.",
                    "An empty file would look like a finished report of nothing found, which is not the same "
                            + "as no report. Widen the query or pick a different bookmark, then export again.");
        }

        List<Map<String, Object>> rows = readRows(openCase, itemIds, groupByConversation);
        if (groupByConversation) {
            sortByConversationThenTime(rows);
        }

        try {
            // The destination has already been approved against the declared write roots by the
            // time this runs, which is what makes creating the folders safe here: a refused
            // destination never reaches this method, so it never leaves a folder behind (FR-002).
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            switch (normalized) {
                case "csv":
                    writeCsv(rows, destination, groupByConversation);
                    break;
                case "json":
                    writeJson(openCase, rows, destination);
                    break;
                default:
                    writeXlsx(rows, destination, groupByConversation);
                    break;
            }
        } catch (IOException e) {
            throw new McpError(McpError.EXPORT_FAILED,
                    "The artifact could not be written to " + destination + ": " + e.getMessage(),
                    "Check that the folder exists and is writable, and that there is free space, then export "
                            + "again.",
                    e).with("destination", destination.toString());
        }
        long bytes = verifyArtifact(destination);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("format", normalized);
        result.put("item_count", rows.size());
        result.put("destination", destination.toString());
        result.put("bytes", bytes);
        result.put("sample", rows.subList(0, Math.min(sampleSize, rows.size())));
        result.put("note", "The full set of " + rows.size() + " records is in the file. Only the first "
                + Math.min(sampleSize, rows.size()) + " are shown here, on purpose: the artifact is the "
                + "deliverable, not this conversation.");
        return result;
    }

    /**
     * Confirms the artifact is actually there afterwards, and returns its size (FR-034).
     *
     * <p>
     * Containment is not integrity. A destination can sit legitimately inside a permitted root and
     * still keep nothing: on Windows, {@code <root>\NUL} names the null device, the write succeeds,
     * and the file does not exist. Without this check the caller would be told the export succeeded,
     * with an item count and a path, over a file that is not there — and the examiner would find out
     * when they went looking for the deliverable.
     *
     * <p>
     * <b>Deliberately not a list of forbidden names.</b> The set of names that behave this way varies
     * by system and by version — measured on this platform, {@code CON} created a real file while
     * {@code NUL} did not — so any such list is incomplete by construction and would hand back
     * exactly the failure it was meant to prevent. Checking the result is insensitive to which
     * mechanism made the file vanish.
     */
    static long verifyArtifact(Path destination) {
        try {
            if (Files.isRegularFile(destination)) {
                long size = Files.size(destination);
                if (size > 0) {
                    return size;
                }
            }
        } catch (IOException e) {
            throw new McpError(McpError.EXPORT_FAILED,
                    "The artifact at " + destination + " could not be confirmed after writing: " + e.getMessage(),
                    "Nothing was delivered. Export again to a plain file under one of the permitted folders.", e)
                            .with("destination", destination.toString());
        }
        throw new McpError(McpError.EXPORT_FAILED,
                "The write to " + destination + " was accepted but nothing is there afterwards.",
                "Some destinations take a write and keep nothing — a reserved device name such as NUL is the "
                        + "usual cause, and it can sit inside a permitted folder. Nothing was delivered. Export "
                        + "again to a plain file name.").with("destination", destination.toString());
    }

    private List<Map<String, Object>> readRows(OpenCase openCase, List<Integer> itemIds, boolean groupByConversation) {
        IPEDSource source = openCase.getSource();
        List<Map<String, Object>> rows = new ArrayList<>(itemIds.size());
        for (Integer itemId : itemIds) {
            int luceneId = source.getLuceneId(itemId);
            if (luceneId < 0) {
                continue;
            }
            try {
                Document doc = source.getSearcher().doc(luceneId);
                Map<String, Object> view = ItemView.of(source, luceneId, doc, null);
                view.remove("unavailable");
                view.put("case_id", openCase.getCaseId());
                if (groupByConversation) {
                    view.put("conversation", first(doc, ExtraProperties.CONVERSATION_ID,
                            ExtraProperties.CONVERSATION_NAME));
                    view.put("sender", first(doc, ExtraProperties.COMMUNICATION_FROM));
                    view.put("recipient", first(doc, ExtraProperties.COMMUNICATION_TO));
                    view.put("message_date", first(doc, ExtraProperties.MESSAGE_DATE.getName(), BasicProps.MODIFIED,
                            BasicProps.CREATED));
                }
                rows.add(view);
            } catch (IOException e) {
                throw new McpError(McpError.EXPORT_FAILED,
                        "Item " + itemId + " could not be read while building the artifact: " + e.getMessage(),
                        "Nothing was written. Reopen the case and try again; if it repeats, the index may be "
                                + "damaged.",
                        e);
            }
        }
        return rows;
    }

    private static String first(Document doc, String... fields) {
        for (String field : fields) {
            String value = doc.get(field);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Conversation first, then time within it — the order an examiner reads a chat in. */
    private static void sortByConversationThenTime(List<Map<String, Object>> rows) {
        rows.sort((a, b) -> {
            String ca = String.valueOf(a.get("conversation"));
            String cb = String.valueOf(b.get("conversation"));
            int cmp = ca.compareTo(cb);
            if (cmp != 0) {
                return cmp;
            }
            String da = String.valueOf(a.get("message_date"));
            String db = String.valueOf(b.get("message_date"));
            return da.compareTo(db);
        });
    }

    private void writeCsv(List<Map<String, Object>> rows, Path destination, boolean withMessages) throws IOException {
        List<String> columns = columns(withMessages);
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(destination), StandardCharsets.UTF_8))) {
            // BOM so that spreadsheet software opens UTF-8 correctly on Windows, which is where
            // most of these files get opened.
            writer.write('﻿');
            writer.write(String.join(",", columns));
            writer.write("\r\n");
            for (Map<String, Object> row : rows) {
                List<String> cells = new ArrayList<>(columns.size());
                for (String column : columns) {
                    cells.add(csvEscape(row.get(column)));
                }
                writer.write(String.join(",", cells));
                writer.write("\r\n");
            }
        }
    }

    private static String csvEscape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof List ? String.join("; ", ((List<?>) value).stream().map(String::valueOf)
                .toArray(String[]::new)) : String.valueOf(value);
        if (text.indexOf('"') >= 0 || text.indexOf(',') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private void writeJson(OpenCase openCase, List<Map<String, Object>> rows, Path destination) throws IOException {
        try (OutputStream out = Files.newOutputStream(destination);
                JsonGenerator generator = JsonRpcCodec.mapper().getFactory().createGenerator(out,
                        JsonEncoding.UTF8)) {
            generator.useDefaultPrettyPrinter();
            generator.writeStartObject();
            generator.writeStringField("case_id", openCase.getCaseId());
            generator.writeStringField("case_path", openCase.getCasePath().getAbsolutePath());
            generator.writeNumberField("item_count", rows.size());
            generator.writeFieldName("items");
            generator.writeStartArray();
            for (Map<String, Object> row : rows) {
                generator.writeObject(row);
            }
            generator.writeEndArray();
            generator.writeEndObject();
        }
    }

    private void writeXlsx(List<Map<String, Object>> rows, Path destination, boolean withMessages) throws IOException {
        List<String> columns = columns(withMessages);
        // Streaming workbook: rows are flushed to disk as they are written, so a five-thousand-item
        // bookmark never sits in memory as a document tree.
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200);
                OutputStream out = Files.newOutputStream(destination)) {
            SXSSFSheet sheet = workbook.createSheet("items");
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i));
            }
            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                Row sheetRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < columns.size(); i++) {
                    Object value = row.get(columns.get(i));
                    if (value == null) {
                        continue;
                    }
                    if (value instanceof Number) {
                        sheetRow.createCell(i).setCellValue(((Number) value).doubleValue());
                    } else if (value instanceof Boolean) {
                        sheetRow.createCell(i).setCellValue((Boolean) value);
                    } else if (value instanceof List) {
                        sheetRow.createCell(i).setCellValue(String.join("; ", ((List<?>) value).stream()
                                .map(String::valueOf).toArray(String[]::new)));
                    } else {
                        sheetRow.createCell(i).setCellValue(String.valueOf(value));
                    }
                }
            }
            workbook.write(out);
            workbook.dispose();
        }
    }

    private static List<String> columns(boolean withMessages) {
        List<String> columns = new ArrayList<>(Arrays.asList(COLUMNS));
        if (withMessages) {
            columns.addAll(Arrays.asList(MESSAGE_COLUMNS));
        }
        return columns;
    }
}
