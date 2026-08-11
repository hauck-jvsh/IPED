package iped.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.mcp.config.McpServerConfig;
import iped.mcp.transport.HandshakeCodec;

/**
 * Bridges a harness that speaks stdio to a server listening on a socket.
 *
 * <pre>
 * opencode ──stdio──▶ McpRelayMain ──socket──▶ McpServerMain
 *  (isolated VM)       (isolated VM)            (host, beside the evidence)
 * </pre>
 *
 * <p>
 * <b>Why a relay rather than teaching the harnesses to dial out.</b> Claude Code, Codex and OpenCode
 * all know how to launch a local process that speaks stdio, and that configuration is already
 * written and tested. Making each of them speak a network transport would mean three different
 * configurations and three different places for the shared secret to live — several of which are
 * files people keep under version control. The relay keeps all of that in one place, and the harness
 * configuration changes by exactly one word: the class it launches.
 *
 * <p>
 * <b>Nothing here writes to {@code System.out}.</b> On this side stdout is the protocol channel back
 * to the harness, exactly as it is on the server side, and a single stray print corrupts the
 * client's session instead of the server's. Diagnostics go to SLF4J.
 */
public final class McpRelayMain {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpRelayMain.class);

    public static final String HOST_PROPERTY = "iped.mcp.relay.host";
    public static final String HOST_ENV = "IPED_MCP_RELAY_HOST";
    public static final String PORT_PROPERTY = "iped.mcp.relay.port";
    public static final String PORT_ENV = "IPED_MCP_RELAY_PORT";
    public static final String SECRET_FILE_PROPERTY = "iped.mcp.relay.secretFile";
    public static final String OPERATOR_PROPERTY = "iped.mcp.relay.operator";

    private McpRelayMain() {
    }

    public static void main(String[] args) {
        String host = resolve(HOST_PROPERTY, HOST_ENV);
        String port = resolve(PORT_PROPERTY, PORT_ENV);
        if (host == null || port == null) {
            LOGGER.error("The relay needs to know where the server is. Set -D{}=<host> and -D{}=<port> on the "
                    + "command line, or the {} and {} environment variables.", HOST_PROPERTY, PORT_PROPERTY, HOST_ENV,
                    PORT_ENV);
            System.exit(2);
            return;
        }
        String secret = resolveSecret();
        if (secret == null) {
            LOGGER.error("No shared secret resolves, so there is nothing to authenticate with. Set {} in the "
                    + "environment, or -D{}=<path> pointing at a file containing it. It must be the same secret "
                    + "the server resolves.", McpServerConfig.SHARED_SECRET_ENV, SECRET_FILE_PROPERTY);
            System.exit(2);
            return;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, Integer.parseInt(port)));
            InputStream fromServer = socket.getInputStream();
            OutputStream toServer = socket.getOutputStream();

            String operator = System.getProperty(OPERATOR_PROPERTY, "");
            StringBuilder opening = new StringBuilder(HandshakeCodec.PROTOCOL).append(' ').append(secret);
            if (!operator.trim().isEmpty()) {
                // A claim, and recorded as exactly that: the secret proves the connection was
                // authorized, not who is at the keyboard.
                opening.append(' ').append(operator.trim().replace(' ', '_'));
            }
            toServer.write((opening.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            toServer.flush();

            String answer = readLine(fromServer);
            if (answer == null || !answer.startsWith(HandshakeCodec.PROTOCOL + " OK")) {
                LOGGER.error("The server refused the connection. The shared secret on this side does not match the "
                        + "one the server resolves, or the server is already serving its maximum number of "
                        + "sessions.");
                System.exit(3);
                return;
            }
            LOGGER.info("Relay connected to {}:{}", host, port);

            relay(System.in, System.out, socket);
        } catch (IOException e) {
            LOGGER.error("The relay could not reach the server at {}:{}: {}", host, port, e.getMessage());
            System.exit(4);
        }
    }

    /**
     * Pumps both directions until the harness or the server hangs up.
     *
     * <p>
     * <b>The half-close is the part that matters.</b> Every harness signals shutdown by closing the
     * child's stdin, and when that happens the connection has to be half-closed so the server sees
     * end-of-input, ends the session and releases the case. Without it the upstream pump simply
     * stops, the server keeps waiting for a request that will never arrive, and this method stays
     * blocked reading a reply that will never come — the relay hangs, and the session it opened sits
     * there holding the case and its write claim until the idle timeout expires.
     *
     * <p>
     * Downstream runs on the calling thread so this returns only once the server has closed its
     * side, which is what makes the process exit cleanly rather than being killed.
     */
    static void relay(InputStream fromHarness, OutputStream toHarness, Socket socket) throws IOException {
        Thread upstream = new Thread(() -> {
            try {
                copy(fromHarness, socket.getOutputStream());
            } catch (IOException e) {
                LOGGER.debug("Relay upstream ended: {}", e.getMessage());
            } finally {
                try {
                    socket.shutdownOutput();
                } catch (IOException ignored) {
                    // Already gone, which is the condition this is announcing anyway.
                }
            }
        }, "iped-mcp-relay-up");
        upstream.setDaemon(true);
        upstream.start();
        copy(socket.getInputStream(), toHarness);
    }

    /** Byte for byte, flushed per chunk: the protocol is line-oriented and must not sit in a buffer. */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    /** Reads the handshake answer without wrapping the stream in a buffer that would swallow bytes. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return line.toString();
            }
            line.append((char) c);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static String resolve(String property, String env) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(env);
        }
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** The same two sources the server reads, so the secret never has to sit in harness configuration. */
    private static String resolveSecret() {
        String fromEnv = System.getenv(McpServerConfig.SHARED_SECRET_ENV);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return fromEnv.trim();
        }
        String file = System.getProperty(SECRET_FILE_PROPERTY);
        if (file == null || file.trim().isEmpty()) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(new File(file.trim()).toPath()), StandardCharsets.UTF_8)
                    .trim();
            return content.isEmpty() ? null : content;
        } catch (IOException e) {
            LOGGER.error("The secret file at {} could not be read: {}", file.trim(), e.getMessage());
            return null;
        }
    }
}
