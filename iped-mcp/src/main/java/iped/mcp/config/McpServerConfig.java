package iped.mcp.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import iped.configuration.Configurable;
import iped.engine.config.ConfigurationManager;
import iped.utils.UTF8Properties;

/**
 * Externally configurable behaviour of the MCP server, read from {@code conf/McpServerConfig.txt}
 * like the rest of IPED.
 *
 * <p>
 * Constitution, Principle IV: audit area, access mode, egress policy, page ceilings and content
 * ceilings are configurable behaviour and MUST live in configuration, never in code constants.
 * {@code Configurable<T>} lives in {@code iped-api} and is generic — it is
 * {@code AbstractTaskConfig<T>} that specializes it for pipeline tasks — so a server process is a
 * legitimate consumer.
 *
 * <p>
 * The in-code values below are last-resort fallbacks for when the configuration file cannot be
 * found at all (a bare unit test, a broken installation). The shipped file is authoritative and
 * carries the same values; the fallbacks exist so that a missing file degrades into a documented
 * default instead of a crash.
 */
public class McpServerConfig implements Configurable<UTF8Properties> {

    private static final long serialVersionUID = 1L;

    public static final String CONFIG_FILE = "McpServerConfig.txt";

    public static final DirectoryStream.Filter<Path> filter = new Filter<Path>() {
        @Override
        public boolean accept(Path entry) throws IOException {
            return entry.endsWith(CONFIG_FILE);
        }
    };

    /** Access mode of a session. Never changeable by a tool exposed to the agent (FR-025). */
    public enum AccessMode {
        READ_ONLY, READ_WRITE
    }

    /** How clients reach the server. {@code STDIO} opens no port at all (FR-011). */
    public enum TransportMode {
        STDIO, SOCKET
    }

    /** Classes of evidence-derived content the egress policy can allow or block (FR-039). */
    public enum ContentClass {
        metadata, text, thumbnail, binary
    }

    private String auditArea = new File(System.getProperty("user.home"), ".iped/mcp-audit").getAbsolutePath();
    private String auditFolderNameInCase = "mcp-audit";
    private int auditSyncIntervalSeconds = 60;

    private AccessMode accessMode = AccessMode.READ_ONLY;

    private boolean egressPolicyActive = false;
    private Set<ContentClass> egressAllowedClasses = new LinkedHashSet<>(Arrays.asList(ContentClass.values()));
    private List<String> egressRestrictedCategories = new ArrayList<>();
    private List<String> egressRestrictedSensitivity = new ArrayList<>();

    private int defaultPageSize = 50;
    private int maxPageSize = 200;
    private int maxBatchSize = 200;
    private long queryTimeoutMs = 30000;
    private boolean autoEscapeFieldNames = false;

    private int maxTextBytes = 100000;
    private int maxContentBytes = 262144;
    private int maxThumbnailBytes = 262144;
    private int snippetLength = 300;
    private int snippetMaxItemsPerPage = 25;
    private int snippetMaxTextBytes = 20000;
    private long snippetBudgetMs = 3000;

    private String supportedVersionPrefix = "4.";
    private boolean allowExportIntoCaseFolder = false;
    private List<String> exportRoots = new ArrayList<>();

    /** Off by default: an installation that configures nothing opens no port (FR-011). */
    private TransportMode transport = TransportMode.STDIO;
    /** No default, and in particular never every interface (FR-012, Principle V). */
    private String listenAddress = null;
    private int listenPort = 0;
    private String sharedSecretFile = null;
    private int maxConcurrentSessions = 4;
    private int sessionIdleTimeoutSeconds = 300;

    private UTF8Properties properties = new UTF8Properties();

    @Override
    public Filter<Path> getResourceLookupFilter() {
        return filter;
    }

    @Override
    public UTF8Properties getConfiguration() {
        return properties;
    }

    @Override
    public void setConfiguration(UTF8Properties config) {
        this.properties = config;
    }

    @Override
    public void processConfig(Path resource) throws IOException {
        properties.load(resource.toFile());
        processProperties(properties);
    }

