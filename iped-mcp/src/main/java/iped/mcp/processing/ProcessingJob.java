package iped.mcp.processing;

import java.time.Instant;

/**
 * One execution of a {@link ProcessingRequest} over time — the only entity in this module that
 * outlives the session that created it (FR-021).
 *
 * <p>
 * Mutable by design: a job is a long-lived record updated at each transition and persisted by
 * {@link JobStore} after every one of them (FR-041).
 */
public final class ProcessingJob {

    /**
     * Where a job is in its life.
     *
     * <p>
     * {@link #INTERRUPTED} is <b>not</b> terminal — it is the only state resume starts from
     * (FR-030). It is also assigned only when the server comes back up, never while it is running:
     * a live server that loses its child sees that through the exit code, which is {@link #FAILED}.
     */
    public enum State {
        ACCEPTED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    /** Why a job failed, when the cause is one the server can tell apart (FR-049). */
    public enum FailureCause {
        /**
         * Media removed, share dropped, permission lost. An environment problem: the evidence is
         * fine and resuming makes sense once it is back.
         */
        SOURCE_INACCESSIBLE,
        /**
         * The source is there and the engine could not read it. A problem with the evidence, which
         * changes what the examiner does next.
         */
        SOURCE_UNREADABLE,
        /** Anything else, carried with the engine's own diagnostic. */
        ENGINE_FAILURE
    }

    private final String jobId;
    private final ProcessingRequest request;
    private final String requestedBy;
    private final Instant acceptedAt;

    private State state = State.ACCEPTED;
    private long pid;
    private Instant processStart;
    private Instant startedAt;
    private Instant endedAt;
    private String cancelledBy;
    private String sessionId;
    private String authorizedUnder;
    private String logPath;
    private String diskWarning;
    private JobProgress progress = new JobProgress();
    private JobOutcome outcome;

    public ProcessingJob(String jobId, ProcessingRequest request, String requestedBy, Instant acceptedAt) {
        this.jobId = jobId;
        this.request = request;
        this.requestedBy = requestedBy;
        this.acceptedAt = acceptedAt;
    }

    /** Stable, opaque, and <b>not a secret</b>: knowing it allows following, not access to the case. */
    public String getJobId() {
        return jobId;
    }

    public ProcessingRequest getRequest() {
        return request;
    }

    /** The operator of the session that asked, in the dual form FR-032 of 006 established. */
    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    /** Operating-system process id of the child, or 0 before it exists. */
    public long getPid() {
        return pid;
    }

    public void setPid(long pid) {
        this.pid = pid;
    }

    /**
     * When the child process started, from {@code ProcessHandle.Info.startInstant()}.
     *
     * <p>
     * Recorded because the operating system reuses process ids. On reconciliation the id alone
     * proves nothing, and destroying an unrelated process that happened to take the number would be
     * worse than the defect being corrected.
     */
    public Instant getProcessStart() {
        return processStart;
    }

    public void setProcessStart(Instant processStart) {
        this.processStart = processStart;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    /** Who asked for the cancellation — not necessarily who started the job (FR-023). */
    public String getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(String cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    /**
     * The session that asked for this job, linking it to that session's audit trail (FR-034).
     *
     * <p>
     * Recorded here rather than in the case's session manifest, and not by preference: the manifest
     * is per case, and when a processing job starts the case does not exist yet. The link has to
     * live where it can be written at the moment it becomes true.
     */
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * The processing posture in force when this job was accepted (FR-038).
     *
     * <p>
     * Authorization here is granted by configuration rather than per request, so it happens before
     * the request exists and leaves no record of its own. Without this, a trail could show that a
     * job ran and never show under what permission — which areas were readable, which roots
     * writable — and that is precisely what a second examiner needs to judge whether it should have
     * run at all.
     */
    public String getAuthorizedUnder() {
        return authorizedUnder;
    }

    public void setAuthorizedUnder(String authorizedUnder) {
        this.authorizedUnder = authorizedUnder;
    }

    /** Where the engine's own log was written, declared in the outcome always (FR-042). */
    public String getLogPath() {
        return logPath;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    /**
     * Present when free space at the destination fell below the requirement (FR-044).
     *
     * <p>
     * Never blocks the job. Also absent — not zero — when the source size could not be measured
     * inside the accept budget (FR-046).
     */
    public String getDiskWarning() {
        return diskWarning;
    }

    public void setDiskWarning(String diskWarning) {
        this.diskWarning = diskWarning;
    }

    public JobProgress getProgress() {
        return progress;
    }

    public void setProgress(JobProgress progress) {
        this.progress = progress;
    }

    public JobOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(JobOutcome outcome) {
        this.outcome = outcome;
    }
}
