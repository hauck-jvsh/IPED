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
 * A case that has been validated and opened, as one session sees it.
 *
 * <p>
 * The engine handle and the vocabulary underneath are <b>shared</b> with any other session that has
 * the same case open — see {@link CasePool}. What is per-session is the rest: the warnings raised
 * when this session opened it, and where this session's audit trail is being synchronized. Closing
 * therefore drops a reference rather than closing the engine handle, because another session may
 * still be reading through it.
 */
public class OpenCase implements AutoCloseable {

    private final String caseId;
    private final String caseBinding;
    private final File casePath;
    private final String ipedVersion;
    private final IPEDSource source;
    private final FieldVocabulary vocabulary;
    private final Runnable onClose;
    private final Instant openedAt = Instant.now();
    private final List<String> warnings = new ArrayList<>();
    private AuditSync.Target syncTarget;

    OpenCase(String caseId, String caseBinding, File casePath, String ipedVersion, CasePool.Handle handle,
            Runnable onClose) {
        this.caseId = caseId;
        this.caseBinding = caseBinding;
        this.casePath = casePath;
        this.ipedVersion = ipedVersion;
        this.source = handle.getSource();
        this.vocabulary = handle.getVocabulary();
        this.onClose = onClose;
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

    /**
     * Drops this session's reference. The engine handle closes when the last session lets go, not
     * here — another session reading the same case must not lose its searcher underneath it.
     */
    @Override
    public void close() {
        onClose.run();
    }
}
