package iped.mcp.transport;

import java.io.IOException;

import iped.mcp.McpServerMain;
import iped.mcp.config.McpServerConfig;
import iped.mcp.session.CasePool;
import iped.mcp.session.WriteClaims;

/**
 * The transport the server has always had: one session over the process's own streams.
 *
 * <p>
 * Extracted rather than redesigned. It opens no port, which is how FR-057 of feature 001 is
 * satisfied by construction in the default configuration rather than by anyone remembering to turn
 * something off.
 */
public class StdioTransport implements Transport {

    private final McpServerConfig config;
    private final CasePool casePool;
    private final WriteClaims writeClaims;

    public StdioTransport(McpServerConfig config, CasePool casePool, WriteClaims writeClaims) {
        this.config = config;
        this.casePool = casePool;
        this.writeClaims = writeClaims;
    }

    @Override
    public Kind kind() {
        return Kind.STDIO;
    }

    @Override
    public void serve() throws IOException {
        try (McpServerMain server = new McpServerMain(config, casePool, writeClaims, Kind.STDIO, null, null)) {
            // Returns when the peer closes stdin, which is how every harness signals shutdown.
            server.start(System.in, System.out);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() {
        // Nothing of its own: the streams belong to the process.
    }
}
