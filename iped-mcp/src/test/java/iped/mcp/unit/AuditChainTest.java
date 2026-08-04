package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.audit.AuditRecord;
import iped.mcp.audit.AuditTrail;
import iped.mcp.protocol.McpError;

/**
 * Audit trail integrity: monotonic sequence without gaps, an intact hash chain, and detection of
 * tampering (Scenario 8, FR-034).
 *
 * <p>
 * Also covers the fail-closed rule: an unwritable audit area refuses the operation rather than
 * letting it run unrecorded (FR-035).
 */
public class AuditChainTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private AuditTrail newTrail() throws IOException {
        return new AuditTrail(new File(temp.newFolder("audit"), "session-test.jsonl"), "session-1", "tester");
    }

    @Test
    public void sequenceIsMonotonicWithoutGaps() throws Exception {
        try (AuditTrail trail = newTrail()) {
            for (int i = 0; i < 10; i++) {
                AuditRecord start = trail.recordStart("iped_search", params("query", "term" + i), "case-1",
                        "binding-1", null);
                trail.recordEnd(start, AuditRecord.Outcome.OK, i, null);
            }
            List<AuditRecord> records = AuditTrail.read(trail.getFile());
            assertEquals(20, records.size());
            for (int i = 0; i < records.size(); i++) {
                assertEquals("seq must be monotonic and gapless", i + 1, records.get(i).getSeq());
            }
        }
    }

    @Test
    public void chainVerifiesOnAnUntouchedTrail() throws Exception {
        try (AuditTrail trail = newTrail()) {
            AuditRecord start = trail.recordStart("iped_open_case", params("case_path", "/cases/one"), null, null,
                    null);
            trail.recordEnd(start, AuditRecord.Outcome.OK, 1, null);
            trail.recordEnd(trail.recordStart("iped_search", params("query", "x"), "case-1", "binding-1", null),
                    AuditRecord.Outcome.OK, 3, null);

            List<AuditRecord> records = AuditTrail.read(trail.getFile());
            assertNull("an untouched trail must verify", AuditTrail.verify(records));
            assertEquals(AuditTrail.GENESIS, records.get(0).getPrevHash());
            for (int i = 1; i < records.size(); i++) {
                assertEquals("each record must chain to the previous one", records.get(i - 1).getHash(),
                        records.get(i).getPrevHash());
            }
        }
    }

    @Test
    public void tamperingWithARecordIsDetected() throws Exception {
        File file;
        try (AuditTrail trail = newTrail()) {
            file = trail.getFile();
            for (int i = 0; i < 5; i++) {
                trail.recordEnd(trail.recordStart("iped_search", params("query", "term" + i), "case-1", "binding-1",
                        null), AuditRecord.Outcome.OK, i, null);
            }
        }
        assertNull(AuditTrail.verify(AuditTrail.read(file)));

        // Alter one recorded parameter, exactly as someone rewriting history would.
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        lines.set(4, lines.get(4).replace("term2", "term9"));
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);

        String problem = AuditTrail.verify(AuditTrail.read(file));
        assertNotNull("tampering must be detected", problem);
        assertTrue("the report must point at the altered record: " + problem, problem.contains("tampered"));
    }

    @Test
    public void removingARecordBreaksTheChain() throws Exception {
        File file;
        try (AuditTrail trail = newTrail()) {
            file = trail.getFile();
            for (int i = 0; i < 5; i++) {
                trail.recordEnd(trail.recordStart("iped_search", params("query", "t" + i), "case-1", "binding-1",
                        null), AuditRecord.Outcome.OK, i, null);
            }
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        lines.remove(3);
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);

        assertNotNull("removing a record must be detected", AuditTrail.verify(AuditTrail.read(file)));
    }

    @Test
    public void priorStateIsRecordedBeforeTheOperation() throws Exception {
        try (AuditTrail trail = newTrail()) {
            Map<String, Object> prior = new LinkedHashMap<>();
            prior.put("name", "Suspects");
            prior.put("item_count", 412);
            prior.put("item_ids", Collections.singletonList(7));

            AuditRecord start = trail.recordStart("iped_delete_bookmark", params("name", "Suspects"), "case-1",
                    "binding-1", prior);
            trail.recordEnd(start, AuditRecord.Outcome.OK, null, null);

            List<AuditRecord> records = AuditTrail.read(trail.getFile());
            AuditRecord written = records.get(0);
            assertEquals(AuditRecord.Outcome.STARTED, written.getOutcome());
            assertNotNull("a destructive operation must carry its prior state", written.getPriorState());
            assertEquals("Suspects", written.getPriorState().get("name"));
            assertEquals("the prior state must precede the outcome record", 1, written.getSeq());
            assertEquals(Long.valueOf(1), records.get(1).getRefSeq());
        }
    }

    @Test
    public void chainSurvivesReopeningTheSameFile() throws Exception {
        File dir = temp.newFolder("resume");
        File file = new File(dir, "session-resume.jsonl");
        try (AuditTrail first = new AuditTrail(file, "session-1", "tester")) {
            first.recordEnd(first.recordStart("iped_search", params("query", "a"), null, null, null),
                    AuditRecord.Outcome.OK, 1, null);
        }
        try (AuditTrail second = new AuditTrail(file, "session-1", "tester")) {
            second.recordEnd(second.recordStart("iped_search", params("query", "b"), null, null, null),
                    AuditRecord.Outcome.OK, 1, null);
        }
        List<AuditRecord> records = AuditTrail.read(file);
        assertEquals(4, records.size());
        assertNull("a resumed session must continue the chain, not fork it", AuditTrail.verify(records));
    }

    @Test
    public void identicalContentInDifferentPositionsHashesDifferently() throws Exception {
        try (AuditTrail trail = newTrail()) {
            AuditRecord first = trail.recordStart("iped_search", params("query", "same"), "case-1", "binding-1", null);
            AuditRecord second = trail.recordStart("iped_search", params("query", "same"), "case-1", "binding-1",
                    null);
            assertNotEquals("chaining must make position part of the digest", first.getHash(), second.getHash());
        }
    }

    @Test
    public void unwritableAuditAreaRefusesBeforeAnythingRuns() throws Exception {
        // A path whose parent is a regular file cannot be created, which is the portable way to
        // produce an unwritable audit area regardless of the platform's permission model.
        File notADirectory = temp.newFile("blocker");
        File impossible = new File(new File(notADirectory, "audit"), "session.jsonl");
        try {
            new AuditTrail(impossible, "session-1", "tester");
            org.junit.Assert.fail("an unwritable audit area must refuse, not proceed unrecorded");
        } catch (McpError e) {
            assertEquals(McpError.AUDIT_UNAVAILABLE, e.getCode());
            assertNotNull("every error must carry a remedy", e.getRemedy());
        }
    }

    private static Map<String, Object> params(String key, Object value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(key, value);
        return params;
    }
}
