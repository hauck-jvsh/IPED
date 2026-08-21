package iped.mcp.processing;

import java.util.ArrayList;
import java.util.List;

import iped.mcp.processing.ProcessingJob.FailureCause;

/**
 * How a job ended (FR-026, FR-029).
 *
 * <p>
 * Two distinctions here exist because collapsing them would mislead the examiner, not because they
 * were convenient:
 *
 * <ul>
 * <li><b>Zero items is a completion, never a failure</b> (FR-048). Evidence that is empty, of an
 * unsupported format, or with nothing recoverable is a legitimate result of the examination.
 * Presenting it as a failure would send the examiner hunting for a defect where there is a
 * finding.</li>
 * <li><b>Inaccessible is not unreadable</b> (FR-049). One is the environment and resuming makes
 * sense; the other is the evidence and changes what happens next.</li>
 * </ul>
 */
public final class JobOutcome {

    private String casePath;
    private long itemCount;
    private long durationMillis;
    private FailureCause cause;
    private String causeDetail;
    private boolean resumable;
    private String diagnosticExcerpt;
    private String remainingAtDestination;
    private final List<String> failedEvidences = new ArrayList<>();
    private boolean resumed;

    /** Present on completion (FR-026). */
    public String getCasePath() {
        return casePath;
    }

    public void setCasePath(String casePath) {
        this.casePath = casePath;
    }

    /**
     * Items in the produced case. Must match what the case reports when opened — that is what SC-008
     * checks, and the only tie between the outcome and the reality of the index.
     */
    public long getItemCount() {
        return itemCount;
    }

    public void setItemCount(long itemCount) {
        this.itemCount = itemCount;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public FailureCause getCause() {
        return cause;
    }

    public void setCause(FailureCause cause) {
        this.cause = cause;
    }

    /** The engine's own words about the failure, when there are any. */
    public String getCauseDetail() {
        return causeDetail;
    }

    public void setCauseDetail(String causeDetail) {
        this.causeDetail = causeDetail;
    }

    public boolean isResumable() {
        return resumable;
    }

    public void setResumable(boolean resumable) {
        this.resumable = resumable;
    }

    /**
     * A bounded tail of the engine log, on failure (FR-043).
     *
     * <p>
     * Declared as evidence-derived content in the tool descriptor and therefore subject to the
     * egress policy through the same boundary that governs item text. It carries item names and
     * paths from the evidence, so a private path for it would let evidence content out past the
     * policy that governs every other content-returning tool.
     */
    public String getDiagnosticExcerpt() {
        return diagnosticExcerpt;
    }

    public void setDiagnosticExcerpt(String diagnosticExcerpt) {
        this.diagnosticExcerpt = diagnosticExcerpt;
    }

    /** What was left behind after a cancellation or failure (FR-023). */
    public String getRemainingAtDestination() {
        return remainingAtDestination;
    }

    public void setRemainingAtDestination(String remainingAtDestination) {
        this.remainingAtDestination = remainingAtDestination;
    }

    /**
     * Evidences the engine marked as failed, from {@code iped/data/evidences_processing_status}.
     *
     * <p>
     * The engine's own record distinguishes "never processed" (no status at all) from "processed
     * with no failures" (an empty list), and that distinction is preserved rather than flattened.
     */
    public List<String> getFailedEvidences() {
        return failedEvidences;
    }

    public void addFailedEvidence(String evidence) {
        failedEvidences.add(evidence);
    }

    /** True when this run continued an interrupted one rather than starting from scratch. */
    public boolean isResumed() {
        return resumed;
    }

    public void setResumed(boolean resumed) {
        this.resumed = resumed;
    }
}
