package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.processing.JobRunner;
import iped.mcp.processing.JobStore;
import iped.mcp.processing.ProcessingJob;
import iped.mcp.protocol.JsonRpcCodec;

/**
 * A job outlives the session that asked for it, and a later session picks it up (FR-021, FR-022,
 * SC-005, SC-006).
 *
 * <p>
 * Closing standard input is how every harness signals it is done, so a session ending is the normal
 * way out rather than an edge case. A job that died with it would be worse than useless: hours burnt
 * and a half-written case folder left behind.
 */
public class JobSurvivesSessionTest {

    private static final long PROGRESS_TIMEOUT_MILLIS = 120_000;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File evidence;
    private File destination;
    private McpServerConfig config;
    private JobRunner runner;

    @Before
    public void setUp() throws Exception {
        evidence = McpTestSupport.requireSourceEvidence();
        File caseRoot = McpTestSupport.requireCaseRoot();
        destination = new File(caseRoot, "survive-" + UUID.randomUUID().toString().substring(0, 8));

        config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);
        config.setProcessingSourceAreas(Arrays.asList(evidence.getParentFile().getAbsolutePath()));
        config.setProcessingCaseRoots(Arrays.asList(caseRoot.getAbsolutePath()));
        runner = new JobRunner(config, iped.mcp.Diagnostics.resolveIpedRoot(),
                new JobStore(config.getAuditArea()));
        JobRunner.setProcessInstance(runner);
    }

    @After
    public void tearDown() {
        JobRunner.setProcessInstance(null);
        ProcessingJob active = runner == null ? null : runner.getActive();
        if (active != null) {
            runner.cancel(active.getJobId(), "teardown");
        }
        if (destination != null && destination.exists()) {
            deleteRecursively(destination);
        }
    }

    @Test
    public void theJobKeepsGoingAfterTheSessionThatStartedItIsGone() throws Exception {
        String jobId = startThroughASessionThatThenEnds();

        long atSessionEnd = processedItems(jobId);
        awaitProgressBeyond(jobId, atSessionEnd);
        long later = processedItems(jobId);

        // Comparing progress, not state. A frozen job also answers RUNNING, so a test that only
        // checked the state would pass with the processing stopped dead — which is precisely the
        // failure it is supposed to catch.
        assertTrue("the job must have advanced after the session ended: " + atSessionEnd + " then " + later,
                later > atSessionEnd);
    }

    @Test
    public void aLaterSessionFindsTheJobAndSeesTheSameDetail() throws Exception {
        String jobId = startThroughASessionThatThenEnds();

        // A different session entirely — the case FR-022 exists for.
        JsonNode status = callThroughASession("iped_job_status", "{\"job_id\":\"" + jobId + "\"}")
                .path("result").path("structuredContent");

        assertEquals(jobId, status.path("job_id").asText());
        assertEquals("RUNNING", status.path("state").asText());
        assertNotNull("a later session gets the progress, not just the state",
                status.path("progress").path("phase"));
        // And the permission it ran under, which the session that started it did not have to
        // remember for this to be answerable.
        assertTrue("the standing authorization must survive the session",
                status.path("authorized_under").asText().contains(evidence.getParentFile().getAbsolutePath()));
    }

    /**
     * Starts a job through a full session and lets that session end.
     *
     * <p>
     * The server returns from {@code start} when its input is exhausted, which is exactly what
     * happens when a harness closes standard input and exits.
     */
    private String startThroughASessionThatThenEnds() throws Exception {
        JsonNode response = callThroughASession("iped_process_evidence",
                "{\"source_path\":" + quote(evidence.getAbsolutePath()) + ",\"destination_path\":"
                        + quote(destination.getAbsolutePath()) + ",\"profile\":\"fastmode\"}");
        String jobId = response.path("result").path("structuredContent").path("job_id").asText(null);
        assertNotNull("the session should have started a job: " + response, jobId);
        return jobId;
    }

    private long processedItems(String jobId) {
        ProcessingJob live = runner.getActive();
        ProcessingJob job = live != null && live.getJobId().equals(jobId) ? live
                : runner.getStore().load(jobId);
        return job == null || job.getProgress() == null ? 0 : job.getProgress().getProcessedItems();
    }

    private void awaitProgressBeyond(String jobId, long mark) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PROGRESS_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (processedItems(jobId) > mark) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("the job made no progress after the session ended");
    }

    private JsonNode callThroughASession(String tool, String argumentsJson) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String requests = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + argumentsJson + "}}\n";
        try (McpServerMain server = new McpServerMain(config)) {
            server.start(new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)), out);
        }
        for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\r?\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode parsed = JsonRpcCodec.mapper().readTree(line);
            if (parsed.path("id").asInt() == 2) {
                return parsed;
            }
        }
        throw new AssertionError("no answer for " + tool);
    }

    private static String quote(String path) {
        return "\"" + path.replace("\\", "\\\\") + "\"";
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            // Scratch leftovers are noise, not a failure.
        }
    }
}
