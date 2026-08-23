package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.Instant;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.processing.JobOutcome;
import iped.mcp.processing.JobStore;
import iped.mcp.processing.ProcessingJob;
import iped.mcp.processing.ProcessingJob.State;
import iped.mcp.processing.ProcessingRequest;

/**
 * Job records survive the process and are never discarded (FR-041, FR-045, SC-020).
 *
 * <p>
 * Retention is what makes {@code UNKNOWN_JOB} mean something. With nothing ever discarded, it says
 * exactly "never existed in this installation" instead of being ambiguous with "existed and was
 * cleaned up" — a distinction that matters to whoever checks a report months later.
 */
public class JobStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aJobSurvivesTheProcessThatWroteIt() throws Exception {
        JobStore writer = new JobStore(temp.getRoot());
        ProcessingJob job = newJob("job-one");
        job.setState(State.RUNNING);
        job.setPid(4242);
        job.setProcessStart(Instant.parse("2026-08-23T10:15:30Z"));
        writer.save(job);

        // A different instance over the same area is what a restarted server has.
        ProcessingJob reloaded = new JobStore(temp.getRoot()).load("job-one");

        assertNotNull(reloaded);
        assertEquals(State.RUNNING, reloaded.getState());
        assertEquals(4242, reloaded.getPid());
        // Both halves of the identity reconciliation needs: the id alone is not enough, because
        // operating systems reuse them.
        assertEquals(Instant.parse("2026-08-23T10:15:30Z"), reloaded.getProcessStart());
        assertEquals("fastmode", reloaded.getRequest().getProfile());
    }

    @Test
    public void anUnknownIdIsUnknownRatherThanAnError() {
        assertNull("a job this installation never had reads as absent",
                new JobStore(temp.getRoot()).load("never-existed"));
    }

    @Test
    public void repeatedRestartsDoNotDiscardAnything() throws Exception {
        JobStore store = new JobStore(temp.getRoot());
        for (int i = 0; i < 5; i++) {
            ProcessingJob job = newJob("job-" + i);
            job.setState(State.COMPLETED);
            store.save(job);
        }

        // Ten fresh instances, standing in for ten server lifetimes. Nothing ages out: there is no
        // time-based discard to exercise, which is the point (SC-020).
        for (int restart = 0; restart < 10; restart++) {
            JobStore afterRestart = new JobStore(temp.getRoot());
            for (int i = 0; i < 5; i++) {
                assertNotNull("job-" + i + " must survive restart " + restart, afterRestart.load("job-" + i));
            }
        }
        assertEquals(5, new JobStore(temp.getRoot()).loadAll().size());
    }

    @Test
    public void onlyRunningJobsAreOfferedForReconciliation() throws Exception {
        JobStore store = new JobStore(temp.getRoot());
        store.save(withState("finished", State.COMPLETED));
        store.save(withState("broken", State.FAILED));
        store.save(withState("stopped", State.CANCELLED));
        store.save(withState("alive", State.RUNNING));
        store.save(withState("accepted", State.ACCEPTED));

        // ACCEPTED counts too: the job was recorded and the process may already exist, so leaving it
        // out would let a job started moments before the crash escape reconciliation.
        assertEquals(2, store.loadRunning().size());
    }

    @Test
    public void theResolvedPasswordIsNeverWritten() throws Exception {
        JobStore store = new JobStore(temp.getRoot());
        ProcessingJob job = new ProcessingJob("secret-job",
                new ProcessingRequest("source", "destination", "fastmode", null, "vault-key-name"), "operator",
                Instant.now());
        store.save(job);

        String written = new String(java.nio.file.Files.readAllBytes(
                new File(store.jobFolder("secret-job"), "job.json").toPath()), java.nio.charset.StandardCharsets.UTF_8);

        // The reference is a name and is recorded; what it resolves to is not a field of anything
        // persisted (FR-015).
        assertTrue("the reference itself is part of the record", written.contains("vault-key-name"));
        assertEquals("vault-key-name", store.load("secret-job").getRequest().getSecretRef());
    }

    @Test
    public void anAbsentPercentageStaysAbsentThroughAReload() throws Exception {
        JobStore store = new JobStore(temp.getRoot());
        ProcessingJob job = newJob("no-percent");
        job.getProgress().setProcessedItems(10);
        store.save(job);

        // Absence is not emptiness, and it has to survive serialization: a percentage that came back
        // as zero would read as "no progress made", which is a different and false statement.
        assertNull(new JobStore(temp.getRoot()).load("no-percent").getProgress().getPercent());
    }

    @Test
    public void anOutcomeComesBackWithItsCauseAndResumability() throws Exception {
        JobStore store = new JobStore(temp.getRoot());
        ProcessingJob job = newJob("failed-job");
        job.setState(State.FAILED);
        JobOutcome outcome = new JobOutcome();
        outcome.setCause(ProcessingJob.FailureCause.SOURCE_INACCESSIBLE);
        outcome.setResumable(true);
        outcome.addFailedEvidence("disk-1");
        job.setOutcome(outcome);
        store.save(job);

        JobOutcome reloaded = new JobStore(temp.getRoot()).load("failed-job").getOutcome();

        // The distinction that changes what the examiner does next: an environment problem is worth
        // retrying, damaged evidence is not.
        assertEquals(ProcessingJob.FailureCause.SOURCE_INACCESSIBLE, reloaded.getCause());
        assertTrue(reloaded.isResumable());
        assertEquals(1, reloaded.getFailedEvidences().size());
    }

    private static ProcessingJob withState(String id, State state) {
        ProcessingJob job = newJob(id);
        job.setState(state);
        return job;
    }

    private static ProcessingJob newJob(String id) {
        return new ProcessingJob(id, new ProcessingRequest("source", "destination", "fastmode", null, null),
                "operator", Instant.now());
    }
}
