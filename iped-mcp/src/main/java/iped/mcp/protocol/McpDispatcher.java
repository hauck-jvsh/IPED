package iped.mcp.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import iped.mcp.audit.AuditRecord;
import iped.mcp.config.McpServerConfig.AccessMode;
import iped.mcp.config.McpServerConfig.ContentClass;
import iped.mcp.session.Session;

/**
 * The MCP method surface: {@code initialize}, {@code tools/list} and {@code tools/call}.
 *
 * <p>
 * This is also where the three cross-cutting invariants are applied, all of them before a tool
 * runs and none of them delegated to the guidance:
 *
 * <ol>
 * <li><b>Every call is audited before it executes</b>, reads included, and refused when the record
 * cannot be written (FR-032, FR-035). Auditing reads too is why the trail is blocking even for a
 * read-only session.</li>
 * <li><b>Curation is gated by access mode</b>: with {@code READ_ONLY} in force, a curation tool is
 * refused with {@code WRITE_NOT_ENABLED} without the case being touched (FR-025).</li>
 * <li><b>The egress policy is applied at the boundary</b>, by declared content class, so it cannot
 * be circumvented through the choice of tool or parameter (FR-040), and every block is recorded
 * with the item and the rule (FR-041).</li>
 * </ol>
 */
public class McpDispatcher {

