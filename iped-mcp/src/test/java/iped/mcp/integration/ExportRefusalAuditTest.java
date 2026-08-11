package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.After;
import org.junit.Before;
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
import iped.mcp.protocol.ToolDescriptor;

/**
 * FR-007: a destination refused for being outside the declared write roots is recorded with the rule
 * that refused it.
 *
 * <p>
 * A refusal is a decision, not a failure, and the trail has to be able to show what was attempted and
 * why it was stopped — the same treatment FR-041 already gives to content blocked by the egress
 * policy. Recording it as a plain error would lose the rule, and the rule is the part that answers
 * "why was this refused" months later.
 *
 * <p>
 * The refusal is raised by a tool registered for the test rather than by a real export, so the suite
 * runs without a processed case. What is under test is the dispatcher's treatment of the refusal, and
 * that treatment does not know which tool raised it.
 */
public class ExportRefusalAuditTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private McpServerMain server;

    @Before
    public void setUp() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        server = new McpServerMain(config);
        server.getDispatcher().registerAll(Collections.singletonList(
                new ToolDescriptor("test_refuses_destination", "Raises a destination refusal.", arguments -> {
                    throw new McpError(McpError.DESTINATION_REFUSED,
                            "The destination is outside every folder this server may write to.",
                            "Write the artifact under one of the permitted folders.")
                                    .with("destination", "C:\\somewhere\\else\\report.xlsx")
                                    .with("rule", "OUTSIDE_ROOTS");
                })));
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void aRefusedDestinationIsRecordedAsDeniedWithTheRule() throws Exception {
        ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
        arguments.put("destination", "C:\\somewhere\\else\\report.xlsx");
        call("test_refuses_destination", arguments);

        List<AuditRecord> records = AuditTrail.read(server.getSession().getAuditTrail().getFile());
        AuditRecord outcome = records.get(records.size() - 1);

        assertEquals("a refusal is a decision, not a failure", AuditRecord.Outcome.DENIED, outcome.getOutcome());
        assertNotNull("the rule that refused it belongs in the record", outcome.getBlockedByPolicy());
        assertEquals("OUTSIDE_ROOTS", outcome.getBlockedByPolicy().get("rule"));
        assertEquals(McpError.DESTINATION_REFUSED, outcome.getBlockedByPolicy().get("code"));
        assertEquals("C:\\somewhere\\else\\report.xlsx", outcome.getBlockedByPolicy().get("destination"));
    }

    @Test
    public void theAttemptedDestinationIsInTheStartRecord() throws Exception {
        ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
        arguments.put("destination", "C:\\somewhere\\else\\report.xlsx");
        call("test_refuses_destination", arguments);

        List<AuditRecord> records = AuditTrail.read(server.getSession().getAuditTrail().getFile());
        AuditRecord start = records.get(records.size() - 2);
        assertEquals(AuditRecord.Outcome.STARTED, start.getOutcome());
        assertTrue("what was asked for has to be legible from the trail alone",
                String.valueOf(start.getParameters().get("destination")).contains("report.xlsx"));
    }

    private void call(String tool, ObjectNode arguments) throws Exception {
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);
        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);
        try (JsonRpcCodec codec = new JsonRpcCodec(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream())) {
            server.getDispatcher().dispatch(request, codec);
        }
    }
}
