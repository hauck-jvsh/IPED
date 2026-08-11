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
 * <li>{@code -Diped.mcp.ipedRoot=<path>} or {@code IPED_ROOT} — an unpacked IPED release. Required
 * alongside either case: the engine configuration has to be loaded before a case can be opened, and
 * the server does it from the installation at startup. See {@link #requireIpedConfiguration()}.</li>
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
        requireIpedConfiguration();
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
        requireIpedConfiguration();
        return largeCase;
    }

    /**
     * Loads the IPED configuration from an installation, exactly as {@code McpServerMain.main} does
     * before serving.
     *
     * <p>
     * Opening a case needs the engine's configurables — {@code IndexTaskConfig} for the analyzer,
     * {@code AnalysisConfig} for the searcher, {@code CategoryConfig} for the category tree.
     * {@code IPEDSource} does call the loader itself, but the loader runs once per process and what
     * a case folder carries is not enough on its own: without this bootstrap the open fails with
     * {@code CASE_INACCESSIBLE} and a null {@code IndexTaskConfig}.
     *
     * <p>
     * This is deliberately a <b>failure</b> rather than a skip. It only runs once a real case has
     * been configured, which means someone asked for a real run; skipping there would report
     * "nothing to do" for what is actually a misconfigured bench, and a skipped test is not a
     * passing test.
     */
    public static synchronized void requireIpedConfiguration() {
        if (configurationLoaded) {
            return;
        }
        File ipedRoot = Diagnostics.resolveIpedRoot();
        if (ipedRoot == null || !new File(ipedRoot, "conf").isDirectory()) {
            throw new AssertionError("A case is configured but the IPED installation is not, so the engine "
                    + "configuration cannot be loaded and no case can be opened. Set -D"
                    + Diagnostics.IPED_ROOT_PROPERTY + "=<path> (or " + Diagnostics.IPED_ROOT_ENV
                    + ") to the folder of an unpacked IPED release — the one containing iped.jar, conf/ and "
                    + "lib/.");
        }
        requireRuntimeThatCanLoadTheEngine(ipedRoot);
        // The returned configuration is the installation's own; the suites keep theirs, with the
        // audit area pointed at a temporary folder. What is wanted here is the side effect on the
        // engine's ConfigurationManager.
        McpServerMain.bootstrapConfiguration(ipedRoot);
        configurationLoaded = true;
    }

    private static boolean configurationLoaded;

    /**
     * Refuses to run the engine on a JVM that will not let it load, naming the runtime to use.
     *
     * <p>
     * Loading the engine's task installer pulls in FST, the serialization library behind
     * {@code RegexTask}'s automaton, and FST reflects into JDK internals — {@code String.value},
     * {@code BigDecimal.intVal}, and more as it registers its default classes. Java permitted that
     * with a warning up to 15 and refuses it from 16 on, so on a newer JVM the suite dies with an
     * {@code InaccessibleObjectException} thrown from inside a serialization library, which
     * explains nothing about what to do.
     *
     * <p>
     * Opening the module up with {@code --add-opens} would be chasing one package per failure, and
     * a half-granted set only moves the confusion. The suites run on the runtime the release ships
     * and production uses, and this says so before anything fails.
     */
    private static void requireRuntimeThatCanLoadTheEngine(File ipedRoot) {
        int major = majorJavaVersion();
        if (major < 16) {
            return;
        }
        File bundled = new File(ipedRoot, "jre/bin/java" + (isWindows() ? ".exe" : ""));
        throw new AssertionError("These tests load the IPED engine, which needs the deep reflection that Java "
                + "permits up to 15 and refuses from 16 on. This JVM is Java " + major
                + ", so the engine cannot be loaded here — the failure would come from inside FST as an "
                + "InaccessibleObjectException. Run the suite on the runtime the release ships, which is what "
                + "production uses: add -Djvm=\"" + bundled.getAbsolutePath() + "\" to the Maven command line, "
                + "or point JAVA_HOME at a JDK 11 as the build documents.");
    }

    private static int majorJavaVersion() {
        String version = System.getProperty("java.specification.version", "11");
        // "1.8" on the old scheme, "11" and up on the current one.
        int dot = version.indexOf('.');
        String major = version.startsWith("1.") ? version.substring(2) : dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(major.trim());
        } catch (NumberFormatException e) {
            return 11;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** A configuration whose audit area points at a temporary folder, isolated per test. */
    public static McpServerConfig configWithTempAudit(File tempRoot) throws IOException {
        McpServerConfig config = new McpServerConfig();
        File auditArea = new File(tempRoot, "audit");
        Files.createDirectories(auditArea.toPath());
        config.setAuditArea(auditArea);
        return config;
    }

    /**
     * Creates a folder under the temporary root and returns it resolved to its real path.
     *
     * <p>
     * Resolution matters even here. On Windows a temporary folder is routinely reached through a
     * path carrying an 8.3 short name — {@code JOAOPA~1} for a longer account name — and a test that
     * declares a write root by its unresolved path while the server compares resolved paths would
     * fail for a reason that has nothing to do with what it is testing.
     */
    public static File realDirectory(File tempRoot, String name) throws IOException {
        File dir = new File(tempRoot, name);
        Files.createDirectories(dir.toPath());
        return dir.toPath().toRealPath().toFile();
    }

    /**
     * Creates a directory link at {@code link} pointing at {@code target}, or skips the calling test
     * when the platform will not make one.
     *
     * <p>
     * <b>Both paths must live under the same temporary root.</b> That is not tidiness, it is safety:
     * JUnit's {@code TemporaryFolder} deletes recursively with {@code File.listFiles()}, which on
     * Windows reports the contents of a junction's <i>target</i> rather than the link itself. A
     * junction pointing outside the temporary tree would therefore have its target emptied during
     * cleanup. Keeping the target inside the same tree makes the recursive delete remove only what
     * it was already going to remove.
     *
     * <p>
     * A junction is used on Windows because that is the mechanism the confinement check has to
     * survive: {@code File.getCanonicalPath()} does not traverse one, which is measured in
     * {@code research.md} R1 and locked in by
     * {@code PathConfinementTest#canonicalPathDoesNotTraverseDirectoryLink}. Elsewhere a symbolic
     * link is the equivalent, and needs no privilege.
     */
    public static void createDirectoryLink(File link, File target) throws IOException {
        if (!isWindows()) {
            Files.createSymbolicLink(link.toPath(), target.toPath());
            return;
        }
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.getAbsolutePath(),
                target.getAbsolutePath()).redirectErrorStream(true).start();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating a directory junction", e);
        }
        Assume.assumeTrue("This platform would not create a directory junction, so the escape vector it "
                + "represents cannot be exercised here.", link.isDirectory());
    }

    /**
     * Removes a directory link without touching what it points at.
     *
     * <p>
     * {@code Files.delete} on a junction removes the link, but a recursive delete written the
     * obvious way descends through it first. Tests that create a link should remove it here before
     * cleanup runs, even though {@link #createDirectoryLink} keeps the blast radius inside the
     * temporary tree.
     */
    public static void removeDirectoryLink(File link) throws IOException {
        if (!link.exists()) {
            return;
        }
        if (!isWindows()) {
            Files.deleteIfExists(link.toPath());
            return;
        }
        Process process = new ProcessBuilder("cmd", "/c", "rmdir", link.getAbsolutePath()).redirectErrorStream(true)
                .start();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while removing a directory junction", e);
        }
    }

    /** Skips the calling test off Windows, for vectors that only exist there. */
    public static void assumeWindows() {
        Assume.assumeTrue("This vector only exists on Windows.", isWindows());
    }
}