    /** Parses the loaded properties into the typed values the server reads. */
    public void processProperties(UTF8Properties properties) {
        auditArea = str(properties, "auditArea", auditArea);
        auditFolderNameInCase = str(properties, "auditFolderNameInCase", auditFolderNameInCase);
        auditSyncIntervalSeconds = integer(properties, "auditSyncIntervalSeconds", auditSyncIntervalSeconds);

        String mode = str(properties, "accessMode", accessMode.name());
        accessMode = AccessMode.valueOf(mode.trim().toUpperCase());

        egressPolicyActive = bool(properties, "egressPolicyActive", egressPolicyActive);
        String allowed = str(properties, "egressAllowedClasses", null);
        if (allowed != null) {
            Set<ContentClass> classes = new LinkedHashSet<>();
            for (String token : allowed.split(",")) {
                if (!token.trim().isEmpty()) {
                    classes.add(ContentClass.valueOf(token.trim().toLowerCase()));
                }
            }
            egressAllowedClasses = classes;
        }
        egressRestrictedCategories = list(properties, "egressRestrictedCategories", egressRestrictedCategories);
        egressRestrictedSensitivity = list(properties, "egressRestrictedSensitivity", egressRestrictedSensitivity);

        defaultPageSize = integer(properties, "defaultPageSize", defaultPageSize);
        maxPageSize = integer(properties, "maxPageSize", maxPageSize);
        maxBatchSize = integer(properties, "maxBatchSize", maxBatchSize);
        queryTimeoutMs = integer(properties, "queryTimeoutMs", (int) queryTimeoutMs);
        autoEscapeFieldNames = bool(properties, "autoEscapeFieldNames", autoEscapeFieldNames);

        maxTextBytes = integer(properties, "maxTextBytes", maxTextBytes);
        maxContentBytes = integer(properties, "maxContentBytes", maxContentBytes);
        maxThumbnailBytes = integer(properties, "maxThumbnailBytes", maxThumbnailBytes);
        snippetLength = integer(properties, "snippetLength", snippetLength);
        snippetMaxItemsPerPage = integer(properties, "snippetMaxItemsPerPage", snippetMaxItemsPerPage);
        snippetMaxTextBytes = integer(properties, "snippetMaxTextBytes", snippetMaxTextBytes);
        snippetBudgetMs = integer(properties, "snippetBudgetMs", (int) snippetBudgetMs);

        supportedVersionPrefix = str(properties, "supportedVersionPrefix", supportedVersionPrefix);
        allowExportIntoCaseFolder = bool(properties, "allowExportIntoCaseFolder", allowExportIntoCaseFolder);
        exportRoots = pathList(properties, "exportRoots", exportRoots);

        transport = TransportMode.valueOf(str(properties, "transport", transport.name()).trim().toUpperCase());
        listenAddress = str(properties, "listenAddress", listenAddress);
        listenPort = integer(properties, "listenPort", listenPort);
        sharedSecretFile = str(properties, "sharedSecretFile", sharedSecretFile);
        maxConcurrentSessions = integer(properties, "maxConcurrentSessions", maxConcurrentSessions);
        sessionIdleTimeoutSeconds = integer(properties, "sessionIdleTimeoutSeconds", sessionIdleTimeoutSeconds);
    }

