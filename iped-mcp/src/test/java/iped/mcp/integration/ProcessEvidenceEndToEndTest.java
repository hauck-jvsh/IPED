package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.processing.JobRunner;
import iped.mcp.processing.JobStore;
import iped.mcp.processing.ProcessingJob;
import iped.mcp.processing.ProcessingJob.State;
import iped.mcp.processing.ProcessingRequest;
import iped.mcp.session.CaseValidator;

/**
 * From request to a queryable case, against real evidence (SC-001, SC-002, SC-008, SC-015).
 *
 * <p>
 * Skips without {@code -Diped.mcp.test.sourceEvidence} and {@code -Diped.mcp.test.caseRoot}. A
 * skipped test is not a passing test, and nothing else in this module proves the engine actually
 * runs — every other suite exercises the surface around it.
 */
public class ProcessEvidenceEndToEndTest {

    /** The engine is given room to finish; the reference image takes about two minutes. */
    private static final long COMPLETION_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    /** SC-002: the accept must return in under five seconds regardless of evidence size. */
    private static final long ACCEPT_BUDGET_MILLIS = 5000;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File evidence;
    private File caseRoot;
    private File destination;
    private JobRunner runner;

    @Before
    public void setUp() throws Exception {
        evidence = McpTestSupport.requireSourceEvidence();
        caseRoot = McpTestSupport.requireCaseRoot();
        destination = new File(caseRoot, "e2e-" + UUID.randomUUID().toString().substring(0, 8));

        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);
        config.setProcessingSourceAreas(Arrays.asList(evidence.getParentFile().getAbsolutePath()));
        config.setProcessingCaseRoots(Arrays.asList(caseRoot.getAbsolutePath()));

        runner = new JobRunner(config, iped.mcp.Diagnostics.resolveIpedRoot(),
                new JobStore(config.getAuditArea()));
    }

    @After
    public void tearDown() {
        if (destination != null && destination.exists()) {
            deleteRecursively(destination);
        }
    }

    @Test
    public void evidenceBecomesAQueryableCaseAndIsLeftUntouched() throws Exception {
        // SC-015 and Principle I: what the evidence was before, byte for byte.
        String before = sha256(evidence);

        long startedAt = System.currentTimeMillis();
        ProcessingJob job = runner.start(
                new ProcessingRequest(evidence.getAbsolutePath(), destination.getAbsolutePath(), "fastmode",
                        "RockPi4 reference", null),
                "test-operator");
        long acceptMillis = System.currentTimeMillis() - startedAt;

        // SC-002, timed rather than assumed: the accept returns before the engine has done anything,
        // so its cost must not scale with the evidence.
        assertTrue("the accept took " + acceptMillis + " ms, over the " + ACCEPT_BUDGET_MILLIS + " ms budget",
                acceptMillis < ACCEPT_BUDGET_MILLIS);
        assertNotNull("an accepted request must return a job id", job.getJobId());

        ProcessingJob finished = awaitCompletion(job.getJobId());
        assertEquals("the reference evidence should process cleanly", State.COMPLETED, finished.getState());
        assertNotNull(finished.getOutcome());

        // SC-008: the number in the outcome is the number a later open returns. They are compared
        // rather than assumed equal, because the engine's own progress totals are a different
        // quantity — it reported 319641 indexed against 267186 active on this image.
        long reported = finished.getOutcome().getItemCount();
        assertTrue("a completed case should report items", reported > 0);

        // SC-001 and FR-027: the case opens through the same validation any other case goes
        // through, with nobody touching the server machine in between.
        CaseValidator validator = new CaseValidator("4.");
        CaseValidator.ValidatedCase validated = validator.validate(destination);
        assertNotNull("the produced case must validate", validated.caseId());

        long counted = openAndCount(destination);
        assertEquals("the outcome's item_count must be what the case answers with", reported, counted);

        // SC-015 again, after the run: the evidence is read-only to every path this feature adds.
        assertEquals("the evidence must be byte-for-byte identical after processing", before, sha256(evidence));
    }

    private ProcessingJob awaitCompletion(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + COMPLETION_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ProcessingJob live = runner.getActive();
            if (live == null || live.getState().isTerminal()) {
                ProcessingJob stored = runner.getStore().load(jobId);
                if (stored != null && stored.getState().isTerminal()) {
                    return stored;
                }
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("the job did not finish within " + COMPLETION_TIMEOUT_MILLIS + " ms");
    }

    private static long openAndCount(File caseDir) throws Exception {
        try (iped.engine.data.IPEDSource source = new iped.engine.data.IPEDSource(caseDir, null, false)) {
            return source.getTotalItems();
        }
    }

    /**
     * Full digest, not size and timestamp.
     *
     * <p>
     * It costs about twenty seconds a pass on the reference image, and that is the price of the
     * claim: SC-015 says byte for byte, and a size comparison would pass over a modification that
     * kept the length — which is exactly the kind a write into a forensic image would be.
     */
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = new DigestInputStream(new BufferedInputStream(new FileInputStream(file)), digest)) {
            while (in.read(buffer) != -1) {
                // Reading is what feeds the digest.
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            // A leftover case folder is noise in a temporary area, not a test failure.
        }
    }
}
