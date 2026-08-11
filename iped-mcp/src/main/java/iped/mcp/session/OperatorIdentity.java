package iped.mcp.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who conducted an operation: what the server can prove, and what the client said (FR-020).
 *
 * <p>
 * Feature 001 recorded a single operator, the account the process runs under, and that was accurate
 * while decision D2 held — one examiner, one workstation, no remote session possible. Over a network
 * transport the term loses its referent: the account running the server may be a service account,
 * and the person at the harness is someone else entirely.
 *
 * <p>
 * So there are two, and they never merge. {@link #getAuthoritative()} is verified — it is the
 * process's own account. {@link #getClaimed()} is what arrived in the handshake and is <b>not</b>
 * verified by anything; the shared secret proves the connection was authorized, not that whoever
 * typed the name is who they say. Presenting the second as though it were the first would put an
 * unverified assertion into a document whose entire purpose is to be verifiable, which is worse than
 * recording no name at all (FR-032).
 */
public final class OperatorIdentity {

    private final String authoritative;
    private final String claimed;

    private OperatorIdentity(String authoritative, String claimed) {
        this.authoritative = authoritative;
        this.claimed = claimed;
    }

    /** The local operator: no claim, because none was made. */
    public static OperatorIdentity ofProcess() {
        return new OperatorIdentity(System.getProperty("user.name", "unknown"), null);
    }

    /**
     * The process account plus what the client declared. An empty or absent claim stays absent — it
     * is never filled in from the authoritative identity, which would manufacture a claim nobody
     * made.
     */
    public static OperatorIdentity withClaim(String claimed) {
        String trimmed = claimed == null ? null : claimed.trim();
        return new OperatorIdentity(System.getProperty("user.name", "unknown"),
                trimmed == null || trimmed.isEmpty() ? null : trimmed);
    }

    /** The account the server process runs under. Verified. */
    public String getAuthoritative() {
        return authoritative;
    }

    /** What the client said it was, or {@code null} when it said nothing. Unverified. */
    public String getClaimed() {
        return claimed;
    }

    public boolean hasClaim() {
        return claimed != null;
    }

    /**
     * How the pair is rendered wherever the trail is read by a person (FR-032).
     *
     * <p>
     * The word "unverified" is part of the value, not of the surrounding documentation, because the
     * value is what survives being copied into a report.
     */
    public String describe() {
        return claimed == null ? authoritative : authoritative + " (client claims: " + claimed + ", unverified)";
    }

    /** The pair as separate, named fields, for the machine-readable surfaces. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("authoritative", authoritative);
        map.put("claimed", claimed);
        map.put("claimed_is_verified", false);
        return map;
    }
}
