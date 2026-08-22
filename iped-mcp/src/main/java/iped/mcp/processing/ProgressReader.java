package iped.mcp.processing;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the engine's own output stream and turns it into progress, a log file, and a diagnostic
 * excerpt (FR-020, FR-042, FR-043).
 *
 * <h2>Progress has two sources, and only one of them survives a locale change</h2>
 *
 * <p>
 * The engine emits two different kinds of line, and FR-020 asks for both:
 *
 * <table border="1">
 * <caption>The two sources</caption>
 * <tr>
 * <th>What</th>
 * <th>How it arrives</th>
 * <th>How it is read</th>
 * </tr>
 * <tr>
 * <td>Counters — processed/total, percent, rate</td>
 * <td>Numbers concatenated around localized prefixes</td>
 * <td>By <b>numeric shape</b>: {@code n/m}, {@code (p%)}, {@code GB/h} are stable in any locale</td>
 * </tr>
 * <tr>
 * <td><b>Current phase</b></td>
 * <td>Logged verbatim, pure localized prose</td>
 * <td>By the text itself, which only works because the child's locale is pinned</td>
 * </tr>
 * </table>
 *
 * <p>
 * So the pinned locale is not a secondary safeguard — it is what makes the phase readable at all. A
 * parser anchored only on numbers reads the counters perfectly and never names the phase, which is
 * half the requirement, failing silently. And the phase is exactly what answers "is it alive?"
 * during discovery, commit and optimization: those emit no numbers and can last minutes on a large
 * case.
 *
 * <h2>Nothing here reaches this process's standard output</h2>
 *
 * <p>
 * The stream is a pipe this server owns. Standard output here is the protocol channel, and one log
 * line written to it corrupts the session — the failure 006 diagnosed, whose symptom looks like a
 * protocol defect rather than a logging mistake.
 */
