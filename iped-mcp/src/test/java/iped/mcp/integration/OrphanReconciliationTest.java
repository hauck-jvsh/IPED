package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.SystemUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.processing.JobRunner;
import iped.mcp.processing.JobStore;
import iped.mcp.processing.OrphanReconciler;
import iped.mcp.processing.ProcessingJob;
import iped.mcp.processing.ProcessingJob.State;
import iped.mcp.processing.ProcessingRequest;

/**
 * What the server does about a job it was running when it died abruptly (FR-024).
 *
 * <p>
 * An orderly shutdown is the easy path and a hook covers it. This is the hard one: after a
 * {@code kill -9} or a power loss the engine survives its parent, the store still says
 * {@code RUNNING}, and FR-024 forbids both wrong answers — never "still running", never "no such
 * job". A hung orphan passes every request/response test in this module.
 */
public class OrphanReconciliationTest {

    private static final long ENGINE_START_TIMEOUT_MILLIS = 120_000;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File evidence;
    private File destination;
    private McpServerConfig config;
    private JobStore store;
    private JobRunner runner;

    @Before
    public void setUp() throws Exception {
        evidence = McpTestSupport.requireSourceEvidence();
        File caseRoot = McpTestSupport.requireCaseRoot();
        destination = new File(caseRoot, "orphan-" + UUID.randomUUID().toString().substring(0, 8));

        config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);
        config.setProcessingSourceAreas(Arrays.asList(evidence.getParentFile().getAbsolutePath()));
        config.setProcessingCaseRoots(Arrays.asList(caseRoot.getAbsolutePath()));
        store = new JobStore(config.getAuditArea());
        runner = new JobRunner(config, iped.mcp.Diagnostics.resolveIpedRoot(), store);
    }

    @After
    public void tearDown() {
        if (destination != null && destination.exists()) {
            deleteRecursively(destination);
        }
    }

    @Test
    public void aLiveOrphanIsFoundEndedAndRecordedAsInterrupted() throws Exception {
        ProcessingJob job = runner.start(new ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "fastmode", null, null), "operator");
        List<ProcessHandle> tree = awaitEngineTree(job.getPid());

        // The whole simulation: a brand-new reconciler over the same store is what a restarted
        // server has — the JobRunner that owned the Process object died with the old one, so all
        // that is left is what is on disk.
        List<OrphanReconciler.Reconciliation> handled = new OrphanReconciler(new JobStore(config.getAuditArea()))
                .reconcile();

        assertEquals("exactly the running job should have been reconciled", 1, handled.size());
        assertEquals(job.getJobId(), handled.get(0).getJobId());
        assertTrue("the engine was still alive, so the reconciler should say so",
                handled.get(0).wasOrphanAlive());

        ProcessingJob reloaded = new JobStore(config.getAuditArea()).load(job.getJobId());
        assertNotNull("the job must not vanish — 'no such job' is one of the two forbidden answers",
                reloaded);
        assertEquals("nor may it still read as running, which is the other one", State.INTERRUPTED,
                reloaded.getState());
        assertTrue("an interrupted job is where resume starts from", reloaded.getOutcome().isResumable());

        for (ProcessHandle handle : tree) {
            assertFalse("an orphan of the engine tree survived reconciliation: pid " + handle.pid(),
                    handle.isAlive());
        }
    }

    @Test
    public void aProcessThatMerelyReusedTheIdIsLeftAlone() throws Exception {
        // Operating systems reuse process ids. Acting on the number alone would eventually destroy
        // a stranger, which is worse than the defect being corrected.
        Process bystander = startHarmlessProcess();
        try {
            ProcessingJob record = new ProcessingJob("reused-id-job",
                    new ProcessingRequest(evidence.getAbsolutePath(), destination.getAbsolutePath(),
                            "fastmode", null, null),
                    "operator", Instant.now());
            record.setState(State.RUNNING);
            record.setPid(bystander.pid());
            // Recorded as having started an hour before this process did: same id, different
            // process. That is exactly what id reuse looks like.
            record.setProcessStart(Instant.now().minusSeconds(3600));
            store.save(record);

            new OrphanReconciler(store).reconcile();

            assertTrue("a process that only shares the id must not be destroyed", bystander.isAlive());
            // The job is still reconciled — the record cannot be left saying RUNNING either way.
            assertEquals(State.INTERRUPTED, store.load("reused-id-job").getState());
        } finally {
            bystander.destroyForcibly();
            bystander.waitFor();
        }
    }

    private static Process startHarmlessProcess() throws IOException {
        List<String> command = SystemUtils.IS_OS_WINDOWS
                ? Arrays.asList("cmd.exe", "/c", "ping -n 120 127.0.0.1 > NUL")
                : Arrays.asList("sh", "-c", "sleep 120");
        return new ProcessBuilder(command).start();
    }

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
        throw new AssertionError("the engine did not spawn its processing JVM in time");
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
            // Scratch leftovers are noise, not a failure.
        }
    }
}
