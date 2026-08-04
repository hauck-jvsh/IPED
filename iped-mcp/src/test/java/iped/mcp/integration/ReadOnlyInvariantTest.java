package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.protocol.McpError;

/**
 * Read-only means read-only (Scenario 6, SC-003).
 *
 * <p>
 * A full session runs — open, overview, searches, item inspection, text, thumbnail, raw bytes — and
 * afterwards the case must be bit-for-bit what it was, with the audit subfolder excluded <b>by
 * name</b>.
 *
 * <p>
 * The exclusion is deliberately narrow. Everything outside that one subfolder is hashed, so a stray
 * write anywhere else still fails the test. If the exclusion were a general licence to write into
 * the case, the criterion would mean nothing.
 */
public class ReadOnlyInvariantTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void aFullReadOnlySessionLeavesTheCaseUnchanged() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String auditFolder = session.config().getAuditFolderNameInCase();

        String before = hashTree(caseDir.toPath(), auditFolder);

        String caseId = session.openCase(caseDir);
        session.call("iped_case_overview", "case_id", caseId);
        session.call("iped_list_fields", "case_id", caseId);
        JsonNode items = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 20,
                "include_snippets", true).path("items");
        session.call("iped_aggregate", "case_id", caseId, "dimension", "category");
        session.call("iped_list_bookmarks", "case_id", caseId);
        session.call("iped_get_selection", "case_id", caseId);
        for (JsonNode item : items) {
            int itemId = item.path("item_id").asInt();
            session.call("iped_item_metadata", "case_id", caseId, "item_id", itemId);
            session.call("iped_item_text", "case_id", caseId, "item_id", itemId);
            session.call("iped_item_thumbnail", "case_id", caseId, "item_id", itemId);
            session.call("iped_item_content", "case_id", caseId, "item_id", itemId, "max_bytes", 4096);
            session.call("iped_item_tree", "case_id", caseId, "item_id", itemId);
        }
        session.call("iped_close_case", "case_id", caseId);

        assertEquals("evidence, index and analysis state must be identical after a read-only session", before,
                hashTree(caseDir.toPath(), auditFolder));
    }

    @Test
    public void curationIsRefusedWithoutTouchingTheCase() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String auditFolder = session.config().getAuditFolderNameInCase();
        String caseId = session.openCase(caseDir);

        String before = hashTree(caseDir.toPath(), auditFolder);

        JsonNode error = session.expectError(McpError.WRITE_NOT_ENABLED, "iped_create_bookmark", "case_id", caseId,
                "name", "Should Never Exist");
        assertTrue("the refusal must say the case was untouched",
                error.path("message").asText().contains("not touched"));
        assertTrue("and that enabling is outside the conversation",
                error.path("remedy").asText().contains("READ_WRITE"));

        session.expectError(McpError.WRITE_NOT_ENABLED, "iped_set_selection", "case_id", caseId, "item_ids",
                new int[] { 1 }, "selected", true);
        session.expectError(McpError.WRITE_NOT_ENABLED, "iped_delete_bookmark", "case_id", caseId, "name",
                "anything");

        assertEquals("a refused write must leave nothing behind", before, hashTree(caseDir.toPath(), auditFolder));
    }

    @Test
    public void theOnlyPathWrittenInsideTheCaseIsTheAuditSubfolder() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String auditFolder = session.config().getAuditFolderNameInCase();

        List<String> before = listTree(caseDir.toPath());

        String caseId = session.openCase(caseDir);
        session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 5, "include_snippets", false);
        session.call("iped_close_case", "case_id", caseId);

        List<String> after = listTree(caseDir.toPath());
        after.removeAll(before);
        for (String added : after) {
            assertTrue("nothing may be written into the case outside the audit subfolder: " + added,
                    added.startsWith(auditFolder + "/"));
        }
    }

    /** SHA-256 over every file in the tree, excluding one subfolder by name. */
    private static String hashTree(Path root, String excludedFolder) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (Path file : sortedFiles(root, excludedFolder)) {
            sha.update(root.relativize(file).toString().replace('\\', '/').getBytes("UTF-8"));
            sha.update(Files.readAllBytes(file));
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static List<Path> sortedFiles(Path root, String excludedFolder) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !root.relativize(path).toString().replace('\\', '/')
                            .startsWith(excludedFolder + "/"))
                    .forEach(files::add);
        }
        files.sort((a, b) -> root.relativize(a).toString().replace('\\', '/')
                .compareTo(root.relativize(b).toString().replace('\\', '/')));
        return files;
    }

    private static List<String> listTree(Path root) throws IOException {
        List<String> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .forEach(path -> paths.add(root.relativize(path).toString().replace('\\', '/')));
        }
        return paths;
    }
}
