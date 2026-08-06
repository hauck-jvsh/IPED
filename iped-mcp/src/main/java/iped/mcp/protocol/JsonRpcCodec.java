package iped.mcp.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 codec over a newline-delimited stdio transport.
 *
 * <p>
 * The official MCP SDK requires Java 17+ and the IPED release ships a JRE 11, so the protocol layer
 * is implemented directly over Jackson (see R2 in research.md). The surface needed by a tools-only
 * server is small: requests, responses, notifications and errors.
 *
 * <p>
 * Charset is explicit UTF-8 on both directions. The platform default MUST NOT be inherited here: it
 * varies per machine and would corrupt evidence-derived text on someone else's workstation
 * (constitution, Principle V).
 */
public class JsonRpcCodec implements AutoCloseable {

    public static final String VERSION = "2.0";

    /** Standard JSON-RPC 2.0 error codes. */
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedReader reader;
    private final Writer writer;

    public JsonRpcCodec(InputStream in, OutputStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Reads the next message from the transport.
     *
     * <p>
     * A line that is not valid JSON is <b>one bad message, not the end of the session</b>. It is
     * reported as {@link McpError#MALFORMED_MESSAGE} so the caller can answer with
     * {@link #PARSE_ERROR} and keep serving, as JSON-RPC 2.0 requires. Letting the underlying
     * Jackson failure escape would take the whole server down and the client would see the
     * connection die mid-session.
     *
     * @return the parsed message, or {@code null} when the stream is exhausted
     * @throws IOException
     *             on transport failure
     * @throws McpError
     *             with {@link #PARSE_ERROR} semantics when the line is not valid JSON
     */
    public JsonNode readMessage() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                return MAPPER.readTree(line);
            } catch (JsonProcessingException e) {
                throw new McpError(McpError.MALFORMED_MESSAGE, "The message is not valid JSON: " + e.getOriginalMessage(),
                        "Send one complete JSON-RPC object per line. A backslash inside a JSON string has to be "
                                + "escaped as \\\\ — to pass a Lucene escape such as \\: in a query, the JSON must "
                                + "carry \\\\: so the server receives \\:. This message was discarded; the session "
                                + "is still open, so retry the call.")
                                        .with("line", abbreviate(line));
            }
        }
        return null;
    }

    /** Keeps a malformed line quotable in the diagnostic without echoing an unbounded payload back. */
    private static String abbreviate(String line) {
        int limit = 400;
        return line.length() <= limit ? line : line.substring(0, limit) + "… [" + line.length() + " chars]";
    }

    /**
     * Writes one message as a single line and flushes, so the peer sees it immediately.
     */
    public synchronized void writeMessage(JsonNode message) throws IOException {
        writer.write(MAPPER.writeValueAsString(message));
        writer.write('\n');
        writer.flush();
    }

    public ObjectNode newResponse(JsonNode id, JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", VERSION);
        response.set("id", id == null ? MAPPER.nullNode() : id);
        response.set("result", result);
        return response;
    }

    public ObjectNode newErrorResponse(JsonNode id, int code, String message, JsonNode data) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", data);
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", VERSION);
        response.set("id", id == null ? MAPPER.nullNode() : id);
        response.set("error", error);
        return response;
    }

    public ObjectNode newNotification(String method, JsonNode params) {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", VERSION);
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        return notification;
    }

    /**
     * A message is a notification when it carries a method and no id; no response may be sent for
     * it.
     */
    public static boolean isNotification(JsonNode message) {
        return message.hasNonNull("method") && !message.hasNonNull("id");
    }

    @Override
    public void close() throws IOException {
        writer.flush();
    }
}
