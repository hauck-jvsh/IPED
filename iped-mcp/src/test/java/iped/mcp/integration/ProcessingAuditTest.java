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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
import iped.mcp.processing.ProcessingRequest;
import iped.mcp.protocol.JsonRpcCodec;

/**
 * What the record has to answer afterwards: who asked, under what permission, and what happened
 * (FR-034, FR-038, SC-010, SC-016), plus the refusals along the way (FR-008).
 *
 * <p>
 * The reconstitution here is deliberately made from the files alone. If it needed anything held in
 * memory it would not be reconstitution — it would be this session remembering.
 */
public class ProcessingAuditTest {

    private static final long ENGINE_START_TIMEOUT_MILLIS = 120_000;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File evidence;
    private File caseRoot;
    private File destination;
    private McpServerConfig config;
    private JobRunner runner;

    @Before
    public void setUp() throws Exception {
        evidence = McpTestSupport.requireSourceEvidence();
        caseRoot = McpTestSupport.requireCaseRoot();
        destination = new File(caseRoot, "audit-" + UUID.randomUUID().toString().substring(0, 8));

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
    public void aRefusedRequestIsRecordedWithWhatWasAskedAndTheRuleApplied() throws Exception {
        File outside = McpTestSupport.realDirectory(temp.getRoot(), "outside-every-area");
        File notEvidence = new File(outside, "elsewhere.E01");
        Files.write(notEvidence.toPath(), new byte[] { 1 });

        JsonNode error = callThroughASession("iped_process_evidence",
                "{\"source_path\":" + quote(notEvidence.getAbsolutePath()) + ",\"destination_path\":"
                        + quote(destination.getAbsolutePath()) + ",\"profile\":\"fastmode\"}");

        assertEquals("SOURCE_NOT_PERMITTED", error.path("error").path("data").path("code").asText());
        // FR-008: the refusal has to say where reading is permitted, or the agent can only guess.
        assertTrue("the refusal must name the permitted areas: " + error,
                error.toString().contains(evidence.getParentFile().getAbsolutePath().replace("\\", "\\\\")));

        // And it has to be in the trail: a refusal is part of the history of the examination, not a
        // non-event.
        String trail = readTrail();
        assertTrue("the refusal must appear in the audit trail", trail.contains("iped_process_evidence"));
        assertFalse("the trail must not be empty", trail.trim().isEmpty());
    }

    @Test
    public void aJobCarriesWhoAskedAndUnderWhatPermission() throws Exception {
        ProcessingJob job = runner.start(new ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "fastmode", "RockPi4", null), "examiner-one");
        awaitEngineTree(job.getPid());
        runner.cancel(job.getJobId(), "examiner-two");

        // Reconstituted from disk alone, by an instance that never saw the job run.
        ProcessingJob recorded = new JobStore(config.getAuditArea()).load(job.getJobId());

        assertNotNull(recorded);
        assertEquals("examiner-one", recorded.getRequestedBy());
        assertEquals("cancelling is open to any session, so who did it has to be recorded separately",
                "examiner-two", recorded.getCancelledBy());
        assertEquals(evidence.getAbsolutePath(), recorded.getRequest().getSourcePath());
        assertEquals("fastmode", recorded.getRequest().getProfile());

        // FR-038: authorization is granted by configuration, before the request exists, so without
        // this the record would show that a job ran and never under what permission.
        assertNotNull("the standing permission must be recorded", recorded.getAuthorizedUnder());
        assertTrue("it must name the areas that were readable: " + recorded.getAuthorizedUnder(),
                recorded.getAuthorizedUnder().contains(evidence.getParentFile().getAbsolutePath()));
        assertTrue("and the roots that were writable",
                recorded.getAuthorizedUnder().contains(caseRoot.getAbsolutePath()));
    }

