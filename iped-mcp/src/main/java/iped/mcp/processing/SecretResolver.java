package iped.mcp.processing;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Turns a secret reference into a container password, server-side (FR-015).
 *
 * <h2>The configuration says where, never which</h2>
 *
 * <p>
 * Same shape as {@code sharedSecretFile} from 006, one level down: the request carries a
 * <i>name</i>, {@code processingSecretsFile} says which file resolves names to passwords, and the
 * password itself never appears in the request, the response, the trail or the log.
 *
 * <h2>The fifth place, which is open and declared</h2>
 *
 * <p>
 * The password does reach the engine through {@code -p} on the command line, so it is readable by
 * other accounts on the machine while the process lives — on Linux through
 * {@code /proc/<pid>/cmdline}. That is a fifth place, and FR-015 names only four. Closing it would
 * mean changing {@code iped-app}, and the examiner decided against it on proportion: the exposure
 * only materialises on an evidence machine with more than one account, and the target workstation
 * has one.
 *
 * <p>
 * What was not accepted is leaving it tacit. FR-050 requires every accept that uses a secret
 * reference to say so. A test asserting the password is absent from {@code argv} would be asserting
 * the opposite of what was decided, and would fail a correct implementation.
 */
public final class SecretResolver {

    /** What a caller must show the examiner whenever a secret reference is used (FR-050). */
    public static final String EXPOSURE_NOTICE = "The container password is handed to the processing engine "
            + "on its command line, so it is readable by other accounts on this machine while the job runs. "
            + "This is a known and accepted limitation of this installation, not a defect. It matters when the "
            + "evidence machine is shared between accounts; on a single-account workstation it does not.";

    private final File secretsFile;

    public SecretResolver(String configuredPath) {
        this.secretsFile = configuredPath == null || configuredPath.trim().isEmpty() ? null
                : new File(configuredPath.trim());
    }

    /**
     * Resolves a reference to a password.
     *
     * @return the password, or {@code null} when the reference is unknown or unresolvable
     */
    public String resolve(String reference) {
        if (reference == null || reference.trim().isEmpty() || secretsFile == null || !secretsFile.isFile()) {
            return null;
        }
        String wanted = reference.trim();
        try {
            // Explicit UTF-8. A password read under the platform default would work on the machine
            // that wrote the file and fail on the one that reads it.
            List<String> lines = Files.readAllLines(secretsFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                if (trimmed.substring(0, separator).trim().equals(wanted)) {
                    // Not trimmed: leading and trailing spaces can be part of a password, and
                    // silently stripping them would turn a correct secret into a wrong one.
                    return trimmed.substring(separator + 1);
                }
            }
        } catch (IOException e) {
            // Deliberately not logged with the reference or any file content: a diagnostic that
            // echoes secret material defeats the point of the file.
            return null;
        }
        return null;
    }

    /** Whether a secrets file is configured at all, for a diagnostic that says what is missing. */
    public boolean isConfigured() {
        return secretsFile != null;
    }

    public String describeSource() {
        return secretsFile == null ? "processingSecretsFile is not set" : secretsFile.getAbsolutePath();
    }
}
