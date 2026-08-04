package iped.mcp.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import iped.mcp.protocol.JsonRpcCodec;

/**
 * One unit of the audit trail. Append-only and hash-chained.
 *
 * <p>
 * Field order is fixed and reproduced verbatim when the record is serialized, because the hash is
 * computed over that serialization: a stable order is what makes the chain verifiable by a second
 * examiner using nothing but the exported file.
 */
public class AuditRecord {

    /** Outcome of an operation. {@code STARTED} is the write-ahead entry. */
    public enum Outcome {
        STARTED, OK, DENIED, ERROR
    }

    private long seq;
    private String timestamp;
    private String operator;
    private String sessionId;
    private String caseId;
    private String caseBinding;
    private String operation;
    private Map<String, Object> parameters = new LinkedHashMap<>();
    private Long refSeq;
    private Integer resultVolume;
    private Outcome outcome;
    private Map<String, Object> priorState;
    private Map<String, Object> blockedByPolicy;
    private String prevHash;
    private String hash;

    public AuditRecord() {
        this.timestamp = Instant.now().toString();
    }

    /**
     * Serializes every field except {@code hash}. This is exactly the input of the chain digest.
     */
    public ObjectNode toNodeWithoutHash() {
        ObjectNode node = JsonRpcCodec.mapper().createObjectNode();
        node.put("seq", seq);
        node.put("timestamp", timestamp);
        node.put("operator", operator);
        node.put("sessionId", sessionId);
        putOrNull(node, "caseId", caseId);
        putOrNull(node, "caseBinding", caseBinding);
        node.put("operation", operation);
        node.set("parameters", JsonRpcCodec.mapper().valueToTree(parameters));
        if (refSeq != null) {
            node.put("refSeq", refSeq);
        }
        if (resultVolume != null) {
            node.put("resultVolume", resultVolume);
        }
        node.put("outcome", outcome == null ? null : outcome.name());
        if (priorState != null) {
            node.set("priorState", JsonRpcCodec.mapper().valueToTree(priorState));
        }
        if (blockedByPolicy != null) {
            node.set("blockedByPolicy", JsonRpcCodec.mapper().valueToTree(blockedByPolicy));
        }
        node.put("prevHash", prevHash);
        return node;
    }

    /**
     * Serializes the complete record, {@code hash} included. This is what a trail line contains.
     */
    public ObjectNode toNode() {
        ObjectNode node = toNodeWithoutHash();
        node.put("hash", hash);
        return node;
    }

    private static void putOrNull(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getCaseBinding() {
        return caseBinding;
    }

    public void setCaseBinding(String caseBinding) {
        this.caseBinding = caseBinding;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters == null ? new LinkedHashMap<>() : parameters;
    }

    public Long getRefSeq() {
        return refSeq;
    }

    public void setRefSeq(Long refSeq) {
        this.refSeq = refSeq;
    }

    public Integer getResultVolume() {
        return resultVolume;
    }

    public void setResultVolume(Integer resultVolume) {
        this.resultVolume = resultVolume;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public void setOutcome(Outcome outcome) {
        this.outcome = outcome;
    }

    public Map<String, Object> getPriorState() {
        return priorState;
    }

    public void setPriorState(Map<String, Object> priorState) {
        this.priorState = priorState;
    }

    public Map<String, Object> getBlockedByPolicy() {
        return blockedByPolicy;
    }

    public void setBlockedByPolicy(Map<String, Object> blockedByPolicy) {
        this.blockedByPolicy = blockedByPolicy;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}
