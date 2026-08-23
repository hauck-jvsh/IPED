package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
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
import iped.mcp.processing.OrphanReconciler;
import iped.mcp.processing.ProcessingJob;
import iped.mcp.processing.ProcessingJob.State;
import iped.mcp.processing.ProcessingRequest;
import iped.mcp.protocol.McpError;
import iped.mcp.session.CaseValidator;

/**
 * An interrupted job continues instead of starting over, and keeps its identity (FR-030, SC-017).
 *
 * <p>
 * The identifier is kept on purpose: two ids for the same evidence would split the history the trail
 * has to reconstitute into two unrelated halves.
 */
public class ResumeJobTest {

    private static final long ENGINE_START_TIMEOUT_MILLIS = 120_000;
    private static final long COMPLETION_TIMEOUT_MILLIS = 20 * 60 * 1000L;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File evidence;
    private File destination;
    private McpServerConfig config;
    private JobRunner runner;

    @Before
    public void setUp() throws Exception {
        evidence = McpTestSupport.requireSourceEvidence();
        File caseRoot = McpTestSupport.requireCaseRoot();
        destination = new File(caseRoot, "resume-" + UUID.randomUUID().toString().substring(0, 8));

        config = McpTestSupport.configWithTempAudit(temp.getRoot());
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
    public void anInterruptedJobResumesUnderTheSameIdAndFinishes() throws Exception {
        ProcessingJob started = runner.start(new ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "fastmode", null, null), "operator");
        String jobId = started.getJobId();
        // Interrupted once the engine is genuinely under way, not the instant the case folder
        // appears. That is not padding: the folder exists seconds before anything has been
        // processed, and continuing from there has nothing to build on. A real interruption happens
        // mid-processing, which is the situation resume is for.
        awaitItemsProcessed(started);

        // The abrupt path, exactly as OrphanReconciliationTest establishes it: a fresh reconciler
        // over the same store is what a restarted server has.
        new OrphanReconciler(new JobStore(config.getAuditArea())).reconcile();

        ProcessingJob interrupted = new JobStore(config.getAuditArea()).load(jobId);
        assertEquals(State.INTERRUPTED, interrupted.getState());

        // While it is interrupted the destination must not pass for a finished case (SC-009). It
        // looks structurally complete at this point — index, data and lib all in place with a
        // committed index — so only the job record can tell the difference.
        assertRefusesToOpen(destination, new JobStore(config.getAuditArea()));

        JobRunner afterRestart = new JobRunner(config, iped.mcp.Diagnostics.resolveIpedRoot(),
                new JobStore(config.getAuditArea()));
        ProcessingJob resumed = afterRestart.resume(jobId, "a-later-operator");

        assertEquals("resuming must not mint a new identity", jobId, resumed.getJobId());

        ProcessingJob finished = awaitTerminal(afterRestart, jobId);
        assertEquals("the resumed run did not complete. Engine log tail:\n" + logTail(finished),
                State.COMPLETED, finished.getState());
        assertNotNull(finished.getOutcome());
        assertTrue("the outcome must say this run continued an earlier one",
                finished.getOutcome().isResumed());

        // And the case it produces is a real one, which is the only proof that continuing worked
        // rather than merely not crashing. Validated against the same job-aware validator, which
        // now has to let a COMPLETED job through as readily as it refused the interrupted one.
        assertNotNull(new CaseValidator("4.", new JobStore(config.getAuditArea())).validate(destination)
                .caseId());
    }

    @Test
    public void aJobStoppedBeforeAnythingExistedSaysSoInsteadOfFailingCryptically() throws Exception {
        // Built directly rather than by racing the engine to kill it before the case folder appears.
        // That race is unwinnable on a fast machine, and the branch under test is about the state,
        // not about how it came to be.
        JobStore store = new JobStore(config.getAuditArea());
        ProcessingJob record = new ProcessingJob("stopped-early",
                new ProcessingRequest(evidence.getAbsolutePath(), destination.getAbsolutePath(), "fastmode",
                        null, null),
                "operator", java.time.Instant.now());
        record.setState(State.INTERRUPTED);
        iped.mcp.processing.JobOutcome outcome = new iped.mcp.processing.JobOutcome();
        outcome.setResumable(true);
        record.setOutcome(outcome);
        store.save(record);

        try {
            new JobRunner(config, iped.mcp.Diagnostics.resolveIpedRoot(), store)
                    .resume("stopped-early", "operator");
            throw new AssertionError("there was nothing to continue, so the resume should have been refused");
        } catch (McpError expected) {
            assertEquals(McpError.JOB_NOT_RESUMABLE, expected.getCode());
            // The engine's own answer here is "inexistent or invalid case folder" — true, and no
            // help at all to someone continuing a job this server recorded as interrupted.
            assertTrue("the refusal must explain there is nothing to build on: " + expected.getMessage(),
                    expected.getMessage().contains("before the engine produced anything"));
        }
    }

