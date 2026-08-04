package iped.mcp.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One tool exposed through {@code tools/list} and invocable through {@code tools/call}.
 *
 * <p>
 * The input schema is JSON Schema, built here rather than hand-written, so name and type always
 * agree between the declared schema and the argument the handler reads.
 */
public class ToolDescriptor {

    /** Invocation contract of a tool. */
    public interface Handler {
        /**
         * @param arguments
         *            the {@code arguments} object of {@code tools/call}, never {@code null}
         * @return the tool result, serialized as the {@code structuredContent} of the response
         */
        Object call(JsonNode arguments) throws Exception;
    }

    /**
     * Captures the state a destructive operation is about to overwrite, so the audit trail carries
     * it <i>before</i> the operation runs (FR-033).
     */
    public interface PriorStateProvider {
        Map<String, Object> capture(JsonNode arguments);
    }

    /** One declared input parameter. */
    public static class Parameter {
        final String name;
        final String type;
        final String description;
        final boolean required;

        Parameter(String name, String type, String description, boolean required) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.required = required;
        }
    }

    private final String name;
    private final String description;
    private final List<Parameter> parameters = new ArrayList<>();
    private final Handler handler;
    private boolean requiresWrite;
    private String contentClass;
    private PriorStateProvider priorStateProvider;

    public ToolDescriptor(String name, String description, Handler handler) {
        this.name = name;
        this.description = description;
        this.handler = handler;
    }

    public ToolDescriptor required(String paramName, String type, String description) {
        parameters.add(new Parameter(paramName, type, description, true));
        return this;
    }

    public ToolDescriptor optional(String paramName, String type, String description) {
        parameters.add(new Parameter(paramName, type, description, false));
        return this;
    }

    /**
     * Marks the tool as curation: the dispatcher refuses it with {@code WRITE_NOT_ENABLED} while the
     * session is read-only, without touching the case (FR-025).
     */
    public ToolDescriptor writeOperation() {
        this.requiresWrite = true;
        return this;
    }

    /**
     * Declares which class of evidence content this tool returns, so the dispatcher can apply the
     * egress policy at the boundary — before any argument is even read. Enforcing at the boundary
     * rather than inside each handler is what makes the policy impossible to get around by choosing
     * a different tool or parameter (FR-040).
     */
    public ToolDescriptor returnsContent(String contentClass) {
        this.contentClass = contentClass;
        return this;
    }

    public ToolDescriptor capturingPriorState(PriorStateProvider provider) {
        this.priorStateProvider = provider;
        return this;
    }

    public String getContentClass() {
        return contentClass;
    }

    public PriorStateProvider getPriorStateProvider() {
        return priorStateProvider;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isWriteOperation() {
        return requiresWrite;
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    public Object invoke(JsonNode arguments) throws Exception {
        return handler.call(arguments);
    }

    /**
     * Renders the {@code tools/list} entry, input schema included.
     */
    public Map<String, Object> toListEntry() {
        ObjectNode schema = JsonRpcCodec.mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = JsonRpcCodec.mapper().createArrayNode();
        for (Parameter p : parameters) {
            ObjectNode property = properties.putObject(p.name);
            if (p.type.endsWith("[]")) {
                property.put("type", "array");
                property.putObject("items").put("type", p.type.substring(0, p.type.length() - 2));
            } else {
                property.put("type", p.type);
            }
            property.put("description", p.description);
            if (p.required) {
                required.add(p.name);
            }
        }
        schema.set("required", required);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("description", description);
        entry.put("inputSchema", schema);
        return entry;
    }
}
