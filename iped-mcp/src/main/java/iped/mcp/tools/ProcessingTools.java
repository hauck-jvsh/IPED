package iped.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import iped.mcp.processing.JobOutcome;
import iped.mcp.processing.JobProgress;
import iped.mcp.processing.JobRunner;
import iped.mcp.processing.ProcessingJob;
import iped.mcp.processing.ProcessingRequest;
import iped.mcp.processing.ProgressReader;
import iped.mcp.processing.SecretResolver;
import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.session.Session;

/**
 * The four tools that create a case and follow it (FR-018, FR-022, FR-023, FR-030).
 *
 * <p>
 * Every one of them is marked {@link ToolDescriptor#processingOperation()}, so with
 * {@code processingEnabled = false} they are absent from {@code tools/list} and a forced call is
 * refused before any argument is read.
 */
public class ProcessingTools {

    private final Session session;
    private final JobRunner runner;

    public ProcessingTools(Session session, JobRunner runner) {
        this.session = session;
        this.runner = runner;
    }

    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> tools = new ArrayList<>();

        tools.add(new ToolDescriptor("iped_process_evidence",
                "Creates a new case by processing an evidence source with the IPED engine. Returns a job_id "
                        + "immediately and does not wait: processing takes minutes to days. Follow it with "
                        + "iped_job_status. Both paths are interpreted on the machine running the server, "
                        + "which is frequently not the machine running this conversation — do not check them "
                        + "against your own filesystem, pass them and read the answer.",
                this::processEvidence)
                        .required("source_path", "string",
                                "Evidence to process, as a path on the server. A forensic image or a folder.")
                        .required("destination_path", "string",
                                "Where the case is created, as a path on the server. Must be empty or absent.")
                        .required("profile", "string",
                                "Processing profile. Only profiles this installation declares are accepted; "
                                        + "the refusal lists them.")
                        .optional("display_name", "string",
                                "Name for the evidence inside the case. Omitted, the engine uses the file name.")
                        .optional("secret_ref", "string",
                                "Name of a container password this server resolves locally. Never the password "
                                        + "itself.")
                        .processingOperation());

        tools.add(new ToolDescriptor("iped_job_status",
                "Progress or outcome of a processing job. Works from any session, including one opened after "
                        + "the job started and after the server was restarted.",
                this::jobStatus).required("job_id", "string", "Identifier returned by iped_process_evidence.")
                        .processingOperation()
                        // The failure excerpt carries item names and paths taken from the evidence,
                        // so it crosses the same egress boundary as item text rather than a private
                        // path of its own (FR-043).
                        .returnsContent("text"));

        tools.add(new ToolDescriptor("iped_cancel_job",
                "Ends a running processing job. Any authorized session may cancel any job, including one it "
                        + "did not start; who asked is recorded.",
                this::cancelJob).required("job_id", "string", "Identifier of the job to cancel.")
                        .processingOperation());

        tools.add(new ToolDescriptor("iped_resume_job",
                "Continues a job that was interrupted, reusing what was already processed instead of starting "
                        + "over. Keeps the same job_id.",
                this::resumeJob).required("job_id", "string", "Identifier of the interrupted job.")
                        .processingOperation());

