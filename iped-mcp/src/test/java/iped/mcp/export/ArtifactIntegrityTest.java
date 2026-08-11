package iped.mcp.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.export.PathConfinement.Verdict;
import iped.mcp.protocol.McpError;

/**
 * FR-034 and SC-015: a destination can be legitimately inside a permitted root and still keep
 * nothing.
 *
 * <p>
 * <b>What this suite is really asserting.</b> Not the containment verdict — that one is
 * {@link Verdict#ALLOWED} here, and correctly so, because nothing escaped a root. The assertion is
 * about the answer the agent gets back. A test written against the verdict would pass with the
 * defect fully present, which is the trap this file exists to avoid.
 *
 * <p>
 * The suite lives in the {@code export} package on purpose: the check it exercises is an internal
 * step of {@code ArtifactWriter}, and reaching it through the public {@code write} would require a
 * processed case, which would turn a defect that reproduces in milliseconds into one that only runs
 * when someone has a case configured.
 */
public class ArtifactIntegrityTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File root;

    @Before
    public void setUp() throws Exception {
        root = McpTestSupport.realDirectory(temp.getRoot(), "allowed");
    }

    @Test
    public void aRealArtifactIsConfirmedAndItsSizeReported() throws Exception {
        Path artifact = root.toPath().resolve("report.csv");
        Files.write(artifact, "item_id\r\n1\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(Files.size(artifact), ArtifactWriter.verifyArtifact(artifact));
    }

    @Test
    public void aDestinationThatKeepsNothingIsRefusedEvenThoughItIsInsideTheRoot() throws Exception {
        McpTestSupport.assumeWindows();
        Path nul = root.toPath().resolve("NUL");

        // 1. Containment approves it, and that is the correct verdict: nothing escaped a root.
        List<Path> roots = Collections.singletonList(root.toPath());
        assertEquals("the allow-list cannot catch this, because the path does not leave the root",
                Verdict.ALLOWED, PathConfinement.resolve(nul.toString(), roots, null, false).getVerdict());

        // 2. The write is accepted by the platform and keeps nothing.
        try (OutputStream out = Files.newOutputStream(nul)) {
            out.write("0123456789".getBytes(StandardCharsets.UTF_8));
        } catch (IOException acceptedElsewhere) {
            // Some runtimes refuse to open the device through NIO. Either way what matters is that
            // no artifact exists afterwards, which the next assertion covers.
        }
        assertFalse("the premise of this test is that nothing is there afterwards",
                Files.isRegularFile(nul));

        // 3. The answer the agent gets must be a failure, never success with bytes: 0.
        try {
            ArtifactWriter.verifyArtifact(nul);
            fail("a destination that keeps nothing must not be reported as a written artifact");
        } catch (McpError e) {
            assertEquals(McpError.EXPORT_FAILED, e.getCode());
            assertNotNull("the refusal has to say what to do instead", e.getRemedy());
            assertTrue("the examiner needs to know nothing was delivered",
                    e.getRemedy().contains("Nothing was delivered"));
        }
    }

    @Test
    public void aMissingArtifactIsRefused() {
        Path missing = root.toPath().resolve("never-written.csv");
        try {
            ArtifactWriter.verifyArtifact(missing);
            fail("a file that is not there must not be reported as written");
        } catch (McpError e) {
            assertEquals(McpError.EXPORT_FAILED, e.getCode());
        }
    }

    @Test
    public void anEmptyArtifactIsRefused() throws Exception {
        // Zero bytes is not a report of nothing found; it is a write that did not land. An artifact
        // always carries at least its header, because an empty set is refused before this point.
        Path empty = root.toPath().resolve("empty.csv");
        Files.write(empty, new byte[0]);
        try {
            ArtifactWriter.verifyArtifact(empty);
            fail("a zero-byte artifact must not be reported as written");
        } catch (McpError e) {
            assertEquals(McpError.EXPORT_FAILED, e.getCode());
        }
    }
}
