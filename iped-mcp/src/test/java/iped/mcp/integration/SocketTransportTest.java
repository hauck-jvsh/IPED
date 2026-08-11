package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.TransportMode;
import iped.mcp.session.CasePool;
import iped.mcp.session.WriteClaims;
import iped.mcp.transport.SocketTransport;

/**
 * FR-011, FR-013, FR-018 and FR-026: the network transport authenticates, and refuses to exist at
 * all when it cannot.
 */
public class SocketTransportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private SocketTransport transport;
    private Thread serving;

    @Before
    public void requireNoAmbientSecret() {
        // The server reads IPED_MCP_SHARED_SECRET before the configured file, and a value in the
        // developer's environment would quietly change what these tests are measuring.
        String ambient = System.getenv(McpServerConfig.SHARED_SECRET_ENV);
        Assume.assumeTrue("The " + McpServerConfig.SHARED_SECRET_ENV + " environment variable is set in this "
                + "environment, which would override the secret these tests configure.",
                ambient == null || ambient.trim().isEmpty());
    }

    @After
    public void tearDown() throws Exception {
        if (transport != null) {
            transport.close();
        }
        if (serving != null) {
            serving.interrupt();
        }
    }

    private McpServerConfig socketConfig(String secret) throws IOException {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setTransport(TransportMode.SOCKET);
        config.setListenEndpoint("127.0.0.1", freePort());
        if (secret != null) {
            File file = new File(temp.getRoot(), "secret.txt");
            Files.write(file.toPath(), secret.getBytes(StandardCharsets.UTF_8));
            config.setSharedSecretFile(file.getAbsolutePath());
        }
        return config;
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private SocketTransport start(McpServerConfig config) throws IOException {
        transport = new SocketTransport(config, new CasePool(), new WriteClaims());
        transport.bind();
        serving = new Thread(() -> {
            try {
                transport.serve();
            } catch (IOException expectedOnClose) {
                // The listener closing is how this thread ends.
            }
        }, "test-socket-transport");
        serving.setDaemon(true);
        serving.start();
        return transport;
    }

    @Test
    public void withoutASecretTheEndpointIsNotEstablished() throws Exception {
        McpServerConfig config = socketConfig(null);
        SocketTransport refusing = new SocketTransport(config, new CasePool(), new WriteClaims());
        try {
            refusing.bind();
            fail("a network transport with no secret must not start");
        } catch (IOException e) {
            assertTrue("the diagnostic has to say what to set", e.getMessage().contains("shared secret"));
            assertTrue(e.getMessage().contains(McpServerConfig.SHARED_SECRET_ENV));
        }
        // And nothing is listening: a half-finished configuration must not degrade into an open port.
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", config.getListenPort()), 500);
            fail("nothing may be listening after a refused bind");
        } catch (IOException expected) {
            assertTrue(expected instanceof ConnectException || expected.getMessage() != null);
        }
    }

    @Test
    public void withoutAnEndpointTheTransportRefusesToStart() throws Exception {
        McpServerConfig config = socketConfig("s3cr3t");
        config.setListenEndpoint(null, 0);
        try {
            new SocketTransport(config, new CasePool(), new WriteClaims()).bind();
            fail("there is no default endpoint on purpose");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("listenAddress"));
        }
    }

    @Test
    public void anOccupiedPortIsReportedRatherThanPretendedAway() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            McpServerConfig config = socketConfig("s3cr3t");
            config.setListenEndpoint("127.0.0.1", occupied.getLocalPort());
            try {
                new SocketTransport(config, new CasePool(), new WriteClaims()).bind();
                fail("binding an occupied port must fail loudly");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("could not be established"));
            }
        }
    }

    @Test
    public void aWrongSecretGetsNothingButTheRefusal() throws Exception {
        McpServerConfig config = socketConfig("s3cr3t");
        start(config);

        try (Socket client = new Socket("127.0.0.1", config.getListenPort())) {
            client.getOutputStream().write("IPED-MCP/1 wrong\n".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("IPED-MCP/1 DENIED", reader.readLine());
            assertFalse("a refused connection must learn nothing about the server's state",
                    String.valueOf(reader.readLine()).contains("case"));
        }
    }

    @Test
    public void theRightSecretGetsAServedSession() throws Exception {
        McpServerConfig config = socketConfig("s3cr3t");
        start(config);

        try (Socket client = new Socket("127.0.0.1", config.getListenPort())) {
            OutputStream out = client.getOutputStream();
            out.write("IPED-MCP/1 s3cr3t perito.silva\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String answer = reader.readLine();
            assertTrue("the handshake answer carries the session id", answer.startsWith("IPED-MCP/1 OK "));

            // And the tool surface is really there, over the same streams, with no adaptation.
            out.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String response = reader.readLine();
            assertTrue("the same tools answer over the network transport", response.contains("iped_session_info"));
        }
    }
}