    @Test
    public void aFinishedJobCannotBeResumed() throws Exception {
        ProcessingJob job = runner.start(new ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "fastmode", null, null), "operator");
        ProcessingJob finished = awaitTerminal(runner, job.getJobId());
        assertEquals(State.COMPLETED, finished.getState());

        try {
            runner.resume(job.getJobId(), "operator");
            throw new AssertionError("a completed job must not be resumable");
        } catch (McpError expected) {
            assertEquals(McpError.JOB_NOT_RESUMABLE, expected.getCode());
        }
    }

    @Test
    public void asecondRequestIsRefusedWhileOneRuns() throws Exception {
        ProcessingJob first = runner.start(new ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "fastmode", null, null), "operator");
        awaitEngineTree(first.getPid());

        File other = new File(destination.getParentFile(), destination.getName() + "-second");
        try {
            runner.start(new ProcessingRequest(evidence.getAbsolutePath(), other.getAbsolutePath(),
                    "fastmode", null, null), "another-operator");
            throw new AssertionError("a second job must be refused while one is running");
        } catch (McpError expected) {
            assertEquals(McpError.JOB_ALREADY_RUNNING, expected.getCode());
            // SC-013: the refusal has to identify what is in the way, or the agent cannot decide
            // whether to wait or to cancel.
            assertTrue("the refusal must name the running job: " + expected.getMessage(),
                    expected.getMessage().contains(first.getJobId()));
        } finally {
            runner.cancel(first.getJobId(), "operator");
            if (other.exists()) {
                deleteRecursively(other);
            }
        }
    }

    /**
     * The end of the engine's own log, for a failure message worth reading.
     *
     * <p>
     * A job that ends {@code FAILED} says so, and "it failed" is not enough to fix anything. The
     * engine's last lines are what say why, and they live in a temporary audit area this test
     * deletes on the way out.
     */
    private static String logTail(ProcessingJob job) {
        if (job.getLogPath() == null) {
            return "(no log path recorded)";
        }
        String excerpt = iped.mcp.processing.ProgressReader
                .excerptFromLog(new File(job.getLogPath()), 30);
        return excerpt == null ? "(log unreadable at " + job.getLogPath() + ")" : excerpt;
    }

    private static void assertRefusesToOpen(File caseDir, JobStore store) {
        try {
            new CaseValidator("4.", store).validate(caseDir);
            throw new AssertionError("an interrupted destination must not open as a complete case");
        } catch (McpError expected) {
            assertTrue("the refusal must be about the case being unusable: " + expected.getCode(),
                    expected.getCode().startsWith("CASE_") || expected.getCode().equals("NOT_A_CASE"));
        }
    }

    private static ProcessingJob awaitTerminal(JobRunner runner, String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + COMPLETION_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ProcessingJob stored = runner.getStore().load(jobId);
            if (stored != null && stored.getState().isTerminal()) {
                return stored;
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("the job did not reach a terminal state in time");
    }

    /**
     * Waits until the engine has actually processed items.
     *
     * <p>
     * The case folder appears well before that, and the engine's own precheck for {@code --continue}
     * only tests that the folder exists — necessary but not sufficient. Continuing from a case that
     * was interrupted in its first second has nothing to build on, and the run fails on something
     * the precheck cannot see. Waiting for real progress reproduces the situation resume exists for.
     */
    private static void awaitItemsProcessed(ProcessingJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ENGINE_START_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (job.getProgress() != null && job.getProgress().getProcessedItems() > 0) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the engine did not start processing items in time");
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
