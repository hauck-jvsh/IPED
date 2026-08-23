package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.processing.CaseRootConfinement;
import iped.mcp.processing.SourceConfinement;

/**
 * What may be read, and where a case may be created (FR-006 to FR-011, FR-039, FR-040).
 *
 * <p>
 * The escape this suite exists for is the one textual prefix comparison lets through: a path that
 * <i>reads</i> as being inside a declared area and <i>resolves</i> outside it. On Windows that is a
 * directory junction, which {@code getCanonicalPath()} does not traverse — the reason
 * {@code PathConfinement} exists at all, applied here to the read side.
 */
public class ProcessingConfinementTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aSourceInsideADeclaredAreaIsAllowed() throws Exception {
        File area = McpTestSupport.realDirectory(temp.getRoot(), "area");
        File evidence = new File(area, "image.E01");
        Files.write(evidence.toPath(), new byte[] { 1, 2, 3 });

        SourceConfinement.ResolvedSource resolved = SourceConfinement.resolve(evidence.getAbsolutePath(),
                Arrays.asList(area.getAbsolutePath()));

        assertTrue(resolved.getReason(), resolved.isAllowed());
    }

    @Test
    public void aSourceOutsideEveryAreaIsRefusedAndTheRefusalNamesWhereReadingIsAllowed() throws Exception {
        File area = McpTestSupport.realDirectory(temp.getRoot(), "area");
        File elsewhere = McpTestSupport.realDirectory(temp.getRoot(), "elsewhere");
        File evidence = new File(elsewhere, "image.E01");
        Files.write(evidence.toPath(), new byte[] { 1, 2, 3 });

        SourceConfinement.ResolvedSource resolved = SourceConfinement.resolve(evidence.getAbsolutePath(),
                Arrays.asList(area.getAbsolutePath()));

        assertEquals(SourceConfinement.Verdict.OUTSIDE_AREAS, resolved.getVerdict());
        // FR-008: a refusal the agent can act on names both what was asked and what is permitted.
        assertTrue("the refusal must carry the declared areas", resolved.getDeclaredAreas().contains(
                area.getAbsolutePath()));
    }

    @Test
    public void aJunctionOutOfADeclaredAreaDoesNotEscape() throws Exception {
        // The whole reason the comparison is on real paths. Textually this lives inside the area.
        File area = McpTestSupport.realDirectory(temp.getRoot(), "area");
        File outside = McpTestSupport.realDirectory(temp.getRoot(), "outside");
        File secret = new File(outside, "not-evidence.E01");
        Files.write(secret.toPath(), new byte[] { 9 });

        // Target kept under the same temporary root: JUnit's cleanup descends through a junction on
        // Windows and would otherwise empty whatever it points at.
        File junction = new File(area, "link");
        McpTestSupport.createDirectoryLink(junction, outside);

        try {
            SourceConfinement.ResolvedSource resolved = SourceConfinement
                    .resolve(new File(junction, "not-evidence.E01").getAbsolutePath(),
                            Arrays.asList(area.getAbsolutePath()));

            assertFalse("a junction inside a declared area must not carry reading outside it",
                    resolved.isAllowed());
            assertEquals(SourceConfinement.Verdict.OUTSIDE_AREAS, resolved.getVerdict());
        } finally {
            // Removed before cleanup runs: a recursive delete written the obvious way descends
            // through a junction instead of unlinking it.
            McpTestSupport.removeDirectoryLink(junction);
        }
    }

    @Test
    public void anEmptyAreaListIsAMisconfigurationRatherThanFullAccess() {
        SourceConfinement.ResolvedSource resolved = SourceConfinement.resolve("C:\\anything",
                Collections.emptyList());

        assertEquals(SourceConfinement.Verdict.NO_AREAS_DECLARED, resolved.getVerdict());
    }

    @Test
    public void aDeclaredAreaThatIsNotThereIsUnavailableRatherThanRefused() throws Exception {
        // FR-039. Merging these two would send the examiner looking for a configuration error when
        // all that is missing is a disk to plug in.
        File absent = new File(temp.getRoot(), "volume-not-mounted");
        File evidence = new File(temp.getRoot(), "image.E01");
        Files.write(evidence.toPath(), new byte[] { 1 });

        SourceConfinement.ResolvedSource resolved = SourceConfinement.resolve(evidence.getAbsolutePath(),
                Arrays.asList(absent.getAbsolutePath()));

        assertEquals(SourceConfinement.Verdict.AREA_UNAVAILABLE, resolved.getVerdict());
    }

    @Test
    public void aDestinationHoldingAFinishedCaseIsAScopeBoundaryNotACollision() throws Exception {
        File root = McpTestSupport.realDirectory(temp.getRoot(), "cases");
        File destination = new File(root, "existing-case");
        // The shape checkIfIsCaseFolder recognizes.
        Files.createDirectories(new File(destination, "iped/index").toPath());
        Files.createDirectories(new File(destination, "iped/data").toPath());
        Files.createDirectories(new File(destination, "iped/lib").toPath());

        CaseRootConfinement.ResolvedCaseRoot resolved = CaseRootConfinement
                .resolve(destination.getAbsolutePath(), Arrays.asList(root.getAbsolutePath()));

        // FR-040: distinct from a merely occupied destination, because it is a decision about scope
        // rather than a collision, and reading it as a defect would send someone hunting a bug.
        assertEquals(CaseRootConfinement.Verdict.HAS_FINISHED_CASE, resolved.getVerdict());
    }

    @Test
    public void anOccupiedDestinationIsDistinguishedFromACase() throws Exception {
        File root = McpTestSupport.realDirectory(temp.getRoot(), "cases");
        File destination = McpTestSupport.realDirectory(root, "busy");
        Files.write(new File(destination, "something.txt").toPath(), new byte[] { 1 });

        CaseRootConfinement.ResolvedCaseRoot resolved = CaseRootConfinement
                .resolve(destination.getAbsolutePath(), Arrays.asList(root.getAbsolutePath()));

        assertEquals(CaseRootConfinement.Verdict.DESTINATION_OCCUPIED, resolved.getVerdict());
    }

    @Test
    public void aDestinationOutsideEveryCaseRootIsRefused() throws Exception {
        File root = McpTestSupport.realDirectory(temp.getRoot(), "cases");
        File elsewhere = McpTestSupport.realDirectory(temp.getRoot(), "elsewhere");

        CaseRootConfinement.ResolvedCaseRoot resolved = CaseRootConfinement
                .resolve(new File(elsewhere, "new-case").getAbsolutePath(),
                        Arrays.asList(root.getAbsolutePath()));

        assertEquals(CaseRootConfinement.Verdict.OUTSIDE_ROOTS, resolved.getVerdict());
        assertFalse("a refused destination must not be created",
                new File(elsewhere, "new-case").exists());
    }

    @Test
    public void aCaseRootThatAlsoServesAsAnExportRootIsJudgedByTheCaseRule() throws Exception {
        // The two lists may overlap on the filesystem; the rule applied is the one belonging to the
        // operation, not the one belonging to whichever list was consulted first (FR-009).
        File shared = McpTestSupport.realDirectory(temp.getRoot(), "shared");
        File destination = new File(shared, "new-case");

        CaseRootConfinement.ResolvedCaseRoot resolved = CaseRootConfinement
                .resolve(destination.getAbsolutePath(), Arrays.asList(shared.getAbsolutePath()));

        assertTrue("a case may be created under a root that also happens to allow exports",
                resolved.isAllowed());
    }
}
