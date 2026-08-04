package iped.mcp.integration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.AccessMode;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpError;

/**
 * A running server with an isolated audit area, driven through the real dispatcher.
 *
 * <p>
 * Integration tests go through {@code tools/call} rather than calling the classes directly, because
 * the invariants worth protecting — audit before execute, the write gate, the egress boundary —
 * live in the dispatcher. A test that bypassed it would pass while the guarantees were broken.
 */
public class McpSessionRule extends ExternalResource {

    private final TemporaryFolder temp;
    private final AccessMode accessMode;
    private McpServerMain server;

    public McpSessionRule(TemporaryFolder temp) {
        this(temp, AccessMode.READ_ONLY);
    }

    public McpSessionRule(TemporaryFolder temp, AccessMode accessMode) {
        this.temp = temp;
        this.accessMode = accessMode;
    }

    @Override
    protected void before() throws Throwable {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setAccessMode(accessMode);
        server = new McpServerMain(config);
    }

    @Override
    protected void after() {
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
                // Nothing useful to do while tearing a test down.
            }
        }
    }

    public McpServerMain server() {
        return server;
    }

    public McpServerConfig config() {
        return server.getSession().getConfig();
    }

    /** Calls a tool and returns its structured content, failing the test on a protocol error. */
    public JsonNode call(String tool, Object... keyValues) {
        JsonNode response = raw(tool, keyValues);
        if (response.has("error")) {
            JsonNode data = response.path("error").path("data");
            throw new AssertionError(tool + " failed: " + data.path("code").asText() + " - "
                    + response.path("error").path("message").asText());
        }
        return response.path("result").path("structuredContent");
    }

    /** Calls a tool and returns the raw JSON-RPC response, errors included. */
    public JsonNode raw(String tool, Object... keyValues) {
        ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object value = keyValues[i + 1];
            if (value == null) {
                continue;
            }
            if (value instanceof Integer) {
                arguments.put(key, (Integer) value);
            } else if (value instanceof Boolean) {
                arguments.put(key, (Boolean) value);
            } else if (value instanceof int[]) {
                com.fasterxml.jackson.databind.node.ArrayNode array = arguments.putArray(key);
                for (int entry : (int[]) value) {
                    array.add(entry);
                }
            } else {
                arguments.put(key, String.valueOf(value));
            }
        }

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
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Asserts that a call failed with a specific code, and returns the error envelope. */
    public JsonNode expectError(String expectedCode, String tool, Object... keyValues) {
        JsonNode response = raw(tool, keyValues);
        org.junit.Assert.assertTrue(tool + " should have failed with " + expectedCode, response.has("error"));
        JsonNode data = response.path("error").path("data");
        org.junit.Assert.assertEquals(expectedCode, data.path("code").asText());
        org.junit.Assert.assertFalse("every error must carry a remedy: " + expectedCode,
                data.path("remedy").asText().isEmpty());
        return data;
    }

    /** Opens a case and returns its id. */
    public String openCase(File casePath) {
        return call("iped_open_case", "case_path", casePath.getAbsolutePath()).path("case_id").asText();
    }

    /** Codes are referenced by name so a rename in the enum breaks the tests, not the semantics. */
    public static String writeNotEnabled() {
        return McpError.WRITE_NOT_ENABLED;
    }
}
