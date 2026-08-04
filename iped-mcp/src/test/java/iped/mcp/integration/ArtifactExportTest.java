package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig.AccessMode;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpError;

/**
 * Artifact export in all three formats (Scenario 9, SC-012).
 *
 * <p>
 * Two things are being proved and they pull in opposite directions: the file must hold the
 * <b>complete</b> set with nothing truncated, and the conversation must receive only a count, a
 * sample and a path. An implementation that satisfies one by giving up the other is the failure
 * this suite exists to catch.
 */
public class ArtifactExportTest {

    private static final String BOOKMARK = "MCP Export Test";

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp, AccessMode.READ_WRITE);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void everyFormatCarriesTheCompleteSet() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);

        List<Integer> ids = allIds(caseId);
        org.junit.Assume.assumeTrue("the reference case needs at least 50 items for this suite", ids.size() >= 50);
        int[] batch = ids.stream().mapToInt(Integer::intValue).toArray();

        File out = temp.newFolder("artifacts");

        JsonNode csv = exportTo(caseId, batch, "csv", new File(out, "report.csv"));
        JsonNode json = exportTo(caseId, batch, "json", new File(out, "report.json"));
        JsonNode xlsx = exportTo(caseId, batch, "xlsx", new File(out, "report.xlsx"));

        assertEquals(batch.length, csv.path("item_count").asInt());
        assertEquals(batch.length, json.path("item_count").asInt());
        assertEquals(batch.length, xlsx.path("item_count").asInt());

        assertEquals("every record must be a CSV row, plus the header", batch.length + 1,
                countCsvRows(new File(out, "report.csv")));
        assertEquals("every record must be in the JSON array", batch.length,
                JsonRpcCodec.mapper().readTree(new File(out, "report.json")).path("items").size());
        assertEquals("every record must be an xlsx row, plus the header", batch.length + 1,
                countXlsxRows(new File(out, "report.xlsx")));
    }

    @Test
    public void theConversationGetsOnlyCountSampleAndPath() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        List<Integer> ids = allIds(caseId);
        int[] batch = ids.stream().mapToInt(Integer::intValue).toArray();

        JsonNode result = exportTo(caseId, batch, "csv", new File(temp.newFolder("out2"), "report.csv"));

        assertTrue(result.has("item_count"));
        assertTrue(result.has("destination"));
        assertTrue(result.has("sample"));
        // FR-067: the whole point is that the rows do not travel through the conversation.
        assertTrue("the sample must stay small regardless of the set size: " + result.path("sample").size(),
                result.path("sample").size() <= 5);
        assertFalse("the full row set must not be returned", result.has("items"));
    }

    @Test
    public void exportingAnEmptySetInformsAndCreatesNoFile() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        File destination = new File(temp.newFolder("out3"), "empty.csv");

        JsonNode error = session.expectError(McpError.EMPTY_RESULT_SET, "iped_export_artifact", "case_id", caseId,
                "format", "csv", "destination", destination.getAbsolutePath(), "query",
                "\"zqxjkvwmpblrfhtn nothing matches\"");

        assertTrue("the refusal must explain why an empty file would be worse",
                error.path("remedy").asText().contains("nothing found"));
        assertFalse("no file may be created", destination.exists());
    }

    @Test
    public void aDestinationInsideTheCaseIsRefused() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        List<Integer> ids = allIds(caseId);

        File inside = new File(caseDir, "report.csv");
        JsonNode error = session.expectError(McpError.DESTINATION_REFUSED, "iped_export_artifact", "case_id",
                caseId, "format", "csv", "destination", inside.getAbsolutePath(), "item_ids",
                new int[] { ids.get(0) });

        assertTrue("the remedy must explain the reason, not just the rule",
                error.path("remedy").asText().contains("indistinguishable"));
        assertFalse("nothing may be written into the case", inside.exists());
    }

    @Test
    public void exportingFromABookmarkCarriesExactlyItsMembers() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);

        List<Integer> ids = allIds(caseId).subList(0, Math.min(20, allIds(caseId).size()));
        int[] batch = ids.stream().mapToInt(Integer::intValue).toArray();

        session.raw("iped_delete_bookmark", "case_id", caseId, "name", BOOKMARK);
        session.call("iped_create_bookmark", "case_id", caseId, "name", BOOKMARK);
        session.call("iped_add_to_bookmark", "case_id", caseId, "name", BOOKMARK, "item_ids", batch);

        File destination = new File(temp.newFolder("out4"), "bookmark.csv");
        JsonNode result = session.call("iped_export_artifact", "case_id", caseId, "format", "csv", "destination",
                destination.getAbsolutePath(), "bookmark", BOOKMARK);

        assertEquals(batch.length, result.path("item_count").asInt());
        assertEquals(batch.length + 1, countCsvRows(destination));

        session.raw("iped_delete_bookmark", "case_id", caseId, "name", BOOKMARK);
    }

    private JsonNode exportTo(String caseId, int[] ids, String format, File destination) {
        return session.call("iped_export_artifact", "case_id", caseId, "format", format, "destination",
                destination.getAbsolutePath(), "item_ids", ids);
    }

    private List<Integer> allIds(String caseId) {
        Set<Integer> ids = new HashSet<>();
        List<Integer> ordered = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 200,
                    "cursor", cursor, "include_snippets", false);
            for (JsonNode item : page.path("items")) {
                int id = item.path("item_id").asInt();
                if (ids.add(id)) {
                    ordered.add(id);
                }
            }
            cursor = page.has("next_cursor") ? page.path("next_cursor").asText() : null;
        } while (cursor != null && ordered.size() < 5000);
        return ordered;
    }

    private static int countCsvRows(File file) throws Exception {
        return (int) Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.trim().isEmpty()).count();
    }

    private static int countXlsxRows(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            int rows = 0;
            for (Row row : sheet) {
                if (row != null) {
                    rows++;
                }
            }
            return rows;
        }
    }
}
