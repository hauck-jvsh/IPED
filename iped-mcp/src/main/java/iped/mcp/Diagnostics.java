package iped.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.mcp.config.McpServerConfig;

/**
 * Pre-flight check of everything the server needs, reporting what is missing and how to fix it
 * (FR-053).
 *
 * <p>
 * The audience is an examiner who has never wired an agent to anything. A stack trace tells them
 * nothing; "IPED not found — set iped.mcp.ipedRoot to the folder containing iped.jar" tells them
 * exactly what to do. Every check here answers the second kind of question.
 *
 * <p>
 * <b>Logging goes through SLF4J, never {@code System.out} or {@code System.err}</b> (constitution,
 * Principle V). That is not a style rule here: the protocol itself runs over stdout, so a stray
 * print would corrupt the JSON-RPC stream and break the session in a way that looks like a protocol
 * bug.
 */
public class Diagnostics {

    private static final Logger LOGGER = LoggerFactory.getLogger(Diagnostics.class);

    /** System property naming the IPED installation root. */
    public static final String IPED_ROOT_PROPERTY = "iped.mcp.ipedRoot";

    /** Environment variable naming the IPED installation root. */
    public static final String IPED_ROOT_ENV = "IPED_ROOT";

    /** One check and its outcome. */
    public static class Check {
        public final String name;
        public final boolean ok;
        public final String detail;
        public final String remedy;

        Check(String name, boolean ok, String detail, String remedy) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
            this.remedy = remedy;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("check", name);
            map.put("ok", ok);
            map.put("detail", detail);
            if (!ok) {
                map.put("remedy", remedy);
            }
            return map;
        }
    }

    private final List<Check> checks = new ArrayList<>();

    /**
     * Runs every check.
     *
     * @param ipedRoot
     *            the resolved IPED installation root, or {@code null} when it could not be found
     */
    public Diagnostics run(File ipedRoot, McpServerConfig config) {
        checkIpedRoot(ipedRoot);
        checkAuditArea(config);
        checkJavaVersion();
        return this;
    }

    private void checkIpedRoot(File ipedRoot) {
        if (ipedRoot == null) {
            checks.add(new Check("iped_installation", false, "The IPED installation could not be located.",
                    "Set -D" + IPED_ROOT_PROPERTY + "=<path> on the server command line, or the " + IPED_ROOT_ENV
                            + " environment variable, to the folder that contains iped.jar and the conf/ "
                            + "folder. That folder is the root of an unpacked IPED release."));
            return;
        }
        if (!ipedRoot.isDirectory()) {
            checks.add(new Check("iped_installation", false, ipedRoot.getAbsolutePath() + " is not a folder.",
                    "Point " + IPED_ROOT_PROPERTY + " at the folder that contains iped.jar, not at a file."));
            return;
        }
        File conf = new File(ipedRoot, "conf");
        if (!conf.isDirectory()) {
            checks.add(new Check("iped_installation", false,
                    "There is no conf/ folder under " + ipedRoot.getAbsolutePath() + ".",
                    "That path does not look like an IPED release. Point " + IPED_ROOT_PROPERTY
                            + " at the folder that contains iped.jar, conf/ and lib/."));
            return;
        }
        File serverConfig = new File(conf, McpServerConfig.CONFIG_FILE);
        if (!serverConfig.isFile()) {
            checks.add(new Check("server_configuration", false,
                    "conf/" + McpServerConfig.CONFIG_FILE + " is missing from " + ipedRoot.getAbsolutePath() + ".",
                    "The server falls back to its built-in defaults, which are read-only access with the "
                            + "egress policy inactive. Copy " + McpServerConfig.CONFIG_FILE
                            + " from the release into conf/ to change any of that."));
        } else {
            checks.add(new Check("server_configuration", true, serverConfig.getAbsolutePath(), null));
        }
        checks.add(new Check("iped_installation", true, ipedRoot.getAbsolutePath(), null));
    }

    private void checkAuditArea(McpServerConfig config) {
        File area = config.getAuditArea();
        try {
            Files.createDirectories(area.toPath());
            File probe = new File(area, ".write-probe");
            Files.write(probe.toPath(), new byte[0]);
            Files.deleteIfExists(probe.toPath());
            checks.add(new Check("audit_area", true, area.getAbsolutePath(), null));
        } catch (IOException | SecurityException e) {
            checks.add(new Check("audit_area", false,
                    "The audit area at " + area.getAbsolutePath() + " is not writable: " + e.getMessage(),
                    "No operation runs without being recorded first, so the server will refuse everything "
                            + "until this is fixed. Grant write permission on that folder, or set auditArea in "
                            + "conf/" + McpServerConfig.CONFIG_FILE + " to a path this account can write to."));
        }
    }

    private void checkJavaVersion() {
        String version = System.getProperty("java.version", "unknown");
        checks.add(new Check("java_runtime", true, version + " (" + System.getProperty("java.vendor", "unknown")
                + ")", null));
    }

    public boolean isOk() {
        return checks.stream().allMatch(check -> check.ok);
    }

    public List<Check> getChecks() {
        return checks;
    }

    public List<Check> getFailures() {
        List<Check> failures = new ArrayList<>();
        for (Check check : checks) {
            if (!check.ok) {
                failures.add(check);
            }
        }
        return failures;
    }

    public Map<String, Object> toMap() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Check check : checks) {
            entries.add(check.toMap());
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", isOk());
        map.put("checks", entries);
        return map;
    }

    /** Writes the outcome to the server log, which is separate from the forensic trail (FR-056). */
    public void log() {
        for (Check check : checks) {
            if (check.ok) {
                LOGGER.info("Diagnostic {}: OK - {}", check.name, check.detail);
            } else {
                LOGGER.error("Diagnostic {}: FAILED - {} | {}", check.name, check.detail, check.remedy);
            }
        }
    }

    /**
     * Resolves the IPED installation root from the system property, the environment, or the
     * location of the running jar.
     *
     * @return the root, or {@code null} when it could not be determined
     */
    public static File resolveIpedRoot() {
        String configured = System.getProperty(IPED_ROOT_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv(IPED_ROOT_ENV);
        }
        if (configured != null && !configured.trim().isEmpty()) {
            File root = new File(configured.trim());
            return root.isDirectory() ? root : null;
        }
        try {
            File jar = new File(
                    Diagnostics.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // In a release the server jar sits in lib/, so the root is its parent's parent.
            File candidate = jar.isFile() ? jar.getParentFile().getParentFile() : jar.getParentFile();
            if (candidate != null && new File(candidate, "conf").isDirectory()) {
                return candidate;
            }
        } catch (Exception e) {
            LOGGER.debug("IPED root could not be derived from the code source", e);
        }
        return null;
    }
}
