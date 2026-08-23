package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.protocol.JsonRpcCodec;

/**
 * The processing surface matches {@code contracts/tool-surface.md}.
 *
 * <p>
 * The list is written out by hand, like the one in {@link ToolSchemaTest} and for the same reason:
 * deriving it from the registry would make the test agree with whatever the code does, which is the
 * opposite of what a contract test is for.
 */
public class ProcessingToolSchemaTest {

    private static final List<String> CONTRACT_TOOLS = Arrays.asList("iped_process_evidence",
            "iped_job_status", "iped_cancel_job", "iped_resume_job");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private McpServerMain server;
    private JsonNode tools;

    @Before
    public void setUp() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);
        server = new McpServerMain(config);

        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/list");
        JsonRpcCodec codec = new JsonRpcCodec(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        tools = server.getDispatcher().dispatch(request, codec).path("result").path("tools");
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void everyProcessingToolIsExposedWhenEnabled() {
        List<String> exposed = new ArrayList<>();
        tools.forEach(tool -> exposed.add(tool.path("name").asText()));
        List<String> missing = new ArrayList<>(CONTRACT_TOOLS);
        missing.removeAll(exposed);
        assertTrue("declared in the contract but not exposed: " + missing, missing.isEmpty());
    }

    @Test
    public void aProcessingRequestDeclaresItsThreeRequiredArguments() {
        JsonNode required = find("iped_process_evidence").path("inputSchema").path("required");
        List<String> names = new ArrayList<>();
        required.forEach(node -> names.add(node.asText()));

        assertTrue("source is required", names.contains("source_path"));
        assertTrue("destination is required", names.contains("destination_path"));
        assertTrue("profile is required", names.contains("profile"));
        // Optional on purpose: absent means the engine uses the file name, which is valid behaviour
        // and different from an empty string handed to it.
        assertFalse("a display name is optional", names.contains("display_name"));
        assertFalse("a secret reference is optional", names.contains("secret_ref"));
    }

    @Test
    public void noToolTakesARawPassword() {
        // The request carries a reference the server resolves locally. A password parameter would
        // put the secret in the conversation, which is the one place FR-015 is unambiguous about.
        for (JsonNode tool : tools) {
            JsonNode properties = tool.path("inputSchema").path("properties");
            properties.fieldNames().forEachRemaining(field -> assertFalse(
                    "no tool may take a password directly: " + tool.path("name").asText() + "." + field,
                    field.equals("password") || field.equals("passwords")));
        }
    }

    @Test
    public void theStatusToolDeclaresThatItReturnsEvidenceDerivedContent() {
        // The failure excerpt carries item names and paths from the evidence, so the tool has to be
        // declared as content-returning or the egress policy would never see it (FR-043).
        assertTrue("iped_job_status must be reachable", !find("iped_job_status").isMissingNode());
        assertEquals("text", server.getDispatcher().getTools().stream()
                .filter(tool -> tool.getName().equals("iped_job_status")).findFirst()
                .orElseThrow(() -> new AssertionError("iped_job_status is not registered"))
                .getContentClass());
    }

    @Test
    public void everyProcessingToolIsMarkedAsSuch() {
        // The marker is what the gate reads. A tool that forgot it would stay reachable in an
        // installation that never enabled case creation.
        for (String name : CONTRACT_TOOLS) {
            assertTrue("missing the processing marker: " + name, server.getDispatcher().getTools().stream()
                    .filter(tool -> tool.getName().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError(name + " is not registered"))
                    .isProcessingOperation());
        }
    }

    @Test
    public void aJobReferenceIsAlwaysTheJobIdAlone() {
        // Unlike an item id, a job id is unique within the installation, so it needs no companion.
        // Requiring a case alongside it would be asking for something that does not exist yet when a
        // job is created.
        for (String name : Arrays.asList("iped_job_status", "iped_cancel_job", "iped_resume_job")) {
            JsonNode required = find(name).path("inputSchema").path("required");
            assertEquals("exactly one required argument on " + name, 1, required.size());
            assertEquals("job_id", required.get(0).asText());
        }
    }

    private JsonNode find(String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        return JsonRpcCodec.mapper().missingNode();
    }
}
