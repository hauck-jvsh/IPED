package iped.mcp.processing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import iped.mcp.processing.ProcessingJob.FailureCause;
import iped.mcp.processing.ProcessingJob.State;

/**
 * Where a job's state lives between server runs (FR-041, FR-045).
 *
 * <h2>Why this is not part of the audit trail</h2>
 *
 * <p>
 * The obvious place for "who processed what, from where, with what outcome" is {@code AuditRecord},
 * and it is the wrong place. {@code AuditTrail.verify} recomposes the hashed node from what it
 * reads, so one extra field changes the result for records <b>already issued</b> — trails emitted
 * before this feature would stop verifying. The division is therefore imposed, not organizational:
 * the request is recorded in the trail through the shape that already exists, job state lives here,
 * and the session-to-job link lives in the session manifest. Reconstitution (FR-034) is the join of
 * the three.
 *
 * <h2>Retention is indefinite, and that is what makes "unknown" meaningful</h2>
 *
 * <p>
 * A job record is a forensic fact: it says an evidence was read, with which profile, by whom, with
 * what outcome. Discarding it on a timer would sit badly with the chain of custody the module exists
 * to sustain, and it costs one small file per job against cases of hundreds of gigabytes. The
 * secondary gain settles it: with nothing ever discarded, {@code UNKNOWN_JOB} means exactly "never
 * existed in this installation" rather than being ambiguous with "existed and was cleaned up" — a
 * distinction that matters to whoever checks a report months later.
 */
