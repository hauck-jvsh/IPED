package iped.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import iped.mcp.audit.AuditRecord;
import iped.mcp.audit.AuditTrail;
import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.session.Session;

/**
 * {@code iped_export_audit}: hands out a copy of this session's trail in JSON Lines (FR-036).
 *
 * <p>
 * <b>This is not the durability mechanism.</b> The trail is written and fsynced on every operation
 * and synchronized into the case folder automatically (FR-071, FR-072). This tool serves one-off
 * deliveries — attaching the trail to a report, sending it to a second examiner — and nothing here
 * is required for the trail to survive.
 *
 * <p>
 * There is deliberately no tool that writes to the trail. It is append-only by the server and not
 * alterable by the agent (FR-034).
 */
public class AuditTools {

    private final Session session;

    public AuditTools(Session session) {
        this.session = session;
    }

    public List<ToolDescriptor> descriptors() {
        return Collections.singletonList(new ToolDescriptor("iped_export_audit",
                "Writes a copy of this session's audit trail to a path you choose, in JSON Lines, and verifies "
                        + "the hash chain on the way out. The trail is already preserved automatically; this is "
                        + "for handing it to someone.",
                arguments -> export(new File(Args.requiredString(arguments, "destination",
                        "Pass an absolute file path to write the trail to, ending in .jsonl."))))
                                .required("destination", "string", "Absolute path of the file to write."));
    }

    private Map<String, Object> export(File destination) {
        AuditTrail trail = session.getAuditTrail();
        try {
            Path parent = destination.toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(trail.getPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new McpError(McpError.EXPORT_FAILED,
                    "The trail could not be written to " + destination.getAbsolutePath() + ": " + e.getMessage(),
                    "Check that the folder exists and is writable, then retry. The trail itself is unaffected: "
                            + "it stays at " + trail.getFile().getAbsolutePath() + ".",
                    e);
        }

        List<AuditRecord> records;
        String integrity;
        try {
            records = AuditTrail.read(destination);
            String problem = AuditTrail.verify(records);
            integrity = problem == null ? "intact" : problem;
        } catch (IOException e) {
            records = new ArrayList<>();
            integrity = "could not be verified: " + e.getMessage();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", destination.getAbsolutePath());
        result.put("records", records.size());
        result.put("chain", integrity);
        result.put("session_id", trail.getSessionId());
        result.put("note", "The exported copy is a snapshot. The live trail continues at "
                + trail.getFile().getAbsolutePath() + " and is synchronized into the case folder automatically; "
                + "this export is not what keeps it safe.");
        return result;
    }
}
