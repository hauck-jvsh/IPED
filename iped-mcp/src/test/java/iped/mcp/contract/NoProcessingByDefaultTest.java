package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpError;

/**
 * A default installation cannot create cases (SC-012, FR-001, FR-002).
 *
 * <p>
 * Both halves are checked, and the second is the one that matters. Verifying only that the tools are
 * absent from {@code tools/list} proves nothing about a client that calls one anyway — and a client
 * that has the name from documentation, from a previous session, or from guessing, will. The refusal
 * has to happen before any argument is read and without the filesystem being touched.
 */
public class NoProcessingByDefaultTest {

    private static final List<String> PROCESSING_TOOLS = Arrays.asList("iped_process_evidence",
            "iped_job_status", "iped_cancel_job", "iped_resume_job");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private McpServerMain server;

    @Before
    public void setUp() throws Exception {
        // The distributed configuration, unedited: processingEnabled defaults to false.
        server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()));
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void noProcessingToolAppearsInTheSurface() {
        List<String> exposed = new ArrayList<>();
        listTools().forEach(tool -> exposed.add(tool.path("name").asText()));
        for (String tool : PROCESSING_TOOLS) {
            assertFalse("a default installation must not offer " + tool, exposed.contains(tool));
        }
    }

    @Test
    public void aForcedCallIsRefusedAndTouchesNothing() throws Exception {
        File source = new File(temp.getRoot(), "evidence-that-does-not-exist.E01");
        File destination = new File(temp.getRoot(), "case-that-must-not-be-created");

        ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
        arguments.put("source_path", source.getAbsolutePath());
        arguments.put("destination_path", destination.getAbsolutePath());
        arguments.put("profile", "forensic");

        JsonNode error = callTool("iped_process_evidence", arguments);

        assertEquals(McpError.PROCESSING_DISABLED, error.path("data").path("code").asText());
        // The whole point of gating before argument reading: nothing was created, and nothing was
        // looked for either.
        assertFalse("a refused request must not create the destination", destination.exists());
        assertFalse("a refused request must not create the source", source.exists());
    }

    @Test
    public void everyProcessingToolIsGated() {
        for (String tool : PROCESSING_TOOLS) {
            ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
            arguments.put("job_id", "whatever");
            JsonNode error = callTool(tool, arguments);
            assertEquals("gate missing on " + tool, McpError.PROCESSING_DISABLED,
                    error.path("data").path("code").asText());
        }
    }

    @Test
    public void theRefusalSaysWhoCanChangeItAndHow() {
        ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
        arguments.put("job_id", "whatever");
        String remedy = callTool("iped_job_status", arguments).path("data").path("remedy").asText();

        // The agent cannot enable this and must not be left trying. The remedy names the key, the
        // file and the person, so the conversation can end with an instruction instead of a retry.
        assertTrue("the remedy must name the configuration key", remedy.contains("processingEnabled"));
        assertTrue("the remedy must name the file", remedy.contains("McpServerConfig.txt"));
    }

    private JsonNode listTools() {
        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/list");
        return dispatch(request).path("result").path("tools");
    }

    private JsonNode callTool(String name, ObjectNode arguments) {
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 2);
        request.put("method", "tools/call");
        request.set("params", params);
        return dispatch(request).path("error");
    }

    private ObjectNode dispatch(ObjectNode request) {
        JsonRpcCodec codec = new JsonRpcCodec(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        return server.getDispatcher().dispatch(request, codec);
    }
}
