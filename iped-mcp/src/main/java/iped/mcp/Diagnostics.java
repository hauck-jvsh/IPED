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
        checkWriteRoots(config);
        checkTransport(config);
        checkJavaVersion();
        return this;
    }

    /**
     * Reports what the transport configuration will actually do (FR-018, FR-026).
     *
     * <p>
     * The check that matters most is the one for the secret. Everything else here is advisory; a
     * network transport configured without a secret does not start at all, because a transport
     * without authentication is not a degraded transport.
     */
    private void checkTransport(McpServerConfig config) {
        if (config.getTransport() != McpServerConfig.TransportMode.SOCKET) {
            checks.add(new Check("transport", true, "stdio; no network port is opened", null));
            return;
        }
        String secretProblem = config.describeSecretProblem();
        if (secretProblem != null) {
            checks.add(new Check("transport_secret", false,
                    "The network transport is configured but no shared secret resolves: " + secretProblem + ".",
                    "The endpoint will not be established. Set " + McpServerConfig.SHARED_SECRET_ENV
                            + " in the environment, or point sharedSecretFile at a file containing the secret. "
                            + "It must not be written into conf/" + McpServerConfig.CONFIG_FILE
                            + ", which ships with the release and tends to be version controlled."));
        } else {
            checks.add(new Check("transport_secret", true, "resolved", null));
        }
        String endpoint = config.describeListenEndpoint();
        if (endpoint == null) {
            checks.add(new Check("transport", false,
                    "The network transport is configured but listenAddress and listenPort are not.",
                    "There is no default on purpose — a server that picks an address for you may expose more "
                            + "than you meant. Declare both in conf/" + McpServerConfig.CONFIG_FILE + "."));
        } else {
            checks.add(new Check("transport", true, "socket on " + endpoint
                    + "; evidence content will cross this connection unencrypted", null));
        }
    }

    /**
     * Reports the state of every declared write root (FR-006).
     *
     * <p>
     * An unusable root does not stop the server, in keeping with the rest of this class: the failure
     * is named here, and the first export under that root fails with something the examiner can act
     * on. Refusing to start would turn one mistyped path into an unavailable server.
     */
    private void checkWriteRoots(McpServerConfig config) {
        boolean usingDefault = config.getExportRoots().isEmpty();
        for (McpServerConfig.WriteRoot root : config.getWriteRoots()) {
            if (root.isUsable()) {
                checks.add(new Check("write_root", true,
                        root.getResolved() + (usingDefault ? " (default; exportRoots is not declared)" : ""), null));
                continue;
            }
            String problem;
            switch (root.getState()) {
                case NOT_A_DIRECTORY:
                    problem = "is not a folder";
                    break;
                case NOT_WRITABLE:
                    problem = "is not writable by this account";
                    break;
                case MISSING:
                default:
                    problem = "does not exist";
                    break;
            }
            checks.add(new Check("write_root", false,
                    "The declared write root " + root.getDeclared() + " " + problem + ".",
                    "Artifacts can only be written under a declared root, so exports aimed at this one will "
                            + "be refused. Create it, grant write permission on it, or correct exportRoots in "
                            + "conf/" + McpServerConfig.CONFIG_FILE + "."));
        }
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
