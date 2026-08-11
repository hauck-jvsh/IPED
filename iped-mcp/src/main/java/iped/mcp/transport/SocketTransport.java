package iped.mcp.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.mcp.McpServerMain;
import iped.mcp.config.McpServerConfig;
import iped.mcp.session.CasePool;
import iped.mcp.session.WriteClaims;

/**
 * Serves the tool surface over network connections, so the harness can run on a different machine
 * from the evidence (FR-010).
 *
 * <p>
 * Each connection gets its own session; the expensive part — the open case — is shared through
 * {@link CasePool}. What a connection does <b>not</b> get is any of this before it authenticates:
 * the handshake happens here, and a connection that fails it is closed without ever reaching the
 * dispatcher, so no tool answers and nothing reveals whether a case exists (FR-013).
 *
 * <p>
 * <b>Nothing starts without a secret.</b> {@link #bind()} refuses outright when none resolves, so a
 * half-finished configuration cannot degrade into an open port (FR-026). There is no code path here
 * that serves an unauthenticated peer.
 */
public class SocketTransport implements Transport {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketTransport.class);

    private final McpServerConfig config;
    private final CasePool casePool;
    private final WriteClaims writeClaims;
    private final AtomicInteger activeSessions = new AtomicInteger();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "iped-mcp-session");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ServerSocket listener;
    private volatile boolean closing;

    public SocketTransport(McpServerConfig config, CasePool casePool, WriteClaims writeClaims) {
        this.config = config;
        this.casePool = casePool;
        this.writeClaims = writeClaims;
    }

    @Override
    public Kind kind() {
        return Kind.SOCKET;
    }

    /**
     * Establishes the listening endpoint, or refuses to.
     *
     * <p>
     * Binding is explicit and separate from serving so the failure modes are separate too: a missing
     * secret, an undeclared endpoint and an occupied port each report themselves before anything is
     * listening, rather than leaving a server that looks like it is serving and is not (FR-018).
     */
    public void bind() throws IOException {
        if (config.resolveSharedSecret() == null) {
            throw new IOException("The network transport is configured but no shared secret resolves ("
                    + config.describeSecretProblem() + "). The endpoint was not established: a transport "
                    + "without authentication is not a degraded transport, it is an open door. Set "
                    + McpServerConfig.SHARED_SECRET_ENV + " in the environment, or point sharedSecretFile at a "
                    + "file containing the secret. It does not go in conf/" + McpServerConfig.CONFIG_FILE
                    + ", which ships with the release.");
        }
        String address = config.getListenAddress();
        int port = config.getListenPort();
        if (address == null || address.trim().isEmpty() || port <= 0) {
            throw new IOException("The network transport is configured but listenAddress and listenPort are not. "
                    + "There is no default on purpose: a server that picks an address for you may expose more "
                    + "than you meant. Declare both in conf/" + McpServerConfig.CONFIG_FILE + ".");
        }
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            // Bound to exactly the declared address. Never every interface by omission (FR-012).
            socket.bind(new InetSocketAddress(InetAddress.getByName(address.trim()), port));
            this.listener = socket;
            LOGGER.info("MCP network transport listening on {}:{}", address.trim(), socket.getLocalPort());
        } catch (IOException e) {
            throw new IOException("The endpoint " + address.trim() + ":" + port + " could not be established: "
                    + e.getMessage() + ". Nothing is listening. Check that the port is free and that the address "
                    + "exists on this machine.", e);
        }
    }

    /** The port actually bound, which matters when the configuration asked for an ephemeral one. */
    public int getBoundPort() {
        ServerSocket socket = listener;
        return socket == null ? -1 : socket.getLocalPort();
    }

    @Override
    public void serve() throws IOException {
        if (listener == null) {
            bind();
        }
        while (!closing) {
            Socket connection;
            try {
                connection = listener.accept();
            } catch (SocketException e) {
                if (closing) {
                    return;
                }
                throw e;
            }
            workers.submit(() -> handle(connection));
        }
    }

    private void handle(Socket connection) {
        String origin = connection.getRemoteSocketAddress() == null ? "unknown"
                : connection.getRemoteSocketAddress().toString();
        boolean counted = false;
        try {
            // An idle connection must not hold a slot forever, before or after the handshake.
            connection.setSoTimeout(Math.max(1, config.getSessionIdleTimeoutSeconds()) * 1000);
            InputStream in = connection.getInputStream();
            OutputStream out = connection.getOutputStream();

            if (activeSessions.get() >= config.getMaxConcurrentSessions()) {
                // Refused with the same single word an authentication failure gets. An
                // unauthenticated peer learns nothing about why; the reason goes to the log, where
                // the person running the server can see it.
                HandshakeCodec.writeDenied(out);
                LOGGER.warn("Refused a connection from {}: {} sessions already active, which is the configured "
                        + "maximum", origin, config.getMaxConcurrentSessions());
                return;
            }

            HandshakeCodec.Accepted accepted = HandshakeCodec.accept(in, config.resolveSharedSecret());
            if (accepted == null) {
                HandshakeCodec.writeDenied(out);
                // FR-027: recorded with its origin, so a run of attempts is visible to whoever reads
                // the log, rather than each one vanishing on its own.
                LOGGER.warn("Refused a connection from {}: the shared secret did not match", origin);
                return;
            }

            activeSessions.incrementAndGet();
            counted = true;
            try (McpServerMain server = new McpServerMain(config, casePool, writeClaims, Kind.SOCKET, origin,
                    accepted.getClaimedOperator())) {
                HandshakeCodec.writeAccepted(out, server.getSession().getSessionId());
                LOGGER.info("MCP session {} accepted from {}", server.getSession().getSessionId(), origin);
                server.start(in, out);
            }
        } catch (Exception e) {
            // A dropped connection is ordinary, not exceptional. The session's own teardown — which
            // runs identically for a normal end and for a drop — has already released the write
            // claim and returned the case to the pool (FR-017, FR-030).
            LOGGER.info("Session from {} ended: {}", origin, e.toString());
        } finally {
            if (counted) {
                activeSessions.decrementAndGet();
            }
            try {
                connection.close();
            } catch (IOException ignored) {
                // Already gone, which is the case this is cleaning up after.
            }
        }
    }

    /** Sessions currently being served. Used by the concurrency suite. */
    public int getActiveSessions() {
        return activeSessions.get();
    }

    @Override
    public void close() throws IOException {
        closing = true;
        ServerSocket socket = listener;
        if (socket != null) {
            socket.close();
        }
        workers.shutdownNow();
    }
}
