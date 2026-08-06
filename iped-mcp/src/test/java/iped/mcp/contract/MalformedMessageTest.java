package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.protocol.JsonRpcCodec;

/**
 * A malformed message is answered and skipped, never fatal (JSON-RPC 2.0 §4.2 and §5.1).
 *
 * <p>
 * This suite exists because of a defect found in the field. An agent that means the Lucene escape
 * for a namespaced field name writes {@code p2p\:fileType} and forgets that JSON escapes the
 * backslash too, putting an invalid escape on the wire. The Jackson failure propagated out of the
 * read loop and terminated the process: one badly written query cost the whole session and every
 * case open in it, and the harness reported it as the server dying rather than as a query to fix.
 */
public class MalformedMessageTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** The exact shape that took the server down: '\:' inside a JSON string. */
    private static final String INVALID_ESCAPE = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"iped_search\",\"arguments\":{\"case_id\":\"x\","
            + "\"query\":\"p2p\\:fileType:mp3\"}}}";

    @Test
    public void anInvalidBackslashEscapeIsAnsweredAndTheSessionSurvives() throws Exception {
        String[] responses = serve("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                INVALID_ESCAPE, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\",\"params\":{}}");

        assertEquals("every message must be answered, the bad one included", 3, responses.length);

        JsonNode parseError = JsonRpcCodec.mapper().readTree(responses[1]);
        assertTrue("a malformed line must produce a JSON-RPC error", parseError.has("error"));
        assertEquals(JsonRpcCodec.PARSE_ERROR, parseError.path("error").path("code").asInt());
        assertTrue("the id cannot be known when the line did not parse, so it goes back null",
                parseError.path("id").isNull());

        JsonNode data = parseError.path("error").path("data");
        assertEquals("MALFORMED_MESSAGE", data.path("code").asText());
        String remedy = data.path("remedy").asText();
        assertFalse("the error must carry a remedy", remedy.isEmpty());
        assertTrue("the remedy must name the JSON escaping that caused it: " + remedy, remedy.contains("\\\\"));

        JsonNode afterTheBadLine = JsonRpcCodec.mapper().readTree(responses[2]);
        assertFalse("the session must still be serving after a malformed message",
                afterTheBadLine.has("error"));
        assertEquals(3, afterTheBadLine.path("id").asInt());
    }

    @Test
    public void garbageThatIsNotJsonAtAllIsSurvivedToo() throws Exception {
        String[] responses = serve("this is not json", "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}");

        assertEquals(2, responses.length);
        assertEquals(JsonRpcCodec.PARSE_ERROR,
                JsonRpcCodec.mapper().readTree(responses[0]).path("error").path("code").asInt());
        assertEquals(9, JsonRpcCodec.mapper().readTree(responses[1]).path("id").asInt());
    }

    @Test
    public void severalMalformedMessagesInARowDoNotAccumulateIntoAFailure() throws Exception {
        String[] responses = serve("{", "}", "[", INVALID_ESCAPE,
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");

        assertEquals(5, responses.length);
        assertEquals(7, JsonRpcCodec.mapper().readTree(responses[4]).path("id").asInt());
    }

    /** Runs the real serve loop over the given lines and returns one response per line written. */
    private String[] serve(String... lines) throws Exception {
        String session = String.join("\n", lines) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (McpServerMain server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()))) {
            // Returning at all is half the assertion: before the fix this threw out of start().
            server.start(new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out);
        }
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        return written.isEmpty() ? new String[0] : written.split("\\r?\\n");
    }
}
