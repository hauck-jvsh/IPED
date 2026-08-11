package iped.mcp.session;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.mcp.audit.AuditSync;
import iped.mcp.audit.AuditTrail;
import iped.mcp.audit.SessionManifest;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.AccessMode;
import iped.mcp.egress.EgressPolicy;
import iped.mcp.transport.Transport;

/**
 * The working context of the server process. One session per process.
 *
 * <p>
 * Access mode is {@code READ_ONLY} by default and is set outside the agent's reach: no exposed tool
 * can change it (FR-025). The egress policy in force and the audit trail are likewise session-wide.
 *
 * <p>
 * At open, the session states what evidence content may be transmitted under the current
 * configuration (FR-043), plus any warning that came out of opening a case — a trail that could not
 * be co-located, or an orphan trail found on the workstation.
 */
public class Session implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Session.class);

    private final String sessionId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();
    private final OperatorIdentity operator;
    private final McpServerConfig config;
    private final AuditTrail auditTrail;
    private final AuditSync auditSync;
    private final EgressPolicy egressPolicy;
    private final ConcurrencyGuard concurrencyGuard;
    private final CaseRegistry caseRegistry;
    private final CasePool casePool;
    private final boolean ownsCasePool;
    private final Transport.Kind transport;
    private final String origin;
    private final List<String> openingWarnings = new ArrayList<>();

    /**
     * A session over the local transport, owning everything it uses. This is the shape the process
     * has when it is serving one stdio client, and the shape the suites build.
     */
    public Session(McpServerConfig config) {
        this(config, new CasePool(), new WriteClaims(), Transport.Kind.STDIO, null, null, true);
    }

    /**
     * A session over a connection, sharing the process's case pool and write register with the other
     * sessions (FR-014).
     *
     * @param origin
     *            where the connection came from, recorded for FR-021; {@code null} for stdio
     * @param claimedOperator
     *            the identity declared in the handshake, unverified; {@code null} when none was
     */
    public Session(McpServerConfig config, CasePool casePool, WriteClaims writeClaims, Transport.Kind transport,
            String origin, String claimedOperator) {
        this(config, casePool, writeClaims, transport, origin, claimedOperator, false);
    }

    private Session(McpServerConfig config, CasePool casePool, WriteClaims writeClaims, Transport.Kind transport,
            String origin, String claimedOperator, boolean ownsCasePool) {
        this.config = config;
        this.casePool = casePool;
        this.ownsCasePool = ownsCasePool;
        this.transport = transport;
        this.origin = origin;
        // Two identities, never merged: the account this process runs under, which is verified, and
        // what the client said, which is not (FR-020). Under the local transport there is no claim.
        this.operator = transport == Transport.Kind.STDIO ? OperatorIdentity.ofProcess()
                : OperatorIdentity.withClaim(claimedOperator);
        // The trail's operator field carries the pair as one rendered string rather than gaining a
        // column. Appending a field would change what AuditRecord hashes, and the order of those
        // fields is part of the verification of trails already emitted.
        this.auditTrail = new AuditTrail(new File(config.getAuditArea(), "session-" + sessionId + ".jsonl"), sessionId,
                operator.describe());
        this.auditSync = new AuditSync(auditTrail, config.getAuditFolderNameInCase(),
                config.getAuditSyncIntervalSeconds());
        this.auditSync.start();
        this.egressPolicy = new EgressPolicy(config);
        this.concurrencyGuard = new ConcurrencyGuard(writeClaims, sessionId);
        // Per-session facts — transport, origin, both identities — live here rather than being
        // repeated inside every operation record, and rather than being added to AuditRecord, whose
        // field order is part of verifying trails already emitted.
        SessionManifest manifest = new SessionManifest(sessionId, transport.name(), origin,
                operator.getAuthoritative(), operator.getClaimed(), () -> (int) auditTrail.getRecordCount());
        this.caseRegistry = new CaseRegistry(config, auditSync, concurrencyGuard, casePool, manifest);

        openingWarnings.add(egressPolicy.openingWarning());
        if (transport == Transport.Kind.SOCKET) {
            // The examiner is entitled to know this before opening a case: under the local transport
            // evidence content never left the process, and now it crosses a wire that nothing
            // protects (FR-023).
            openingWarnings.add("This session arrived over a network connection. Evidence content returned by "
                    + "these tools — item text, thumbnails and raw bytes — travels over that connection, and "
                    + "the channel is not encrypted. Keep the traffic inside one physical machine or a trusted "
                    + "segment, or enable the egress policy to restrict what may leave at all.");
        }
        if (config.getAccessMode() == AccessMode.READ_ONLY) {
            openingWarnings.add("Access mode is READ_ONLY. Curation tools are refused without touching the case. "
                    + "Enabling writes is done outside this conversation, in conf/McpServerConfig.txt.");
        } else {
            openingWarnings.add("Access mode is READ_WRITE. Bookmark and selection changes will be written into "
                    + "the case, each one recorded in the audit trail with its prior state.");
        }
        LOGGER.info("MCP session {} started for operator {} in {} mode", sessionId, operator, config.getAccessMode());
    }

    public String getSessionId() {
        return sessionId;
    }

    public OperatorIdentity getOperator() {
        return operator;
    }

    public Transport.Kind getTransport() {
        return transport;
    }

    /** Where the connection came from, or {@code null} under the local transport. */
    public String getOrigin() {
        return origin;
    }

    public CasePool getCasePool() {
        return casePool;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public McpServerConfig getConfig() {
        return config;
    }

    public AccessMode getAccessMode() {
        return config.getAccessMode();
    }

    public AuditTrail getAuditTrail() {
        return auditTrail;
    }

    public AuditSync getAuditSync() {
        return auditSync;
    }

    public EgressPolicy getEgressPolicy() {
        return egressPolicy;
    }

    public ConcurrencyGuard getConcurrencyGuard() {
        return concurrencyGuard;
    }

    public CaseRegistry getCaseRegistry() {
        return caseRegistry;
    }

    /** Warnings issued at open, plus anything a case open added since. */
    public List<String> getWarnings() {
        List<String> warnings = new ArrayList<>(openingWarnings);
        for (OpenCase openCase : caseRegistry.getOpenCases()) {
            warnings.addAll(openCase.getWarnings());
        }
        return warnings;
    }

    /**
     * What is exposed and what may be written, answerable from inside the session (FR-022).
     *
     * <p>
     * A security posture that cannot be checked from inside is one nobody trusts, and the examiner
     * is the person who signs the report. It answers with the network transport inactive too — the
     * absence of a listening endpoint is itself the fact worth confirming.
     */
    public Map<String, Object> describePosture() {
        Map<String, Object> posture = new LinkedHashMap<>();
        posture.put("transport", transport.name());
        posture.put("origin", origin);
        posture.put("listen_endpoint", transport == Transport.Kind.SOCKET ? config.describeListenEndpoint() : null);
        posture.put("channel_protected", false);

        List<Map<String, Object>> roots = new ArrayList<>();
        for (McpServerConfig.WriteRoot root : config.getWriteRoots()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("declared", root.getDeclared());
            entry.put("resolved", root.getResolved() == null ? null : root.getResolved().toString());
            entry.put("state", root.getState().name());
            roots.add(entry);
        }
        posture.put("write_roots", roots);
        posture.put("write_roots_are_declared", !config.getExportRoots().isEmpty());
        posture.put("allow_export_into_case_folder", config.isAllowExportIntoCaseFolder());

        List<Map<String, Object>> claims = new ArrayList<>();
        for (OpenCase openCase : caseRegistry.getOpenCases()) {
            WriteClaims.Claim claim = concurrencyGuard.getWriteClaims().holderOf(openCase.getCaseId());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("case_id", openCase.getCaseId());
            entry.put("write_held_by", claim == null ? null : claim.getSessionId());
            entry.put("write_held_by_this_session", claim != null && claim.getSessionId().equals(sessionId));
            entry.put("sessions_holding_case", casePool.referenceCount(openCase.getCaseId()));
            claims.add(entry);
        }
        posture.put("write_claims", claims);
        return posture;
    }

    /** Full session state, as returned by {@code iped_session_info}. */
    public Map<String, Object> describe() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("session_id", sessionId);
        info.put("operator", operator.toMap());
        info.put("started_at", startedAt.toString());
        info.put("access_mode", getAccessMode().name());
        info.put("egress_policy", egressPolicy.describe());
        info.put("posture", describePosture());

        List<Map<String, Object>> cases = new ArrayList<>();
        for (OpenCase openCase : caseRegistry.getOpenCases()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("case_id", openCase.getCaseId());
            entry.put("case_path", openCase.getCasePath().getAbsolutePath());
            entry.put("iped_version", openCase.getIpedVersion());
            entry.put("total_items", openCase.getTotalItems());
            entry.put("opened_at", openCase.getOpenedAt().toString());
            AuditSync.Target target = openCase.getSyncTarget();
            entry.put("audit_sync_state", target == null ? "STAGED" : target.state.name());
            if (target != null && target.degradationReason != null) {
                entry.put("audit_sync_degradation", target.degradationReason);
            }
            cases.add(entry);
        }
        info.put("open_cases", cases);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("staging_location", auditTrail.getFile().getAbsolutePath());
        audit.put("audit_folder_name_in_case", config.getAuditFolderNameInCase());
        audit.put("records", auditTrail.getRecordCount());
        info.put("audit_trail", audit);

        info.put("warnings", getWarnings());
        return info;
    }

    @Override
    public void close() throws IOException {
        try {
            caseRegistry.close();
        } finally {
            concurrencyGuard.close();
            // Close the trail before the final synchronization, so what is co-located is the
            // complete file rather than one missing its last lines.
            auditTrail.close();
            auditSync.close();
            if (ownsCasePool) {
                // Only the local transport owns its pool. Under the network transport the pool
                // belongs to the process and outlives any one connection — closing it here would
                // pull the engine handle out from under the other sessions.
                casePool.close();
            }
            LOGGER.info("MCP session {} closed after {} audit records", sessionId, auditTrail.getRecordCount());
        }
    }
}