public final class JobStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobStore.class);
    private static final String JOBS_DIR = "jobs";
    private static final String JOB_FILE = "job.json";
    private static final String LOG_FILE = "processing.log";

    private final File jobsRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobStore(File auditArea) {
        this.jobsRoot = new File(auditArea, JOBS_DIR);
    }

    /** The folder holding one job's state and log. Created on demand. */
    public File jobFolder(String jobId) {
        return new File(jobsRoot, jobId);
    }

    /** Where the engine's own log for this job is written (FR-042). */
    public File logFile(String jobId) {
        return new File(jobFolder(jobId), LOG_FILE);
    }

    /**
     * Persists a job, replacing whatever was there.
     *
     * <p>
     * Written to a temporary file and moved into place, so a server that dies mid-write leaves the
     * previous state readable rather than a truncated one. Reconciliation depends on being able to
     * read this back (FR-024), and half a file would make the job neither running nor interrupted —
     * the two answers FR-024 forbids.
     */
    public synchronized void save(ProcessingJob job) throws IOException {
        File folder = jobFolder(job.getJobId());
        Files.createDirectories(folder.toPath());
        Path target = new File(folder, JOB_FILE).toPath();
        Path temp = new File(folder, JOB_FILE + ".tmp").toPath();
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(toNode(job));
        Files.write(temp, bytes);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** @return the job, or {@code null} when this installation never had it (FR-045). */
    public synchronized ProcessingJob load(String jobId) {
        File file = new File(jobFolder(jobId), JOB_FILE);
        if (!file.isFile()) {
            return null;
        }
        try {
            return fromNode(mapper.readTree(Files.readAllBytes(file.toPath())));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Job record at {} could not be read", file.getAbsolutePath(), e);
            return null;
        }
    }

    /** Every job this installation ever ran, newest first. */
    public synchronized List<ProcessingJob> loadAll() {
        List<ProcessingJob> jobs = new ArrayList<>();
        File[] folders = jobsRoot.listFiles(File::isDirectory);
        if (folders == null) {
            return jobs;
        }
        for (File folder : folders) {
            ProcessingJob job = load(folder.getName());
            if (job != null) {
                jobs.add(job);
            }
        }
        jobs.sort(Comparator.comparing(ProcessingJob::getAcceptedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return jobs;
    }

    /** Jobs left marked as running by a previous server process, for reconciliation (FR-024). */
    public synchronized List<ProcessingJob> loadRunning() {
        List<ProcessingJob> running = new ArrayList<>();
        for (ProcessingJob job : loadAll()) {
            if (job.getState() == State.RUNNING || job.getState() == State.ACCEPTED) {
                running.add(job);
            }
        }
        return running;
    }

    private ObjectNode toNode(ProcessingJob job) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jobId", job.getJobId());
        node.put("state", job.getState().name());
        node.put("requestedBy", job.getRequestedBy());
        putInstant(node, "acceptedAt", job.getAcceptedAt());
        putInstant(node, "startedAt", job.getStartedAt());
        putInstant(node, "endedAt", job.getEndedAt());
        node.put("pid", job.getPid());
        putInstant(node, "processStart", job.getProcessStart());
        node.put("cancelledBy", job.getCancelledBy());
        node.put("logPath", job.getLogPath());
        node.put("diskWarning", job.getDiskWarning());

        ProcessingRequest request = job.getRequest();
        ObjectNode requestNode = node.putObject("request");
        requestNode.put("sourcePath", request.getSourcePath());
        requestNode.put("destinationPath", request.getDestinationPath());
        requestNode.put("profile", request.getProfile());
        requestNode.put("displayName", request.getDisplayName());
        // The reference only. A resolved password is never written here, nor anywhere else that
        // persists (FR-015).
        requestNode.put("secretRef", request.getSecretRef());

        JobProgress progress = job.getProgress();
        if (progress != null) {
            ObjectNode progressNode = node.putObject("progress");
            progressNode.put("phase", progress.getPhase());
            progressNode.put("processedItems", progress.getProcessedItems());
            progressNode.put("discoveredItems", progress.getDiscoveredItems());
            // Absent stays absent: a percentage of zero where none is known would be a lie.
            if (progress.getPercent() != null) {
                progressNode.put("percent", progress.getPercent());
            }
            putInstant(progressNode, "estimatedCompletion", progress.getEstimatedCompletion());
            progressNode.put("measurable", progress.isMeasurable());
            progressNode.put("stalled", progress.isStalled());
            putInstant(progressNode, "lastObservedAt", progress.getLastObservedAt());
        }

        JobOutcome outcome = job.getOutcome();
        if (outcome != null) {
            ObjectNode outcomeNode = node.putObject("outcome");
            outcomeNode.put("casePath", outcome.getCasePath());
            outcomeNode.put("itemCount", outcome.getItemCount());
            outcomeNode.put("durationMillis", outcome.getDurationMillis());
            outcomeNode.put("cause", outcome.getCause() == null ? null : outcome.getCause().name());
            outcomeNode.put("causeDetail", outcome.getCauseDetail());
            outcomeNode.put("resumable", outcome.isResumable());
            outcomeNode.put("resumed", outcome.isResumed());
            outcomeNode.put("remainingAtDestination", outcome.getRemainingAtDestination());
            // Deliberately not persisted: the diagnostic excerpt is evidence-derived content and is
            // governed by the egress policy at the tool boundary. The full log is already on disk at
            // logPath, so keeping a second copy here would only widen where it lives.
            ArrayNode failed = outcomeNode.putArray("failedEvidences");
            for (String evidence : outcome.getFailedEvidences()) {
                failed.add(evidence);
            }
        }
        return node;
    }

    private ProcessingJob fromNode(JsonNode node) {
        JsonNode requestNode = node.path("request");
        ProcessingRequest request = new ProcessingRequest(text(requestNode, "sourcePath"),
                text(requestNode, "destinationPath"), text(requestNode, "profile"),
                text(requestNode, "displayName"), text(requestNode, "secretRef"));

        ProcessingJob job = new ProcessingJob(text(node, "jobId"), request, text(node, "requestedBy"),
                instant(node, "acceptedAt"));
        job.setState(State.valueOf(node.path("state").asText(State.ACCEPTED.name())));
        job.setStartedAt(instant(node, "startedAt"));
        job.setEndedAt(instant(node, "endedAt"));
        job.setPid(node.path("pid").asLong());
        job.setProcessStart(instant(node, "processStart"));
        job.setCancelledBy(text(node, "cancelledBy"));
        job.setLogPath(text(node, "logPath"));
        job.setDiskWarning(text(node, "diskWarning"));

        JsonNode progressNode = node.path("progress");
        if (progressNode.isObject()) {
            JobProgress progress = new JobProgress();
            progress.setPhase(text(progressNode, "phase"));
            progress.setProcessedItems(progressNode.path("processedItems").asLong());
            progress.setDiscoveredItems(progressNode.path("discoveredItems").asLong());
            progress.setPercent(progressNode.hasNonNull("percent") ? progressNode.get("percent").asInt() : null);
            progress.setEstimatedCompletion(instant(progressNode, "estimatedCompletion"));
            progress.setMeasurable(progressNode.path("measurable").asBoolean());
            progress.setStalled(progressNode.path("stalled").asBoolean());
            progress.setLastObservedAt(instant(progressNode, "lastObservedAt"));
            job.setProgress(progress);
        }

        JsonNode outcomeNode = node.path("outcome");
        if (outcomeNode.isObject()) {
            JobOutcome outcome = new JobOutcome();
            outcome.setCasePath(text(outcomeNode, "casePath"));
            outcome.setItemCount(outcomeNode.path("itemCount").asLong());
            outcome.setDurationMillis(outcomeNode.path("durationMillis").asLong());
            String cause = text(outcomeNode, "cause");
            outcome.setCause(cause == null ? null : FailureCause.valueOf(cause));
            outcome.setCauseDetail(text(outcomeNode, "causeDetail"));
            outcome.setResumable(outcomeNode.path("resumable").asBoolean());
            outcome.setResumed(outcomeNode.path("resumed").asBoolean());
            outcome.setRemainingAtDestination(text(outcomeNode, "remainingAtDestination"));
            for (JsonNode evidence : outcomeNode.path("failedEvidences")) {
                outcome.addFailedEvidence(evidence.asText());
            }
            job.setOutcome(outcome);
        }
        return job;
    }

    private static void putInstant(ObjectNode node, String field, Instant value) {
        node.put(field, value == null ? null : value.toString());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }

    /** The jobs area, for diagnostics. */
    public File getJobsRoot() {
        return jobsRoot;
    }

    /** Every job id this installation knows, for listing. */
    public synchronized List<String> knownJobIds() {
        File[] folders = jobsRoot.listFiles(File::isDirectory);
        if (folders == null) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (File folder : folders) {
            ids.add(folder.getName());
        }
        return ids;
    }
}
