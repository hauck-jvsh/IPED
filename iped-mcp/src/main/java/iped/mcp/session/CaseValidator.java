package iped.mcp.session;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import iped.engine.data.IPEDSource;
import iped.mcp.protocol.McpError;

/**
 * Validates a case folder before anything is opened: structural integrity and supported version
 * range (FR-001, FR-002, FR-054).
 *
 * <p>
 * Every refusal names a specific condition and says what to do about it. A case that fails
 * validation produces a diagnostic, never a partially opened case.
 */
public class CaseValidator {

    /** Result of a successful validation. */
    public static class ValidatedCase {
        public final File caseDir;
        public final File moduleDir;
        public final String canonicalPath;
        public final String indexIdentity;
        public final String ipedVersion;

        ValidatedCase(File caseDir, File moduleDir, String canonicalPath, String indexIdentity, String ipedVersion) {
            this.caseDir = caseDir;
            this.moduleDir = moduleDir;
            this.canonicalPath = canonicalPath;
            this.indexIdentity = indexIdentity;
            this.ipedVersion = ipedVersion;
        }

        /**
         * Case identifier, derived from the index identity alone so that it stays stable when the
         * folder is moved.
         */
        public String caseId() {
            return indexIdentity.substring(0, 16);
        }

        /**
         * Strong binding recorded in the audit trail: canonical path plus index identity. It is
         * what lets an orphan trail be reassociated with its case later (FR-032).
         */
        public String caseBinding() {
            return canonicalPath + "|" + indexIdentity;
        }
    }

    private final String supportedVersionPrefix;

    public CaseValidator(String supportedVersionPrefix) {
        this.supportedVersionPrefix = supportedVersionPrefix;
    }

