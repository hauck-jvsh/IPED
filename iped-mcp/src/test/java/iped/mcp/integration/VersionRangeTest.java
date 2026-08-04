package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.protocol.McpError;
import iped.mcp.session.CaseValidator;

/**
 * The declared 4.x compatibility range is enforced and diagnosed (SC-013).
 *
 * <p>
 * The version is read from the versioned jar names copied into {@code iped/lib} when the case was
 * produced. That is the only record of it inside a finished case that does not require launching
 * the case's own search app.
 *
 * <p>
 * A version that cannot be determined is refused rather than assumed. Guessing would mean querying
 * a case whose field vocabulary and index format are unknown, which is how a wrong answer gets
 * produced with full confidence.
 */
public class VersionRangeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aCaseWithinTheRangeIsAccepted() throws Exception {
        for (String version : new String[] { "4.0.0", "4.1.5", "4.3.1", "4.9.99" }) {
            File caseDir = syntheticCase("case-" + version, "iped-engine-" + version + ".jar");
            CaseValidator.ValidatedCase validated = new CaseValidator("4.").validate(caseDir);
            assertEquals(version, validated.ipedVersion);
        }
    }

    @Test
    public void aCaseBelowTheRangeIsRefusedWithTheRangeNamed() throws Exception {
        File caseDir = syntheticCase("old", "iped-engine-3.18.0.jar");
        try {
            new CaseValidator("4.").validate(caseDir);
            fail("a 3.x case must be refused");
        } catch (McpError e) {
            assertEquals(McpError.VERSION_UNSUPPORTED, e.getCode());
            assertEquals("3.18.0", e.getDetails().get("caseVersion"));
            assertEquals("4.x", e.getDetails().get("supportedRange"));
            assertTrue("the remedy must say what to do about it", e.getRemedy().contains("Reprocess"));
        }
    }

    @Test
    public void aCaseAboveTheRangeIsRefused() throws Exception {
        File caseDir = syntheticCase("future", "iped-engine-5.0.0.jar");
        try {
            new CaseValidator("4.").validate(caseDir);
            fail("a 5.x case must be refused");
        } catch (McpError e) {
            assertEquals(McpError.VERSION_UNSUPPORTED, e.getCode());
            assertEquals("5.0.0", e.getDetails().get("caseVersion"));
        }
    }

    @Test
    public void anUndeterminableVersionIsRefusedRatherThanAssumed() throws Exception {
        File caseDir = syntheticCase("no-jars", null);
        try {
            new CaseValidator("4.").validate(caseDir);
            fail("an unknown version must be refused, not assumed compatible");
        } catch (McpError e) {
            assertEquals(McpError.VERSION_UNSUPPORTED, e.getCode());
            assertTrue("the remedy must explain where the version is read from",
                    e.getRemedy().contains("iped/lib"));
        }
    }

    @Test
    public void theVersionIsAlsoRecoverableFromOtherModuleJars() throws Exception {
        // Not every case folder keeps a full lib/. Falling back across module jars is what keeps
        // a pruned but otherwise valid case usable.
        File caseDir = syntheticCase("api-only", "iped-api-4.2.0.jar");
        assertEquals("4.2.0", new CaseValidator("4.").validate(caseDir).ipedVersion);
    }

    @Test
    public void theRealReferenceCaseIsInsideTheRange() {
        File caseDir = McpTestSupport.requireReferenceCase();
        CaseValidator.ValidatedCase validated = new CaseValidator("4.").validate(caseDir);
        assertTrue("the reference case must be a 4.x case, got " + validated.ipedVersion,
                validated.ipedVersion.startsWith("4."));
    }

    /** A folder with the structure of a case: enough for validation, not for querying. */
    private File syntheticCase(String name, String jarName) throws Exception {
        File caseDir = temp.newFolder(name);
        File moduleDir = new File(caseDir, "iped");
        Files.createDirectories(new File(moduleDir, "index").toPath());
        Files.createDirectories(new File(moduleDir, "data").toPath());
        Files.createDirectories(new File(moduleDir, "lib").toPath());
        Files.write(new File(moduleDir, "index/segments_1").toPath(), name.getBytes("UTF-8"));
        if (jarName != null) {
            Files.write(new File(moduleDir, "lib/" + jarName).toPath(), new byte[0]);
        }
        return caseDir;
    }
}
