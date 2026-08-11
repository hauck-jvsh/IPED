package iped.mcp.session;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.data.BitmapBookmarks;
import iped.engine.data.IPEDSource;
import iped.mcp.protocol.McpError;

/**
 * Detects concurrent access to a case by another process on the same machine (FR-028).
 *
 * <p>
 * Decision D2 restricts the requirement to the local machine, which is what makes a file-based
 * mechanism sufficient. Reading is never blocked; only writing is refused.
 *
 * <p>
 * Two signals, deliberately different in strength:
 *
 * <ul>
 * <li><b>Lock file</b>, held in the case's audit subfolder for as long as a write-enabled session
 * is open. This detects another {@code iped-mcp} server reliably, because both sides cooperate.</li>
 * <li><b>Exclusive lock probe</b> on the bookmarks state file. This is best-effort: the IPED 4.3.1
 * UI takes no lock on the case, so a UI that merely has the case open is not always visible. When
 * it is holding the state file — which is when a conflicting write would actually corrupt
 * something — the probe catches it.</li>
 * </ul>
 *
 * <p>
 * The lock file lives inside the audit subfolder, the one area of the case this server is allowed
 * to write to and the one the read-only invariant check excludes by name.
 */
public class ConcurrencyGuard implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrencyGuard.class);

    private static final String LOCK_FILE = "access.lock";

    private final Map<String, FileLock> heldLocks = new HashMap<>();
    private final Map<String, RandomAccessFile> heldFiles = new HashMap<>();
    private final WriteClaims writeClaims;
    private final String sessionId;

    /**
     * @param writeClaims
     *            the process-wide register of who holds what, so a refusal can name the holder
     * @param sessionId
     *            the session this guard belongs to
     */
    public ConcurrencyGuard(WriteClaims writeClaims, String sessionId) {
        this.writeClaims = writeClaims;
        this.sessionId = sessionId;
    }

    /**
     * Acquires the write lock for a case, or throws when someone else holds it.
     *
     * @throws McpError
     *             with {@link McpError#CONCURRENT_ACCESS}
     */
    public synchronized void acquireWriteLock(String caseId, File caseDir, String auditFolderNameInCase) {
        if (heldLocks.containsKey(caseId)) {
            return;
        }
        probeBookmarksState(caseDir);

        File lockDir = new File(caseDir, auditFolderNameInCase);
        try {
            Files.createDirectories(lockDir.toPath());
            File lockFile = new File(lockDir, LOCK_FILE);
            RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
            FileChannel channel = raf.getChannel();
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                raf.close();
                throw refusal(caseId, lockFile);
            }
            raf.setLength(0);
            raf.write((ProcessIdentity.describe() + " session=" + sessionId + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            heldLocks.put(caseId, lock);
            heldFiles.put(caseId, raf);
            writeClaims.record(caseId, sessionId);
        } catch (IOException e) {
            throw new McpError(McpError.CONCURRENT_ACCESS,
                    "The concurrency lock for the case could not be taken: " + e.getMessage(),
                    "Check that the audit subfolder inside the case is writable, then retry.", e).with("caseId",
                            caseId);
        }
    }

    /**
     * Names who is holding the write, when it can be named (FR-029).
     *
     * <p>
     * The message this replaced said "by another process on this machine", which stopped being true
     * the moment two sessions could exist in one process: the holder is now just as likely to be a
     * second connection to this same server. Telling an examiner to close another process when the
     * conflict is with their own second session sends them looking for something that is not there.
     */
    private McpError refusal(String caseId, File lockFile) {
        WriteClaims.Claim holder = writeClaims.holderOf(caseId);
        if (holder != null) {
            return new McpError(McpError.CONCURRENT_ACCESS,
                    "The case is open for writing by another session of this server, held since " + holder.getSince()
                            + ".",
                    "Wait for that session to finish or close it, then retry. Reading is unaffected and stays "
                            + "available meanwhile — only one session at a time may write to a case.")
                                    .with("caseId", caseId).with("holderSessionId", holder.getSessionId())
                                    .with("heldSince", holder.getSince().toString());
        }
        return new McpError(McpError.CONCURRENT_ACCESS,
                "The case is open for writing by another process on this machine.",
                "Close the other IPED session — the UI or another MCP server — and retry. Reading is "
                        + "unaffected and stays available meanwhile.").with("caseId", caseId)
                                .with("lockFile", lockFile.getAbsolutePath());
    }

    /**
     * Best-effort detection of a UI holding the bookmarks state file. Absence of a lock is not
     * proof of absence of a reader; presence of one is proof of a conflict.
     */
    private void probeBookmarksState(File caseDir) {
        Path stateFile = new File(new File(caseDir, IPEDSource.MODULE_DIR), BitmapBookmarks.STATEFILENAME).toPath();
        if (!Files.exists(stateFile)) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(stateFile.toFile(), "rw");
                FileChannel channel = raf.getChannel()) {
            FileLock probe = channel.tryLock();
            if (probe == null) {
                throw new McpError(McpError.CONCURRENT_ACCESS,
                        "The bookmarks state of the case is held by another process on this machine.",
                        "Close the IPED UI for this case and retry. Writing now would overwrite what the "
                                + "other session is holding.").with("stateFile", stateFile.toString());
            }
            probe.release();
        } catch (OverlappingFileLockException e) {
            throw new McpError(McpError.CONCURRENT_ACCESS,
                    "The bookmarks state of the case is held by another process on this machine.",
                    "Close the IPED UI for this case and retry.").with("stateFile", stateFile.toString());
        } catch (IOException e) {
            // A read-only case cannot be opened "rw"; that is not a conflict, and writing is
            // refused elsewhere for a different and clearer reason.
            LOGGER.debug("Bookmarks state probe skipped for {}: {}", stateFile, e.getMessage());
        }
    }

    /** The process-wide register, so the session can report who holds what (FR-022). */
    public WriteClaims getWriteClaims() {
        return writeClaims;
    }

    public synchronized void release(String caseId) {
        FileLock lock = heldLocks.remove(caseId);
        RandomAccessFile file = heldFiles.remove(caseId);
        writeClaims.release(caseId, sessionId);
        try {
            if (lock != null) {
                lock.release();
            }
            if (file != null) {
                file.close();
            }
        } catch (IOException e) {
            LOGGER.warn("Could not release the concurrency lock of case {}", caseId, e);
        }
    }

    @Override
    public synchronized void close() {
        for (String caseId : heldLocks.keySet().toArray(new String[0])) {
            release(caseId);
        }
        // Belt and braces for FR-030: a session that dropped its connection mid-operation must not
        // leave a case claimed by nobody, whatever state its locks were in.
        writeClaims.releaseAll(sessionId);
    }

    /** Identifies this process in the lock file, so a stale lock can be traced to its owner. */
    static class ProcessIdentity {
        static String describe() {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return "iped-mcp jvm=" + name + " user=" + System.getProperty("user.name") + " at "
                    + java.time.Instant.now();
        }
    }
}
