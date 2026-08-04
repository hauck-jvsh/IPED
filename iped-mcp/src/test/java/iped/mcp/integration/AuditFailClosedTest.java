package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.audit.AuditRecord;
import iped.mcp.audit.AuditTrail;
import iped.mcp.config.McpServerConfig;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpError;

/**
 * Nothing executes without being recorded first (FR-035), reads included.
 *
 * <p>
 * Auditing reads is what makes the trail blocking even for a read-only session, and that is
 * deliberate: a second examiner reproducing the work needs the queries, not only the changes.
 */
public class AuditFailClosedTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void anUnwritableAuditAreaStopsTheSessionFromStarting() throws Exception {
        McpServerConfig config = new McpServerConfig();
        config.setAuditArea(new File(temp.newFile("blocker"), "audit"));

        try {
            new McpServerMain(config).close();
            org.junit.Assert.fail("a session must not start when it cannot record");
        } catch (McpError e) {
            assertEquals(McpError.AUDIT_UNAVAILABLE, e.getCode());
            assertNotNull(e.getRemedy());
            assertTrue("the remedy must name the setting to change: " + e.getRemedy(),
                    e.getRemedy().contains("auditArea"));
        }
    }

    @Test
    public void readOperationsAreRecordedBeforeTheyRun() throws Exception {
        try (McpServerMain server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()))) {
            call(server, "iped_session_info", JsonRpcCodec.mapper().createObjectNode());

            List<AuditRecord> records = AuditTrail.read(server.getSession().getAuditTrail().getFile());
            assertEquals("a read produces a write-ahead record and a completion record", 2, records.size());
            assertEquals(AuditRecord.Outcome.STARTED, records.get(0).getOutcome());
            assertEquals("iped_session_info", records.get(0).getOperation());
            assertEquals(AuditRecord.Outcome.OK, records.get(1).getOutcome());
            assertEquals(Long.valueOf(records.get(0).getSeq()), records.get(1).getRefSeq());
        }
    }

    @Test
    public void theWriteAheadRecordPrecedesTheOutcome() throws Exception {
        try (McpServerMain server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()))) {
            ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
            arguments.put("case_path", new File(temp.getRoot(), "no-such-case").getAbsolutePath());
            JsonNode response = call(server, "iped_open_case", arguments);

            assertTrue("opening a missing case must fail", response.has("error"));

            List<AuditRecord> records = AuditTrail.read(server.getSession().getAuditTrail().getFile());
            // The attempt is recorded even though it failed: the trail describes what was tried,
            // not only what succeeded.
            assertEquals(AuditRecord.Outcome.STARTED, records.get(0).getOutcome());
            assertEquals(AuditRecord.Outcome.ERROR, records.get(1).getOutcome());
            assertTrue("the attempted parameters must be recorded",
                    records.get(0).getParameters().containsKey("case_path"));
        }
    }

    @Test
    public void refusedWritesAreRecordedAsDenied() throws Exception {
        try (McpServerMain server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()))) {
            ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
            arguments.put("case_id", "whatever");
            arguments.put("name", "Findings");
            JsonNode response = call(server, "iped_create_bookmark", arguments);

            assertEquals(McpError.WRITE_NOT_ENABLED, response.path("error").path("data").path("code").asText());

            List<AuditRecord> records = AuditTrail.read(server.getSession().getAuditTrail().getFile());
            assertEquals(2, records.size());
            assertEquals("a refusal is an operation too and must be in the trail",
                    AuditRecord.Outcome.DENIED, records.get(1).getOutcome());
            assertFalse("nothing may run after a refusal",
                    records.stream().anyMatch(record -> record.getOutcome() == AuditRecord.Outcome.OK));
        }
    }

    @Test
    public void theTrailIsOnDiskImmediately() throws Exception {
        // Not buffered until close: an abnormal termination must lose nothing already recorded.
        try (McpServerMain server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()))) {
            call(server, "iped_session_info", JsonRpcCodec.mapper().createObjectNode());

            File trailFile = server.getSession().getAuditTrail().getFile();
            assertTrue("the trail file must exist while the session is still open", trailFile.isFile());
            assertEquals("its records must be readable by another process right away", 2,
                    AuditTrail.read(trailFile).size());
        }
    }

    private static JsonNode call(McpServerMain server, String tool, ObjectNode arguments) throws Exception {
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);
        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);
        try (JsonRpcCodec codec = new JsonRpcCodec(new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream())) {
            return server.getDispatcher().dispatch(request, codec);
        }
    }
}
