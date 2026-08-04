package iped.mcp.session;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import iped.engine.data.IPEDSource;
import iped.mcp.audit.AuditSync;
import iped.mcp.query.FieldVocabulary;

/**
 * A case that has been validated and opened. Read-only handle onto the engine plus everything the
 * tool surface needs to answer without reopening anything.
 */
public class OpenCase implements AutoCloseable {

    private final String caseId;
    private final String caseBinding;
    private final File casePath;
    private final String ipedVersion;
    private final IPEDSource source;
    private final FieldVocabulary vocabulary;
    private final Instant openedAt = Instant.now();
    private final List<String> warnings = new ArrayList<>();
    private AuditSync.Target syncTarget;

    OpenCase(String caseId, String caseBinding, File casePath, String ipedVersion, IPEDSource source) {
        this.caseId = caseId;
        this.caseBinding = caseBinding;
        this.casePath = casePath;
        this.ipedVersion = ipedVersion;
        this.source = source;
        this.vocabulary = new FieldVocabulary(source);
    }

    public String getCaseId() {
        return caseId;
    }

    public String getCaseBinding() {
        return caseBinding;
    }

    public File getCasePath() {
        return casePath;
    }

    public String getIpedVersion() {
        return ipedVersion;
    }

    public IPEDSource getSource() {
        return source;
    }

    public FieldVocabulary getVocabulary() {
        return vocabulary;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public int getTotalItems() {
        return source.getTotalItems();
    }

    public List<String> getWarnings() {
        return warnings;
    }

    void addWarning(String warning) {
        warnings.add(warning);
    }

    public AuditSync.Target getSyncTarget() {
        return syncTarget;
    }

    void setSyncTarget(AuditSync.Target syncTarget) {
        this.syncTarget = syncTarget;
    }

    /** Evidences composing this case, with their names. */
    public List<Map<String, Object>> describeEvidences() {
        List<Map<String, Object>> evidences = new ArrayList<>();
        for (String uuid : source.getEvidenceUUIDs()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("evidenceUUID", uuid);
            evidences.add(evidence);
        }
        return evidences;
    }

    @Override
    public void close() {
        source.close();
    }
}
