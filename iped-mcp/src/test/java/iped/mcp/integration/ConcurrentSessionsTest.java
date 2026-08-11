package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.protocol.McpError;
import iped.mcp.session.ConcurrencyGuard;
import iped.mcp.session.WriteClaims;

/**
 * FR-014, FR-029, FR-030 and FR-031: several sessions may read a case at once, at most one may write
 * it, and the one that writes can be named.
 *
 * <p>
 * The exclusion mechanism is the {@code access.lock} file the guard takes inside the case's audit
 * subfolder, and it needs only a folder to work — not a processed case. That is deliberate here: the
 * guarantee is about two sessions contending for the same lock, and making it depend on a reference
 * case would turn a property that reproduces in milliseconds into one that only runs on a bench
 * where someone has configured a case.
 */
public class ConcurrentSessionsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String AUDIT_FOLDER = "mcp-audit";

    @Test
    public void onlyOneSessionAtATimeHoldsTheWriteOnACase() throws Exception {
        File caseDir = temp.newFolder("case");
        WriteClaims claims = new WriteClaims();
        ConcurrencyGuard first = new ConcurrencyGuard(claims, "session-A");
        ConcurrencyGuard second = new ConcurrencyGuard(claims, "session-B");

        first.acquireWriteLock("case-1", caseDir, AUDIT_FOLDER);
        assertEquals("session-A", claims.holderOf("case-1").getSessionId());

        try {
            second.acquireWriteLock("case-1", caseDir, AUDIT_FOLDER);
            fail("a second session must not obtain the write on a case another one holds");
        } catch (McpError e) {
            assertEquals(McpError.CONCURRENT_ACCESS, e.getCode());
            // FR-029: the refusal has to name the holder. "Another session" leaves whoever receives
            // it with nowhere to go.
            assertEquals("session-A", e.getDetails().get("holderSessionId"));
            assertNotNull(e.getDetails().get("heldSince"));
            assertTrue("the diagnostic must not send the examiner looking for another process",
                    e.getMessage().contains("another session of this server"));
        }
        first.close();
        second.close();
    }

    @Test
    public void writeExclusivityIsPerCaseNotPerServer() throws Exception {
        File caseOne = temp.newFolder("case-one");
        File caseTwo = temp.newFolder("case-two");
        WriteClaims claims = new WriteClaims();
        ConcurrencyGuard first = new ConcurrencyGuard(claims, "session-A");
        ConcurrencyGuard second = new ConcurrencyGuard(claims, "session-B");

        first.acquireWriteLock("case-1", caseOne, AUDIT_FOLDER);
        // Two sessions writing different cases is not a conflict and must not be treated as one.
        second.acquireWriteLock("case-2", caseTwo, AUDIT_FOLDER);

        assertEquals("session-A", claims.holderOf("case-1").getSessionId());
        assertEquals("session-B", claims.holderOf("case-2").getSessionId());
        first.close();
        second.close();
    }

    @Test
    public void theClaimIsReleasedWhenTheHolderGoesAwayWithoutRestartingAnything() throws Exception {
        File caseDir = temp.newFolder("case");
        WriteClaims claims = new WriteClaims();
        ConcurrencyGuard first = new ConcurrencyGuard(claims, "session-A");
        ConcurrencyGuard second = new ConcurrencyGuard(claims, "session-B");

        first.acquireWriteLock("case-1", caseDir, AUDIT_FOLDER);
        // The single teardown path, which runs the same way for a normal end and for a dropped
        // connection (FR-030).
        first.close();
        assertNull("a closed session must not leave a case claimed by nobody", claims.holderOf("case-1"));

        second.acquireWriteLock("case-1", caseDir, AUDIT_FOLDER);
        assertEquals("session-B", claims.holderOf("case-1").getSessionId());
        second.close();
    }

    @Test
    public void reacquiringWithinTheSameSessionIsIdempotent() throws Exception {
        File caseDir = temp.newFolder("case");
        WriteClaims claims = new WriteClaims();
        ConcurrencyGuard guard = new ConcurrencyGuard(claims, "session-A");

        guard.acquireWriteLock("case-1", caseDir, AUDIT_FOLDER);
        guard.acquireWriteLock("case-1", caseDir, AUDIT_FOLDER);
        assertEquals("session-A", claims.holderOf("case-1").getSessionId());
        guard.close();
        assertNull(claims.holderOf("case-1"));
    }

    @Test
    public void releasingAClaimHeldByAnotherSessionDoesNothing() {
        WriteClaims claims = new WriteClaims();
        claims.record("case-1", "session-A");
        claims.release("case-1", "session-B");
        assertEquals("a session must not be able to drop a claim it does not hold", "session-A",
                claims.holderOf("case-1").getSessionId());
    }
}
