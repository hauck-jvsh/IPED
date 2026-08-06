package iped.mcp.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Common error envelope of the MCP tool surface: {@code {code, message, remedy, details}}.
 *
 * <p>
 * {@code remedy} is mandatory and is not decoration. The agent driving this server may be a local
 * model (FR-065), so every failure has to carry what is needed to correct it without requiring the
 * model to deduce anything. An error without a remedy is a defect, not a style issue.
 *
 * @see <a href="../../../../../../../specs/001-iped-llm-integration/contracts/mcp-tools.md">contracts/mcp-tools.md</a>
 */
public class McpError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // Case and session diagnostics (FR-001, FR-002, FR-054).
    public static final String NOT_A_CASE = "NOT_A_CASE";
    public static final String CASE_INCOMPLETE = "CASE_INCOMPLETE";
    public static final String CASE_IN_PROCESSING = "CASE_IN_PROCESSING";
    public static final String VERSION_UNSUPPORTED = "VERSION_UNSUPPORTED";
    public static final String CASE_NOT_OPEN = "CASE_NOT_OPEN";
    public static final String CASE_INACCESSIBLE = "CASE_INACCESSIBLE";

    // Query diagnostics (FR-008, FR-017).
    public static final String QUERY_SYNTAX = "QUERY_SYNTAX";
    public static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";
    public static final String INVALID_CURSOR = "INVALID_CURSOR";
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";

    // Item diagnostics (FR-022).
    public static final String ITEM_NOT_FOUND = "ITEM_NOT_FOUND";
    public static final String CONTENT_UNAVAILABLE = "CONTENT_UNAVAILABLE";

    // Curation diagnostics (FR-025, FR-028).
    public static final String WRITE_NOT_ENABLED = "WRITE_NOT_ENABLED";
    public static final String CONCURRENT_ACCESS = "CONCURRENT_ACCESS";
    public static final String BOOKMARK_NOT_FOUND = "BOOKMARK_NOT_FOUND";
    public static final String BOOKMARK_EXISTS = "BOOKMARK_EXISTS";

    // Audit diagnostics (FR-035).
    public static final String AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE";

    // Egress policy diagnostics (FR-040, FR-041).
    public static final String BLOCKED_BY_POLICY = "BLOCKED_BY_POLICY";

    // Export diagnostics (FR-068, FR-070).
    public static final String EMPTY_RESULT_SET = "EMPTY_RESULT_SET";
    public static final String DESTINATION_REFUSED = "DESTINATION_REFUSED";
    public static final String EXPORT_FAILED = "EXPORT_FAILED";

    // Protocol diagnostics.
    public static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";
    public static final String MALFORMED_MESSAGE = "MALFORMED_MESSAGE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private final String code;
    private final String remedy;
    private final Map<String, Object> details = new LinkedHashMap<>();

    public McpError(String code, String message, String remedy) {
        super(message);
        this.code = code;
        this.remedy = remedy;
    }

    public McpError(String code, String message, String remedy, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.remedy = remedy;
    }

    /**
     * Adds one entry to {@code details} and returns {@code this}, so callers can build the error in
     * a single expression.
     */
    public McpError with(String key, Object value) {
        details.put(key, value);
        return this;
    }

    /**
     * The same failure carrying guidance a later stage was able to make specific.
     *
     * <p>
     * Used when the layer that detects a failure cannot yet say how to fix it, and the layer above
     * can — a query that fails to parse, for instance, becomes an error naming the exact corrected
     * expression once the server has verified that correction against the case.
     */
    public McpError withRemedy(String newRemedy) {
        McpError reworded = new McpError(code, getMessage(), newRemedy, getCause());
        reworded.details.putAll(details);
        return reworded;
    }

    public String getCode() {
        return code;
    }

    public String getRemedy() {
        return remedy;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    /**
     * Renders the envelope as a plain map, ready for JSON serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", getMessage());
        map.put("remedy", remedy);
        map.put("details", details);
        return map;
    }
}