public final class ProgressReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProgressReader.class);

    /** {@code 1234/5678} — processed over discovered. Locale-independent. */
    private static final Pattern COUNTERS = Pattern.compile("(\\d[\\d.,]*)\\s*/\\s*(\\d[\\d.,]*)");
    /** {@code (42%)} — only meaningful once discovery has ended. */
    private static final Pattern PERCENT = Pattern.compile("\\((\\d{1,3})%\\)");

    /** How many trailing lines the failure excerpt carries. */
    private static final int EXCERPT_LINES = 40;

    private final ProcessingJob job;
    private final File logFile;
    private final Charset childCharset;
    private final long stallThresholdSeconds;
    private final Deque<String> tail = new ArrayDeque<>();
    private final JobProgress progress;

    private volatile boolean running = true;
    private volatile String lastMeaningfulLine;
    private Thread pump;

    /**
     * @param childCharset
     *            what the engine actually encodes its output in — see {@link #pumpUntilClosed}
     */
    public ProgressReader(ProcessingJob job, File logFile, Charset childCharset, int stallThresholdSeconds) {
        this.job = job;
        this.logFile = logFile;
        this.childCharset = childCharset;
        this.stallThresholdSeconds = stallThresholdSeconds;
        this.progress = job.getProgress() == null ? new JobProgress() : job.getProgress();
        job.setProgress(progress);
    }

    public JobProgress getProgress() {
        refreshStall();
        return progress;
    }

    /** Consumes the child's stream on a daemon thread until it closes. */
    public void startPumping(InputStream stream) {
        pump = new Thread(() -> pumpUntilClosed(stream), "iped-mcp-progress-" + job.getJobId());
        pump.setDaemon(true);
        pump.start();
    }

    public void stop() {
        running = false;
        if (pump != null) {
            try {
                pump.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Reads the child stream in the charset the child writes, and stores it in UTF-8.
     *
     * <p>
     * Measured, not assumed: on Windows the engine's console output comes out in the ANSI codepage —
     * "Evidências" arrives as the single byte {@code 0xEA}, which is CP1252, not UTF-8. Decoding it
     * as UTF-8 replaces every accented character with U+FFFD, and the engine's output is full of
     * item names taken from the evidence, so the damage lands exactly on the content that matters.
     *
     * <p>
     * The charset is passed in rather than picked up here, and the same value is pinned on the
     * child's command line, so the two sides cannot drift apart if the server itself was launched
     * with an override. Forcing the child to UTF-8 instead was rejected: {@code file.encoding}
     * governs the engine's own file I/O too, and changing how it writes exports to suit a log parser
     * is the wrong trade.
     */
    private void pumpUntilClosed(InputStream stream) {
        // In: what the engine emits. Out: UTF-8 always, because this file is ours.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, childCharset));
                BufferedWriter writer = Files.newBufferedWriter(logFile.toPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
                remember(line);
                interpret(line);
            }
        } catch (IOException e) {
            LOGGER.warn("The engine output stream for job {} ended abnormally", job.getJobId(), e);
        }
    }

    private synchronized void remember(String line) {
        tail.addLast(line);
        while (tail.size() > EXCERPT_LINES) {
            tail.removeFirst();
        }
        if (!line.trim().isEmpty()) {
            lastMeaningfulLine = line.trim();
        }
    }

    /**
     * Reads one line for whatever it carries.
     *
     * <p>
     * A line can carry counters, or a phase, or neither. It is never assumed to carry both: the
     * counter line and the phase line are different messages from different parts of the engine.
     */
    private void interpret(String line) {
        progress.setLastObservedAt(Instant.now());
        progress.setStalled(false);

        // Both readings work on the message field, never on the whole line: the timestamp and the
        // classpath entries carry digits and slashes that would otherwise be mistaken for counters.
        String message = phaseFrom(line);
        if (message == null) {
            return;
        }

        boolean sawNumbers = false;
        Matcher counters = COUNTERS.matcher(message);
        if (counters.find()) {
            long processed = parse(counters.group(1));
            long discovered = parse(counters.group(2));
            if (processed >= 0 && discovered >= 0) {
                progress.setProcessedItems(processed);
                progress.setDiscoveredItems(discovered);
                progress.setMeasurable(true);
                sawNumbers = true;
            }
        }

        Matcher percent = PERCENT.matcher(message);
        if (percent.find()) {
            int value = Integer.parseInt(percent.group(1));
            // Zero before discovery ends is the engine's placeholder, not a measurement — the real
            // output shows "(0%)" for minutes while discovery is still running. Absent is the honest
            // answer, and writing zero here would be the easiest way to lie.
            progress.setPercent(value > 0 ? Integer.valueOf(value) : null);
            sawNumbers = true;
        }

        if (!sawNumbers) {
            progress.setPhase(message);
            // A phase with no counters is a real state, not a gap: index commit and optimization
            // legitimately report nothing measurable for minutes.
            progress.setMeasurable(false);
            progress.setPercent(null);
        }
    }

    /**
     * The phase carried by a log line, or {@code null} when the line is not one.
     *
     * <p>
     * Read from the message text, which is only safe because the child's locale is pinned.
     *
     * <p>
     * The engine's layout is tab-separated and puts the message last, after timestamp, level and
     * logger:
     *
     * <pre>
     * 2026-08-22 18:46:57\t[MSG]\t[processing.ui.ProgressConsole]\t\t\tProcessing 165051/259026 (0%) 860GB/h
     * </pre>
     *
     * so the message is the final non-empty tab-separated field. A line with no tabs is taken whole,
     * which is what stack traces and native tool output look like.
     */
    static String phaseFrom(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] fields = trimmed.split("\t");
        String message = trimmed;
        for (int i = fields.length - 1; i >= 0; i--) {
            if (!fields[i].trim().isEmpty()) {
                message = fields[i].trim();
                break;
            }
        }
        // A phase is a short status line. Anything long is a stack frame or a native tool dumping,
        // and reporting it as "the current phase" would be worse than reporting nothing.
        if (message.isEmpty() || message.length() > 200) {
            return null;
        }
        return message;
    }

    private static long parse(String number) {
        try {
            return Long.parseLong(number.replace(".", "").replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Marks the job stalled when the stream has been silent past the threshold (FR-047).
     *
     * <p>
     * Only ever reported alongside the phase. The same silence means different things in different
     * phases: minutes of quiet during an index commit are normal, and the same minutes during item
     * processing are not — so a stall flag on its own would turn a legitimate quiet phase into a
     * false alarm.
     */
    private void refreshStall() {
        Instant last = progress.getLastObservedAt();
        if (last == null) {
            return;
        }
        long silent = Duration.between(last, Instant.now()).getSeconds();
        progress.setStalled(silent > stallThresholdSeconds);
    }

    /** The engine's last non-empty line, for the failure cause. */
    public String lastMeaningfulLine() {
        return lastMeaningfulLine;
    }

    /**
     * A bounded tail of the log, for a failure the examiner has to diagnose without reaching the
     * server machine (FR-043).
     *
     * <p>
     * Evidence-derived content: it carries item names and paths from the evidence, so it is declared
     * as such in the tool descriptor and governed by the egress policy at the same boundary as item
     * text. A private path for it would let evidence content out past the policy that governs every
     * other content-returning tool.
     */
    public synchronized String diagnosticExcerpt() {
        if (tail.isEmpty()) {
            return null;
        }
        return String.join(System.lineSeparator(), tail);
    }
}
