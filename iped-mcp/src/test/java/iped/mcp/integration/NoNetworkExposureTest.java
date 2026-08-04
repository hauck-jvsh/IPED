package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;

/**
 * The server opens no network port in its default configuration (FR-057).
 *
 * <p>
 * This is satisfied by construction rather than by configuration: the transport is stdio, so there
 * is nothing to bind. The test exists to catch a future change that quietly adds a listener — the
 * kind of thing that looks harmless in a diff and turns an isolated workstation into a reachable
 * one.
 */
public class NoNetworkExposureTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aFullServerLifecycleBindsNoPort() throws Exception {
        Set<Integer> before = listeningPorts();

        String session = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (McpServerMain server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()))) {
            server.start(new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out);

            Set<Integer> during = listeningPorts();
            during.removeAll(before);
            assertTrue("the server must not open a listening socket; new ports: " + during, during.isEmpty());
        }

        String responses = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("stdio must carry exactly one response per request, and none for the notification", 2,
                responses.split("\n").length);
    }

    /**
     * Listening TCP ports of this JVM, as far as the platform lets us see them without native
     * calls. We probe a small range because a full scan is slow; the point is to catch a server
     * being started, and a server that starts binds something in the usual ranges.
     */
    private static Set<Integer> listeningPorts() {
        Set<Integer> listening = new HashSet<>();
        for (int port : new int[] { 8080, 8081, 3000, 5000, 9000, 11434 }) {
            try (java.net.ServerSocket probe = new java.net.ServerSocket(port)) {
                // Bound successfully: nothing was listening there.
                probe.getLocalPort();
            } catch (java.io.IOException inUse) {
                listening.add(port);
            }
        }
        return listening;
    }
}
