package iped.mcp.audit;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpError;

/**
 * Append-only, hash-chained audit trail in JSON Lines.
 *
 * <p>
 * Two properties are deliberately kept apart, because they protect against different failures and
 * passing one does not imply passing the other (see R7 in research.md):
 *
 * <ul>
 * <li><b>Durability against a crash</b> — every line is written and {@code fsync}ed before the call
 * returns. That is this class.</li>
 * <li><b>Survival of handoff and re-imaging</b> — the trail is co-located with the case. That is
 * {@link AuditSync}.</li>
 * </ul>
 *
 * <p>
 * The workstation area is a write-ahead buffer; the case folder is the durable home.
 *
 * <p>
 * <b>Register first, act second.</b> Since the trail is append-only, an operation produces two
 * chained records: a {@code STARTED} record written <i>before</i> the operation runs, carrying the
 * parameters and, for destructive operations, the prior state; and a completion record carrying the
 * outcome and the result volume, linked back through {@code refSeq}. If the {@code STARTED} record
 * cannot be written, the operation is refused before it executes (FR-035).
 *
 * <p>
 * Charset is explicit UTF-8 (constitution, Principle V). Item names and extracted text come from
 * systems we do not control; a trail written in the platform default would be unreadable evidence.
 */
public class AuditTrail implements Closeable {

    /** Digest of the empty chain — the {@code prevHash} of the very first record. */
    public static final String GENESIS = "0000000000000000000000000000000000000000000000000000000000000000";

    private final File file;
    private final String sessionId;
    private final String operator;

    private Writer writer;
    private FileOutputStream stream;
    private long nextSeq = 1;
    private String lastHash = GENESIS;
    private boolean closed;

