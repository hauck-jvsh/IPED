package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.audit.AuditRecord;
import iped.mcp.audit.AuditSync;
import iped.mcp.audit.AuditTrail;

/**
 * Detection of an orphan trail at case open (FR-074), and the degradation path for non-writable
 * media (FR-073).
 *
 * <p>
 * Of the whole durability design, orphan detection is the piece with the most value and it is worth
 * saying why. Every other mechanism reduces the <i>probability</i> of losing the trail. This one
 * guarantees that a loss is <i>noticed</i> — at the start of the next examination, rather than at
 * the moment someone needs the trail and finds it gone.
 */
public class AuditOrphanTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aTrailOnTheStationWithNoCounterpartInTheCaseIsReported() throws Exception {
        File stagingArea = temp.newFolder("staging");
        File caseDir = temp.newFolder("case");
        String binding = "/cases/one|abcdef";

        // A previous session's trail, on the station, never co-located.
        try (AuditTrail previous = new AuditTrail(new File(stagingArea, "session-old.jsonl"), "session-old",
                "tester")) {
            previous.recordEnd(previous.recordStart("iped_search", param("query", "x"), "case-1", binding, null),
                    AuditRecord.Outcome.OK, 1, null);
        }

        List<String> orphans = AuditSync.findOrphanTrails(stagingArea, binding, caseDir, "mcp-audit");

        assertEquals("the orphan must be reported", 1, orphans.size());
        assertEquals("session-old.jsonl", orphans.get(0));
    }

    @Test
    public void aTrailAlreadyCoLocatedIsNotReportedAsOrphan() throws Exception {
        File stagingArea = temp.newFolder("staging2");
        File caseDir = temp.newFolder("case2");
        String binding = "/cases/two|123456";

        try (AuditTrail trail = new AuditTrail(new File(stagingArea, "session-synced.jsonl"), "session-synced",
                "tester"); AuditSync sync = new AuditSync(trail, "mcp-audit", 3600)) {
            sync.bindCase("case-2", binding, caseDir);
            trail.recordEnd(trail.recordStart("iped_search", param("query", "x"), "case-2", binding, null),
                    AuditRecord.Outcome.OK, 1, null);
            sync.syncQuietly();
        }

        assertTrue("a co-located trail is not an orphan",
                AuditSync.findOrphanTrails(stagingArea, binding, caseDir, "mcp-audit").isEmpty());
    }

    @Test
    public void deletingTheCaseAuditFolderTurnsASyncedTrailIntoAReportedOrphan() throws Exception {
        // Scenario 8, step 8: the loss has to become visible, not silent.
        File stagingArea = temp.newFolder("staging3");
        File caseDir = temp.newFolder("case3");
        String binding = "/cases/three|deadbeef";

        try (AuditTrail trail = new AuditTrail(new File(stagingArea, "session-lost.jsonl"), "session-lost",
                "tester"); AuditSync sync = new AuditSync(trail, "mcp-audit", 3600)) {
            sync.bindCase("case-3", binding, caseDir);
            trail.recordEnd(trail.recordStart("iped_search", param("query", "x"), "case-3", binding, null),
                    AuditRecord.Outcome.OK, 1, null);
            sync.syncQuietly();
        }
        assertTrue(AuditSync.findOrphanTrails(stagingArea, binding, caseDir, "mcp-audit").isEmpty());

        Files.delete(new File(caseDir, "mcp-audit/session-lost.jsonl").toPath());

        List<String> orphans = AuditSync.findOrphanTrails(stagingArea, binding, caseDir, "mcp-audit");
        assertEquals("removing the co-located copy must make the trail an orphan again", 1, orphans.size());
    }

    @Test
    public void aTrailBoundToAnotherCaseIsNotReported() throws Exception {
        File stagingArea = temp.newFolder("staging4");
        File caseDir = temp.newFolder("case4");

        try (AuditTrail other = new AuditTrail(new File(stagingArea, "session-other.jsonl"), "session-other",
                "tester")) {
            other.recordEnd(other.recordStart("iped_search", param("query", "x"), "case-9", "/cases/nine|999", null),
                    AuditRecord.Outcome.OK, 1, null);
        }

        assertTrue("only trails bound to this case may be reported",
                AuditSync.findOrphanTrails(stagingArea, "/cases/four|444", caseDir, "mcp-audit").isEmpty());
    }

    @Test
    public void nonWritableCaseIsDetectedAtBindWithItsReason() throws Exception {
        File stagingArea = temp.newFolder("staging5");
        File caseDir = temp.newFile("case-on-read-only-media");

        try (AuditTrail trail = new AuditTrail(new File(stagingArea, "session-ro.jsonl"), "session-ro", "tester");
                AuditSync sync = new AuditSync(trail, "mcp-audit", 3600)) {
            AuditSync.Target target = sync.bindCase("case-5", "/cases/five|555", caseDir);

            assertEquals(AuditSync.SyncState.STAGED, target.state);
            assertNotNull("the examiner must be told why co-location failed", target.degradationReason);
        }
    }

    private static Map<String, Object> param(String key, Object value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(key, value);
        return params;
    }
}
