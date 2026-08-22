package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import iped.mcp.processing.ProcessingJob.State;
import iped.mcp.processing.ProcessingRequest;
import iped.mcp.protocol.JsonRpcCodec;

/**
 * A failure is diagnosable without reaching the server machine, and no log byte reaches the protocol
 * channel (SC-018).
 *
 * <p>
 * The second half is the invariant 006 established, applied to a source it never had. It is not a
 * noisy failure: a single log line on stdout corrupts the session, and the symptom looks like a
 * protocol defect in the server rather than a logging mistake — which is exactly how it was
 * misdiagnosed in the field the first time.
 */
public class ProcessingLogTest {

    private static final long COMPLETION_TIMEOUT_MILLIS = 5 * 60 * 1000L;

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
        destination = new File(caseRoot, "log-" + UUID.randomUUID().toString().substring(0, 8));

        config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setProcessingEnabled(true);
        config.setProcessingSourceAreas(Arrays.asList(evidence.getParentFile().getAbsolutePath()));
        config.setProcessingCaseRoots(Arrays.asList(caseRoot.getAbsolutePath()));
        // A profile the installation does not have. It passes the permitted-set check and then the
        // engine refuses it, which is a genuine engine failure rather than a simulated one — and it
        // is deterministic, unlike corrupting evidence and hoping the reader rejects it.
        config.setProcessingProfiles(Arrays.asList("fastmode", "no-such-profile"));

        runner = new JobRunner(config, iped.mcp.Diagnostics.resolveIpedRoot(),
                new JobStore(config.getAuditArea()));
        JobRunner.setProcessInstance(runner);
    }

    @After
    public void tearDown() {
        JobRunner.setProcessInstance(null);
        if (destination != null && destination.exists()) {
            deleteRecursively(destination);
        }
    }

    @Test
    public void aFailureCarriesItsCauseAndTheFullLogStaysOnDisk() throws Exception {
        ProcessingJob job = runner.start(new ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "no-such-profile", null, null), "operator");

        ProcessingJob finished = awaitTerminal(job.getJobId());
        assertEquals("an unknown profile must make the engine fail", State.FAILED, finished.getState());

        // FR-042 first, because it is the durable artifact: if this is empty the reader never saw
        // the stream at all, which is a different defect from the excerpt not being derived.
        File logFile = new File(finished.getLogPath());
        assertTrue("the declared log path must exist: " + logFile, logFile.isFile());
        String log = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("the engine's own failure must be in the log, not just its exit code: " + log,
                log.contains("Profile not found"));

        // FR-043 together with FR-022, which is the case that matters and the one a field read of
        // the in-memory job would have missed: a *different* session asks about the job and has to
        // get the same explanation the session that ran it did. This is what a later session, or one
        // after a restart, actually sees.
        JsonNode status = statusThroughAFreshSession(job.getJobId());
        String excerpt = status.path("result").path("structuredContent").path("outcome")
                .path("diagnostic_excerpt").asText(null);
        assertNotNull("a later session must still get the diagnostic excerpt; got: " + status, excerpt);
        assertTrue("the excerpt must carry the cause, which is what makes it worth returning: " + excerpt,
                excerpt.contains("Profile not found"));
    }

    @Test
    public void notOneLogLineReachesTheProtocolChannel() throws Exception {
        // A whole session over a pair of streams, which is what the transport hands the server.
        // stdout here is the protocol channel: whatever lands in it is what the harness parses.
        ByteArrayOutputStream protocolChannel = new ByteArrayOutputStream();
        String requests = initialize() + "\n" + processEvidence("no-such-profile") + "\n";

        try (McpServerMain server = new McpServerMain(config)) {
            server.start(new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)), protocolChannel);
        }

        awaitNoActiveJob();

        String written = new String(protocolChannel.toByteArray(), StandardCharsets.UTF_8);
        for (String line : written.split("\r?\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            // Every line must parse as JSON-RPC. An engine log line here would not, and would take
            // the session down with it.
            JsonNode parsed = JsonRpcCodec.mapper().readTree(line);
            assertEquals("a non-protocol line reached the channel: " + line, JsonRpcCodec.VERSION,
                    parsed.path("jsonrpc").asText());
        }
        // And the engine's own signature must be nowhere in it.
        assertFalse("engine log output leaked into the protocol channel",
                written.contains("[processing.ui.ProgressConsole]") || written.contains("[engine.core."));
    }

    /**
     * Asks for a job's status from a session that did not run it.
     *
     * <p>
     * A fresh {@link McpServerMain} over the same configuration is what a later session is — and,
     * for everything that lives on disk, what a restarted server is too.
     */
    private JsonNode statusThroughAFreshSession(String jobId) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String requests = initialize() + "\n" + "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"iped_job_status\",\"arguments\":{\"job_id\":\"" + jobId + "\"}}}\n";
        try (McpServerMain later = new McpServerMain(config)) {
            later.start(new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)), out);
        }
        for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\r?\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode parsed = JsonRpcCodec.mapper().readTree(line);
            if (parsed.path("id").asInt() == 9) {
                return parsed;
            }
        }
        throw new AssertionError("the later session got no answer for iped_job_status");
    }

    private String initialize() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    }

    private String processEvidence(String profile) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"iped_process_evidence\",\"arguments\":{"
                + "\"source_path\":" + quote(evidence.getAbsolutePath()) + ","
                + "\"destination_path\":" + quote(destination.getAbsolutePath()) + ","
                + "\"profile\":\"" + profile + "\"}}}";
    }

    private static String quote(String path) {
        return "\"" + path.replace("\\", "\\\\") + "\"";
    }

    private ProcessingJob awaitTerminal(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + COMPLETION_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ProcessingJob stored = runner.getStore().load(jobId);
            if (stored != null && stored.getState().isTerminal()) {
                return stored;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("the job did not reach a terminal state in time");
    }

    private void awaitNoActiveJob() throws Exception {
        long deadline = System.currentTimeMillis() + COMPLETION_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline && runner.getActive() != null) {
            Thread.sleep(500);
        }
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
