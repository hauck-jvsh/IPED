package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.session.CasePool;
import iped.mcp.session.WriteClaims;
import iped.mcp.transport.Transport;

/**
 * FR-015: the tool surface is the same over both transports.
 *
 * <p>
 * This is the same reasoning that gives the skill a single canonical source and byte-identical
 * wrappers. Guidance that differs between harnesses would produce different analyses of the same
 * evidence; a tool surface that differs between transports would do the same, and would do it
 * without anyone noticing, because nobody compares the two by hand.
 */
public class TransportParityTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void bothTransportsExposeTheSameToolsWithTheSameSchemas() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        CasePool pool = new CasePool();
        WriteClaims claims = new WriteClaims();

        String overStdio;
        String overSocket;
        try (McpServerMain local = new McpServerMain(config)) {
            overStdio = toolsOf(local);
        }
        try (McpServerMain remote = new McpServerMain(config, pool, claims, Transport.Kind.SOCKET, "127.0.0.1:65000",
                "perito.silva")) {
            overSocket = toolsOf(remote);
        }
        pool.close();

        assertFalse("the surface cannot be empty, or this test proves nothing", overStdio.isEmpty());
        assertEquals("no tool may exist, or differ, on only one transport", overStdio, overSocket);
    }

    /** The tools/list answer, which is the surface exactly as an agent sees it. */
    private static String toolsOf(McpServerMain server) throws Exception {
        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/list");
        try (JsonRpcCodec codec = new JsonRpcCodec(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream())) {
            return server.getDispatcher().dispatch(request, codec).path("result").toString();
        }
    }
}
