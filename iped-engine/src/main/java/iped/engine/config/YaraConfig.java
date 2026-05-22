package iped.engine.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import iped.engine.task.yara.YaraInstallPaths;
import iped.utils.UTF8Properties;

/**
 * Configurable da {@code YaraScanTask}. Lê o arquivo {@code conf/YaraConfig.txt}
 * e expõe os parâmetros operacionais (diretórios de regras, limites de tamanho/timeout,
 * comportamento de scan).
 *
 * <p>Schema completo do arquivo está documentado em
 * {@code specs/001-yara-rules-engine/contracts/YaraConfig.txt.contract.md}.</p>
 *
 * @see iped.engine.config.AbstractTaskPropertiesConfig
 */
public class YaraConfig extends AbstractTaskPropertiesConfig {

    private static final long serialVersionUID = 1L;

    public static final String ENABLE_PARAM = "enableYara";
    public static final String CONFIG_FILE = "YaraConfig.txt";

    /** Default de tamanho máximo a escanear: 250 MiB (262144000 bytes). */
    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 250L * 1024L * 1024L;

    /** Default de timeout por item: 30 segundos. */
    public static final int DEFAULT_PER_ITEM_TIMEOUT_MS = 30_000;

    /** Default de bytes brutos por matched-string persistidos no JSON. */
    public static final int DEFAULT_MATCH_HEX_MAX_BYTES = 256;

    /** Limite superior absoluto de bytes por matched-string (proteção contra config maliciosa). */
    public static final int ABSOLUTE_MAX_HEX_MAX_BYTES = 65_536;

    private final List<File> ruleDirectories = new ArrayList<>();
    private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;
    private int perItemTimeoutMs = DEFAULT_PER_ITEM_TIMEOUT_MS;
    private boolean scanAllItems = false;
    private int matchHexMaxBytes = DEFAULT_MATCH_HEX_MAX_BYTES;
    private String engineLibraryHint = "";

    @Override
    public String getTaskEnableProperty() {
        return ENABLE_PARAM;
    }

    @Override
    public String getTaskConfigFileName() {
        return CONFIG_FILE;
    }

    @Override
    public void processProperties(UTF8Properties properties) {
        ruleDirectories.clear();

        String dirs = properties.getProperty("ruleDirectories");
        if (dirs != null && !dirs.trim().isEmpty()) {
            for (String entry : dirs.split("[;" + java.io.File.pathSeparator + "]")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    ruleDirectories.add(resolveRuleDir(trimmed));
                }
            }
        }

        String maxSize = properties.getProperty("maxFileSizeBytes");
        if (maxSize != null && !maxSize.trim().isEmpty()) {
            try {
                maxFileSizeBytes = parseSizeWithSuffix(maxSize.trim());
            } catch (IllegalArgumentException e) {
                // Mantém default; o erro é registrado pelo loader.
            }
        }

        String timeout = properties.getProperty("perItemTimeoutMs");
        if (timeout != null && !timeout.trim().isEmpty()) {
            try {
                int parsed = Integer.parseInt(timeout.trim());
                if (parsed >= 100) {
                    perItemTimeoutMs = parsed;
                }
            } catch (NumberFormatException e) {
                // mantém default
            }
        }

        String scanAll = properties.getProperty("scanAllItems");
        if (scanAll != null) {
            scanAllItems = Boolean.parseBoolean(scanAll.trim());
        }

        String hexMax = properties.getProperty("matchHexMaxBytes");
        if (hexMax != null && !hexMax.trim().isEmpty()) {
            try {
                int parsed = Integer.parseInt(hexMax.trim());
                if (parsed > 0 && parsed <= ABSOLUTE_MAX_HEX_MAX_BYTES) {
                    matchHexMaxBytes = parsed;
                }
            } catch (NumberFormatException e) {
                // mantém default
            }
        }

        String hint = properties.getProperty("engineLibraryHint");
        engineLibraryHint = (hint == null) ? "" : hint.trim();
    }

    /**
     * Resolves a {@code ruleDirectories} entry to an absolute {@link File}:
     * <ul>
     *   <li>If the entry contains the literal {@code ${IPED_HOME}} token, replaces
     *       it with the running IPED installation root (the directory that
     *       contains {@code iped.jar}, {@code lib/}, {@code tools/}). When the
     *       root cannot be detected (dev/test, IDE), the token is left as-is and
     *       the path will likely fail discovery — caller gets a clear WARN.</li>
     *   <li>Otherwise, the value is passed straight to {@code new File(...)}.
     *       Bare relative paths (e.g. {@code "yara-rules"}) resolve against the
     *       JVM working directory — works when IPED is started from its install
     *       dir, fragile otherwise. Prefer {@code ${IPED_HOME}/...} or an
     *       absolute path.</li>
     * </ul>
     */
    static File resolveRuleDir(String raw) {
        String resolved = raw;
        if (resolved.contains("${IPED_HOME}")) {
            File root = YaraInstallPaths.getIpedRoot();
            if (root != null) {
                resolved = resolved.replace("${IPED_HOME}", root.getAbsolutePath());
            }
            // If the root could not be detected, leave the placeholder in place;
            // YaraRulesetLoader.discover will then log a clear "directory does
            // not exist" warning citing the unexpanded path.
        }
        return new File(resolved);
    }

    /**
     * Aceita números puros ou com sufixo {@code K}/{@code M}/{@code G} (case-insensitive,
     * base 1024). Exemplos: {@code "250M"} → {@code 262144000}; {@code "1G"} →
     * {@code 1073741824}; {@code "65536"} → {@code 65536}.
     */
    static long parseSizeWithSuffix(String value) {
        if (value == null) {
            throw new IllegalArgumentException("size value is null");
        }
        String s = value.trim().toUpperCase();
        long multiplier = 1L;
        if (s.endsWith("K")) {
            multiplier = 1024L;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("M")) {
            multiplier = 1024L * 1024L;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("G")) {
            multiplier = 1024L * 1024L * 1024L;
            s = s.substring(0, s.length() - 1);
        }
        long base = Long.parseLong(s.trim());
        if (base <= 0) {
            throw new IllegalArgumentException("size must be positive: " + value);
        }
        return base * multiplier;
    }

    public List<File> getRuleDirectories() {
        return Collections.unmodifiableList(ruleDirectories);
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public int getPerItemTimeoutMs() {
        return perItemTimeoutMs;
    }

    public boolean isScanAllItems() {
        return scanAllItems;
    }

    public int getMatchHexMaxBytes() {
        return matchHexMaxBytes;
    }

    public String getEngineLibraryHint() {
        return engineLibraryHint;
    }
}
