package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
import iped.mcp.config.McpServerConfig.ContentClass;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpError;

/**
 * The egress policy in both of its modes (SC-014).
 *
 * <p>
 * Inactive is the default (D3), and in that mode the only guarantee is that the session says out
 * loud what will be transmitted (FR-043) — the safeguard is operational, not technical.
 *
 * <p>
 * Active, the guarantee is stronger and is the one worth testing hard: <b>no blocked content
 * reaches the agent by any route tried</b>. Enforcement is at the dispatcher boundary rather than
 * inside each handler, so a new tool cannot accidentally become a way around it.
 */
public class EgressPolicyTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void inactiveIsTheDefaultAndTheWarningAlwaysAppears() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        assertFalse("the policy must be inactive by default", config.isEgressPolicyActive());

        try (McpServerMain server = new McpServerMain(config)) {
            List<String> warnings = server.getSession().getWarnings();
            String joined = String.join(" ", warnings);
            assertTrue("the session must state what may be transmitted: " + joined,
                    joined.contains("Egress policy INACTIVE"));
            assertTrue("and be explicit that content leaves the machine: " + joined,
                    joined.contains("leaves this machine"));
            assertTrue("and name the recommended mitigation: " + joined, joined.contains("local model"));
        }
    }

    @Test
    public void thePolicyIsReadableEvenWhileInactive() throws Exception {
        // FR-042: an examiner has to be able to ask what is in force without turning it on.
        try (McpServerMain server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()))) {
            JsonNode policy = call(server, "iped_session_info").path("result").path("structuredContent")
                    .path("egress_policy");

            assertFalse("the policy must be reported even when inactive", policy.isMissingNode());
            assertFalse(policy.path("active").asBoolean());
            assertTrue("the allowed classes must be visible", policy.path("allowedClasses").isArray());
        }
    }

    @Test
    public void activePolicyBlocksAtTheBoundaryNotInsideTheHandler() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setEgressPolicyActive(true);
        config.setEgressAllowedClasses(new LinkedHashSet<>(Arrays.asList(ContentClass.metadata)));

        try (McpServerMain server = new McpServerMain(config)) {
            // No case is open, so if the block were inside the handler this would fail with
            // CASE_NOT_OPEN instead. It must fail on the policy first.
            for (String tool : new String[] { "iped_item_text", "iped_item_thumbnail", "iped_item_content" }) {
                ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
                arguments.put("case_id", "any");
                arguments.put("item_id", 1);
                JsonNode response = call(server, tool, arguments);

                assertEquals("the policy must apply before the tool reads any argument: " + tool,
                        McpError.BLOCKED_BY_POLICY, response.path("error").path("data").path("code").asText());
                assertFalse("the refusal must carry a remedy: " + tool,
                        response.path("error").path("data").path("remedy").asText().isEmpty());
                assertTrue("and be clear it is not negotiable from the conversation: " + tool,
                        response.path("error").path("data").path("remedy").asText().contains("cannot be worked "
                                + "around"));
            }
        }
    }

    @Test
    public void everyBlockIsRecordedWithTheRuleThatCausedIt() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setEgressPolicyActive(true);
        config.setEgressAllowedClasses(new LinkedHashSet<>(Arrays.asList(ContentClass.metadata)));

        File trailFile;
        try (McpServerMain server = new McpServerMain(config)) {
            trailFile = server.getSession().getAuditTrail().getFile();
            ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
            arguments.put("case_id", "any");
            arguments.put("item_id", 42);
            call(server, "iped_item_content", arguments);
        }

        AuditRecord blocked = null;
        for (AuditRecord record : AuditTrail.read(trailFile)) {
            if (record.getBlockedByPolicy() != null) {
                blocked = record;
            }
        }
        // FR-041: a block that leaves no trace is indistinguishable, later, from a query nobody ran.
        org.junit.Assert.assertNotNull("the block must be recorded in the trail", blocked);
        assertEquals(AuditRecord.Outcome.DENIED, blocked.getOutcome());
        assertEquals("allowedClasses", blocked.getBlockedByPolicy().get("rule"));
        assertEquals("binary", blocked.getBlockedByPolicy().get("contentClass"));
    }

    @Test
    public void metadataStaysAvailableWhenOnlyContentIsBlocked() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setEgressPolicyActive(true);
        config.setEgressAllowedClasses(new LinkedHashSet<>(Arrays.asList(ContentClass.metadata)));

        try (McpServerMain server = new McpServerMain(config)) {
            // A blocked class must not take the whole surface down with it: an examiner working
            // under a restrictive policy still needs to be able to find things.
            JsonNode response = call(server, "iped_session_info");
            assertFalse(response.has("error"));
        }
    }

    @Test
    public void activePolicyAnnouncesWhatIsAllowedAtSessionOpen() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setEgressPolicyActive(true);
        config.setEgressAllowedClasses(new LinkedHashSet<>(Arrays.asList(ContentClass.metadata, ContentClass.text)));

        try (McpServerMain server = new McpServerMain(config)) {
            String joined = String.join(" ", server.getSession().getWarnings());
            assertTrue("the active policy must announce itself: " + joined, joined.contains("Egress policy ACTIVE"));
            assertTrue("and list what the agent may receive: " + joined, joined.contains("metadata, text"));
        }
    }

    private static JsonNode call(McpServerMain server, String tool) throws Exception {
        return call(server, tool, JsonRpcCodec.mapper().createObjectNode());
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