    /** MCP protocol revision this server implements and declares at handshake. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    public static final String SERVER_NAME = "iped-mcp";

    private final Session session;
    private final String serverVersion;
    private final Map<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private boolean initialized;

    public McpDispatcher(Session session, String serverVersion) {
        this.session = session;
        this.serverVersion = serverVersion;
    }

    public void register(ToolDescriptor tool) {
        tools.put(tool.getName(), tool);
    }

    public void registerAll(Collection<ToolDescriptor> descriptors) {
        descriptors.forEach(this::register);
    }

    public Collection<ToolDescriptor> getTools() {
        return tools.values();
    }

    public Session getSession() {
        return session;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Handles one incoming message.
     *
     * @return the response to write back, or {@code null} when the message was a notification
     */
    public ObjectNode dispatch(JsonNode message, JsonRpcCodec codec) {
        JsonNode id = message.get("id");
        String method = message.path("method").asText(null);
        if (method == null) {
            return codec.newErrorResponse(id, JsonRpcCodec.INVALID_REQUEST, "Missing 'method' member.", null);
        }
        JsonNode params = message.has("params") ? message.get("params") : JsonRpcCodec.mapper().createObjectNode();

        try {
            switch (method) {
                case "initialize":
                    return codec.newResponse(id, initialize(params));
                case "notifications/initialized":
                    initialized = true;
                    return null;
                case "ping":
                    return codec.newResponse(id, JsonRpcCodec.mapper().createObjectNode());
                case "tools/list":
                    return codec.newResponse(id, listTools());
                case "tools/call":
                    return codec.newResponse(id, callTool(params));
                default:
                    if (JsonRpcCodec.isNotification(message)) {
                        return null;
                    }
                    return codec.newErrorResponse(id, JsonRpcCodec.METHOD_NOT_FOUND,
                            "Unknown method '" + method + "'.",
                            JsonRpcCodec.mapper()
                                    .valueToTree(new McpError(McpError.UNKNOWN_TOOL,
                                            "The method '" + method + "' is not implemented by this server.",
                                            "This server implements initialize, tools/list and tools/call. Call "
                                                    + "tools/list to see the tools it exposes.").toMap()));
            }
        } catch (McpError e) {
            return codec.newErrorResponse(id, JsonRpcCodec.INVALID_PARAMS, e.getMessage(),
                    JsonRpcCodec.mapper().valueToTree(e.toMap()));
        } catch (Exception e) {
            McpError wrapped = new McpError(McpError.INTERNAL_ERROR,
                    "The server failed while handling '" + method + "': " + e,
                    "This is a defect in the server, not something the conversation can correct. Report it "
                            + "with the server log attached.");
            return codec.newErrorResponse(id, JsonRpcCodec.INTERNAL_ERROR, wrapped.getMessage(),
                    JsonRpcCodec.mapper().valueToTree(wrapped.toMap()));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        ObjectNode result = JsonRpcCodec.mapper().createObjectNode();
        // The client proposes a version; we answer with the one we actually implement, which is
        // what the client checks before continuing.
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", serverVersion);
        result.put("instructions", String.join("\n", session.getWarnings()));
        return result;
    }

    private ObjectNode listTools() {
        ObjectNode result = JsonRpcCodec.mapper().createObjectNode();
        ArrayNode array = result.putArray("tools");
        for (ToolDescriptor tool : tools.values()) {
            array.add(JsonRpcCodec.mapper().valueToTree(tool.toListEntry()));
        }
        return result;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText(null);
        JsonNode arguments = params.has("arguments") && params.get("arguments").isObject() ? params.get("arguments")
                : JsonRpcCodec.mapper().createObjectNode();

        ToolDescriptor tool = tools.get(name);
        if (tool == null) {
            List<String> known = new ArrayList<>(tools.keySet());
            throw new McpError(McpError.UNKNOWN_TOOL, "There is no tool named '" + name + "'.",
                    "Call tools/list and use one of the names it returns.").with("requested", name).with("available",
                            known);
        }

        // 1. Access mode gate, before anything touches the case (FR-025).
        if (tool.isWriteOperation() && session.getAccessMode() != AccessMode.READ_WRITE) {
            McpError denial = new McpError(McpError.WRITE_NOT_ENABLED,
                    "Writing is not enabled in this session, so '" + name + "' was refused. The case was not "
                            + "touched.",
                    "Enabling writes is outside this conversation's reach: an examiner sets accessMode to "
                            + "READ_WRITE in conf/McpServerConfig.txt and restarts the server. Until then, "
                            + "report the finding rather than recording it.").with("tool", name)
                                    .with("accessMode", session.getAccessMode().name());
            auditDenial(tool, name, arguments, denial);
            throw denial;
        }

        // 2. Concurrency: refuse a write while another local process holds the case (FR-028).
        // Reading is never blocked, which is why this sits inside the write branch.
        if (tool.isWriteOperation()) {
            String targetCase = arguments.path("case_id").asText(null);
            if (targetCase != null && !targetCase.isEmpty()) {
                try {
                    iped.mcp.session.OpenCase openCase = session.getCaseRegistry().require(targetCase);
                    session.getConcurrencyGuard().acquireWriteLock(targetCase, openCase.getCasePath(),
                            session.getConfig().getAuditFolderNameInCase());
                } catch (McpError e) {
                    if (McpError.CONCURRENT_ACCESS.equals(e.getCode())) {
                        auditDenial(tool, name, arguments, e);
                        throw e;
                    }
                    // The case is not open; the tool produces the clearer diagnostic itself.
                }
            }
        }

        // 3. Egress policy at the boundary, by declared content class (FR-040).
        McpError policyDenial = null;
        if (tool.getContentClass() != null && session.getEgressPolicy().isActive()) {
            ContentClass contentClass = ContentClass.valueOf(tool.getContentClass());
            if (!session.getConfig().getEgressAllowedClasses().contains(contentClass)) {
                policyDenial = new McpError(McpError.BLOCKED_BY_POLICY,
                        "The egress policy in force does not allow content of class '" + contentClass + "', so '"
                                + name + "' returns nothing.",
                        "This is applied by the server and cannot be worked around from the conversation. Use "
                                + "the metadata tools, which remain available, or ask the examiner to review "
                                + "the egress policy in conf/McpServerConfig.txt.").with("rule", "allowedClasses")
                                        .with("contentClass", contentClass.name()).with("tool", name);
            }
        }

        // 4. Write-ahead audit record. Nothing runs before this succeeds (FR-035).
        Map<String, Object> priorState = null;
        if (tool.getPriorStateProvider() != null) {
            priorState = tool.getPriorStateProvider().capture(arguments);
        }
        String caseId = arguments.path("case_id").asText(null);
        String caseBinding = null;
        if (caseId != null && !caseId.isEmpty()) {
            try {
                caseBinding = session.getCaseRegistry().require(caseId).getCaseBinding();
            } catch (McpError ignored) {
                // The case is not open; the tool itself produces the diagnostic. The trail still
                // records the attempt, with the case id as given.
            }
        }
        AuditRecord start = session.getAuditTrail().recordStart(name, toMap(arguments), caseId, caseBinding,
                priorState);

        if (policyDenial != null) {
            session.getAuditTrail().recordEnd(start, AuditRecord.Outcome.DENIED, null, blockDetails(policyDenial));
            throw policyDenial;
        }

        try {
            Object result = tool.invoke(arguments);
            session.getAuditTrail().recordEnd(start, AuditRecord.Outcome.OK, volumeOf(result), null);
            return renderResult(result);
        } catch (McpError e) {
            Map<String, Object> blocked = isPolicyRefusal(e) ? blockDetails(e) : null;
            session.getAuditTrail().recordEnd(start,
                    blocked != null ? AuditRecord.Outcome.DENIED : AuditRecord.Outcome.ERROR, null, blocked);
            throw e;
        } catch (Exception e) {
            session.getAuditTrail().recordEnd(start, AuditRecord.Outcome.ERROR, null, null);
            throw new McpError(McpError.INTERNAL_ERROR, "The tool '" + name + "' failed: " + e,
                    "This is a defect in the server. Report it with the server log attached; the conversation "
                            + "cannot correct it.",
                    e);
        }
    }

    /** A refusal is an operation too: it is recorded, so the trail shows what was attempted. */
    private void auditDenial(ToolDescriptor tool, String name, JsonNode arguments, McpError denial) {
        String caseId = arguments.path("case_id").asText(null);
        AuditRecord start = session.getAuditTrail().recordStart(name, toMap(arguments), caseId, null, null);
        session.getAuditTrail().recordEnd(start, AuditRecord.Outcome.DENIED, null, null);
    }

    /**
     * Whether a refusal is a policy decision rather than a failure.
     *
     * <p>
     * The distinction matters to the trail. A failure says the operation could not be carried out; a
     * policy refusal says it was stopped on purpose, and the rule that stopped it belongs in the
     * record — content blocked by the egress policy (FR-041), and a destination outside the declared
     * write roots (FR-007). Both are recorded as {@code DENIED} with their details attached.
     */
    private static boolean isPolicyRefusal(McpError error) {
        return McpError.BLOCKED_BY_POLICY.equals(error.getCode())
                || McpError.DESTINATION_REFUSED.equals(error.getCode());
    }

    private static Map<String, Object> blockDetails(McpError error) {
        Map<String, Object> details = new LinkedHashMap<>(error.getDetails());
        details.put("code", error.getCode());
        return details;
    }

    /** Result volume recorded in the trail: how many items the call actually returned. */
    @SuppressWarnings("unchecked")
    private static Integer volumeOf(Object result) {
        if (result instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) result;
            for (String key : new String[] { "items", "buckets", "bookmarks", "fields", "item_ids" }) {
                Object value = map.get(key);
                if (value instanceof Collection) {
                    return ((Collection<?>) value).size();
                }
            }
            Object count = map.get("item_count");
            if (count instanceof Number) {
                return ((Number) count).intValue();
            }
        }
        return null;
    }

    /**
     * Renders a tool result in the shape MCP clients expect: a text block carrying the JSON, plus
     * the same payload as structured content for clients that read it.
     */
    private static ObjectNode renderResult(Object result) {
        ObjectNode response = JsonRpcCodec.mapper().createObjectNode();
        JsonNode structured = JsonRpcCodec.mapper().valueToTree(result);
        ArrayNode content = response.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        try {
            text.put("text", JsonRpcCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(structured));
        } catch (Exception e) {
            text.put("text", String.valueOf(structured));
        }
        response.set("structuredContent", structured);
        response.put("isError", false);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return JsonRpcCodec.mapper().convertValue(node, Map.class);
    }
}
