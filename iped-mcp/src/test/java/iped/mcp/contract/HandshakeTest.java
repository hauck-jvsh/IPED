package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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
import iped.mcp.protocol.McpDispatcher;

/**
 * Protocol handshake (Scenario 1).
 *
 * <p>
 * This suite is what stands in for an SDK. The MCP layer here is hand-written because the official
 * Java SDK requires Java 17 and the release ships a JRE 11 (R2), so nothing else is watching for a
 * protocol regression. It runs without a case, on purpose: the protocol has to be correct
 * independently of any evidence.
 */
public class HandshakeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private McpServerMain server;
    private McpDispatcher dispatcher;
    private JsonRpcCodec codec;

    @Before
    public void setUp() throws Exception {
        server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()));
        dispatcher = server.getDispatcher();
        codec = new JsonRpcCodec(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void initializeDeclaresProtocolVersionAndToolCapability() {
        ObjectNode response = dispatcher.dispatch(request(1, "initialize", null), codec);

        assertNotNull(response);
        assertEquals(JsonRpcCodec.VERSION, response.path("jsonrpc").asText());
        assertFalse("initialize must not error", response.has("error"));

        JsonNode result = response.path("result");
        assertEquals(McpDispatcher.PROTOCOL_VERSION, result.path("protocolVersion").asText());
        assertTrue("the tools capability must be declared", result.path("capabilities").has("tools"));
        assertEquals(McpDispatcher.SERVER_NAME, result.path("serverInfo").path("name").asText());
        assertFalse("serverInfo must carry a version", result.path("serverInfo").path("version").asText().isEmpty());
    }

    @Test
    public void initializeCarriesTheSessionWarningsAsInstructions() {
        JsonNode result = dispatcher.dispatch(request(1, "initialize", null), codec).path("result");
        String instructions = result.path("instructions").asText();

        // FR-043: the session says at open what content may be transmitted under the current
        // configuration. Losing this is the kind of regression nothing else catches.
        assertTrue("the egress warning must reach the agent at handshake: " + instructions,
                instructions.contains("Egress policy"));
        assertTrue("the access mode must be stated at handshake: " + instructions,
                instructions.contains("READ_ONLY"));
    }

    @Test
    public void initializedNotificationProducesNoResponse() {
        ObjectNode response = dispatcher.dispatch(request(null, "notifications/initialized", null), codec);
        org.junit.Assert.assertNull("a notification must never be answered", response);
        assertTrue(dispatcher.isInitialized());
    }

    @Test
    public void toolsListReturnsEveryToolWithAValidInputSchema() {
        JsonNode tools = dispatcher.dispatch(request(2, "tools/list", null), codec).path("result").path("tools");

        assertTrue("tools/list must return the surface", tools.isArray() && tools.size() > 0);
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            assertTrue("tool names are prefixed iped_: " + name, name.startsWith("iped_"));
            assertFalse("every tool needs a description: " + name, tool.path("description").asText().isEmpty());
            JsonNode schema = tool.path("inputSchema");
            assertEquals("input schema must be an object schema: " + name, "object", schema.path("type").asText());
            assertTrue("input schema must declare properties: " + name, schema.has("properties"));
            assertTrue("input schema must declare required: " + name, schema.path("required").isArray());
        }
    }

    @Test
    public void callingAnUnknownToolReturnsAWellFormedErrorNotAnException() {
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("name", "iped_does_not_exist");
        params.putObject("arguments");

        ObjectNode response = dispatcher.dispatch(request(3, "tools/call", params), codec);

        assertNotNull(response);
        assertTrue("an unknown tool must produce a JSON-RPC error", response.has("error"));
        assertEquals(JsonRpcCodec.VERSION, response.path("jsonrpc").asText());
        assertEquals(3, response.path("id").asInt());

        JsonNode data = response.path("error").path("data");
        assertEquals("UNKNOWN_TOOL", data.path("code").asText());
        assertFalse("the error must carry a remedy", data.path("remedy").asText().isEmpty());
        assertTrue("the remedy must list what is available",
                data.path("details").path("available").isArray() && data.path("details").path("available").size() > 0);
    }

    @Test
    public void unknownMethodReturnsMethodNotFound() {
        ObjectNode response = dispatcher.dispatch(request(4, "resources/list", null), codec);

        assertTrue(response.has("error"));
        assertEquals(JsonRpcCodec.METHOD_NOT_FOUND, response.path("error").path("code").asInt());
        assertFalse(response.path("error").path("data").path("remedy").asText().isEmpty());
    }

    @Test
    public void pingIsAnswered() {
        ObjectNode response = dispatcher.dispatch(request(5, "ping", null), codec);
        assertFalse(response.has("error"));
        assertTrue(response.has("result"));
    }

    @Test
    public void messageWithoutMethodIsAnInvalidRequest() {
        ObjectNode message = JsonRpcCodec.mapper().createObjectNode();
        message.put("jsonrpc", JsonRpcCodec.VERSION);
        message.put("id", 6);

        ObjectNode response = dispatcher.dispatch(message, codec);
        assertEquals(JsonRpcCodec.INVALID_REQUEST, response.path("error").path("code").asInt());
    }

    @Test
    public void codecReadsAndWritesUtf8Regardless0fPlatformDefault() throws Exception {
        // Item names and extracted text come from systems we do not control. A codec that
        // inherited the platform charset would corrupt them on someone else's machine.
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"note\":\"acentuação ção\"}}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (JsonRpcCodec utf8Codec = new JsonRpcCodec(in, out)) {
            JsonNode message = utf8Codec.readMessage();
            assertEquals("acentuação ção", message.path("params").path("note").asText());
            utf8Codec.writeMessage(utf8Codec.newResponse(message.get("id"), message.get("params")));
        }
        assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).contains("acentuação ção"));
    }

    private static ObjectNode request(Integer id, String method, JsonNode params) {
        ObjectNode message = JsonRpcCodec.mapper().createObjectNode();
        message.put("jsonrpc", JsonRpcCodec.VERSION);
        if (id != null) {
            message.put("id", id);
        }
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        return message;
    }
}
