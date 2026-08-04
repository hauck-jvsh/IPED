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
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.AccessMode;
import iped.mcp.egress.EgressPolicy;

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
    private final String operator;
    private final McpServerConfig config;
    private final AuditTrail auditTrail;
    private final AuditSync auditSync;
    private final EgressPolicy egressPolicy;
    private final ConcurrencyGuard concurrencyGuard;
    private final CaseRegistry caseRegistry;
    private final List<String> openingWarnings = new ArrayList<>();

    public Session(McpServerConfig config) {
        this.config = config;
        // D2: single-operator workstation, no authentication of its own. The operator is the
        // account the process runs under, which is what the audit trail records.
        this.operator = System.getProperty("user.name", "unknown");
        this.auditTrail = new AuditTrail(new File(config.getAuditArea(), "session-" + sessionId + ".jsonl"), sessionId,
                operator);
        this.auditSync = new AuditSync(auditTrail, config.getAuditFolderNameInCase(),
                config.getAuditSyncIntervalSeconds());
        this.auditSync.start();
        this.egressPolicy = new EgressPolicy(config);
        this.concurrencyGuard = new ConcurrencyGuard();
        this.caseRegistry = new CaseRegistry(config, auditSync, concurrencyGuard);

        openingWarnings.add(egressPolicy.openingWarning());
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

    public String getOperator() {
        return operator;
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

    /** Full session state, as returned by {@code iped_session_info}. */
    public Map<String, Object> describe() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("session_id", sessionId);
        info.put("operator", operator);
        info.put("started_at", startedAt.toString());
        info.put("access_mode", getAccessMode().name());
        info.put("egress_policy", egressPolicy.describe());

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
            LOGGER.info("MCP session {} closed after {} audit records", sessionId, auditTrail.getRecordCount());
        }
    }
}
