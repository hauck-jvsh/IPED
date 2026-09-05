package iped.mcp.session;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.data.IPEDSource;
import iped.engine.preview.PreviewRepositoryManager;
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

        configurePreviews(caseId, source);
        entry = new Entry(source, new FieldVocabulary(source));
        entry.refCount = 1;
        entries.put(caseId, entry);
        LOGGER.info("Case {} opened into the pool", caseId);
        return new Handle(entry.source, entry.vocabulary);
    }

    /**
     * Opens the case's preview database for reading, as {@code UICaseDataLoader} does when the IPED
     * UI opens a case.
     *
     * <p>
     * An item that was decoded rather than carved out of a filesystem may have no bytes of its own:
     * {@code IndexItem} gives such an item a {@code PreviewInputStreamFactory}, so its only readable
     * content is the preview stored in the case's {@code previews.mv.db}. Reading it needs this
     * configuration. Without it the engine throws "Repository not configured", and because the
     * failure surfaces where content is read, the server reported it as a property of the evidence —
     * telling the agent that an item with content had none.
     *
     * <p>
     * Read-only is not a detail. {@code configureWritable} locks the database for exclusive access by
     * this process, which would take the case away from the examiner who has it open in the UI;
     * read-only opens H2 with {@code ACCESS_MODE_DATA=r}, which admits concurrent readers. The server
     * never processes, so it has nothing to write here.
     *
     * <p>
     * This cannot fail the case open: the call only records a connection configuration and opens
     * nothing, so a case with no preview database, or one whose database cannot be read, still opens
     * and stays queryable — the failure appears later, when content is actually asked for.
     */
    private void configurePreviews(String caseId, IPEDSource source) {
        File moduleDir = source.getModuleDir();
        try {
            PreviewRepositoryManager.configureReadOnly(moduleDir);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("The preview database of case {} could not be configured; items whose only content is a "
                    + "stored preview will not be readable in this session", caseId, e);
        }
    }

    /**
     * Releases the case's preview database, the other half of {@link #configurePreviews}.
     *
     * <p>
     * Not optional, and not merely hygiene for the connection pool. {@code PreviewRepositoryManager}
     * keys its configuration by folder and refuses a second one for a folder it already knows, so a
     * case opened, closed and opened again in the same session would fail to configure the second
     * time. A server that holds cases for the length of a session and lets go of them between
     * questions does exactly that.
     *
     * <p>
     * What makes the pair balanced is the pool's own reference counting: this runs when the last
     * session lets go, not when any session does. The manager underneath is process-global while a
     * pool is per-server, so this holds because a server process has one pool — which is true of the
     * server and of the suites, where each rule builds and tears down its own.
     */
    private void closePreviews(String caseId, IPEDSource source) {
        try {
            PreviewRepositoryManager.close(source.getModuleDir());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("The preview database of case {} could not be closed", caseId, e);
        }
    }

    /**
     * The folders of the cases this process currently holds open.
     *
     * <p>
     * Used to refuse creating a case on top of one being queried right now (FR-010). That collision
     * is the more dangerous of the two the rule covers: writing a new case over a finished one is
     * refused as a scope boundary, but writing over one a session has open corrupts both at once —
     * the reader's index underneath it and the writer's output.
     */
    public synchronized List<File> openCaseFolders() {
        List<File> folders = new ArrayList<>();
        for (Entry entry : entries.values()) {
            File caseDir = entry.source.getCaseDir();
            if (caseDir != null) {
                folders.add(caseDir);
            }
        }
        return folders;
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
        closePreviews(caseId, entry.source);
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
            closePreviews(caseId, entry.source);
        }
    }
}
