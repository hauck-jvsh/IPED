package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.processing.DiskPreflight;

/**
 * The space requirement is computed from the source, and it warns rather than refuses (FR-044,
 * FR-046, SC-019, SC-021).
 *
 * <p>
 * A test here that asserted a refusal would be encoding the opposite of what the examiner decided:
 * an estimate of how much an index occupies depends on the profile, the material and whether item
 * export is on, and blocking legitimate work on it is worse than letting a job start that may not
 * fit. The decision to go ahead stays with whoever asked.
 */
public class DiskPreflightTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void theMinimumIsAPercentageOfTheSource() throws Exception {
        File source = new File(temp.getRoot(), "image.E01");
        Files.write(source.toPath(), new byte[1000]);

        DiskPreflight.Assessment assessment = new DiskPreflight(50).assess(source, temp.getRoot());

        assertTrue(assessment.isMeasured());
        assertEquals(1000, assessment.getSourceBytes());
        assertEquals("50% of a 1000-byte source is 500 bytes", 500, assessment.getRequiredBytes());
    }

    @Test
    public void aSegmentedImageIsMeasuredAsTheWholeSet() throws Exception {
        // Forensic images almost always arrive segmented. Measuring only the .E01 would give a
        // fraction of the real size, and the warning would then fail to appear in exactly the case
        // that most needs it (SC-021).
        File first = new File(temp.getRoot(), "split.E01");
        Files.write(first.toPath(), new byte[100]);
        Files.write(new File(temp.getRoot(), "split.E02").toPath(), new byte[200]);
        Files.write(new File(temp.getRoot(), "split.E03").toPath(), new byte[300]);

        DiskPreflight.Assessment assessment = new DiskPreflight(50).assess(first, temp.getRoot());

        assertEquals("the set is 600 bytes, not the 100 of its first segment", 600,
                assessment.getSourceBytes());
    }

    @Test
    public void acquisitionSidecarsAreNotPartOfTheEvidence() throws Exception {
        // A hash list and a log left beside the image by the acquisition tool share its name and are
        // not evidence. Counting them would inflate the requirement, and a pattern loose enough to
        // accept "csv" would accept most three-letter extensions there are.
        File first = new File(temp.getRoot(), "acquired.E01");
        Files.write(first.toPath(), new byte[100]);
        Files.write(new File(temp.getRoot(), "acquired.csv").toPath(), new byte[9999]);
        Files.write(new File(temp.getRoot(), "acquired.txt").toPath(), new byte[9999]);
        Files.write(new File(temp.getRoot(), "acquired.log").toPath(), new byte[9999]);

        DiskPreflight.Assessment assessment = new DiskPreflight(50).assess(first, temp.getRoot());

        assertEquals("only the image counts", 100, assessment.getSourceBytes());
    }

    @Test
    public void aNumericallySplitImageIsAlsoASet() throws Exception {
        File first = new File(temp.getRoot(), "raw.001");
        Files.write(first.toPath(), new byte[10]);
        Files.write(new File(temp.getRoot(), "raw.002").toPath(), new byte[20]);

        DiskPreflight.Assessment assessment = new DiskPreflight(50).assess(first, temp.getRoot());

        assertEquals(30, assessment.getSourceBytes());
    }

    @Test
    public void aLogicalFolderIsSummedRecursively() throws Exception {
        File folder = McpTestSupport.realDirectory(temp.getRoot(), "logical");
        Files.write(new File(folder, "a.txt").toPath(), new byte[10]);
        File nested = McpTestSupport.realDirectory(folder, "nested");
        Files.write(new File(nested, "b.txt").toPath(), new byte[20]);

        DiskPreflight.Assessment assessment = new DiskPreflight(50).assess(folder, temp.getRoot());

        assertEquals(30, assessment.getSourceBytes());
    }

    @Test
    public void notEnoughSpaceWarnsAndNeverRefuses() throws Exception {
        File source = new File(temp.getRoot(), "huge.E01");
        Files.write(source.toPath(), new byte[100_000]);

        // The percentage is derived from the volume's actual free space rather than guessed, so the
        // test does not quietly stop exercising the warning on a machine with more disk than the
        // author had.
        long free = java.nio.file.Files.getFileStore(temp.getRoot().toPath()).getUsableSpace();
        int percent = (int) Math.min(Integer.MAX_VALUE, free / 1000 + 1000);
        DiskPreflight.Assessment assessment = new DiskPreflight(percent).assess(source, temp.getRoot());

        assertNotNull("insufficient space must produce a warning", assessment.getWarning());
        // SC-019: the three numbers, because a warning that does not say how far short it falls is
        // not something an examiner can weigh.
        assertTrue("the warning must carry free space: " + assessment.getWarning(),
                assessment.getWarning().contains("free"));
        assertTrue("the warning must say it is not a refusal: " + assessment.getWarning(),
                assessment.getWarning().contains("warning, not a refusal"));
    }

    @Test
    public void enoughSpaceProducesNoWarningAtAll() throws Exception {
        File source = new File(temp.getRoot(), "small.E01");
        Files.write(source.toPath(), new byte[10]);

        DiskPreflight.Assessment assessment = new DiskPreflight(50).assess(source, temp.getRoot());

        assertFalse("there is room, so there is nothing to say", assessment.hasWarning());
    }

    @Test
    public void anUnmeasurableSourceLeavesTheRequirementUnavailableRatherThanZero() throws Exception {
        File missing = new File(temp.getRoot(), "not-there.E01");

        DiskPreflight.Assessment assessment = new DiskPreflight(50).assess(missing, temp.getRoot());

        // Absence is not zero. A requirement of zero would silently mean "always fits" (FR-046).
        assertFalse("an unmeasurable source must be declared unmeasured", assessment.isMeasured());
        assertFalse("and must not warn on a number it does not have", assessment.hasWarning());
    }
}
