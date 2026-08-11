package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.audit.AuditRecord;
import iped.mcp.audit.AuditTrail;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.TransportMode;
import iped.mcp.session.CasePool;
import iped.mcp.session.WriteClaims;
import iped.mcp.transport.SocketTransport;

/**
 * FR-017 and SC-005: a client that disappears mid-session costs that session and nothing else.
 *
 * <p>
 * The teardown path is the same for a normal end and for a drop, which is what makes the guarantee
 * affordable: there is no second code path that has to remember to release the write claim, return
 * the case to the pool and finish the trail.
 */
public class ConnectionDropTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private SocketTransport transport;
    private Thread serving;
    private McpServerConfig config;

    @Before
    public void setUp() throws Exception {
        String ambient = System.getenv(McpServerConfig.SHARED_SECRET_ENV);
        Assume.assumeTrue("The " + McpServerConfig.SHARED_SECRET_ENV + " environment variable is set here, which "
                + "would override the secret this test configures.", ambient == null || ambient.trim().isEmpty());

        config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setTransport(TransportMode.SOCKET);
        File secret = new File(temp.getRoot(), "secret.txt");
        Files.write(secret.toPath(), "s3cr3t".getBytes(StandardCharsets.UTF_8));
        config.setSharedSecretFile(secret.getAbsolutePath());
        try (ServerSocket probe = new ServerSocket(0)) {
            config.setListenEndpoint("127.0.0.1", probe.getLocalPort());
        }

        transport = new SocketTransport(config, new CasePool(), new WriteClaims());
        transport.bind();
        serving = new Thread(() -> {
            try {
                transport.serve();
            } catch (IOException expectedOnClose) {
                // Closing the listener is how this thread ends.
            }
        }, "test-drop-transport");
        serving.setDaemon(true);
        serving.start();
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

    @Test
    public void aDroppedConnectionDoesNotTakeTheServerWithIt() throws Exception {
        Socket first = new Socket("127.0.0.1", config.getListenPort());
        String sessionId = handshake(first);
        call(first, "iped_session_info");
        // Abrupt: no protocol-level goodbye, the socket simply goes away.
        first.close();

        // The listener is still there and still serving, which is the whole assertion.
        try (Socket second = new Socket("127.0.0.1", config.getListenPort())) {
            String secondSession = handshake(second);
            assertFalse("a new connection gets its own session", sessionId.equals(secondSession));
            assertTrue("the surface still answers after a drop", call(second, "iped_session_info").contains("result"));
        }
    }

    @Test
    public void everyOperationStartedLeavesAnOutcomeInTheTrail() throws Exception {
        Socket client = new Socket("127.0.0.1", config.getListenPort());
        String sessionId = handshake(client);
        call(client, "iped_session_info");
        client.close();

        // Give the session's teardown a moment to finish writing.
        for (int attempt = 0; attempt < 40; attempt++) {
            if (trailOf(sessionId).exists()) {
                break;
            }
            Thread.sleep(50);
        }

        List<AuditRecord> records = AuditTrail.read(trailOf(sessionId));
        assertFalse("the session must have recorded something", records.isEmpty());
        long started = records.stream().filter(r -> r.getOutcome() == AuditRecord.Outcome.STARTED).count();
        long finished = records.size() - started;
        // A STARTED with no counterpart is exactly what a drop must not produce.
        assertEquals("every operation that started has an outcome recorded", started, finished);
    }

    private File trailOf(String sessionId) {
        return new File(config.getAuditArea(), "session-" + sessionId + ".jsonl");
    }

    private String handshake(Socket client) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write("IPED-MCP/1 s3cr3t\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        String answer = reader(client).readLine();
        assertTrue("the session must have been accepted", answer.startsWith("IPED-MCP/1 OK "));
        return answer.substring("IPED-MCP/1 OK ".length()).trim();
    }

    private String call(Socket client, String tool) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{}}}\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return reader(client).readLine();
    }

    private final Map<Socket, BufferedReader> readers = new IdentityHashMap<>();

    /**
     * One reader per socket, kept for the socket's lifetime.
     *
     * <p>
     * A fresh {@code BufferedReader} on each call would drop whatever the previous one had already
     * pulled off the stream into its buffer, and the test would fail on a message that was in fact
     * delivered correctly.
     */
    private BufferedReader reader(Socket client) throws IOException {
        BufferedReader reader = readers.get(client);
        if (reader == null) {
            reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            readers.put(client, reader);
        }
        return reader;
    }
}
