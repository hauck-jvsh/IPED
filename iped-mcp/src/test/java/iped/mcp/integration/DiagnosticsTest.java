package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.Diagnostics;
import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.protocol.McpError;
import iped.mcp.session.CaseValidator;

/**
 * The diagnostic matrix of Scenario 13 (SC-011).
 *
 * <p>
 * The bar is not "an error is produced". It is that every failure names what is wrong <i>and what
 * to do about it</i>, in terms an examiner who has never wired an agent to anything can act on. A
 * stack trace fails this test even when it is technically accurate.
 */
public class DiagnosticsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void ipedNotLocatedSaysWhatToConfigure() throws Exception {
        Diagnostics diagnostics = new Diagnostics().run(null, McpTestSupport.configWithTempAudit(temp.getRoot()));

        assertFalse("a missing installation must fail the check", diagnostics.isOk());
        Diagnostics.Check failure = diagnostics.getFailures().get(0);
        assertEquals("iped_installation", failure.name);
        assertTrue("the remedy must name the property to set: " + failure.remedy,
                failure.remedy.contains(Diagnostics.IPED_ROOT_PROPERTY));
        assertTrue("the remedy must say what the folder looks like: " + failure.remedy,
                failure.remedy.contains("iped.jar"));
    }

    @Test
    public void aFolderThatIsNotAnIpedReleaseIsDistinguishedFromAMissingPath() throws Exception {
        File notARelease = temp.newFolder("not-a-release");
        Diagnostics diagnostics = new Diagnostics().run(notARelease,
                McpTestSupport.configWithTempAudit(temp.getRoot()));

        Diagnostics.Check failure = diagnostics.getFailures().get(0);
        assertTrue("the message must say the folder exists but is not a release: " + failure.detail,
                failure.detail.contains("conf/"));
    }

    @Test
    public void unwritableAuditAreaExplainsWhyOperationsAreRefused() throws Exception {
        McpServerConfig config = new McpServerConfig();
        // A path whose parent is a regular file cannot be created on any platform.
        config.setAuditArea(new File(temp.newFile("blocker"), "audit"));

        Diagnostics diagnostics = new Diagnostics().run(null, config);
        Diagnostics.Check auditCheck = diagnostics.getChecks().stream()
                .filter(check -> "audit_area".equals(check.name)).findFirst().orElse(null);

        assertNotNull(auditCheck);
        assertFalse(auditCheck.ok);
        assertTrue("the examiner must learn that everything will be refused, and why: " + auditCheck.remedy,
                auditCheck.remedy.contains("refuse"));
        assertTrue("the remedy must name the setting: " + auditCheck.remedy,
                auditCheck.remedy.contains("auditArea"));
    }

    @Test
    public void inaccessibleCaseDistinguishesMissingFromUnreadable() throws Exception {
        CaseValidator validator = new CaseValidator("4.");
        try {
            validator.validate(new File(temp.getRoot(), "no-such-folder"));
            fail("a missing path must be refused");
        } catch (McpError e) {
            assertEquals(McpError.CASE_INACCESSIBLE, e.getCode());
            assertEquals("does not exist", e.getDetails().get("reason"));
            assertTrue("the message must distinguish missing from unreadable: " + e.getRemedy(),
                    e.getRemedy().contains("not a permission problem"));
        }
    }

    @Test
    public void aFolderWithoutTheIpedSubfolderIsNotACase() throws Exception {
        File plain = temp.newFolder("plain-folder");
        try {
            new CaseValidator("4.").validate(plain);
            fail("a plain folder must be refused");
        } catch (McpError e) {
            assertEquals(McpError.NOT_A_CASE, e.getCode());
            assertTrue("the remedy must describe the folder to point at: " + e.getRemedy(),
                    e.getRemedy().contains("iped"));
        }
    }

    @Test
    public void aCaseStillProcessingIsDistinguishedFromABrokenOne() throws Exception {
        // While processing runs the index lives in a temporary folder, and the case records where.
        File caseDir = temp.newFolder("processing-case");
        File moduleDir = new File(caseDir, "iped");
        Files.createDirectories(new File(moduleDir, "data").toPath());
        Files.createDirectories(new File(moduleDir, "lib").toPath());
        Files.write(new File(moduleDir, "data/prevTempDir.txt").toPath(),
                temp.newFolder("temp-index").getAbsolutePath().getBytes("UTF-8"));

        try {
            new CaseValidator("4.").validate(caseDir);
            fail("a case still being processed must be refused");
        } catch (McpError e) {
            assertEquals(McpError.CASE_IN_PROCESSING, e.getCode());
            assertTrue("the remedy must say to wait, and why: " + e.getRemedy(),
                    e.getRemedy().contains("incomplete collection"));
        }
    }

    @Test
    public void anIncompleteCaseNamesWhatIsMissing() throws Exception {
        File caseDir = temp.newFolder("incomplete-case");
        Files.createDirectories(new File(caseDir, "iped/data").toPath());

        try {
            new CaseValidator("4.").validate(caseDir);
            fail("an incomplete case must be refused");
        } catch (McpError e) {
            assertEquals(McpError.CASE_INCOMPLETE, e.getCode());
            assertNotNull("the diagnostic must list what is missing", e.getDetails().get("missing"));
            assertTrue(String.valueOf(e.getDetails().get("missing")).contains("iped/index"));
        }
    }

    @Test
    public void aCaseOutsideTheSupportedRangeDeclaresTheRange() throws Exception {
        File caseDir = temp.newFolder("old-case");
        File moduleDir = new File(caseDir, "iped");
        Files.createDirectories(new File(moduleDir, "index").toPath());
        Files.createDirectories(new File(moduleDir, "data").toPath());
        Files.createDirectories(new File(moduleDir, "lib").toPath());
        Files.write(new File(moduleDir, "index/segments_1").toPath(), new byte[] { 1, 2, 3 });
        Files.write(new File(moduleDir, "lib/iped-engine-3.18.0.jar").toPath(), new byte[0]);

        try {
            new CaseValidator("4.").validate(caseDir);
            fail("a case outside the supported range must be refused");
        } catch (McpError e) {
            assertEquals(McpError.VERSION_UNSUPPORTED, e.getCode());
            assertEquals("3.18.0", e.getDetails().get("caseVersion"));
            assertEquals("4.x", e.getDetails().get("supportedRange"));
        }
    }

    @Test
    public void everyDiagnosticFailureCarriesARemedy() throws Exception {
        McpServerConfig config = new McpServerConfig();
        config.setAuditArea(new File(temp.newFile("blocker2"), "audit"));
        Diagnostics diagnostics = new Diagnostics().run(null, config);

        for (Diagnostics.Check failure : diagnostics.getFailures()) {
            assertNotNull("check '" + failure.name + "' failed without a remedy", failure.remedy);
            assertFalse("check '" + failure.name + "' has an empty remedy", failure.remedy.trim().isEmpty());
        }
    }
}
