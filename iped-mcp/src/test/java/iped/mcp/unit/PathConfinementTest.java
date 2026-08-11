package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.export.PathConfinement;
import iped.mcp.export.PathConfinement.ResolvedDestination;
import iped.mcp.export.PathConfinement.Verdict;

/**
 * The battery behind SC-001: a destination is approved only if the place it really reaches is inside
 * a declared write root.
 *
 * <p>
 * Every vector here was measured on this platform before the rule was written, not imagined. The one
 * that decided the implementation is the directory junction: it is why the check cannot be built on
 * {@code File.getCanonicalPath()}, and {@link #canonicalPathDoesNotTraverseDirectoryLink} keeps that
 * reason in the suite rather than only in a design document.
 */
public class PathConfinementTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File root;
    private File outside;
    private File caseFolder;
    private List<Path> roots;
    private File junction;

    @Before
    public void setUp() throws Exception {
        root = McpTestSupport.realDirectory(temp.getRoot(), "allowed");
        outside = McpTestSupport.realDirectory(temp.getRoot(), "outside");
        caseFolder = McpTestSupport.realDirectory(temp.getRoot(), "case");
        roots = Collections.singletonList(root.toPath());
    }

    @After
    public void tearDown() throws Exception {
        if (junction != null) {
            // Before TemporaryFolder's recursive delete runs. See McpTestSupport#createDirectoryLink.
            McpTestSupport.removeDirectoryLink(junction);
        }
    }

    private ResolvedDestination resolve(String requested) {
        return PathConfinement.resolve(requested, roots, caseFolder, false);
    }

    @Test
    public void destinationInsideARootIsAllowed() {
        ResolvedDestination result = resolve(new File(root, "report.xlsx").getAbsolutePath());
        assertEquals(Verdict.ALLOWED, result.getVerdict());
        assertEquals(root.toPath(), result.getRoot());
    }

    @Test
    public void destinationInASubfolderThatDoesNotExistYetIsAllowed() {
        // toRealPath throws for a file that is not there, and an export destination never is. The
        // resolution has to climb to the deepest existing ancestor and rebuild from it.
        ResolvedDestination result = resolve(new File(root, "deep/deeper/report.csv").getAbsolutePath());
        assertEquals(Verdict.ALLOWED, result.getVerdict());
        assertFalse("resolving must not create anything", new File(root, "deep").exists());
    }

    @Test
    public void destinationOutsideEveryRootIsRefused() {
        ResolvedDestination result = resolve(new File(outside, "report.csv").getAbsolutePath());
        assertEquals(Verdict.OUTSIDE_ROOTS, result.getVerdict());
        assertNull(result.getRoot());
    }

    @Test
    public void relativePathIsRefused() {
        // What a relative path resolves against depends on how the server was launched.
        ResolvedDestination result = resolve("report.csv");
        assertEquals(Verdict.UNRESOLVABLE, result.getVerdict());
        assertNotNull(result.getReason());
    }

    @Test
    public void parentTraversalOutOfTheRootIsRefused() {
        String traversal = new File(root, ".." + File.separator + "outside" + File.separator + "report.csv")
                .getAbsolutePath();
        assertEquals(Verdict.OUTSIDE_ROOTS, resolve(traversal).getVerdict());
    }

    @Test
    public void directoryJunctionOutOfTheRootIsRefused() throws Exception {
        junction = new File(root, "escape");
        McpTestSupport.createDirectoryLink(junction, outside);

        ResolvedDestination result = resolve(new File(junction, "loot.csv").getAbsolutePath());
        assertEquals("a link inside the root that lands outside it must be refused", Verdict.OUTSIDE_ROOTS,
                result.getVerdict());
        assertTrue("the refusal has to say where it would really have gone",
                result.getResolved().startsWith(outside.toPath()));
    }

    @Test
    public void canonicalPathDoesNotTraverseDirectoryLink() throws Exception {
        McpTestSupport.assumeWindows();
        junction = new File(root, "escape");
        McpTestSupport.createDirectoryLink(junction, outside);
        File target = new File(junction, "loot.csv");

        // This is the measurement that forced the implementation off java.io.File, kept here so the
        // reason survives in the suite. If a future JDK starts resolving junctions in
        // getCanonicalPath, this fails and someone gets to re-read the decision rather than
        // discovering it by accident.
        String canonical = target.getCanonicalPath();
        assertTrue("getCanonicalPath is expected to keep the junction in the path, which is why a prefix "
                + "check built on it would have approved this destination",
                canonical.startsWith(root.getCanonicalPath()));

        Path real = target.getParentFile().toPath().toRealPath();
        assertTrue("toRealPath is expected to resolve the junction to its target",
                real.startsWith(outside.toPath()));
    }

    @Test
    public void alternateDataStreamIsRefusedAsUnresolvable() {
        McpTestSupport.assumeWindows();
        // java.io.File accepts this and FileOutputStream writes to the hidden stream; NIO rejects
        // the name outright, which turns a silent escape into a named refusal.
        ResolvedDestination result = resolve(new File(root, "report.xlsx").getAbsolutePath() + ":hidden");
        assertEquals(Verdict.UNRESOLVABLE, result.getVerdict());
        assertNotNull(result.getReason());
    }

    @Test
    public void trailingSpaceIsRefusedAsUnresolvable() {
        McpTestSupport.assumeWindows();
        assertEquals(Verdict.UNRESOLVABLE, resolve(new File(root, "report.csv").getAbsolutePath() + " ").getVerdict());
    }

    @Test
    public void caseDifferenceStillResolvesIntoTheRoot() {
        McpTestSupport.assumeWindows();
        String upper = new File(root, "report.csv").getAbsolutePath().toUpperCase();
        assertEquals("a path that differs only in case names the same folder", Verdict.ALLOWED,
                resolve(upper).getVerdict());
    }

    @Test
    public void extendedLengthPrefixOutsideTheRootIsStillRefused() {
        McpTestSupport.assumeWindows();
        String extended = "\\\\?\\" + new File(outside, "report.csv").getAbsolutePath();

        // Deliberately asserting "refused" rather than a particular verdict, because which one it is
        // depends on the runtime. On Java 11 — the version the release embeds — Paths.get rejects
        // the "\\?\" prefix outright with InvalidPathException, so this arrives as UNRESOLVABLE. On
        // Java 25 the same string parses, the prefix is stripped, and it arrives as OUTSIDE_ROOTS.
        // Both are refusals and the guarantee holds either way; pinning the verdict would make this
        // test pass on the bench and fail on the runtime that ships, or the reverse.
        assertFalse("an extended-length path naming somewhere outside the roots must not be allowed",
                resolve(extended).isAllowed());
    }

    @Test
    public void aRootIsNotMatchedByASiblingSharingItsPrefix() throws Exception {
        File sibling = McpTestSupport.realDirectory(temp.getRoot(), "allowed-extra");
        // Path.startsWith compares whole name elements. A String.startsWith would have approved this.
        assertEquals(Verdict.OUTSIDE_ROOTS, resolve(new File(sibling, "report.csv").getAbsolutePath()).getVerdict());
    }

    /**
     * A root that contains the case folder, resolved the way the server resolves its own roots.
     *
     * <p>
     * {@code toRealPath} and not {@code toAbsolutePath}: on Windows the temporary folder is reached
     * through an 8.3 short name, and a root left unresolved would never match a destination that was
     * resolved — which is the same mistake the rule itself exists to prevent.
     */
    private List<Path> wideRoot() throws Exception {
        return Collections.singletonList(temp.getRoot().toPath().toRealPath());
    }

    @Test
    public void theCaseFolderIsRefusedEvenWhenARootContainsIt() throws Exception {
        // The root is the temporary tree, which contains the case folder. FR-004: the case rule wins.
        ResolvedDestination result = PathConfinement.resolve(new File(caseFolder, "report.csv").getAbsolutePath(),
                wideRoot(), caseFolder, false);
        assertEquals(Verdict.INSIDE_CASE, result.getVerdict());
    }

    @Test
    public void allowInsideCaseSuppressesOnlyTheCaseRule() throws Exception {
        List<Path> wideRoot = wideRoot();
        assertEquals("with the escape hatch on, the case folder becomes writable", Verdict.ALLOWED,
                PathConfinement.resolve(new File(caseFolder, "report.csv").getAbsolutePath(), wideRoot, caseFolder,
                        true).getVerdict());

        // ...and nothing else does. This is the semantic that was narrowed: the flag used to make
        // the whole filesystem writable, because the check returned before doing anything else.
        File elsewhere = new File(temp.getRoot().getParentFile(), "somewhere-else.csv");
        assertEquals("the escape hatch must not re-open the rest of the filesystem", Verdict.OUTSIDE_ROOTS,
                PathConfinement.resolve(elsewhere.getAbsolutePath(), wideRoot, caseFolder, true).getVerdict());
    }

    @Test
    public void withNoRootsDeclaredNothingIsAllowed() {
        ResolvedDestination result = PathConfinement.resolve(new File(root, "report.csv").getAbsolutePath(),
                Collections.emptyList(), caseFolder, false);
        assertEquals(Verdict.OUTSIDE_ROOTS, result.getVerdict());
    }

    @Test
    public void aRefusedDestinationLeavesNothingBehind() throws Exception {
        // FR-002. The previous implementation created the intermediate folders before deciding
        // anything, so a refusal left a trail of empty directories at a path the examiner never
        // agreed to write to.
        File deep = new File(outside, "a/b/c");
        String destination = new File(deep, "report.csv").getAbsolutePath();
        for (String each : Arrays.asList(destination, destination + ":hidden", "report.csv")) {
            assertFalse("no verdict may be ALLOWED here", resolve(each).isAllowed());
        }
        assertFalse("a refused destination must not have created its folders",
                Files.exists(new File(outside, "a").toPath()));
    }
}
