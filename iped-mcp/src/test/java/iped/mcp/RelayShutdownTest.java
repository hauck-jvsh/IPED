package iped.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * The relay must exit when the harness closes its stdin.
 *
 * <p>
 * <b>Found in the field, not here.</b> The first end-to-end run of the relay against a real server
 * answered both requests correctly and then never terminated. Closing the child's stdin is how every
 * harness signals shutdown, and the upstream pump was simply stopping at end-of-input without
 * telling the server: the server went on waiting for a request that would never arrive, the relay
 * went on waiting for a reply that would never come, and the session sat there holding the case and
 * its write claim until the idle timeout expired.
 *
 * <p>
 * The fix is a half-close of the connection once stdin ends. This test exists so it does not get
 * refactored away — a relay that hangs looks perfectly healthy in every request/response test.
 */
public class RelayShutdownTest {

    @Test
    public void closingTheHarnessInputEndsTheRelayAndTellsTheServer() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<Exception> serverFailure = new AtomicReference<>();

        try (ServerSocket listener = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket accepted = listener.accept()) {
                    // Read until end-of-input. This only returns if the peer half-closes, which is
                    // exactly the behaviour under test.
                    InputStream in = accepted.getInputStream();
                    ByteArrayOutputStream collected = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        collected.write(buffer, 0, read);
                    }
                    received.set(new String(collected.toByteArray(), StandardCharsets.UTF_8));
                    OutputStream out = accepted.getOutputStream();
                    out.write("goodbye\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception e) {
                    serverFailure.set(e);
                }
            }, "test-relay-server");
            server.setDaemon(true);
            server.start();

            try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
                ByteArrayInputStream fromHarness = new ByteArrayInputStream(
                        "hello\n".getBytes(StandardCharsets.UTF_8));
                ByteArrayOutputStream toHarness = new ByteArrayOutputStream();

                AtomicReference<Exception> relayFailure = new AtomicReference<>();
                Thread relay = new Thread(() -> {
                    try {
                        McpRelayMain.relay(fromHarness, toHarness, client);
                    } catch (IOException e) {
                        relayFailure.set(e);
                    }
                }, "test-relay");
                relay.setDaemon(true);
                relay.start();

                relay.join(10_000);
                assertTrue("the relay must return once the harness closes its input and the server hangs up; "
                        + "if this times out it is hanging exactly as it did in the field", !relay.isAlive());
                assertEquals(null, relayFailure.get());
                assertEquals("what the harness wrote must reach the server", "hello\n", received.get());
                assertEquals("what the server answered must reach the harness", "goodbye\n",
                        new String(toHarness.toByteArray(), StandardCharsets.UTF_8));
            }
            server.join(5_000);
            assertEquals(null, serverFailure.get());
        }
    }
}
