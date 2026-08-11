package iped.mcp.session;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which session holds the write on which case, for this process (FR-029).
 *
 * <p>
 * <b>This does not enforce anything.</b> Exclusion is still the {@code access.lock} file that
 * {@link ConcurrencyGuard} takes inside the case's audit subfolder, and it already covers the case
 * this feature introduces: two sessions in the same JVM open distinct channels over the same file,
 * and the second gets {@code OverlappingFileLockException}, which the guard already converts into
 * {@code CONCURRENT_ACCESS}. Replacing that with an in-memory semaphore would lose detection between
 * separate {@code iped-mcp} processes, which is the thing the lock file exists for.
 *
 * <p>
 * What this adds is the <i>name</i> of the holder. FR-029 requires the refusal to identify the
 * session holding the write, and reading it out of the lock file is not dependable while that file
 * is locked. Keeping the mapping alongside the lock costs nothing and makes the diagnostic
 * actionable: "another session" is a dead end for whoever receives it, "session 7f3a… since 14:02"
 * is not.
 */
public class WriteClaims {

    /** One case's writer. */
    public static final class Claim {

        private final String caseId;
        private final String sessionId;
        private final Instant since;

        Claim(String caseId, String sessionId, Instant since) {
            this.caseId = caseId;
            this.sessionId = sessionId;
            this.since = since;
        }

        public String getCaseId() {
            return caseId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public Instant getSince() {
            return since;
        }
    }

    private final Map<String, Claim> claims = new LinkedHashMap<>();

    /** Records that a session has taken the write on a case. Called after the lock is held. */
    public synchronized void record(String caseId, String sessionId) {
        claims.put(caseId, new Claim(caseId, sessionId, Instant.now()));
    }

    /** Drops a claim, but only if the given session is the one that holds it. */
    public synchronized void release(String caseId, String sessionId) {
        Claim claim = claims.get(caseId);
        if (claim != null && claim.getSessionId().equals(sessionId)) {
            claims.remove(caseId);
        }
    }

    /**
     * Drops every claim a session holds.
     *
     * <p>
     * Called from the single teardown path, so a session that dropped its connection mid-operation
     * does not leave a case claimed by nobody (FR-030).
     */
    public synchronized void releaseAll(String sessionId) {
        claims.entrySet().removeIf(entry -> entry.getValue().getSessionId().equals(sessionId));
    }

    /** The session holding the write on a case, or {@code null} when no session in this process is. */
    public synchronized Claim holderOf(String caseId) {
        return claims.get(caseId);
    }
}
