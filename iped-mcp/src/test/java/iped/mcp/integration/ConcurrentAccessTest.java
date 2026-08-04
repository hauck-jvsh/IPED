package iped.mcp.integration;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig.AccessMode;
import iped.mcp.protocol.McpError;

/**
 * Writing is refused while another local process holds the case; reading never is (FR-028).
 *
 * <p>
 * Decision D2 restricts this to the local machine, which is what makes a file lock sufficient.
 *
 * <p>
 * <b>A limitation worth stating.</b> IPED 4.3.1's UI takes no lock on a case it merely has open, so
 * the cooperative lock below detects another {@code iped-mcp} process reliably and the UI only when
 * it is actually holding the bookmarks state. That is the point where a conflicting write would
 * corrupt something, so the guard covers the case that matters — but "no conflict detected" is not
 * proof that no other reader exists.
 */
public class ConcurrentAccessTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp, AccessMode.READ_WRITE);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void writeIsRefusedWhileAnotherProcessHoldsTheLock() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        String auditFolder = session.config().getAuditFolderNameInCase();

        File lockFile = new File(new File(caseDir, auditFolder), "access.lock");
        Files.createDirectories(lockFile.getParentFile().toPath());

        // Stand in for the other process by taking its lock from outside the session under test.
        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw"); FileChannel channel = raf.getChannel()) {
            FileLock held = channel.lock();
            try {
                JsonNode error = session.expectError(McpError.CONCURRENT_ACCESS, "iped_create_bookmark", "case_id",
                        caseId, "name", "Should Not Be Created");
                assertTrue("the remedy must name what to close", error.path("remedy").asText().contains("Close"));
                assertTrue("and reassure that reading still works",
                        error.path("remedy").asText().contains("Reading"));
            } finally {
                held.release();
            }
        }
    }

    @Test
    public void readingIsNeverBlockedByAConcurrentHolder() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        String auditFolder = session.config().getAuditFolderNameInCase();

        File lockFile = new File(new File(caseDir, auditFolder), "access.lock");
        Files.createDirectories(lockFile.getParentFile().toPath());

        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw"); FileChannel channel = raf.getChannel()) {
            FileLock held = channel.lock();
            try {
                // Everything read-only must keep working while another process holds the case.
                assertTrue(session.call("iped_case_overview", "case_id", caseId).path("total_items").asInt() > 0);
                assertTrue(session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 5,
                        "include_snippets", false).path("total_matches").asLong() > 0);
                session.call("iped_list_bookmarks", "case_id", caseId);
                session.call("iped_list_fields", "case_id", caseId);
            } finally {
                held.release();
            }
        }
    }

    @Test
    public void theLockIsReleasedWhenTheCaseIsClosed() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);
        String auditFolder = session.config().getAuditFolderNameInCase();

        // Take the lock through a write, then close and confirm someone else can take it.
        session.raw("iped_create_bookmark", "case_id", caseId, "name", "MCP Lock Probe");
        session.raw("iped_delete_bookmark", "case_id", caseId, "name", "MCP Lock Probe");
        session.call("iped_close_case", "case_id", caseId);

        File lockFile = new File(new File(caseDir, auditFolder), "access.lock");
        if (lockFile.isFile()) {
            try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
                    FileChannel channel = raf.getChannel()) {
                FileLock acquired = channel.tryLock();
                assertTrue("closing the case must release the lock", acquired != null);
                acquired.release();
            }
        }
    }
}
