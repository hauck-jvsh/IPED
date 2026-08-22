package iped.mcp.processing;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.mcp.processing.ProcessingJob.FailureCause;
import iped.mcp.processing.ProcessingJob.State;

/**
 * Decides, when the server comes back up, what became of a job it was running (FR-024).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>
 * An orderly shutdown destroys the tree and records the interruption, so the common case needs
 * nothing. An abrupt end — power loss, {@code kill -9}, the machine restarting — does not: on both
 * platforms the child survives its parent's death, and {@code Bootstrap} does not help, since its own
 * shutdown hook has {@code process.destroy()} commented out and relies on the grandchild noticing.
 *
 * <p>
 * So the store is left saying {@code RUNNING} for a process that may be alive or dead, and FR-024
 * forbids both wrong answers: never "still running", never "no such job".
 *
 * <h2>Why the process id alone is not enough</h2>
 *
 * <p>
 * Operating systems reuse process ids. Acting on the number alone would eventually destroy an
 * unrelated process that happened to take it — worse than the defect being corrected. The recorded
 * start instant settles it: a process bearing the same id but started after ours is not ours.
 *
 * <h2>Why the orphan is destroyed rather than adopted</h2>
 *
 * <p>
 * Adopting it would contradict FR-024, which says a job ends with the server. Leaving it running
 * would be worse still — evidence being read by a process nobody is watching, writing into a
 * destination the server has already given up on. Adoption is a recorded possible evolution, with a
 * stated trigger; it is not this.
 */
public final class OrphanReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrphanReconciler.class);

    private final JobStore store;

    public OrphanReconciler(JobStore store) {
        this.store = store;
    }

    /** What was found and what was done about it, for the startup diagnostic. */
    public static final class Reconciliation {
        private final String jobId;
        private final boolean orphanWasAlive;

        Reconciliation(String jobId, boolean orphanWasAlive) {
            this.jobId = jobId;
            this.orphanWasAlive = orphanWasAlive;
        }

        public String getJobId() {
            return jobId;
        }

        /** True when a live process matching the record was found and destroyed. */
        public boolean wasOrphanAlive() {
            return orphanWasAlive;
        }
    }

    /**
     * Reconciles every job the store still believes is running.
     *
     * <p>
     * All three branches end at {@link State#INTERRUPTED}, which is not terminal: it is where resume
     * starts from.
     */
    public List<Reconciliation> reconcile() {
        List<Reconciliation> handled = new ArrayList<>();
        for (ProcessingJob job : store.loadRunning()) {
            boolean wasAlive = destroyIfStillOurs(job);
            job.setState(State.INTERRUPTED);
            job.setEndedAt(Instant.now());
            JobOutcome outcome = job.getOutcome() == null ? new JobOutcome() : job.getOutcome();
            outcome.setCause(FailureCause.ENGINE_FAILURE);
            outcome.setCauseDetail(wasAlive
                    ? "The server was restarted while this job was running; the engine process was still alive "
                            + "and was ended, because a job does not outlive the server that started it."
                    : "The server was restarted while this job was running, and the engine process was already "
                            + "gone.");
            // Interrupted is precisely the state resume was built for.
            outcome.setResumable(true);
            job.setOutcome(outcome);
            try {
                store.save(job);
            } catch (IOException e) {
                LOGGER.error("Job {} could not be marked interrupted; it will be reconciled again next start",
                        job.getJobId(), e);
            }
            LOGGER.warn("Job {} was left running by a previous server process and is now marked interrupted "
                    + "(engine process {})", job.getJobId(), wasAlive ? "was alive and was ended" : "was gone");
            handled.add(new Reconciliation(job.getJobId(), wasAlive));
        }
        return handled;
    }

    /**
     * @return whether a live process matching the record was found and destroyed
     */
    private boolean destroyIfStillOurs(ProcessingJob job) {
        if (job.getPid() <= 0) {
            return false;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(job.getPid());
        if (!handle.isPresent() || !handle.get().isAlive()) {
            return false;
        }
        if (!isSameProcess(handle.get(), job.getProcessStart())) {
            // The id was reused. Destroying this would kill somebody else's process over a
            // coincidence of numbering.
            LOGGER.info("Process {} is alive but started at {}, not at {}, so it is not job {}'s engine",
                    job.getPid(), handle.get().info().startInstant().orElse(null), job.getProcessStart(),
                    job.getJobId());
            return false;
        }
        JobRunner.destroyTree(handle.get());
        return true;
    }

    /**
     * Whether a live handle is the process the record refers to.
     *
     * <p>
     * Compared with a second of tolerance: the instant a platform reports is not always identical to
     * the one recorded at launch, and requiring exactness would make every reconciliation decide
     * "not ours" and leave real orphans running.
     */
    private static boolean isSameProcess(ProcessHandle handle, Instant recordedStart) {
        if (recordedStart == null) {
            return false;
        }
        Optional<Instant> actual = handle.info().startInstant();
        if (!actual.isPresent()) {
            return false;
        }
        return Math.abs(actual.get().toEpochMilli() - recordedStart.toEpochMilli()) < 1000;
    }
}