    @Test
    public void aSecretReferenceIsRecordedButTheSecretIsNot() throws Exception {
        File secrets = new File(temp.getRoot(), "secrets.txt");
        Files.write(secrets.toPath(), "container-one=Sup3rSecretPassphrase\n".getBytes(StandardCharsets.UTF_8));
        config.setProcessingSecretsFile(secrets.getAbsolutePath());
        JobRunner withSecrets = new JobRunner(config, iped.mcp.Diagnostics.resolveIpedRoot(),
                new JobStore(config.getAuditArea()));
        JobRunner.setProcessInstance(withSecrets);

        ProcessingJob job = withSecrets.start(new ProcessingRequest(evidence.getAbsolutePath(),
                destination.getAbsolutePath(), "fastmode", null, "container-one"), "examiner");
        awaitEngineTree(job.getPid());
        withSecrets.cancel(job.getJobId(), "examiner");

        String record = new String(Files.readAllBytes(
                new File(withSecrets.getStore().jobFolder(job.getJobId()), "job.json").toPath()),
                StandardCharsets.UTF_8);
        String log = readIfPresent(new File(job.getLogPath()));
        String trail = readTrail();

        // SC-011, over the four places FR-015 names. Not over argv: the password is there by a
        // decision that was taken deliberately and is declared to the examiner instead.
        for (String where : Arrays.asList(record, log, trail)) {
            assertFalse("the passphrase must not be stored", where.contains("Sup3rSecretPassphrase"));
        }
        assertTrue("the reference itself is part of the record", record.contains("container-one"));
    }

    @Test
    public void aRequestUsingASecretIsToldAboutTheExposure() throws Exception {
        File secrets = new File(temp.getRoot(), "secrets.txt");
        Files.write(secrets.toPath(), "container-one=whatever\n".getBytes(StandardCharsets.UTF_8));
        config.setProcessingSecretsFile(secrets.getAbsolutePath());
        JobRunner withSecrets = new JobRunner(config, iped.mcp.Diagnostics.resolveIpedRoot(),
                new JobStore(config.getAuditArea()));
        JobRunner.setProcessInstance(withSecrets);

        JsonNode response = callThroughASession("iped_process_evidence",
                "{\"source_path\":" + quote(evidence.getAbsolutePath()) + ",\"destination_path\":"
                        + quote(destination.getAbsolutePath())
                        + ",\"profile\":\"fastmode\",\"secret_ref\":\"container-one\"}");

        try {
            String notice = response.path("result").path("structuredContent")
                    .path("secret_exposure_notice").asText(null);
            // SC-025. A known limitation that is stated is a decision the examiner can make; the
            // same limitation unstated is a trap.
            assertNotNull("an accept using a secret must declare the exposure: " + response, notice);
            assertTrue("and say where it applies: " + notice, notice.contains("command line"));
        } finally {
            ProcessingJob active = withSecrets.getActive();
            if (active != null) {
                withSecrets.cancel(active.getJobId(), "teardown");
            }
        }
    }

    @Test
    public void anUnknownJobIsUnknownRatherThanExpired() throws Exception {
        JsonNode error = callThroughASession("iped_job_status", "{\"job_id\":\"never-existed-here\"}");

        assertEquals("UNKNOWN_JOB", error.path("error").path("data").path("code").asText());
        // Retention is indefinite, which is what lets this answer mean one thing only.
        assertTrue("the answer must not leave room for 'it expired': " + error,
                error.toString().contains("never existed"));
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

    private String readTrail() throws IOException {
        StringBuilder all = new StringBuilder();
        File[] files = config.getAuditArea().listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files != null) {
            for (File file : files) {
                all.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }

    private static String readIfPresent(File file) throws IOException {
        return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    }

    private static String quote(String path) {
        return "\"" + path.replace("\\", "\\\\") + "\"";
    }

    private static List<ProcessHandle> awaitEngineTree(long pid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ENGINE_START_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                List<ProcessHandle> descendants = new ArrayList<>();
                handle.get().descendants().forEach(descendants::add);
                if (!descendants.isEmpty()) {
                    return descendants;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the engine did not spawn its processing JVM in time");
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
