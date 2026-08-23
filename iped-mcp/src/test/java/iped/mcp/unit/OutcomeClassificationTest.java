package iped.mcp.unit;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.processing.JobRunner;
import iped.mcp.processing.JobStore;
import iped.mcp.processing.ProcessingJob;
import iped.mcp.processing.ProcessingJob.FailureCause;
import iped.mcp.processing.ProcessingRequest;

/**
 * A failure of the environment is not a failure of the evidence (FR-049, SC-024).
 *
 * <p>
 * The two change what the examiner does next. Media unplugged, a share dropped, a permission lost:
 * plug it back in and continue. Evidence the engine cannot read: continuing will not help, and the
 * next step is about the material rather than about the machine. Reporting both as "processing
 * failed" would leave the examiner to guess which one they have.
 */
public class OutcomeClassificationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aSourceThatIsGoneIsInaccessible() throws Exception {
        // What an unplugged disk or a dropped share looks like after the fact: the path the job was
        // given no longer resolves to anything.
        File missing = new File(temp.getRoot(), "removed-media.E01");

        assertEquals(FailureCause.SOURCE_INACCESSIBLE, classify(missing));
    }

    @Test
    public void aSourceStillThereIsNotBlamedWithoutTheEngineSayingSo() throws Exception {
        File present = new File(temp.getRoot(), "perfectly-fine.E01");
        Files.write(present.toPath(), new byte[] { 0, 1, 2 });

        // The source is there and nothing in the engine's own record accuses it. An unknown profile
        // fails exactly this way, and answering SOURCE_UNREADABLE would send the examiner to
        // re-acquire a disk that is fine. Only the engine's per-evidence record earns that claim.
        assertEquals(FailureCause.ENGINE_FAILURE, classify(present));
    }

    private FailureCause classify(File source) throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);
        config.setProcessingSourceAreas(Arrays.asList(temp.getRoot().getAbsolutePath()));
        config.setProcessingCaseRoots(Arrays.asList(temp.getRoot().getAbsolutePath()));

        JobRunner runner = new JobRunner(config, null, new JobStore(config.getAuditArea()));
        ProcessingJob job = new ProcessingJob("classify",
                new ProcessingRequest(source.getAbsolutePath(), new File(temp.getRoot(), "out").getAbsolutePath(),
                        "fastmode", null, null),
                "operator", Instant.now());
        return runner.classify(job);
    }
}
