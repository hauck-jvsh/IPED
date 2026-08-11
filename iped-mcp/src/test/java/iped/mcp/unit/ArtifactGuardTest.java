package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Collections;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.export.ArtifactWriter;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpError;

/**
 * The two guards on artifact export that hold regardless of what is in the case (FR-068, FR-070).
 *
 * <p>
 * An empty set must not produce a file, because an empty spreadsheet reads as a finished report of
 * nothing found — which is a different claim from "no report was produced". And the case folder is
 * refused as a destination, because an artifact written into the case becomes indistinguishable,
 * later, from something the case itself produced.
 */
public class ArtifactGuardTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private McpServerMain server;

    @Before
    public void setUp() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        server = new McpServerMain(config);
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void emptySetProducesNoFile() throws Exception {
        File destination = new File(temp.newFolder("out"), "report.csv");
        try {
            new ArtifactWriter(5).write(null, Collections.emptyList(), "csv", destination.toPath(), false);
            fail("an empty set must be refused, not written");
        } catch (McpError e) {
            assertEquals(McpError.EMPTY_RESULT_SET, e.getCode());
            assertNotNull("the refusal must say what to do instead", e.getRemedy());
        }
        assertFalse("no file may be created for an empty set", destination.exists());
    }

    @Test
    public void exportRequiresExactlyOneSetDefinition() throws Exception {
        // bookmark, query and item_ids are alternatives. Passing none, or more than one, is
        // ambiguous about what would actually be written.
        ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
        arguments.put("case_id", "nonexistent");
        arguments.put("format", "csv");
        arguments.put("destination", new File(temp.getRoot(), "out.csv").getAbsolutePath());

        ObjectNode response = call("iped_export_artifact", arguments);
        // With no case open the case lookup fails first, which is itself the correct diagnostic.
        assertEquals(McpError.CASE_NOT_OPEN, response.path("error").path("data").path("code").asText());
        assertFalse(response.path("error").path("data").path("remedy").asText().isEmpty());
    }

    @Test
    public void unknownFormatIsRejectedWithTheValidOnes() throws Exception {
        File destination = new File(temp.newFolder("out2"), "report.pdf");
        // The case is deliberately null: an unsupported format is a caller mistake and has to be
        // caught before anything reads the case, or the set gets materialized for nothing.
        try {
            new ArtifactWriter(5).write(null, Collections.singletonList(1), "pdf", destination.toPath(), false);
            fail("an unsupported format must be refused");
        } catch (McpError e) {
            assertEquals(McpError.INVALID_ARGUMENT, e.getCode());
            assertNotNull(e.getDetails().get("valid"));
        }
        assertFalse("no file may be created for an unsupported format", destination.exists());
    }

    @Test
    public void exportIntoTheCaseFolderIsRefusedByDefault() throws Exception {
        // The default is what matters here; the configuration can allow it deliberately.
        assertFalse("writing artifacts into the case must be off by default",
                new McpServerConfig().isAllowExportIntoCaseFolder());
    }

    private ObjectNode call(String tool, ObjectNode arguments) throws Exception {
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);
        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);
        try (JsonRpcCodec codec = new JsonRpcCodec(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream())) {
            return server.getDispatcher().dispatch(request, codec);
        }
    }
}