    /**
     * @throws McpError
     *             with {@code NOT_A_CASE}, {@code CASE_INCOMPLETE}, {@code CASE_IN_PROCESSING},
     *             {@code VERSION_UNSUPPORTED} or {@code CASE_INACCESSIBLE}
     */
    public ValidatedCase validate(File caseDir) {
        if (!caseDir.exists()) {
            throw new McpError(McpError.CASE_INACCESSIBLE, "There is nothing at " + caseDir.getAbsolutePath() + ".",
                    "Check the path. This is a missing path, not a permission problem: nothing exists there. "
                            + "The case path is the IPED output folder, the one that contains the 'iped' subfolder.")
                                    .with("path", caseDir.getAbsolutePath()).with("reason", "does not exist");
        }
        if (!caseDir.isDirectory()) {
            throw new McpError(McpError.NOT_A_CASE, caseDir.getAbsolutePath() + " is a file, not a folder.",
                    "Point at the IPED output folder, the one that contains the 'iped' subfolder.")
                            .with("path", caseDir.getAbsolutePath());
        }
        if (!caseDir.canRead()) {
            throw new McpError(McpError.CASE_INACCESSIBLE,
                    "The folder " + caseDir.getAbsolutePath() + " exists but this account cannot read it.",
                    "Grant read permission to the account running the server, or mount the volume with read "
                            + "access. The folder is there; only the permission is missing.")
                                    .with("path", caseDir.getAbsolutePath()).with("reason", "not readable");
        }

        File moduleDir = new File(caseDir, IPEDSource.MODULE_DIR);
        File indexDir = new File(moduleDir, IPEDSource.INDEX_DIR);
        File dataDir = new File(moduleDir, IPEDSource.DATA_DIR);
        File libDir = new File(moduleDir, IPEDSource.LIB_DIR);

        if (!moduleDir.isDirectory()) {
            throw new McpError(McpError.NOT_A_CASE,
                    caseDir.getAbsolutePath() + " is not an IPED case: it has no 'iped' subfolder.",
                    "Point at the IPED output folder — the one given to -o when the case was processed. It "
                            + "contains an 'iped' subfolder with 'index', 'data' and 'lib' inside it.")
                                    .with("path", caseDir.getAbsolutePath()).with("expected", "iped/{index,data,lib}");
        }

        // The index lives in a temporary folder while processing runs, and is moved into the case
        // only at the end. Its absence next to a populated data folder means the case is still
        // being processed, not that it is broken.
        if (!indexDir.exists() && IPEDSource.getTempDirInfoFile(moduleDir).exists()) {
            throw new McpError(McpError.CASE_IN_PROCESSING,
                    "The case at " + caseDir.getAbsolutePath() + " is still being processed: its index is still "
                            + "in the temporary folder.",
                    "Wait for processing to finish and try again. Querying a case mid-processing would give "
                            + "answers over an incomplete collection.").with("path", caseDir.getAbsolutePath());
        }

        if (!IPEDSource.checkIfIsCaseFolder(caseDir)) {
            String missing = (!indexDir.exists() ? "iped/index " : "") + (!dataDir.exists() ? "iped/data " : "")
                    + (!libDir.exists() ? "iped/lib" : "");
            throw new McpError(McpError.CASE_INCOMPLETE,
                    "The case at " + caseDir.getAbsolutePath() + " is incomplete; missing: " + missing.trim() + ".",
                    "This case did not finish processing, or was copied partially. Reprocess it, or copy the "
                            + "whole output folder including the 'iped' subfolder.")
                                    .with("path", caseDir.getAbsolutePath()).with("missing", missing.trim());
        }

        if (findSegmentsFile(indexDir) == null) {
            throw new McpError(McpError.CASE_INCOMPLETE,
                    "The index at " + indexDir.getAbsolutePath() + " has no commit point.",
                    "The index was never committed, so there is nothing to query. Reprocess the case.")
                            .with("path", indexDir.getAbsolutePath());
        }

        String version = detectVersion(libDir);
        if (version == null) {
            throw new McpError(McpError.VERSION_UNSUPPORTED,
                    "The IPED version that produced " + caseDir.getAbsolutePath() + " could not be determined.",
                    "The supported range is " + supportedVersionPrefix + "x. The version is read from the jar "
                            + "names in iped/lib; if that folder was pruned, reopen the case in the IPED UI to "
                            + "confirm it is readable, or reprocess it.")
                                    .with("supportedRange", supportedVersionPrefix + "x")
                                    .with("path", caseDir.getAbsolutePath());
        }
        if (!version.startsWith(supportedVersionPrefix)) {
            throw new McpError(McpError.VERSION_UNSUPPORTED,
                    "The case was produced by IPED " + version + ", outside the supported range.",
                    "The supported range is " + supportedVersionPrefix
                            + "x. Reprocess the case with a supported version, or query it with a server "
                            + "matching the version that produced it.").with("caseVersion", version)
                                    .with("supportedRange", supportedVersionPrefix + "x");
        }

        try {
            return new ValidatedCase(caseDir, moduleDir, caseDir.getCanonicalPath(), indexIdentity(indexDir), version);
        } catch (IOException e) {
            throw new McpError(McpError.CASE_INACCESSIBLE,
                    "The case path could not be canonicalized: " + e.getMessage(),
                    "Check that the path is reachable and that no link in it is broken.", e);
        }
    }

    /**
     * Reads the IPED version from the versioned jar names copied into the case's {@code iped/lib}
     * when the case was produced.
     */
    static String detectVersion(File libDir) {
        File[] jars = libDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null) {
            return null;
        }
        Arrays.sort(jars);
        for (String prefix : new String[] { "iped-engine-", "iped-api-", "iped-utils-", "iped-parsers-impl-" }) {
            for (File jar : jars) {
                String name = jar.getName();
                if (name.startsWith(prefix)) {
                    String version = name.substring(prefix.length(), name.length() - ".jar".length());
                    if (!version.isEmpty() && Character.isDigit(version.charAt(0))) {
                        return version;
                    }
                }
            }
        }
        return null;
    }

    static File findSegmentsFile(File indexDir) {
        File[] segments = indexDir.listFiles((dir, name) -> name.startsWith("segments"));
        if (segments == null || segments.length == 0) {
            return null;
        }
        Arrays.sort(segments);
        return segments[segments.length - 1];
    }

    /**
     * Identity of the index commit: SHA-256 of the segments file, which uniquely identifies the
     * committed state. Independent of where the folder sits, so it survives a move.
     */
    public static String indexIdentity(File indexDir) throws IOException {
        File segments = findSegmentsFile(indexDir);
        if (segments == null) {
            throw new IOException("no commit point in " + indexDir.getAbsolutePath());
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(segments.getName().getBytes("UTF-8"));
            byte[] hash = sha.digest(Files.readAllBytes(segments.toPath()));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
