package iped.mcp.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import iped.mcp.protocol.McpError;

/**
 * Argument reading with diagnostics an agent can act on by itself.
 *
 * <p>
 * A missing or mistyped argument is the most common failure in a tool call, and the message it
 * produces is the first thing a local model has to work from. "Expected integer, got string" is not
 * enough; the message has to name the parameter and say what to pass.
 */
final class Args {

    private Args() {
    }

    static String requiredString(JsonNode arguments, String name, String hint) {
        JsonNode node = arguments.get(name);
        if (node == null || node.isNull() || node.asText().isEmpty()) {
            throw new McpError(McpError.INVALID_ARGUMENT, "The required parameter '" + name + "' is missing.", hint)
                    .with("parameter", name);
        }
        return node.asText();
    }

    static String optionalString(JsonNode arguments, String name, String fallback) {
        JsonNode node = arguments.get(name);
        return node == null || node.isNull() ? fallback : node.asText();
    }

    static int requiredInt(JsonNode arguments, String name, String hint) {
        JsonNode node = arguments.get(name);
        if (node == null || node.isNull()) {
            throw new McpError(McpError.INVALID_ARGUMENT, "The required parameter '" + name + "' is missing.", hint)
                    .with("parameter", name);
        }
        if (!node.canConvertToInt()) {
            throw new McpError(McpError.INVALID_ARGUMENT,
                    "The parameter '" + name + "' must be an integer, but '" + node.asText() + "' was given.", hint)
                            .with("parameter", name).with("received", node.asText());
        }
        return node.asInt();
    }

    static Integer optionalInt(JsonNode arguments, String name) {
        JsonNode node = arguments.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.canConvertToInt()) {
            throw new McpError(McpError.INVALID_ARGUMENT,
                    "The parameter '" + name + "' must be an integer, but '" + node.asText() + "' was given.",
                    "Pass a number, or omit the parameter to use the server default.").with("parameter", name);
        }
        return node.asInt();
    }

    static Long optionalLong(JsonNode arguments, String name) {
        Integer value = optionalInt(arguments, name);
        return value == null ? null : value.longValue();
    }

    static boolean optionalBoolean(JsonNode arguments, String name, boolean fallback) {
        JsonNode node = arguments.get(name);
        return node == null || node.isNull() ? fallback : node.asBoolean(fallback);
    }

    static List<Integer> requiredIntList(JsonNode arguments, String name, String hint, int maxSize) {
        JsonNode node = arguments.get(name);
        if (node == null || !node.isArray() || node.size() == 0) {
            throw new McpError(McpError.INVALID_ARGUMENT,
                    "The required parameter '" + name + "' must be a non-empty array of integers.", hint)
                            .with("parameter", name);
        }
        if (node.size() > maxSize) {
            throw new McpError(McpError.INVALID_ARGUMENT,
                    "The parameter '" + name + "' has " + node.size() + " entries, past the server batch ceiling of "
                            + maxSize + ".",
                    "Split the request into batches of at most " + maxSize + " ids.").with("parameter", name)
                            .with("received", node.size()).with("maxBatchSize", maxSize);
        }
        List<Integer> values = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            if (!entry.canConvertToInt()) {
                throw new McpError(McpError.INVALID_ARGUMENT,
                        "The array '" + name + "' contains '" + entry.asText() + "', which is not an integer.", hint)
                                .with("parameter", name).with("received", entry.asText());
            }
            values.add(entry.asInt());
        }
        return values;
    }
}
