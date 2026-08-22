package iped.mcp.processing;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.core.EvidenceStatus;
import iped.engine.data.IPEDSource;
import iped.mcp.config.McpServerConfig;
import iped.mcp.processing.CaseRootConfinement.ResolvedCaseRoot;
import iped.mcp.processing.ProcessingJob.FailureCause;
import iped.mcp.processing.ProcessingJob.State;
import iped.mcp.processing.SourceConfinement.ResolvedSource;
import iped.mcp.protocol.McpError;

/**
 * Runs the engine, out of this process, and owns the life of the one job at a time (FR-019).
 *
 * <h2>Out of process is forced, and turns out right</h2>
 *
 * <p>
 * See {@code package-info}: {@code iped-app} depends on {@code iped-mcp}, so calling
 * {@code Bootstrap} directly is a circular dependency the build refuses. Executing the release's
 * {@code iped.jar} instead hands over a process handle to cancel with, an exit code to derive the
 * outcome from, and memory isolation that keeps this server responsive while the engine takes the
 * machine.
 *
 * <h2>Three things the engine's own launcher imposes</h2>
 *
 * <ul>
 * <li><b>No heap arguments.</b> {@code Bootstrap} throws when the JVM that launched it carries
 * {@code -Xms} or {@code -Xmx}; it sizes the engine's heap itself.</li>
 *
 * <li><b>System properties propagate.</b> {@code Bootstrap} copies every property of its own JVM
 * onto the grandchild, so pinning {@code -Diped-locale} here reaches the process that actually
 * reports progress. Without that the phase would be read in whatever language the machine happens to
 * use.</li>
 *
 * <li><b>Cancelling must destroy the tree.</b> {@code Bootstrap} spawns a second JVM and the
 * grandchild is what reads evidence — and its shutdown hook has {@code process.destroy()} commented
 * out, relying on the grandchild noticing its parent died. Killing only the child would leave the
 * engine reading evidence, writing to the destination, at the mercy of a cooperative watchdog, after
 * this server has already declared the job over.</li>
 * </ul>
 */
