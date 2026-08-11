package iped.mcp.transport;

import java.io.IOException;

/**
 * How a client reaches the tool surface.
 *
 * <p>
 * There is deliberately very little here, because there is very little difference.
 * {@code McpServerMain.start(InputStream, OutputStream)} was written transport-agnostic for FR-064
 * of feature 001, so serving a socket is handing it the streams of a connection. That is what makes
 * FR-015 — identical tool surface on both transports — a property of the structure rather than
 * something maintenance has to keep remembering.
 */
public interface Transport extends AutoCloseable {

    /** Which transport a session arrived over. Recorded in the session manifest (FR-021). */
    enum Kind {
        STDIO, SOCKET
    }

    Kind kind();

    /**
     * Serves until there is nothing left to serve: stdin closing for the local transport, the
     * listener being closed for the network one.
     */
    void serve() throws IOException;

    @Override
    void close() throws IOException;
}
