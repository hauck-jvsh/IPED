package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

import iped.mcp.export.EvidenceFileName;

/**
 * A name taken from evidence is input, not a name (FR-087).
 *
 * <p>
 * The item's name was chosen by whoever made the file, inside material that was seized precisely
 * because of what someone did with it. These tests are about the difference between a string that
 * looks like a name and a string that <i>acts</i> like a path — the second is how an export leaves
 * the folder it was confined to, or lands somewhere other than where the answer says it did.
 */
public class EvidenceFileNameTest {

    private static final Path ROOT = Paths.get("C:", "exports").toAbsolutePath();

    @Test
    public void aNameThatWouldClimbOutOfTheFolderStaysInIt() {
        // The classic, and the reason this class exists rather than a string concatenation.
        String name = EvidenceFileName.forItem(4711, "../../autoexec.bat", null);
        assertFalse("a separator must not survive: " + name, name.contains("/") || name.contains("\\"));
        Path written = ROOT.resolve(name).normalize();
        assertTrue("the resolved path left the root: " + written, written.getParent().equals(ROOT));
    }

    @Test
    public void aNameThatWouldOpenAnAlternateDataStreamDoesNot() {
        // On Windows "report.txt:hidden" writes bytes into a stream of report.txt, invisible to
        // anyone listing the folder. The host file is inside the root, so confinement alone would
        // not catch it.
        String name = EvidenceFileName.forItem(9, "report.txt:hidden", null);
        assertFalse("the stream separator must not survive: " + name, name.contains(":"));
    }

    @Test
    public void aNameThatNamesADeviceBecomesAFile() {
        // CON, NUL, LPT1 and friends are devices on Windows: opening one for writing succeeds and
        // writes nowhere. The id prefix is what defuses every one of them at once.
        for (String device : new String[] { "CON", "NUL", "PRN", "AUX", "COM1", "LPT1" }) {
            String name = EvidenceFileName.forItem(5, device, null);
            assertFalse("a device name must not be produced: " + name, name.equalsIgnoreCase(device));
            assertTrue("the id is what makes it a file: " + name, name.startsWith("5-"));
        }
    }

    @Test
    public void trailingDotsAndSpacesGoBecauseWindowsDropsThemSilently() {
        // "photo.jpg. " and "photo.jpg" are the same file on Windows, but only one of them is the
        // path this server would report. The answer has to name the file that exists.
        assertEquals("7-photo.jpg", EvidenceFileName.forItem(7, "photo.jpg. ", null));
        assertEquals("7-photo.jpg", EvidenceFileName.forItem(7, "  photo.jpg  ", null));
    }

    @Test
    public void controlCharactersGoBecauseTheyAreInvisibleInTheAnswer() {
        String name = EvidenceFileName.forItem(3, "in" + (char) 0x07 + "voice" + (char) 0x0A + "real.pdf", null);
        for (int i = 0; i < name.length(); i++) {
            assertTrue("a control character survived at " + i + ": " + name, name.charAt(i) >= 0x20);
        }
    }

    @Test
    public void anItemWithNoUsableNameIsStillNamedByItsId() {
        assertEquals("42", EvidenceFileName.forItem(42, null, null));
        assertEquals("42", EvidenceFileName.forItem(42, "   ", null));
        assertEquals("42", EvidenceFileName.forItem(42, "...", null));
    }

    @Test
    public void anOverLongNameIsCutButKeepsWhatSaysWhatItIs() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longName.append('a');
        }
        String name = EvidenceFileName.forItem(1, longName + ".jpg", null);
        assertTrue("the name must be cut to something a path can hold: " + name.length(), name.length() < 140);
        assertTrue("the extension is what tells a viewer what the file is: " + name, name.endsWith(".jpg"));
    }

    @Test
    public void theSuffixMarksATextExportAsOneWithoutHidingWhatItCameFrom() {
        // Both the original name and the fact that this is text, because an examiner reading a
        // folder of exports has to be able to tell an item from its transcription.
        assertEquals("8-conversa.db.txt", EvidenceFileName.forItem(8, "conversa.db", ".txt"));
    }

    @Test
    public void theIdIsAlwaysThereBecauseTheFileHasToSayWhichItemItIs() {
        // Two items in one case can carry the same name, and a file that cannot be traced back to
        // an item is not evidence of anything.
        assertTrue(EvidenceFileName.forItem(100, "IMG_0001.JPG", null).startsWith("100-"));
        assertTrue(EvidenceFileName.forItem(200, "IMG_0001.JPG", null).startsWith("200-"));
    }
}