    private static String str(UTF8Properties p, String key, String fallback) {
        String value = p.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int integer(UTF8Properties p, String key, int fallback) {
        String value = str(p, key, null);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static boolean bool(UTF8Properties p, String key, boolean fallback) {
        String value = str(p, key, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static List<String> list(UTF8Properties p, String key, List<String> fallback) {
        String value = str(p, key, null);
        if (value == null) {
            return fallback;
        }
        List<String> result = new ArrayList<>();
        for (String token : value.split(",")) {
            if (!token.trim().isEmpty()) {
                result.add(token.trim());
            }
        }
        return result;
    }

    /**
     * Returns the instance registered with the {@link ConfigurationManager}, registering and
     * loading it on first use. Returns a defaults-only instance when the configuration system has
     * not been initialized, so that the server can still report a diagnostic instead of failing
     * with a null reference.
     */
    public static McpServerConfig get() {
        ConfigurationManager manager = ConfigurationManager.get();
        if (manager == null) {
            return new McpServerConfig();
        }
        McpServerConfig config = manager.findObject(McpServerConfig.class);
        if (config == null) {
            config = new McpServerConfig();
            manager.addObject(config);
            try {
                manager.loadConfig(config);
            } catch (IOException e) {
                // Keep the fallback values; Diagnostics reports the unreadable configuration.
                return config;
            }
        }
        return config;
    }

    /**
     * Splits a list of filesystem paths.
     *
     * <p>
     * Separated by {@code ;}, not by the {@code ,} the other list keys use. A Windows path carries a
     * comma often enough that splitting on one would silently cut a declared root in half, and the
     * examiner would see a root they wrote being ignored with no error to explain it. Semicolon is
     * also what {@code PATH} uses on the platform, so it reads as a path list to whoever edits it.
     */
    private static List<String> pathList(UTF8Properties p, String key, List<String> fallback) {
        String value = str(p, key, null);
        if (value == null) {
            return fallback;
        }
        List<String> result = new ArrayList<>();
        for (String token : value.split(";")) {
            if (!token.trim().isEmpty()) {
                result.add(token.trim());
            }
        }
        return result;
    }

    /**
     * Loads the configuration straight from a file, for standalone use and tests where no
     * {@link ConfigurationManager} exists.
     */
    public static McpServerConfig loadFromFile(File file) throws IOException {
        McpServerConfig config = new McpServerConfig();
        UTF8Properties properties = new UTF8Properties();
        properties.load(file);
        config.setConfiguration(properties);
        config.processProperties(properties);
        return config;
    }

    public File getAuditArea() {
        return new File(auditArea);
    }

    public void setAuditArea(File area) {
        this.auditArea = area.getAbsolutePath();
    }

    public String getAuditFolderNameInCase() {
        return auditFolderNameInCase;
    }

    public int getAuditSyncIntervalSeconds() {
        return auditSyncIntervalSeconds;
    }

    public AccessMode getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(AccessMode accessMode) {
        this.accessMode = accessMode;
    }

    public boolean isEgressPolicyActive() {
        return egressPolicyActive;
    }

    public void setEgressPolicyActive(boolean active) {
        this.egressPolicyActive = active;
    }

    public Set<ContentClass> getEgressAllowedClasses() {
        return egressAllowedClasses;
    }

    public void setEgressAllowedClasses(Set<ContentClass> classes) {
        this.egressAllowedClasses = classes;
    }

    public List<String> getEgressRestrictedCategories() {
        return egressRestrictedCategories;
    }

    public void setEgressRestrictedCategories(List<String> categories) {
        this.egressRestrictedCategories = categories;
    }

    public List<String> getEgressRestrictedSensitivity() {
        return egressRestrictedSensitivity;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public long getQueryTimeoutMs() {
        return queryTimeoutMs;
    }

    /**
     * Whether the server may repair an expression whose only defect is an unescaped colon inside a
     * field name this case actually has.
     *
     * <p>
     * Off by default: the expression recorded and answered is the expression asked for. With it on,
     * a repair is applied and declared in the result as {@code query_normalized} — never silently.
     * It exists for weaker local models, which spend the session looping on the escape instead of
     * working the case.
     */
    public boolean isAutoEscapeFieldNames() {
        return autoEscapeFieldNames;
    }

    public int getMaxTextBytes() {
        return maxTextBytes;
    }

    public int getMaxContentBytes() {
        return maxContentBytes;
    }

    public int getMaxThumbnailBytes() {
        return maxThumbnailBytes;
    }

    public int getSnippetLength() {
        return snippetLength;
    }

    public int getSnippetMaxItemsPerPage() {
        return snippetMaxItemsPerPage;
    }

    public int getSnippetMaxTextBytes() {
        return snippetMaxTextBytes;
    }

    public long getSnippetBudgetMs() {
        return snippetBudgetMs;
    }

    public String getSupportedVersionPrefix() {
        return supportedVersionPrefix;
    }

    public boolean isAllowExportIntoCaseFolder() {
        return allowExportIntoCaseFolder;
    }

    /** Environment variable holding the shared secret, checked before {@code sharedSecretFile}. */
    public static final String SHARED_SECRET_ENV = "IPED_MCP_SHARED_SECRET";

    public TransportMode getTransport() {
        return transport;
    }

    public void setTransport(TransportMode transport) {
        this.transport = transport;
    }

    public String getListenAddress() {
        return listenAddress;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenEndpoint(String address, int port) {
        this.listenAddress = address;
        this.listenPort = port;
    }

    public void setSharedSecretFile(String path) {
        this.sharedSecretFile = path;
    }

    public int getMaxConcurrentSessions() {
        return maxConcurrentSessions;
    }

    public void setMaxConcurrentSessions(int max) {
        this.maxConcurrentSessions = max;
    }

    public int getSessionIdleTimeoutSeconds() {
        return sessionIdleTimeoutSeconds;
    }

    public void setSessionIdleTimeoutSeconds(int seconds) {
        this.sessionIdleTimeoutSeconds = seconds;
    }

    /** The endpoint as configured, for the posture answer. {@code null} when nothing is declared. */
    public String describeListenEndpoint() {
        if (listenAddress == null || listenPort <= 0) {
            return null;
        }
        return listenAddress + ":" + listenPort;
    }

    /**
     * The shared secret, from the environment or from the file the configuration points at (FR-013).
     *
     * <p>
     * <b>The secret is never written in {@code McpServerConfig.txt}.</b> That file ships with the
     * release and is the kind of file people put under version control, which is exactly what FR-028
     * — extending FR-055 of feature 001 — forbids. So the configuration declares <i>where</i> the
     * secret is, never what it is.
     *
     * @return the secret, or {@code null} when neither source yields a non-empty value
     */
    public String resolveSharedSecret() {
        String fromEnv = System.getenv(SHARED_SECRET_ENV);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return fromEnv.trim();
        }
        if (sharedSecretFile == null || sharedSecretFile.trim().isEmpty()) {
            return null;
        }
        try {
            String fromFile = new String(Files.readAllBytes(new File(sharedSecretFile.trim()).toPath()),
                    StandardCharsets.UTF_8).trim();
            return fromFile.isEmpty() ? null : fromFile;
        } catch (IOException e) {
            return null;
        }
    }

    /** Why the secret could not be resolved, for the startup diagnostic. {@code null} when it could. */
    public String describeSecretProblem() {
        if (resolveSharedSecret() != null) {
            return null;
        }
        if (sharedSecretFile == null || sharedSecretFile.trim().isEmpty()) {
            return "neither the " + SHARED_SECRET_ENV + " environment variable nor sharedSecretFile is set";
        }
        File file = new File(sharedSecretFile.trim());
        if (!file.isFile()) {
            return "sharedSecretFile points at " + file.getAbsolutePath() + ", which is not a readable file";
        }
        return "the file at " + file.getAbsolutePath() + " is empty or could not be read";
    }

    /** How usable a declared write root turned out to be when it was probed. */
    public enum WriteRootState {
        USABLE, MISSING, NOT_A_DIRECTORY, NOT_WRITABLE
    }

    /** A declared write root, with where it really is and whether it can be written to. */
    public static final class WriteRoot {

        private final String declared;
        private final Path resolved;
        private final WriteRootState state;

        WriteRoot(String declared, Path resolved, WriteRootState state) {
            this.declared = declared;
            this.resolved = resolved;
            this.state = state;
        }

        public String getDeclared() {
            return declared;
        }

        /** The real path, or {@code null} when the root could not be resolved. */
        public Path getResolved() {
            return resolved;
        }

        public WriteRootState getState() {
            return state;
        }

        public boolean isUsable() {
            return state == WriteRootState.USABLE;
        }
    }

    /** The roots exactly as declared, before any probing. Empty means the default root is in force. */
    public List<String> getExportRoots() {
        return exportRoots;
    }

    public void setExportRoots(List<String> roots) {
        this.exportRoots = roots == null ? new ArrayList<>() : new ArrayList<>(roots);
    }

    /**
     * The default write root, in force when {@code exportRoots} is not declared (FR-024).
     *
     * <p>
     * Refusing every export until someone configures a root would protect more and would break every
     * installation that upgrades without editing configuration. This keeps them working <i>and</i>
     * confined from the first minute. The case folder stays refused either way.
     */
    public File getDefaultExportRoot() {
        return new File(System.getProperty("user.home"), ".iped/mcp-artifacts");
    }

    /**
     * Probes the declared roots and reports what each one is, for the startup diagnostic (FR-006).
     *
     * <p>
     * An unusable root does not stop the server: the diagnostic reports it and the first write under
     * it fails with something the examiner can act on, which is the behaviour the rest of
     * {@code Diagnostics} already has.
     */
    public List<WriteRoot> getWriteRoots() {
        List<String> declared = exportRoots.isEmpty()
                ? Collections.singletonList(getDefaultExportRoot().getAbsolutePath())
                : exportRoots;
        List<WriteRoot> roots = new ArrayList<>(declared.size());
        for (String each : declared) {
            roots.add(probeWriteRoot(each, exportRoots.isEmpty()));
        }
        return roots;
    }

    private static WriteRoot probeWriteRoot(String declared, boolean createOnDemand) {
        File file = new File(declared);
        if (createOnDemand && !file.exists()) {
            // Only ever the server's own default area, never a caller-supplied destination — FR-002
            // is about leaving nothing behind at a refused destination, which this is not.
            try {
                Files.createDirectories(file.toPath());
            } catch (IOException e) {
                return new WriteRoot(declared, null, WriteRootState.MISSING);
            }
        }
        if (!file.exists()) {
            return new WriteRoot(declared, null, WriteRootState.MISSING);
        }
        if (!file.isDirectory()) {
            return new WriteRoot(declared, null, WriteRootState.NOT_A_DIRECTORY);
        }
        Path resolved;
        try {
            resolved = file.toPath().toRealPath();
        } catch (IOException e) {
            return new WriteRoot(declared, null, WriteRootState.MISSING);
        }
        if (!Files.isWritable(resolved)) {
            return new WriteRoot(declared, resolved, WriteRootState.NOT_WRITABLE);
        }
        return new WriteRoot(declared, resolved, WriteRootState.USABLE);
    }

    /** The real paths writing is permitted under. Only usable roots take part in the decision. */
    public List<Path> getResolvedExportRoots() {
        List<Path> resolved = new ArrayList<>();
        for (WriteRoot root : getWriteRoots()) {
            if (root.isUsable()) {
                resolved.add(root.getResolved());
            }
        }
        return resolved;
    }
}
