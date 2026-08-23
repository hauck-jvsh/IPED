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
 * The server keeps answering while the engine has the machine (FR-025).
 *
 * <p>
 * The engine takes every core and tens of gigabytes for as long as the work lasts. What must not
 * happen is the <i>server</i> going quiet with it: a server that only replies once processing
 * finishes is, to whoever is waiting, indistinguishable from a dead one.
 *
 * <p>
 * This is why the engine runs in its own process. The check is cheap and the property it protects is
 * the reason for the whole arrangement, so it is worth asserting rather than assuming.
 */
public class ResponsivenessDuringJobTest {

    /** Generous on purpose: the machine is genuinely busy, and this is not a latency benchmark. */
    private static final long ANSWER_BUDGET_MILLIS = 15_000;
    private static final long ENGINE_START_TIMEOUT_MILLIS = 120_000;

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
        destination = new File(caseRoot, "busy-" + UUID.randomUUID().toString().substring(0, 8));

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
    public void theServerStillAnswersWhileTheEngineWorks() throws Exception {
        ProcessingJob job = startAJob();
        awaitProcessing(job);

        // Two different questions, both unrelated to the running job: one about the session itself,
        // one about the job. Neither may wait for the engine.
        long before = System.currentTimeMillis();
        JsonNode posture = callThroughASession("iped_session_info", "{}");
        long postureMillis = System.currentTimeMillis() - before;

        before = System.currentTimeMillis();
        JsonNode status = callThroughASession("iped_job_status", "{\"job_id\":\"" + job.getJobId() + "\"}");
        long statusMillis = System.currentTimeMillis() - before;

        assertNotNull("the session must still describe itself",
                posture.path("result").path("structuredContent").path("session_id").asText(null));
        assertEquals("RUNNING", status.path("result").path("structuredContent").path("state").asText());

        assertTrue("iped_session_info took " + postureMillis + " ms while the engine ran",
                postureMillis < ANSWER_BUDGET_MILLIS);
        assertTrue("iped_job_status took " + statusMillis + " ms while the engine ran",
                statusMillis < ANSWER_BUDGET_MILLIS);
    }

    @Test
    public void thePostureShowsTheRunningJobWhileItRuns() throws Exception {
        ProcessingJob job = startAJob();
        awaitProcessing(job);

        // FR-004: an examiner asking what this installation is doing gets the answer while it is
        // doing it, not afterwards.
        JsonNode processing = callThroughASession("iped_session_info", "{}").path("result")
                .path("structuredContent").path("posture").path("processing");

        assertEquals(Boolean.TRUE, processing.path("enabled").asBoolean());
        assertTrue("the declared source areas must be visible",
                processing.path("source_areas").isArray() && processing.path("source_areas").size() > 0);
    }

    private ProcessingJob startAJob() {
        return runner.start(new iped.mcp.processing.ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "fastmode", null, null), "operator");
    }

    /** Waits until the engine is genuinely working, so the question lands while the machine is busy. */
    private void awaitProcessing(ProcessingJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ENGINE_START_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (job.getProgress() != null && job.getProgress().getProcessedItems() > 0) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the engine did not start processing in time");
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
