package iped.mcp.session;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.data.IPEDSource;
import iped.mcp.protocol.McpError;
import iped.mcp.query.FieldVocabulary;

/**
 * One open engine handle per case, shared by every session in this process (FR-014).
 *
 * <p>
 * <b>Why this exists.</b> Until sessions could be simultaneous there was exactly one, so each
 * session opening its own {@link IPEDSource} cost nothing. Opening a ten-million-item case takes up
 * to thirty seconds and holds memory in proportion to the collection (SC-015 of feature 001); two
 * examiners reading the same case would pay both, twice, and SC-006 of this feature requires the
 * performance targets to survive the new transport. So the expensive part is shared and counted, and
 * only the cheap per-session state — the audit trail, the write claim, the warnings — is duplicated.
 *
 * <p>
 * Sharing is safe because everything shared here is read-only after construction: Lucene's
 * {@code IndexSearcher} supports concurrent search, and {@link FieldVocabulary} populates itself in
 * its constructor and only answers questions afterwards.
 *
 * <p>
 * The write claim is deliberately <b>not</b> here. It belongs to a session, not to the case: a case
 * with three readers and one writer is one entry with a reference count of four and a single claim
 * held elsewhere. Putting the claim in the pool would make it outlive the session that took it.
 */
public class CasePool implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CasePool.class);

    /** The shared, read-only view of one case handed to a session. */
    public static final class Handle {

        private final IPEDSource source;
        private final FieldVocabulary vocabulary;

        Handle(IPEDSource source, FieldVocabulary vocabulary) {
            this.source = source;
            this.vocabulary = vocabulary;
        }

        public IPEDSource getSource() {
            return source;
        }

        public FieldVocabulary getVocabulary() {
            return vocabulary;
        }
    }

    private static final class Entry {

        private final IPEDSource source;
        private final FieldVocabulary vocabulary;
        private int refCount;

        Entry(IPEDSource source, FieldVocabulary vocabulary) {
            this.source = source;
            this.vocabulary = vocabulary;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * Opens the case, or takes another reference on the one already open for that id.
     *
     * @throws McpError
     *             with {@link McpError#CASE_INACCESSIBLE} when the engine refuses the folder
     */
    public synchronized Handle acquire(String caseId, File caseDir) {
        Entry entry = entries.get(caseId);
        if (entry != null) {
            entry.refCount++;
            LOGGER.debug("Case {} taken from the pool, now held by {} session(s)", caseId, entry.refCount);
            return new Handle(entry.source, entry.vocabulary);
        }

        IPEDSource source;
        try {
            // askImagePathIfNotFound = false: a server with no console must never block on a
            // dialog. A portable case with missing evidence stays queryable for metadata; raw
            // content declares its unavailability when asked (FR-022 of feature 001).
            source = new IPEDSource(caseDir, null, false);
        } catch (RuntimeException e) {
            throw new McpError(McpError.CASE_INACCESSIBLE,
                    "The case at " + caseDir.getAbsolutePath() + " could not be opened: " + e.getMessage(),
                    "The folder looks like a case but the engine refused it. If the message mentions a missing "
                            + "image, this is a portable case whose evidence files are not present: metadata "
                            + "stays queryable, raw content does not. Otherwise check the server log for the "
                            + "underlying failure.",
                    e).with("path", caseDir.getAbsolutePath());
        }

        entry = new Entry(source, new FieldVocabulary(source));
        entry.refCount = 1;
        entries.put(caseId, entry);
        LOGGER.info("Case {} opened into the pool", caseId);
        return new Handle(entry.source, entry.vocabulary);
    }

    /** Drops one reference, closing the engine handle when the last session lets go. */
    public synchronized void release(String caseId) {
        Entry entry = entries.get(caseId);
        if (entry == null) {
            return;
        }
        entry.refCount--;
        if (entry.refCount > 0) {
            LOGGER.debug("Case {} released, still held by {} session(s)", caseId, entry.refCount);
            return;
        }
        entries.remove(caseId);
        try {
            entry.source.close();
            LOGGER.info("Case {} closed; no session holds it", caseId);
        } catch (RuntimeException e) {
            LOGGER.warn("Failure while closing the pooled case {}", caseId, e);
        }
    }

    /** How many sessions hold this case. Zero when it is not open. Used by the concurrency suite. */
    public synchronized int referenceCount(String caseId) {
        Entry entry = entries.get(caseId);
        return entry == null ? 0 : entry.refCount;
    }

    @Override
    public synchronized void close() {
        for (String caseId : entries.keySet().toArray(new String[0])) {
            Entry entry = entries.remove(caseId);
            try {
                entry.source.close();
            } catch (RuntimeException e) {
                LOGGER.warn("Failure while closing the pooled case {}", caseId, e);
            }
        }
    }
}
