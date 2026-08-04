package iped.mcp.session;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.data.IPEDSource;
import iped.mcp.audit.AuditSync;
import iped.mcp.config.McpServerConfig;
import iped.mcp.protocol.McpError;

/**
 * The cases open in a session.
 *
 * <p>
 * Opening is idempotent: reopening an already open case succeeds and returns the same
 * {@code caseId} (FR-004). Closing releases the engine handle without leaving a lock on the folder
 * (FR-005).
 *
 * <p>
 * Opening is also where an orphan trail is reported (FR-074) — a previous trail for this case
 * sitting on the workstation with no counterpart inside the case folder. Reporting it here is the
 * point: it turns a loss that would otherwise be discovered at the moment the trail was needed into
 * a loss noticed at the start of the next examination.
 */
public class CaseRegistry implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseRegistry.class);

    private final McpServerConfig config;
    private final CaseValidator validator;
    private final AuditSync auditSync;
    private final ConcurrencyGuard concurrencyGuard;
    private final Map<String, OpenCase> openCases = new LinkedHashMap<>();

    public CaseRegistry(McpServerConfig config, AuditSync auditSync, ConcurrencyGuard concurrencyGuard) {
        this.config = config;
        this.validator = new CaseValidator(config.getSupportedVersionPrefix());
        this.auditSync = auditSync;
        this.concurrencyGuard = concurrencyGuard;
    }

    /**
     * Validates and opens a case, or returns the one already open for that path.
     *
     * @throws McpError
     *             with a specific diagnostic when the folder is not a usable case
     */
    public synchronized OpenCase open(File casePath) {
        CaseValidator.ValidatedCase validated = validator.validate(casePath);
        OpenCase existing = openCases.get(validated.caseId());
        if (existing != null) {
            return existing;
        }

        IPEDSource source;
        try {
            // askImagePathIfNotFound = false: a server with no console must never block on a
            // dialog. A portable case with missing evidence stays queryable for metadata; raw
            // content declares its unavailability when asked (FR-022).
            source = new IPEDSource(validated.caseDir, null, false);
        } catch (RuntimeException e) {
            throw new McpError(McpError.CASE_INACCESSIBLE,
                    "The case at " + casePath.getAbsolutePath() + " could not be opened: " + e.getMessage(),
                    "The folder looks like a case but the engine refused it. If the message mentions a missing "
                            + "image, this is a portable case whose evidence files are not present: metadata "
                            + "stays queryable, raw content does not. Otherwise check the server log for the "
                            + "underlying failure.",
                    e).with("path", casePath.getAbsolutePath());
        }

        OpenCase openCase = new OpenCase(validated.caseId(), validated.caseBinding(), validated.caseDir,
                validated.ipedVersion, source);

        List<String> orphans = AuditSync.findOrphanTrails(config.getAuditArea(), validated.caseBinding(),
                validated.caseDir, config.getAuditFolderNameInCase());
        if (!orphans.isEmpty()) {
            String warning = "An earlier audit trail for this case exists on this workstation but has no "
                    + "counterpart inside the case folder: " + String.join(", ", orphans)
                    + ". It was never co-located, or the case folder's audit subfolder was removed. Recover it "
                    + "from " + config.getAuditArea().getAbsolutePath() + " before it is lost with the station.";
            openCase.addWarning(warning);
            LOGGER.warn(warning);
        }

        AuditSync.Target target = auditSync.bindCase(validated.caseId(), validated.caseBinding(), validated.caseDir);
        openCase.setSyncTarget(target);
        if (target.state == AuditSync.SyncState.STAGED) {
            openCase.addWarning("The audit trail cannot be co-located with this case: "
                    + target.degradationReason
                    + ". The copy at " + config.getAuditArea().getAbsolutePath()
                    + " is authoritative for this session. Copy it alongside the case before archiving, or the "
                    + "trail stays behind on this workstation.");
        }

        openCases.put(openCase.getCaseId(), openCase);
        return openCase;
    }

    /**
     * @throws McpError
     *             with {@link McpError#CASE_NOT_OPEN} naming the cases that are open
     */
    public synchronized OpenCase require(String caseId) {
        OpenCase openCase = openCases.get(caseId);
        if (openCase == null) {
            throw new McpError(McpError.CASE_NOT_OPEN, "No case is open under the id " + caseId + ".",
                    openCases.isEmpty()
                            ? "Call iped_open_case with the case folder path first. No case is open in this session."
                            : "Use one of the open case ids, or call iped_open_case for another case.")
                                    .with("openCaseIds", openCases.keySet());
        }
        return openCase;
    }

    public synchronized Collection<OpenCase> getOpenCases() {
        return openCases.values();
    }

    public synchronized void close(String caseId) {
        OpenCase openCase = require(caseId);
        concurrencyGuard.release(caseId);
        auditSync.unbindCase(caseId);
        openCases.remove(caseId);
        openCase.close();
    }

    @Override
    public synchronized void close() {
        for (String caseId : openCases.keySet().toArray(new String[0])) {
            try {
                close(caseId);
            } catch (RuntimeException e) {
                LOGGER.warn("Failure while closing case {}", caseId, e);
            }
        }
    }
}
