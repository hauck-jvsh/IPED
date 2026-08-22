package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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

/**
 * Cancelling ends the whole process tree, not just the child (SC-014, and Principle I).
 *
 * <p>
 * The last assertion is the one that matters and the one a request/response test cannot make.
 * {@code Bootstrap} assembles a classpath and spawns a <i>second</i> JVM; the grandchild is what
 * reads evidence. A cancel that destroys only the child passes every check about state and timing
 * while leaving the engine reading the evidence and writing to the destination — and
 * {@code Bootstrap}'s own shutdown hook has its {@code destroy()} commented out, so nothing else
 * will stop it either.
 */
public class CancelJobTest {

    private static final long CANCEL_BUDGET_MILLIS = 60_000;
    private static final long ENGINE_START_TIMEOUT_MILLIS = 120_000;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File destination;
    private JobRunner runner;

    @Before
    public void setUp() throws Exception {
        File evidence = McpTestSupport.requireSourceEvidence();
        File caseRoot = McpTestSupport.requireCaseRoot();
        destination = new File(caseRoot, "cancel-" + UUID.randomUUID().toString().substring(0, 8));

        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);
        config.setProcessingSourceAreas(Arrays.asList(evidence.getParentFile().getAbsolutePath()));
        config.setProcessingCaseRoots(Arrays.asList(caseRoot.getAbsolutePath()));
        runner = new JobRunner(config, iped.mcp.Diagnostics.resolveIpedRoot(),
                new JobStore(config.getAuditArea()));

        this.evidence = evidence;
    }

    private File evidence;

    @After
    public void tearDown() {
        if (destination != null && destination.exists()) {
            deleteRecursively(destination);
        }
    }

    @Test
    public void cancellingEndsTheWholeTreeAndAnySessionMayDoIt() throws Exception {
        ProcessingJob job = runner.start(new ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "fastmode", null, null), "operator-who-started-it");

        List<ProcessHandle> tree = awaitEngineTree(job.getPid());
        assertFalse("the engine should have spawned at least one descendant to read evidence with",
                tree.isEmpty());

        long startedAt = System.currentTimeMillis();
        // A different operator on purpose: any authorized session may cancel any job (FR-023).
        // Tying this to ownership would build authority on an identity the trail itself records as
        // unverified, and would strand the machine when the session that started the job is gone.
        ProcessingJob cancelled = runner.cancel(job.getJobId(), "a-different-operator");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertEquals(State.CANCELLED, cancelled.getState());
        assertEquals("who asked is what the record has to carry", "a-different-operator",
                cancelled.getCancelledBy());
        assertTrue("cancelling took " + elapsed + " ms, over the " + CANCEL_BUDGET_MILLIS + " ms budget",
                elapsed < CANCEL_BUDGET_MILLIS);

        // The heart of it. Everything above passes with a cancel that kills only the child.
        for (ProcessHandle handle : tree) {
            assertFalse("a process of the engine tree survived the cancellation: pid " + handle.pid(),
                    handle.isAlive());
        }
        Optional<ProcessHandle> child = ProcessHandle.of(job.getPid());
        assertFalse("the child process survived the cancellation", child.isPresent() && child.get().isAlive());

        assertNotNull(cancelled.getOutcome());
        assertNotNull("the examiner has to be told what is left behind",
                cancelled.getOutcome().getRemainingAtDestination());
    }

    @Test
    public void whatIsLeftBehindIsNotOpenableAsACase() throws Exception {
        ProcessingJob job = runner.start(new ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "fastmode", null, null), "operator");
        awaitEngineTree(job.getPid());
        runner.cancel(job.getJobId(), "operator");

        // SC-009: a half-written destination must never present itself as a finished case.
        if (destination.exists()) {
            try {
                new iped.mcp.session.CaseValidator("4.").validate(destination);
                throw new AssertionError("a cancelled destination must not validate as a complete case");
            } catch (iped.mcp.protocol.McpError expected) {
                assertTrue("the refusal should name the reason: " + expected.getCode(),
                        expected.getCode().startsWith("CASE_") || expected.getCode().equals("NOT_A_CASE"));
            }
        }
    }

    /**
     * Waits until the engine has spawned the JVM that actually reads evidence.
     *
     * <p>
     * Cancelling before the grandchild exists would test nothing: the defect this suite is about
     * only becomes visible once there is a descendant to leave behind.
     */
    private static List<ProcessHandle> awaitEngineTree(long pid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ENGINE_START_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                List<ProcessHandle> descendants = new ArrayList<>();
                handle.get().descendants().forEach(descendants::add);
                if (!descendants.isEmpty()) {
                    return descendants;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the engine did not spawn its processing JVM within "
                + ENGINE_START_TIMEOUT_MILLIS + " ms");
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
            // Leftovers in a scratch area are noise, not a failure.
        }
    }
}
