package iped.mcp.processing;

import java.time.Instant;

/**
 * How far a running job has got (FR-020).
 *
 * <p>
 * The rule that governs this whole class is the module's own: <b>absence is not emptiness</b>. A
 * percentage of zero in a field that ought to be absent is the easiest way to lie here, and it is
 * exactly what SC-007 measures the alternative of — new information, or a declaration that the
 * current phase produces no measure.
 */
public final class JobProgress {

    private String phase;
    private long processedItems;
    private long discoveredItems;
    private Integer percent;
    private Instant estimatedCompletion;
    private boolean measurable;
    private boolean stalled;
    private Instant lastObservedAt;

    /**
     * The phase the engine reports, in the child's declared locale.
     *
     * <p>
     * This is the half of FR-020 a numeric parser cannot supply: the engine emits its phase only as
     * localized prose with no numeric anchor. It is also the only thing that answers "is it alive?"
     * during discovery, commit and optimization, which emit no counters at all.
     */
    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public long getProcessedItems() {
        return processedItems;
    }

    public void setProcessedItems(long processedItems) {
        this.processedItems = processedItems;
    }

    public long getDiscoveredItems() {
        return discoveredItems;
    }

    public void setDiscoveredItems(long discoveredItems) {
        this.discoveredItems = discoveredItems;
    }

    /** {@code null} until discovery has finished — absent, never zero. */
    public Integer getPercent() {
        return percent;
    }

    public void setPercent(Integer percent) {
        this.percent = percent;
    }

    /** {@code null} when no estimate can be derived. Never invented (FR-020). */
    public Instant getEstimatedCompletion() {
        return estimatedCompletion;
    }

    public void setEstimatedCompletion(Instant estimatedCompletion) {
        this.estimatedCompletion = estimatedCompletion;
    }

    /** False declares that the current phase produces no measure, rather than reporting a fake one. */
    public boolean isMeasurable() {
        return measurable;
    }

    public void setMeasurable(boolean measurable) {
        this.measurable = measurable;
    }

    /**
     * True when the child has been silent longer than the declared threshold (FR-047).
     *
     * <p>
     * Only meaningful alongside {@link #getPhase()}, and never reported without it: minutes of
     * silence during an index commit are normal, and the same minutes during item processing are
     * not. A stall flag on its own would turn a legitimate quiet phase into a false alarm.
     */
    public boolean isStalled() {
        return stalled;
    }

    public void setStalled(boolean stalled) {
        this.stalled = stalled;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public void setLastObservedAt(Instant lastObservedAt) {
        this.lastObservedAt = lastObservedAt;
    }
}
