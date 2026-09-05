package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.protocol.JsonRpcCodec;

/**
 * The tool surface matches {@code contracts/mcp-tools.md} (US1).
 *
 * <p>
 * The list below is written out by hand on purpose. Deriving it from the registry would make the
 * test agree with whatever the code does, which is the opposite of what a contract test is for: a
 * tool silently dropped or renamed has to fail here.
 */
public class ToolSchemaTest {

    /** Every tool the contract declares. */
    private static final List<String> CONTRACT_TOOLS = Arrays.asList(
            // Session and case
            "iped_session_info", "iped_open_case", "iped_case_overview", "iped_close_case",
            // Field vocabulary
            "iped_list_fields", "iped_check_field", "iped_item_fields",
            // Query
            "iped_search", "iped_aggregate",
            // Item inspection
            "iped_get_items", "iped_item_metadata", "iped_item_text", "iped_item_thumbnail", "iped_item_content",
            "iped_item_tree",
            // Curation
            "iped_list_bookmarks", "iped_create_bookmark", "iped_rename_bookmark", "iped_delete_bookmark",
            "iped_add_to_bookmark", "iped_remove_from_bookmark", "iped_get_selection", "iped_set_selection",
            // Output artifacts
            "iped_export_artifact", "iped_export_item",
            // Audit
            "iped_export_audit");

    /** Tools that must refuse in read-only mode. */
    private static final Set<String> WRITE_TOOLS = new HashSet<>(Arrays.asList("iped_create_bookmark",
            "iped_rename_bookmark", "iped_delete_bookmark", "iped_add_to_bookmark", "iped_remove_from_bookmark",
            "iped_set_selection"));

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private McpServerMain server;
    private JsonNode tools;

    @Before
    public void setUp() throws Exception {
        server = new McpServerMain(McpTestSupport.configWithTempAudit(temp.getRoot()));
        JsonRpcCodec codec = new JsonRpcCodec(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream());
        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/list");
        tools = server.getDispatcher().dispatch(request, codec).path("result").path("tools");
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void everyContractToolIsExposed() {
        List<String> exposed = names();
        List<String> missing = new ArrayList<>(CONTRACT_TOOLS);
        missing.removeAll(exposed);
        assertTrue("tools declared in contracts/mcp-tools.md but not exposed: " + missing, missing.isEmpty());
    }

    @Test
    public void noToolIsExposedBeyondTheContract() {
        List<String> extra = new ArrayList<>(names());
        extra.removeAll(CONTRACT_TOOLS);
        assertTrue("tools exposed but absent from contracts/mcp-tools.md: " + extra, extra.isEmpty());
    }

    @Test
    public void everyItemToolRequiresTheCaseAlongsideTheItemId() {
        // Item ids are local to a case and collide between cases. A tool that took an id alone
        // would make it possible to cite an item from the wrong case (FR-003).
        for (JsonNode tool : tools) {
            JsonNode required = tool.path("inputSchema").path("required");
            List<String> names = new ArrayList<>();
            required.forEach(node -> names.add(node.asText()));
            if (names.contains("item_id") || names.contains("item_ids")) {
                assertTrue("case_id must accompany an item reference in " + tool.path("name").asText(),
                        names.contains("case_id"));
            }
        }
    }

    @Test
    public void writeToolsAreDeclaredWithTheirParameters() {
        for (String name : WRITE_TOOLS) {
            JsonNode tool = find(name);
            assertFalse("write tool missing from the surface: " + name, tool.isMissingNode());
            assertTrue("a write tool must take a case_id: " + name,
                    tool.path("inputSchema").path("required").toString().contains("case_id"));
        }
    }

    /** Item types a parameter may declare. An array of anything else is a schema a client cannot fill. */
    private static final Set<String> ITEM_TYPES = new HashSet<>(
            Arrays.asList("integer", "string", "number", "boolean"));

    @Test
    public void arrayParametersDeclareTheirItemType() {
        for (JsonNode tool : tools) {
            JsonNode properties = tool.path("inputSchema").path("properties");
            properties.fieldNames().forEachRemaining(field -> {
                JsonNode property = properties.path(field);
                if ("array".equals(property.path("type").asText())) {
                    String itemType = property.path("items").path("type").asText();
                    assertTrue("array parameter " + field + " of " + tool.path("name").asText()
                            + " must declare a scalar item type, got '" + itemType + "'",
                            ITEM_TYPES.contains(itemType));
                }
            });
        }
    }

    @Test
    public void everyParameterCarriesADescription() {
        // The agent may be a local model. A parameter without a description is a parameter it has
        // to guess at (FR-065).
        for (JsonNode tool : tools) {
            JsonNode properties = tool.path("inputSchema").path("properties");
            properties.fieldNames().forEachRemaining(field -> assertFalse(
                    "parameter " + field + " of " + tool.path("name").asText() + " has no description",
                    properties.path(field).path("description").asText().isEmpty()));
        }
    }

    @Test
    public void searchExposesBookmarkAsAnOptionalFilter() {
        JsonNode schema = find("iped_search").path("inputSchema");
        assertEquals("string", schema.path("properties").path("bookmark").path("type").asText());
        assertFalse("bookmark must remain optional so existing search calls keep working",
                schema.path("required").toString().contains("bookmark"));
    }

    @Test
    public void thereIsNoToolThatWritesToTheAuditTrail() {
        // FR-034: the trail is append-only by the server and not alterable by the agent.
        for (String name : names()) {
            assertFalse("no tool may write to the audit trail: " + name,
                    name.contains("audit") && !name.equals("iped_export_audit"));
        }
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    private JsonNode find(String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        return JsonRpcCodec.mapper().missingNode();
    }
}
