package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
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
 * Co-location of the audit trail with the case, without any manual action (FR-072).
 *
 * <p>
 * This is a different failure from the one {@code AuditDurabilityTest} covers, and passing that one
 * does not imply passing this one. Durability against a crash is {@code fsync} per operation.
 * Survival of handoff and re-imaging is co-location with the case, because under an isolated
 * workstation the case folder is the only storage that gets archived.
 */
public class AuditSyncTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void trailAppearsInsideTheCaseWithoutAnyManualAction() throws Exception {
        File stagingArea = temp.newFolder("staging");
        File caseDir = temp.newFolder("case");

        try (AuditTrail trail = new AuditTrail(new File(stagingArea, "session-a.jsonl"), "session-a", "tester");
                AuditSync sync = new AuditSync(trail, "mcp-audit", 3600)) {
            AuditSync.Target target = sync.bindCase("case-1", "binding-1", caseDir);
            assertEquals("a writable case must be co-located", AuditSync.SyncState.SYNCED, target.state);

            trail.recordEnd(trail.recordStart("iped_search", param("query", "x"), "case-1", "binding-1", null),
                    AuditRecord.Outcome.OK, 3, null);
            sync.syncQuietly();

            File coLocated = new File(caseDir, "mcp-audit/session-a.jsonl");
            assertTrue("the trail must be inside the case folder, unasked", coLocated.isFile());

            List<AuditRecord> records = AuditTrail.read(coLocated);
            assertEquals(2, records.size());
            assertNull("the co-located copy must verify as intact", AuditTrail.verify(records));
        }
    }

    @Test
    public void closingTheSessionLeavesTheCompleteTrailInsideTheCase() throws Exception {
        File stagingArea = temp.newFolder("staging2");
        File caseDir = temp.newFolder("case2");

        AuditTrail trail = new AuditTrail(new File(stagingArea, "session-b.jsonl"), "session-b", "tester");
        AuditSync sync = new AuditSync(trail, "mcp-audit", 3600);
        sync.bindCase("case-2", "binding-2", caseDir);
        for (int i = 0; i < 4; i++) {
            trail.recordEnd(trail.recordStart("iped_search", param("query", "q" + i), "case-2", "binding-2", null),
                    AuditRecord.Outcome.OK, i, null);
        }
        // Close the trail before the final synchronization, so what lands in the case is the whole
        // file rather than one missing its last lines.
        trail.close();
        sync.close();

        List<AuditRecord> coLocated = AuditTrail.read(new File(caseDir, "mcp-audit/session-b.jsonl"));
        assertEquals("every record must be co-located, not just those written before the last sync", 8,
                coLocated.size());
        assertNull(AuditTrail.verify(coLocated));
    }

    @Test
    public void nonWritableCaseKeepsTheWorkstationCopyAuthoritativeAndSaysSo() throws Exception {
        File stagingArea = temp.newFolder("staging3");
        // A case whose folder cannot hold a subfolder stands in for read-only media, portably.
        File caseDir = temp.newFile("read-only-case");

        try (AuditTrail trail = new AuditTrail(new File(stagingArea, "session-c.jsonl"), "session-c", "tester");
                AuditSync sync = new AuditSync(trail, "mcp-audit", 3600)) {
            AuditSync.Target target = sync.bindCase("case-3", "binding-3", caseDir);

            assertEquals("a non-writable case must degrade, not fail", AuditSync.SyncState.STAGED, target.state);
            assertNotNull("the degradation must carry its reason", target.degradationReason);

            trail.recordEnd(trail.recordStart("iped_search", param("query", "x"), "case-3", "binding-3", null),
                    AuditRecord.Outcome.OK, 1, null);
            sync.syncQuietly();

            assertTrue("the workstation copy stays authoritative and complete",
                    AuditTrail.read(trail.getFile()).size() == 2);
        }
    }

    @Test
    public void syncIsAtomicSoAPartialFileNeverAppearsInTheCase() throws Exception {
        File stagingArea = temp.newFolder("staging4");
        File caseDir = temp.newFolder("case4");

        try (AuditTrail trail = new AuditTrail(new File(stagingArea, "session-d.jsonl"), "session-d", "tester");
                AuditSync sync = new AuditSync(trail, "mcp-audit", 3600)) {
            sync.bindCase("case-4", "binding-4", caseDir);
            for (int i = 0; i < 20; i++) {
                trail.recordEnd(trail.recordStart("iped_search", param("query", "q" + i), "case-4", "binding-4",
                        null), AuditRecord.Outcome.OK, i, null);
                sync.syncQuietly();
            }
            File auditDir = new File(caseDir, "mcp-audit");
            String[] leftovers = auditDir.list((dir, name) -> name.endsWith(".part"));
            assertNotNull(leftovers);
            assertEquals("no partial file may be left behind in the case", 0, leftovers.length);
            assertNull(AuditTrail.verify(AuditTrail.read(new File(auditDir, "session-d.jsonl"))));
        }
    }

    @Test
    public void everyRecordCarriesTheStrongCaseBindingForReassociation() throws Exception {
        File stagingArea = temp.newFolder("staging5");
        try (AuditTrail trail = new AuditTrail(new File(stagingArea, "session-e.jsonl"), "session-e", "tester")) {
            trail.recordEnd(trail.recordStart("iped_search", param("query", "x"), "case-5",
                    "/cases/five|abcdef0123456789", null), AuditRecord.Outcome.OK, 1, null);

            for (AuditRecord record : AuditTrail.read(trail.getFile())) {
                assertEquals("/cases/five|abcdef0123456789", record.getCaseBinding());
                assertFalse("the binding must carry both path and index identity",
                        record.getCaseBinding().indexOf('|') < 0);
            }
        }
    }

    private static Map<String, Object> param(String key, Object value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(key, value);
        return params;
    }
}
