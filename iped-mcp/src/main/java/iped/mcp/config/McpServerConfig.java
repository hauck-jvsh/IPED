package iped.mcp.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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

    private int maxTextBytes = 100000;
    private int maxContentBytes = 262144;
    private int maxThumbnailBytes = 262144;
    private int snippetLength = 300;
    private int snippetMaxItemsPerPage = 25;
    private int snippetMaxTextBytes = 20000;
    private long snippetBudgetMs = 3000;

    private String supportedVersionPrefix = "4.";
    private boolean allowExportIntoCaseFolder = false;

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

        maxTextBytes = integer(properties, "maxTextBytes", maxTextBytes);
        maxContentBytes = integer(properties, "maxContentBytes", maxContentBytes);
        maxThumbnailBytes = integer(properties, "maxThumbnailBytes", maxThumbnailBytes);
        snippetLength = integer(properties, "snippetLength", snippetLength);
        snippetMaxItemsPerPage = integer(properties, "snippetMaxItemsPerPage", snippetMaxItemsPerPage);
        snippetMaxTextBytes = integer(properties, "snippetMaxTextBytes", snippetMaxTextBytes);
        snippetBudgetMs = integer(properties, "snippetBudgetMs", (int) snippetBudgetMs);

        supportedVersionPrefix = str(properties, "supportedVersionPrefix", supportedVersionPrefix);
        allowExportIntoCaseFolder = bool(properties, "allowExportIntoCaseFolder", allowExportIntoCaseFolder);
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
}
