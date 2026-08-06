package iped.mcp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.Version;
import iped.engine.config.Configuration;
import iped.engine.config.ConfigurationManager;
import iped.mcp.config.McpServerConfig;
import iped.mcp.curation.BookmarkWriter;
import iped.mcp.egress.EgressPolicy;
import iped.mcp.export.ArtifactWriter;
import iped.mcp.item.ContentAccess;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpDispatcher;
import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.query.Aggregator;
import iped.mcp.query.PagedSearcher;
import iped.mcp.query.SnippetBuilder;
import iped.mcp.session.Session;
import iped.mcp.tools.AuditTools;
import iped.mcp.tools.BookmarkTools;
import iped.mcp.tools.ExportTools;
import iped.mcp.tools.ItemTools;
import iped.mcp.tools.QueryTools;
import iped.mcp.tools.SelectionTools;
import iped.mcp.tools.SessionTools;
import iped.mcp.tools.VocabularyTools;

/**
 * Entry point of the MCP server, speaking JSON-RPC 2.0 over stdio.
 *
 * <p>
 * stdio is the transport with the best support across the target harnesses and, more importantly,
 * it opens no network port — FR-057 is satisfied by construction rather than by configuration.
 *
 * <p>
 * The class is also usable programmatically (FR-064): {@link #start(InputStream, OutputStream)}
 * runs a session over any pair of streams, which is how a host process — eventually the IPED UI
 * itself — can drive it without a shell.
 *
 * <p>
 * <b>Nothing here writes to {@code System.out}.</b> stdout <i>is</i> the protocol channel; a single
 * stray print corrupts the stream. All diagnostics go to SLF4J.
 */
public class McpServerMain implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServerMain.class);

    private final Session session;
    private final McpDispatcher dispatcher;

    /**
     * Builds a server over an already-initialized configuration.
     */
    public McpServerMain(McpServerConfig config) {
        this.session = new Session(config);
        this.dispatcher = new McpDispatcher(session, Version.APP_VERSION);
        registerTools();
    }

    private void registerTools() {
        EgressPolicy egressPolicy = session.getEgressPolicy();
        McpServerConfig config = session.getConfig();

        ContentAccess contentAccess = new ContentAccess(config, egressPolicy);
        SnippetBuilder snippetBuilder = new SnippetBuilder(config, contentAccess);
        PagedSearcher pagedSearcher = new PagedSearcher(config, contentAccess, snippetBuilder);
        Aggregator aggregator = new Aggregator(pagedSearcher);
        BookmarkWriter bookmarkWriter = new BookmarkWriter();
        ArtifactWriter artifactWriter = new ArtifactWriter(5);

        List<ToolDescriptor> tools = new ArrayList<>();
        tools.addAll(new SessionTools(session, aggregator).descriptors());
        tools.addAll(new VocabularyTools(session).descriptors());
        tools.addAll(new QueryTools(session, pagedSearcher, aggregator).descriptors());
        tools.addAll(new ItemTools(session, contentAccess).descriptors());
        tools.addAll(new BookmarkTools(session, bookmarkWriter).descriptors());
        tools.addAll(new SelectionTools(session, bookmarkWriter).descriptors());
        tools.addAll(new ExportTools(session, pagedSearcher, artifactWriter).descriptors());
        tools.addAll(new AuditTools(session).descriptors());
        dispatcher.registerAll(tools);

        LOGGER.info("Registered {} MCP tools", tools.size());
    }

    public Session getSession() {
        return session;
    }

    public McpDispatcher getDispatcher() {
        return dispatcher;
    }

    /**
     * Serves messages until the input stream is exhausted. Returns when the peer closes stdin,
     * which is how every harness signals shutdown.
     *
     * <p>
     * A malformed line is answered and skipped, never fatal. Only stdin closing ends the loop: an
     * agent that emits one bad message — an unescaped backslash in a query is the common way — must
     * get a diagnostic it can act on, not a dead server. Killing the session over one bad line would
     * also discard every case this server has open.
     */
    public void start(InputStream in, OutputStream out) throws IOException {
        try (JsonRpcCodec codec = new JsonRpcCodec(in, out)) {
            while (true) {
                JsonNode message;
                try {
                    message = codec.readMessage();
                } catch (McpError e) {
                    LOGGER.warn("Discarding a malformed message: {}", e.getMessage());
                    // The id cannot be known when the line did not parse, so it goes back null,
                    // as JSON-RPC 2.0 prescribes for a parse error.
                    codec.writeMessage(codec.newErrorResponse(null, JsonRpcCodec.PARSE_ERROR, e.getMessage(),
                            JsonRpcCodec.mapper().valueToTree(e.toMap())));
                    continue;
                }
                if (message == null) {
                    return;
                }
                ObjectNode response = dispatcher.dispatch(message, codec);
                if (response != null) {
                    codec.writeMessage(response);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        session.close();
    }

    /**
     * Initializes the IPED configuration system from the installation root.
     *
     * <p>
     * This mirrors what the IPED UI does at startup. It matters that the server does it first:
     * {@code IPEDSource} calls the same loader when a case is opened, and the loader runs once per
     * process — so whoever calls first decides which configuration directory the whole process
     * uses. Doing it here means the server's own settings come from the installation, not from
     * whatever happened to be copied into the first case opened.
     */
    static McpServerConfig bootstrapConfiguration(File ipedRoot) {
        if (ipedRoot == null) {
            LOGGER.warn("No IPED installation was located; the server starts on built-in defaults");
            return new McpServerConfig();
        }
        try {
            Configuration.getInstance().loadConfigurables(ipedRoot.getAbsolutePath(), true);
        } catch (Exception e) {
            LOGGER.error("The IPED configuration at {} could not be loaded; falling back to built-in defaults",
                    ipedRoot.getAbsolutePath(), e);
            return new McpServerConfig();
        }
        McpServerConfig config = new McpServerConfig();
        ConfigurationManager manager = ConfigurationManager.get();
        if (manager == null) {
            return config;
        }
        manager.addObject(config);
        try {
            manager.loadConfig(config);
        } catch (IOException e) {
            LOGGER.error("conf/{} could not be read; built-in defaults stay in force", McpServerConfig.CONFIG_FILE, e);
        }
        return config;
    }

    public static void main(String[] args) {
        File ipedRoot = Diagnostics.resolveIpedRoot();
        McpServerConfig config = bootstrapConfiguration(ipedRoot);

        Diagnostics diagnostics = new Diagnostics().run(ipedRoot, config);
        diagnostics.log();
        if (!diagnostics.isOk()) {
            // A failed check is not always fatal — a missing config file only means defaults — so
            // the server still starts and lets the first tool call report the specific problem.
            LOGGER.warn("Starting with {} failed diagnostic check(s); see the entries above",
                    diagnostics.getFailures().size());
        }

        try (McpServerMain server = new McpServerMain(config)) {
            server.start(System.in, System.out);
        } catch (Exception e) {
            LOGGER.error("The MCP server terminated abnormally", e);
            System.exit(1);
        }
    }
}