    /**
     * Opens (or resumes) a trail file. An existing file is read to recover the sequence number and
     * the chain head, so a resumed session continues the same chain instead of forking it.
     *
     * @throws McpError
     *             with {@link McpError#AUDIT_UNAVAILABLE} when the area cannot be written
     */
    public AuditTrail(File file, String sessionId, String operator) {
        this.file = file;
        this.sessionId = sessionId;
        this.operator = operator;
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            if (file.exists()) {
                resumeChain();
            }
            this.stream = new FileOutputStream(file, true);
            this.writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new McpError(McpError.AUDIT_UNAVAILABLE,
                    "The audit trail could not be opened at " + file.getAbsolutePath() + ": " + e.getMessage(),
                    "No operation runs without being recorded first. Make the audit area writable, or point "
                            + "auditArea in conf/McpServerConfig.txt at a location this account can write to, "
                            + "and start the server again.",
                    e).with("auditArea", file.getAbsolutePath());
        }
    }

    private void resumeChain() throws IOException {
        List<AuditRecord> existing = read(file);
        if (!existing.isEmpty()) {
            AuditRecord last = existing.get(existing.size() - 1);
            nextSeq = last.getSeq() + 1;
            lastHash = last.getHash();
        }
    }

    /**
     * Writes the write-ahead record for an operation that is about to run.
     *
     * @return the record, whose {@code seq} identifies the operation in the completion record
     * @throws McpError
     *             with {@link McpError#AUDIT_UNAVAILABLE} when the record cannot be persisted; the
     *             caller MUST NOT execute the operation in that case
     */
    public synchronized AuditRecord recordStart(String operation, Map<String, Object> parameters, String caseId,
            String caseBinding, Map<String, Object> priorState) {
        AuditRecord record = new AuditRecord();
        record.setOperation(operation);
        record.setParameters(parameters);
        record.setCaseId(caseId);
        record.setCaseBinding(caseBinding);
        record.setPriorState(priorState);
        record.setOutcome(AuditRecord.Outcome.STARTED);
        append(record);
        return record;
    }

    /**
     * Writes the completion record of an operation previously registered by
     * {@link #recordStart(String, Map, String, String, Map)}.
     */
    public synchronized AuditRecord recordEnd(AuditRecord start, AuditRecord.Outcome outcome, Integer resultVolume,
            Map<String, Object> blockedByPolicy) {
        AuditRecord record = new AuditRecord();
        record.setOperation(start.getOperation());
        record.setCaseId(start.getCaseId());
        record.setCaseBinding(start.getCaseBinding());
        record.setRefSeq(start.getSeq());
        record.setOutcome(outcome);
        record.setResultVolume(resultVolume);
        record.setBlockedByPolicy(blockedByPolicy);
        append(record);
        return record;
    }

    private void append(AuditRecord record) {
        if (closed) {
            throw new McpError(McpError.AUDIT_UNAVAILABLE, "The audit trail of this session is already closed.",
                    "Open a new session. A closed trail is never reopened for writing, by design.");
        }
        record.setSeq(nextSeq);
        record.setSessionId(sessionId);
        record.setOperator(operator);
        record.setPrevHash(lastHash);
        record.setHash(digest(record));
        try {
            writer.write(JsonRpcCodec.mapper().writeValueAsString(record.toNode()));
            writer.write('\n');
            writer.flush();
            // fsync on every operation. This is what makes a kill in the middle of a session lose
            // nothing that was already recorded.
            stream.getFD().sync();
        } catch (IOException e) {
            throw new McpError(McpError.AUDIT_UNAVAILABLE,
                    "The operation was refused because it could not be recorded in the audit trail: " + e.getMessage(),
                    "Free space or restore write permission on the audit area, then retry. The operation did "
                            + "not run.",
                    e).with("auditArea", file.getAbsolutePath());
        }
        nextSeq++;
        lastHash = record.getHash();
    }

    /** SHA-256 over the record without its own hash, chained through {@code prevHash}. */
    static String digest(AuditRecord record) {
        try {
            ObjectNode node = record.toNodeWithoutHash();
            byte[] payload = JsonRpcCodec.mapper().writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(payload);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new McpError(McpError.AUDIT_UNAVAILABLE, "The audit record could not be hashed: " + e.getMessage(),
                    "This is an internal failure of the server; report it with the server log attached.", e);
        }
    }

    /**
     * Reads a trail file back into records. Used by verification and by chain resumption.
     */
    public static List<AuditRecord> read(File file) throws IOException {
        List<AuditRecord> records = new ArrayList<>();
        if (!file.exists()) {
            return records;
        }
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode node = JsonRpcCodec.mapper().readTree(line);
            AuditRecord record = new AuditRecord();
            record.setSeq(node.path("seq").asLong());
            record.setTimestamp(node.path("timestamp").asText());
            record.setOperator(node.path("operator").asText(null));
            record.setSessionId(node.path("sessionId").asText(null));
            record.setCaseId(node.path("caseId").isNull() ? null : node.path("caseId").asText(null));
            record.setCaseBinding(node.path("caseBinding").isNull() ? null : node.path("caseBinding").asText(null));
            record.setOperation(node.path("operation").asText(null));
            if (node.has("parameters")) {
                record.setParameters(JsonRpcCodec.mapper().convertValue(node.get("parameters"), Map.class));
            }
            if (node.has("refSeq")) {
                record.setRefSeq(node.get("refSeq").asLong());
            }
            if (node.has("resultVolume")) {
                record.setResultVolume(node.get("resultVolume").asInt());
            }
            String outcome = node.path("outcome").asText(null);
            if (outcome != null && !outcome.isEmpty()) {
                record.setOutcome(AuditRecord.Outcome.valueOf(outcome));
            }
            if (node.has("priorState")) {
                record.setPriorState(JsonRpcCodec.mapper().convertValue(node.get("priorState"), Map.class));
            }
            if (node.has("blockedByPolicy")) {
                record.setBlockedByPolicy(JsonRpcCodec.mapper().convertValue(node.get("blockedByPolicy"), Map.class));
            }
            record.setPrevHash(node.path("prevHash").asText(null));
            record.setHash(node.path("hash").asText(null));
            records.add(record);
        }
        return records;
    }

    /**
     * Verifies a trail: sequence monotonic without gaps, and the hash chain intact.
     *
     * @return the description of the first problem found, or {@code null} when the trail is sound
     */
    public static String verify(List<AuditRecord> records) {
        String expectedPrev = GENESIS;
        long expectedSeq = 1;
        for (AuditRecord record : records) {
            if (record.getSeq() != expectedSeq) {
                return "sequence gap at seq " + record.getSeq() + ", expected " + expectedSeq;
            }
            if (!expectedPrev.equals(record.getPrevHash())) {
                return "broken chain at seq " + record.getSeq() + ": prevHash does not match the previous record";
            }
            String recomputed = digest(record);
            if (!recomputed.equals(record.getHash())) {
                return "tampered record at seq " + record.getSeq() + ": content does not match its own hash";
            }
            expectedPrev = record.getHash();
            expectedSeq++;
        }
        return null;
    }

    public File getFile() {
        return file;
    }

    public Path getPath() {
        return file.toPath();
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getRecordCount() {
        return nextSeq - 1;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        writer.flush();
        stream.getFD().sync();
        writer.close();
    }
}