public final class JobRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobRunner.class);

    /** How long a cancelled tree gets to end politely before it is forced. */
    private static final long GRACEFUL_STOP_SECONDS = 20;

    private final McpServerConfig config;
    private final JobStore store;
    private final File ipedRoot;
    private final ProfileRegistry profiles;
    private final SecretResolver secrets;
    private final DiskPreflight preflight;

    private final AtomicReference<ProcessingJob> active = new AtomicReference<>();
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    public JobRunner(McpServerConfig config, File ipedRoot, JobStore store) {
        this.config = config;
        this.ipedRoot = ipedRoot;
        this.store = store;
        this.profiles = new ProfileRegistry(config.getProcessingProfiles());
        this.secrets = new SecretResolver(config.getProcessingSecretsFile());
        this.preflight = new DiskPreflight(config.getProcessingMinFreeSpacePercentOfSource());
    }

    public JobStore getStore() {
        return store;
    }

    /** The running job, or {@code null}. */
    public ProcessingJob getActive() {
        ProcessingJob job = active.get();
        return job != null && !job.getState().isTerminal() ? job : null;
    }

    /**
     * Validates the whole request and starts the engine (FR-017, FR-018).
     *
     * <p>
     * Everything knowable beforehand is checked before a process exists: configuration, paths,
     * profile, destination. Disk space is <b>not</b> among them — it warns and never refuses
     * (FR-044), so the promise to fail early is scoped to what can actually be decided.
     *
     * <p>
     * The audit record that must precede any read (FR-032) is already written by
     * {@code McpDispatcher.callTool} before the handler runs, which is the invariant the module has
     * had since 001. This method is inside that handler, so the ordering holds without a second
     * mechanism.
     */
    public synchronized ProcessingJob start(ProcessingRequest request, String requestedBy) {
        requireNoActiveJob();
        Validated validated = validate(request);

        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ProcessingJob job = new ProcessingJob(jobId, request, requestedBy, Instant.now());
        job.setLogPath(store.logFile(jobId).getAbsolutePath());

        DiskPreflight.Assessment assessment = preflight.assess(validated.source.toFile(),
                new File(request.getDestinationPath()));
        if (assessment.hasWarning()) {
            job.setDiskWarning(assessment.getWarning());
        }

        launch(job, validated, false);
        return job;
    }

    /**
     * Continues a job that did not finish, keeping the same identifier (FR-030).
     *
     * <p>
     * The identifier is kept on purpose: two ids for the same evidence would split the history the
     * trail has to reconstitute.
     */
    public synchronized ProcessingJob resume(String jobId, String requestedBy) {
        requireNoActiveJob();
        ProcessingJob job = store.load(jobId);
        if (job == null) {
            throw unknownJob(jobId);
        }
        boolean resumable = job.getState() == State.INTERRUPTED
                || (job.getState() == State.FAILED && job.getOutcome() != null && job.getOutcome().isResumable());
        if (!resumable) {
            throw new McpError(McpError.JOB_NOT_RESUMABLE,
                    "Job " + jobId + " is " + job.getState() + " and cannot be resumed.",
                    "Only an interrupted job, or a failed one whose outcome says it is resumable, can be "
                            + "continued. Start a new job instead.").with("job_id", jobId)
                                    .with("state", job.getState().name());
        }
        Validated validated = validate(job.getRequest());
        job.setState(State.ACCEPTED);
        job.setEndedAt(null);
        launch(job, validated, true);
        return job;
    }

    /**
     * Ends a running job (FR-023).
     *
     * <p>
     * Any authorized session may cancel any job, whether or not it started it. Tying this to
     * ownership would build authority on an identity the trail itself declares unverified; the
     * defence is the record of who asked, which is what {@code cancelledBy} carries.
     */
    public synchronized ProcessingJob cancel(String jobId, String requestedBy) {
        ProcessingJob job = active.get();
        if (job == null || !job.getJobId().equals(jobId) || job.getState().isTerminal()) {
            ProcessingJob stored = store.load(jobId);
            if (stored == null) {
                throw unknownJob(jobId);
            }
            throw new McpError(McpError.JOB_NOT_RESUMABLE,
                    "Job " + jobId + " is not running, so there is nothing to cancel.",
                    "Its state is " + stored.getState() + ". Ask for its status instead.").with("job_id", jobId)
                            .with("state", stored.getState().name());
        }

        job.setCancelledBy(requestedBy);
        Process process = activeProcess.get();
        if (process != null) {
            destroyTree(process);
        }
        job.setState(State.CANCELLED);
        job.setEndedAt(Instant.now());
        JobOutcome outcome = new JobOutcome();
        outcome.setRemainingAtDestination(describeRemains(job));
        outcome.setDurationMillis(elapsed(job));
        job.setOutcome(outcome);
        persist(job);
        return job;
    }

    /**
     * Destroys the whole process tree, descendants first.
     *
     * <p>
     * Descendants before the child on purpose: killing the child first orphans the grandchild, and
     * the grandchild is the one reading evidence. {@code Bootstrap} does not kill it — its shutdown
     * hook has the call commented out — so this cannot be delegated.
     */
    static void destroyTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>();
        process.toHandle().descendants().forEach(descendants::add);
        for (ProcessHandle handle : descendants) {
            handle.destroy();
        }
        process.destroy();
        try {
            if (!process.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A descendant can outlive the child it was spawned from, so they are checked again rather
        // than assumed gone once the parent has exited.
        for (ProcessHandle handle : descendants) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
    }

    private void launch(ProcessingJob job, Validated validated, boolean resuming) {
        List<String> command = buildCommand(job.getRequest(), validated, resuming);
        File logFile = store.logFile(job.getJobId());
        try {
            java.nio.file.Files.createDirectories(logFile.getParentFile().toPath());
            persist(job);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(ipedRoot);
            // Merged so the engine's stderr lands in the same log and the same progress reader. Two
            // pipes would need two pumps and could interleave a failure away from its context.
            builder.redirectErrorStream(true);
            Process process = builder.start();

            job.setPid(process.pid());
            job.setProcessStart(process.info().startInstant().orElse(Instant.now()));
            job.setStartedAt(Instant.now());
            job.setState(State.RUNNING);
            active.set(job);
            activeProcess.set(process);
            persist(job);

            ProgressReader reader = new ProgressReader(job, logFile, Charset.defaultCharset(),
                    config.getProcessingStallThresholdSeconds());
            reader.startPumping(process.getInputStream());
            watch(job, process, reader, resuming);
        } catch (IOException e) {
            job.setState(State.FAILED);
            job.setEndedAt(Instant.now());
            JobOutcome outcome = new JobOutcome();
            outcome.setCause(FailureCause.ENGINE_FAILURE);
            outcome.setCauseDetail("The processing engine could not be started: " + e.getMessage());
            outcome.setResumable(false);
            job.setOutcome(outcome);
            persist(job);
            throw new McpError(McpError.INTERNAL_ERROR, "The processing engine could not be started.",
                    "Check that " + new File(ipedRoot, "iped.jar").getAbsolutePath() + " exists and that the "
                            + "configured JVM can run it.", e);
        }
    }

    /** The command line, in the order {@code Bootstrap} expects. */
    List<String> buildCommand(ProcessingRequest request, Validated validated, boolean resuming) {
        List<String> command = new ArrayList<>();
        command.add(resolveJvm());
        // Declared, never inherited: progress reports its phase only as localized prose, so the
        // locale is what makes the phase readable at all. Bootstrap copies this onto the grandchild.
        command.add("-Diped-locale=" + config.getProcessingLocale());
        // Pinned so the encoding this server decodes with and the one the engine writes with cannot
        // drift apart — the value is the one the child would have chosen anyway, declared rather
        // than inherited. Forcing UTF-8 here was rejected: file.encoding also governs the engine's
        // own file I/O, and changing how it writes exports to suit a log parser is the wrong trade.
        command.add("-Dfile.encoding=" + Charset.defaultCharset().name());
        // No -Xms/-Xmx: Bootstrap refuses to start when its own JVM carries them, and it sizes the
        // engine's heap itself.
        command.add("-jar");
        command.add(new File(ipedRoot, "iped.jar").getAbsolutePath());
        command.add("-d");
        command.add(validated.source.toString());
        if (request.getDisplayName() != null && !request.getDisplayName().trim().isEmpty()) {
            command.add("-dname");
            command.add(request.getDisplayName().trim());
        }
        if (request.hasSecretRef()) {
            String password = secrets.resolve(request.getSecretRef());
            if (password == null) {
                throw new McpError(McpError.SECRET_UNRESOLVED,
                        "The secret reference '" + request.getSecretRef() + "' could not be resolved.",
                        "Check that it is named in " + secrets.describeSource() + ".").with("secret_ref",
                                request.getSecretRef());
            }
            // The password lands in argv here. That is the accepted, declared exposure of FR-050,
            // announced in the accept — not an oversight.
            command.add("-p");
            command.add(password);
        }
        command.add("-o");
        command.add(validated.destination.toString());
        command.add("-profile");
        command.add(request.getProfile());
        command.add("--nogui");
        // Sends the engine log to standard output, which is the pipe this server owns. It never
        // reaches this server's own standard output, where it would corrupt the protocol.
        command.add("--nologfile");
        if (resuming) {
            command.add("--continue");
        }
        return command;
    }

    private String resolveJvm() {
        String configured = config.getProcessingJvm();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String executable = SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java";
        File embedded = new File(new File(new File(ipedRoot, "jre"), "bin"), executable);
        return embedded.isFile() ? embedded.getAbsolutePath() : executable;
    }

    private void watch(ProcessingJob job, Process process, ProgressReader reader, boolean resuming) {
        Thread watcher = new Thread(() -> {
            int exit = -1;
            try {
                exit = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reader.stop();
            if (job.getState() == State.CANCELLED) {
                return;
            }
            finish(job, exit, reader, resuming);
        }, "iped-mcp-job-" + job.getJobId());
        watcher.setDaemon(true);
        watcher.start();
    }

    private void finish(ProcessingJob job, int exit, ProgressReader reader, boolean resuming) {
        JobOutcome outcome = new JobOutcome();
        outcome.setDurationMillis(elapsed(job));
        outcome.setResumed(resuming);
        File destination = new File(job.getRequest().getDestinationPath());

        if (exit == 0) {
            Long counted = readCaseItemCount(destination);
            if (counted == null) {
                // The engine said success and the case will not open. Reporting COMPLETED here would
                // hand the examiner an outcome whose item count came from a progress line rather
                // than from the case, which is exactly the mismatch SC-008 exists to catch.
                job.setState(State.FAILED);
                outcome.setCause(FailureCause.ENGINE_FAILURE);
                outcome.setCauseDetail("The engine reported success but the case at "
                        + destination.getAbsolutePath() + " could not be opened afterwards.");
                outcome.setResumable(true);
                outcome.setDiagnosticExcerpt(reader.diagnosticExcerpt());
                outcome.setRemainingAtDestination(describeRemains(job));
            } else {
                job.setState(State.COMPLETED);
                outcome.setCasePath(destination.getAbsolutePath());
                // Read from the case, not from the progress counter: the engine's own totals count
                // things the case does not report the same way (319641 indexed against 267186 active
                // on the reference image), and SC-008 requires this number to be the one a later
                // open returns.
                //
                // Zero is a completion, never a failure (FR-048): empty or unsupported evidence is a
                // legitimate result, and reporting it as failure sends the examiner hunting a defect
                // where there is a finding.
                outcome.setItemCount(counted);
                collectFailedEvidences(destination, outcome);
            }
        } else {
            job.setState(State.FAILED);
            FailureCause cause = classify(job);
            outcome.setCause(cause);
            outcome.setCauseDetail(reader.lastMeaningfulLine());
            // An environment problem is worth retrying once it is fixed; damaged evidence is not.
            outcome.setResumable(cause == FailureCause.SOURCE_INACCESSIBLE);
            outcome.setDiagnosticExcerpt(reader.diagnosticExcerpt());
            outcome.setRemainingAtDestination(describeRemains(job));
        }
        job.setEndedAt(Instant.now());
        job.setOutcome(outcome);
        persist(job);
    }

    /**
     * Tells an environment problem from a damaged evidence (FR-049).
     *
     * <p>
     * Checked after the fact rather than guessed from the log: if the source is no longer reachable
     * now, the medium went away — removable disk unplugged, share dropped, permission lost. If it is
     * still there, the engine could not read what is there, which is a different problem and changes
     * what the examiner does next.
     */
    private FailureCause classify(ProcessingJob job) {
        File source = new File(job.getRequest().getSourcePath());
        if (!source.exists() || !source.canRead()) {
            return FailureCause.SOURCE_INACCESSIBLE;
        }
        return FailureCause.SOURCE_UNREADABLE;
    }

    /**
     * The item count the produced case itself reports, or {@code null} when it will not open.
     *
     * <p>
     * Deliberately the same call the case-opening tool uses, so the number in the outcome is by
     * construction the number a later open returns (SC-008) rather than a second count that happens
     * to agree. The engine's own progress totals are not the same quantity — on the reference image
     * it reported 319641 indexed and 267186 active, and neither is what the case answers with.
     */
    private Long readCaseItemCount(File caseDir) {
        try (IPEDSource source = new IPEDSource(caseDir, null, false)) {
            return (long) source.getTotalItems();
        } catch (Exception e) {
            LOGGER.error("The case at {} was produced but could not be opened to count its items",
                    caseDir.getAbsolutePath(), e);
            return null;
        }
    }

    private void collectFailedEvidences(File caseDir, JobOutcome outcome) {
        try {
            List<String> failed = new EvidenceStatus(caseDir).getFailedEvidences();
            // null means "never processed" and an empty list means "processed with no failures".
            // The engine keeps that distinction and it is preserved rather than flattened.
            if (failed != null) {
                failed.forEach(outcome::addFailedEvidence);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Evidence status at {} could not be read", caseDir, e);
        }
    }

    private String describeRemains(ProcessingJob job) {
        File destination = new File(job.getRequest().getDestinationPath());
        if (!destination.exists()) {
            return "nothing was left at the destination";
        }
        String[] entries = destination.list();
        int count = entries == null ? 0 : entries.length;
        return "a partial case folder with " + count + " entries at " + destination.getAbsolutePath()
                + "; it is not a usable case and will be refused if opened";
    }

    private long elapsed(ProcessingJob job) {
        Instant from = job.getStartedAt() == null ? job.getAcceptedAt() : job.getStartedAt();
        return from == null ? 0 : Duration.between(from, Instant.now()).toMillis();
    }

    private void persist(ProcessingJob job) {
        try {
            store.save(job);
        } catch (IOException e) {
            LOGGER.error("Job {} could not be persisted; reconciliation after a restart will not see it",
                    job.getJobId(), e);
        }
    }

    private void requireNoActiveJob() {
        ProcessingJob running = getActive();
        if (running != null) {
            throw new McpError(McpError.JOB_ALREADY_RUNNING,
                    "Job " + running.getJobId() + " is already running, and this server runs one at a time.",
                    "Wait for it to finish, or cancel it. Two processings at once finish later than the same "
                            + "two in sequence, because the bottleneck is the machine.").with("job_id",
                                    running.getJobId()).with("state", running.getState().name());
        }
    }

    private static McpError unknownJob(String jobId) {
        // Retention is indefinite, so "unknown" means exactly "never existed here" (FR-045).
        return new McpError(McpError.UNKNOWN_JOB, "This installation has no job with id " + jobId + ".",
                "Job records are never discarded, so this id never existed here. Check the id, or ask for a "
                        + "new processing.").with("job_id", jobId);
    }

    /** A request that passed every check, with both paths already resolved. */
    static final class Validated {
        final java.nio.file.Path source;
        final java.nio.file.Path destination;

        Validated(java.nio.file.Path source, java.nio.file.Path destination) {
            this.source = source;
            this.destination = destination;
        }
    }

    Validated validate(ProcessingRequest request) {
        if (!profiles.isPermitted(request.getProfile())) {
            throw new McpError(McpError.PROFILE_NOT_PERMITTED,
                    "The profile '" + request.getProfile() + "' is not permitted in this installation.",
                    "Use one of: " + String.join(", ", profiles.getPermitted()) + ".")
                            .with("requested", request.getProfile()).with("permitted", profiles.getPermitted());
        }

        ResolvedSource source = SourceConfinement.resolve(request.getSourcePath(), config.getProcessingSourceAreas());
        if (!source.isAllowed()) {
            throw sourceRefusal(source);
        }

        ResolvedCaseRoot destination = CaseRootConfinement.resolve(request.getDestinationPath(),
                config.getProcessingCaseRoots());
        if (!destination.isAllowed()) {
            throw destinationRefusal(destination);
        }
        return new Validated(source.getResolved(), destination.getResolved());
    }

    private static McpError sourceRefusal(ResolvedSource source) {
        switch (source.getVerdict()) {
            case NO_AREAS_DECLARED:
                return new McpError(McpError.PROCESSING_MISCONFIGURED,
                        "Processing is enabled but processingSourceAreas is empty.",
                        "An examiner declares the folders evidence may be read from. An empty list is a "
                                + "configuration error, not permission to read anywhere.");
            case AREA_UNAVAILABLE:
                return new McpError(McpError.SOURCE_AREA_UNAVAILABLE,
                        "Every declared source area is absent right now.",
                        "This is a missing volume rather than a refused source: mount the media the areas "
                                + "point at, then retry. No restart is needed.").with("declared_areas",
                                        source.getDeclaredAreas());
            case UNRESOLVABLE:
                return new McpError(McpError.SOURCE_NOT_PERMITTED,
                        "The source " + source.getRequested() + " could not be resolved on the server.",
                        "The path is interpreted on the machine running the server, which may not be the one "
                                + "running this conversation. " + source.getReason() + ".").with("requested",
                                        source.getRequested());
            case OUTSIDE_AREAS:
            default:
                return new McpError(McpError.SOURCE_NOT_PERMITTED,
                        "Reading " + source.getRequested() + " is not permitted.",
                        "Evidence may only be read from the declared areas. Ask the examiner to declare the "
                                + "area, or move the evidence under one of them.").with("requested",
                                        source.getRequested()).with("permitted_areas", source.getDeclaredAreas());
        }
    }

    private static McpError destinationRefusal(ResolvedCaseRoot destination) {
        switch (destination.getVerdict()) {
            case NO_ROOTS_DECLARED:
                return new McpError(McpError.PROCESSING_MISCONFIGURED,
                        "Processing is enabled but processingCaseRoots is empty.",
                        "An examiner declares the folders cases may be created under. An empty list is a "
                                + "configuration error, not permission to write anywhere.");
            case ROOT_UNAVAILABLE:
                return new McpError(McpError.DESTINATION_NOT_PERMITTED,
                        "Every declared case root is absent right now.",
                        "Mount the volume the roots point at and retry.").with("declared_roots",
                                destination.getDeclaredRoots());
            case HAS_FINISHED_CASE:
                // Deliberately distinct from an occupied destination: this is a scope boundary, and
                // merging the two would make a decision read as a defect.
                return new McpError(McpError.APPEND_NOT_SUPPORTED,
                        "There is already a finished case at " + destination.getRequested() + ".",
                        "Adding evidence to a case that already exists is not supported by this server. "
                                + "Choose an empty destination to create a new case.").with("requested",
                                        destination.getRequested());
            case DESTINATION_OCCUPIED:
                return new McpError(McpError.DESTINATION_HAS_CASE,
                        "The destination " + destination.getRequested() + " is not empty.",
                        "A case is created in an empty folder. Choose one that does not exist yet, or an "
                                + "empty one.").with("requested", destination.getRequested());
            case UNRESOLVABLE:
                return new McpError(McpError.DESTINATION_NOT_PERMITTED,
                        "The destination " + destination.getRequested() + " could not be resolved.",
                        destination.getReason() == null ? "Use an absolute path on the server."
                                : destination.getReason() + ".").with("requested", destination.getRequested());
            case OUTSIDE_ROOTS:
            default:
                return new McpError(McpError.DESTINATION_NOT_PERMITTED,
                        "Creating a case at " + destination.getRequested() + " is not permitted.",
                        "Cases may only be created under the declared roots.").with("requested",
                                destination.getRequested()).with("permitted_roots", destination.getDeclaredRoots());
        }
    }
}
