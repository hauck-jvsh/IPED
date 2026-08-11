package iped.mcp.audit;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.mcp.protocol.JsonRpcCodec;

/**
 * The roll of sessions that touched a case, append-only, alongside their trails (FR-033).
 *
 * <p>
 * <b>The problem it solves.</b> Trails already land distinctly: each session writes
 * {@code session-<uuid>.jsonl} and the synchronization copies it into the case's audit subfolder. So
 * an examiner opening that folder after two simultaneous sessions sees two files — and has no way to
 * answer the two questions that matter: <i>are these all of them?</i> and <i>in what order do I read
 * them?</i> While only one session could exist at a time, the sequence over a case was total and
 * reading them by timestamp was enough. Simultaneous sessions break that, and with it FR-037 of
 * feature 001, which requires a second examiner to reconstitute the sequence and reach the same set.
 *
 * <p>
 * One line per event, never rewritten. It carries the session identity, the transport it arrived
 * over, where it came from and both operator identities (FR-020, FR-021) — the per-session facts that
 * would be pure repetition inside every operation record, and that deliberately do <b>not</b> go into
 * {@link AuditRecord}: the order of the fields it hashes is part of verifying trails already emitted,
 * so the schema stays exactly as it was.
 */
public class SessionManifest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionManifest.class);

    public static final String FILE_NAME = "sessions.jsonl";

    /** What happened to a session with respect to one case. */
    public enum Event {
        OPENED, CLOSED
    }

    private final String sessionId;
    private final String transport;
    private final String origin;
    private final String operatorAuthoritative;
    private final String operatorClaimed;
    private final IntSupplier recordCount;

    public SessionManifest(String sessionId, String transport, String origin, String operatorAuthoritative,
            String operatorClaimed, IntSupplier recordCount) {
        this.sessionId = sessionId;
        this.transport = transport;
        this.origin = origin;
        this.operatorAuthoritative = operatorAuthoritative;
        this.operatorClaimed = operatorClaimed;
        this.recordCount = recordCount;
    }

    /**
     * Appends one line. Failure is logged and never propagated: the manifest makes a trail easier to
     * reconstitute, and losing it must not cost the operation the trail is recording. The trail
     * itself keeps its own fail-closed rule (FR-035 of feature 001).
     */
    public void record(File caseAuditDir, Event event) {
        if (caseAuditDir == null) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("session_id", sessionId);
        entry.put("event", event.name());
        entry.put("at", Instant.now().toString());
        entry.put("transport", transport);
        entry.put("origin", origin);
        entry.put("operator_authoritative", operatorAuthoritative);
        entry.put("operator_claimed", operatorClaimed);
        entry.put("operator_claimed_is_verified", false);
        entry.put("trail_file", "session-" + sessionId + ".jsonl");
        entry.put("trail_records", recordCount.getAsInt());
        try {
            Files.createDirectories(caseAuditDir.toPath());
            byte[] line = (JsonRpcCodec.mapper().writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = Files.newOutputStream(new File(caseAuditDir, FILE_NAME).toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(line);
            }
        } catch (IOException | RuntimeException e) {
            // RuntimeException as well as IOException, and not by reflex: a case folder whose path
            // the platform will not accept — a leading space is enough on Windows — makes toPath()
            // throw InvalidPathException, which is unchecked. Catching only IOException would let it
            // escape and fail the very operation this method promises never to cost anything.
            LOGGER.warn("The session manifest in {} could not be appended to; the trail itself is unaffected",
                    caseAuditDir, e);
        }
    }

    /**
     * Reads the manifest of a case.
     *
     * <p>
     * This is what makes "are these all of them?" answerable: every session that ever bound this case
     * is named here, whether or not its trail file is present. A named session with no trail beside
     * it is a loss the examiner can see, which is the same distinction FR-074 of feature 001 draws
     * for orphan trails.
     */
    public static List<Map<String, Object>> read(File caseAuditDir) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        File file = new File(caseAuditDir, FILE_NAME);
        if (!file.isFile()) {
            return entries;
        }
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode node = JsonRpcCodec.mapper().readTree(line);
            Map<String, Object> entry = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> entry.put(field.getKey(),
                    field.getValue().isNull() ? null : field.getValue().asText()));
            entries.add(entry);
        }
        return entries;
    }
}
