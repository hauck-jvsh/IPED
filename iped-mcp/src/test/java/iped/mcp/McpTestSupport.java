package iped.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Assume;

import iped.mcp.config.McpServerConfig;

/**
 * Shared plumbing for the test suites.
 *
 * <p>
 * <b>On the reference cases.</b> Most requirements of this feature can only be verified against a
 * real processed case, and a real case cannot live in the repository — it is large, and a case with
 * meaningful content is by nature not something to commit. So the integration suites resolve the
 * case from a system property or an environment variable and skip when it is absent.
 *
 * <p>
 * A skipped test is not a passing test. The recipe for building the small reference case is
 * versioned at {@code src/test/resources/reference-case/README.md}, and the suites are only
 * meaningful once it has been built and pointed at.
 *
 * <ul>
 * <li>{@code -Diped.mcp.test.referenceCase=<path>} or {@code IPED_MCP_REFERENCE_CASE} — the small
 * case of known, non-sensitive content.</li>
 * <li>{@code -Diped.mcp.test.largeCase=<path>} or {@code IPED_MCP_LARGE_CASE} — the ~10 M item case
 * used by the scale suite. The difference between paging and materializing does not show up on the
 * small case.</li>
 * </ul>
 */
public final class McpTestSupport {

    public static final String REFERENCE_CASE_PROPERTY = "iped.mcp.test.referenceCase";
    public static final String REFERENCE_CASE_ENV = "IPED_MCP_REFERENCE_CASE";
    public static final String LARGE_CASE_PROPERTY = "iped.mcp.test.largeCase";
    public static final String LARGE_CASE_ENV = "IPED_MCP_LARGE_CASE";

    private McpTestSupport() {
    }

    public static File referenceCase() {
        return resolve(REFERENCE_CASE_PROPERTY, REFERENCE_CASE_ENV);
    }

    public static File largeCase() {
        return resolve(LARGE_CASE_PROPERTY, LARGE_CASE_ENV);
    }

    private static File resolve(String property, String env) {
        String path = System.getProperty(property);
        if (path == null || path.trim().isEmpty()) {
            path = System.getenv(env);
        }
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = new File(path.trim());
        return file.isDirectory() ? file : null;
    }

    /**
     * Skips the calling test when the small reference case is not available, naming what to set.
     */
    public static File requireReferenceCase() {
        File referenceCase = referenceCase();
        Assume.assumeTrue("Reference case not available. Build it from "
                + "iped-mcp/src/test/resources/reference-case/README.md and set -D" + REFERENCE_CASE_PROPERTY
                + "=<path> (or " + REFERENCE_CASE_ENV + ").", referenceCase != null);
        return referenceCase;
    }

    /**
     * Skips the calling test when the large case is not available. Running the scale suite against
     * the small case would pass and prove nothing.
     */
    public static File requireLargeCase() {
        File largeCase = largeCase();
        Assume.assumeTrue("Large reference case (~10 M items) not available. Set -D" + LARGE_CASE_PROPERTY
                + "=<path> (or " + LARGE_CASE_ENV + "). SC-002 and SC-015 are not verified without it.",
                largeCase != null);
        return largeCase;
    }

    /** A configuration whose audit area points at a temporary folder, isolated per test. */
    public static McpServerConfig configWithTempAudit(File tempRoot) throws IOException {
        McpServerConfig config = new McpServerConfig();
        File auditArea = new File(tempRoot, "audit");
        Files.createDirectories(auditArea.toPath());
        config.setAuditArea(auditArea);
        return config;
    }
}