        return tools;
    }

    private Object processEvidence(JsonNode arguments) {
        rejectUnknownArguments(arguments, "source_path", "destination_path", "profile", "display_name",
                "secret_ref");

        ProcessingRequest request = new ProcessingRequest(Args.requiredString(arguments, "source_path", "a path to the evidence on the server"),
                Args.requiredString(arguments, "destination_path", "an empty or absent folder on the server"), Args.requiredString(arguments, "profile", "one of the profiles this installation declares"),
                Args.optionalString(arguments, "display_name", null), Args.optionalString(arguments, "secret_ref", null));

        ProcessingJob job = runner.start(request, session.getOperator().describe());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job_id", job.getJobId());
        result.put("state", job.getState().name());
        result.put("case_path", request.getDestinationPath());
        // Said plainly because the failure it prevents is silent: an agent that reads a Windows path,
        // notices it is on Linux and concludes the evidence is missing never calls the tool that
        // would have worked, and produces no error while doing it.
        result.put("paths_are_server_side", true);
        result.put("log_path", job.getLogPath());
        if (job.getDiskWarning() != null) {
            result.put("disk_warning", job.getDiskWarning());
        }
        if (request.hasSecretRef()) {
            // A known and accepted limitation, declared at the moment it applies rather than left
            // tacit (FR-050).
            result.put("secret_exposure_notice", SecretResolver.EXPOSURE_NOTICE);
        }
        return result;
    }

    private Object jobStatus(JsonNode arguments) {
        rejectUnknownArguments(arguments, "job_id");
        String jobId = Args.requiredString(arguments, "job_id", "a job identifier returned by iped_process_evidence");

        ProcessingJob live = runner.getActive();
        ProcessingJob job = live != null && live.getJobId().equals(jobId) ? live : runner.getStore().load(jobId);
        if (job == null) {
            throw new McpError(McpError.UNKNOWN_JOB, "This installation has no job with id " + jobId + ".",
                    "Job records are never discarded here, so this id never existed in this installation — "
                            + "it is not a record that expired.").with("job_id", jobId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job_id", job.getJobId());
        result.put("state", job.getState().name());
        result.put("requested_by", job.getRequestedBy());
        result.put("log_path", job.getLogPath());
        result.put("progress", describeProgress(job.getProgress()));
        if (job.getOutcome() != null) {
            result.put("outcome", describeOutcome(job.getOutcome(), job.getLogPath()));
        }
        if (job.getCancelledBy() != null) {
            result.put("cancelled_by", job.getCancelledBy());
        }
        return result;
    }

    private Object cancelJob(JsonNode arguments) {
        rejectUnknownArguments(arguments, "job_id");
        ProcessingJob job = runner.cancel(Args.requiredString(arguments, "job_id", "a job identifier returned by iped_process_evidence"),
                session.getOperator().describe());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job_id", job.getJobId());
        result.put("state", job.getState().name());
        result.put("cancelled_by", job.getCancelledBy());
        if (job.getOutcome() != null) {
            result.put("remaining_at_destination", job.getOutcome().getRemainingAtDestination());
        }
        return result;
    }

    private Object resumeJob(JsonNode arguments) {
        rejectUnknownArguments(arguments, "job_id");
        ProcessingJob job = runner.resume(Args.requiredString(arguments, "job_id", "a job identifier returned by iped_process_evidence"),
                session.getOperator().describe());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job_id", job.getJobId());
        result.put("state", job.getState().name());
        result.put("log_path", job.getLogPath());
        result.put("paths_are_server_side", true);
        return result;
    }

    private static Map<String, Object> describeProgress(JobProgress progress) {
        Map<String, Object> described = new LinkedHashMap<>();
        if (progress == null) {
            described.put("measurable", false);
            return described;
        }
        described.put("phase", progress.getPhase());
        described.put("processed_items", progress.getProcessedItems());
        described.put("discovered_items", progress.getDiscoveredItems());
        // Absent stays absent. A percentage of zero where none is known reads as "no progress" and
        // would be the easiest way to lie here.
        if (progress.getPercent() != null) {
            described.put("percent", progress.getPercent());
        }
        if (progress.getEstimatedCompletion() != null) {
            described.put("estimated_completion", progress.getEstimatedCompletion().toString());
        }
        described.put("measurable", progress.isMeasurable());
        described.put("stalled", progress.isStalled());
        if (progress.isStalled()) {
            // Never reported without the phase: minutes of silence during an index commit are
            // normal, and the same minutes during item processing are not.
            described.put("stalled_note", "No new output for longer than this installation's threshold. "
                    + "Read it together with the phase: some phases are legitimately quiet for minutes.");
        }
        if (progress.getLastObservedAt() != null) {
            described.put("last_observed_at", progress.getLastObservedAt().toString());
        }
        return described;
    }

    private static Map<String, Object> describeOutcome(JobOutcome outcome, String logPath) {
        Map<String, Object> described = new LinkedHashMap<>();
        if (outcome.getCasePath() != null) {
            described.put("case_path", outcome.getCasePath());
            described.put("item_count", outcome.getItemCount());
        }
        described.put("duration_millis", outcome.getDurationMillis());
        if (outcome.getCause() != null) {
            described.put("cause", outcome.getCause().name());
            described.put("cause_detail", outcome.getCauseDetail());
            described.put("resumable", outcome.isResumable());
        }
        // Derived from the log rather than carried in the record, so a session that did not run
        // the job — or one opened after a restart — gets the same explanation the original one did
        // (FR-022 + FR-043). Evidence-derived content either way, governed by the egress policy at
        // the tool boundary.
        String excerpt = outcome.getDiagnosticExcerpt() != null ? outcome.getDiagnosticExcerpt()
                : ProgressReader.excerptFromLog(logPath == null ? null : new java.io.File(logPath),
                        ProgressReader.excerptLines());
        if (excerpt != null) {
            described.put("diagnostic_excerpt", excerpt);
        }
        if (outcome.getRemainingAtDestination() != null) {
            described.put("remaining_at_destination", outcome.getRemainingAtDestination());
        }
        if (!outcome.getFailedEvidences().isEmpty()) {
            described.put("failed_evidences", outcome.getFailedEvidences());
        }
        if (outcome.isResumed()) {
            described.put("resumed", true);
        }
        return described;
    }

    /**
     * Refuses an argument the tool does not declare, rather than ignoring it (FR-016).
     *
     * <p>
     * Without this, engine options would arrive through the back door — {@code -X} and the rest —
     * and the confinement rules would be bypassable by parameter rather than by path.
     */
    private static void rejectUnknownArguments(JsonNode arguments, String... declared) {
        List<String> known = java.util.Arrays.asList(declared);
        List<String> unknown = new ArrayList<>();
        arguments.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new McpError(McpError.INVALID_ARGUMENT,
                    "This tool does not accept " + String.join(", ", unknown) + ".",
                    "Only the declared parameters are honoured: " + String.join(", ", known) + ". Processing "
                            + "options are a matter of installation configuration, not of this request.")
                                    .with("unknown", unknown).with("accepted", known);
        }
    }
}
