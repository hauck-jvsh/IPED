package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.session.Session;

/**
 * The examiner can see, from inside the session, whether this installation creates cases and under
 * what limits (FR-004, FR-005).
 *
 * <p>
 * A posture that cannot be checked from inside is one nobody trusts, and the examiner is the person
 * who signs the report. It answers with processing disabled too — the absence of the capability is
 * itself the fact worth confirming.
 */
public class ProcessingPostureTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aDefaultInstallationSaysProcessingIsOff() throws Exception {
        try (Session session = new Session(McpTestSupport.configWithTempAudit(temp.getRoot()))) {
            Map<String, Object> processing = processing(session);

            assertEquals(Boolean.FALSE, processing.get("enabled"));
            // No opening warning about it either: an examiner should not be told about a capability
            // this installation does not have.
            assertFalse("a disabled installation must not advertise case creation",
                    String.join(" ", session.getWarnings()).contains("Case creation is enabled"));
        }
    }

    @Test
    public void anEnabledInstallationDeclaresItsAreasRootsAndProfiles() throws Exception {
        File area = McpTestSupport.realDirectory(temp.getRoot(), "evidence");
        File root = McpTestSupport.realDirectory(temp.getRoot(), "cases");

        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);
        config.setProcessingSourceAreas(Arrays.asList(area.getAbsolutePath()));
        config.setProcessingCaseRoots(Arrays.asList(root.getAbsolutePath()));
        config.setProcessingProfiles(Arrays.asList("forensic", "fastmode"));

        try (Session session = new Session(config)) {
            Map<String, Object> processing = processing(session);

            assertEquals(Boolean.TRUE, processing.get("enabled"));
            assertEquals(Arrays.asList("forensic", "fastmode"), processing.get("allowed_profiles"));
            assertEquals(Boolean.TRUE, processing.get("source_areas_declared"));
            assertEquals(Boolean.TRUE, processing.get("case_roots_declared"));

            // FR-005: told at the door, not discovered from a tool list.
            assertTrue("an enabled installation must say so when the session opens",
                    String.join(" ", session.getWarnings()).contains("Case creation is enabled"));
        }
    }

    @Test
    public void aDeclaredAreaReportsWhetherItIsPresentRightNow() throws Exception {
        File present = McpTestSupport.realDirectory(temp.getRoot(), "mounted");
        File absent = new File(temp.getRoot(), "not-mounted");

        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);
        config.setProcessingSourceAreas(Arrays.asList(present.getAbsolutePath(), absent.getAbsolutePath()));
        config.setProcessingCaseRoots(Arrays.asList(present.getAbsolutePath()));

        try (Session session = new Session(config)) {
            List<?> areas = (List<?>) processing(session).get("source_areas");
            assertEquals(2, areas.size());

            // The distinction the examiner acts on: one is a disk to plug in, the other a line to
            // add. Reporting only what the file says would conflate them (FR-039).
            Map<?, ?> first = (Map<?, ?>) areas.get(0);
            Map<?, ?> second = (Map<?, ?>) areas.get(1);
            assertEquals(Boolean.TRUE, first.get("present"));
            assertEquals(Boolean.FALSE, second.get("present"));
        }
    }

    @Test
    public void anEnabledInstallationWithNoRootsSaysSoRatherThanLookingPermissive() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);

        try (Session session = new Session(config)) {
            Map<String, Object> processing = processing(session);

            // Empty is a misconfiguration, not a grant of full access — and saying so here means the
            // examiner sees it before a request fails rather than after.
            assertEquals(Boolean.FALSE, processing.get("source_areas_declared"));
            assertEquals(Boolean.FALSE, processing.get("case_roots_declared"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> processing(Session session) {
        return (Map<String, Object>) session.describePosture().get("processing");
    }
}
