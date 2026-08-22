package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.processing.JobProgress;
import iped.mcp.processing.ProcessingJob;
import iped.mcp.processing.ProcessingRequest;
import iped.mcp.processing.ProgressReader;

/**
 * Progress is read from two different sources, and only one of them survives a locale change.
 *
 * <p>
 * Every sample below is a real line captured from the engine processing RockPi4.E01, not an invented
 * one. That matters because the first version of this parser was written against an assumed format —
 * it looked for {@code " - "} as the delimiter, which does not appear anywhere in the engine's
 * output — and it would have reported counters correctly while never naming a phase. Half of FR-020,
 * failing silently.
 */
public class ProgressReaderTest {

    /** Exactly as the engine emits them: tab-separated, message last. */
    private static final String BANNER = "2026-08-22 18:45:56\t[INFO]\t[app.processing.Main]\t\t\t"
            + "Indexador e Processador de Evidências Digitais 4.3.1";
    private static final String COUNTERS = "2026-08-22 18:46:57\t[MSG]\t[processing.ui.ProgressConsole]\t\t\t"
            + "Processing 165051/259026 (0%) 860GB/h";
    private static final String COUNTERS_WITH_PERCENT = "2026-08-22 18:47:10\t[MSG]\t"
            + "[processing.ui.ProgressConsole]\t\t\tProcessing 240000/259026 (42%) 700GB/h";
    private static final String PHASE_COMMIT = "2026-08-22 18:47:30\t[INFO]\t[engine.core.Manager]\t\t\t"
            + "Commit started";
    private static final String CLASSPATH = "2026-08-22 18:46:03\t[INFO]\t[engine.core.Statistics]\t\t\t"
            + "ClassPath: C:\\iped\\iped-mcp\\iped-4.3.1/iped.jar";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void countersAreReadFromTheirNumericShape() throws Exception {
        JobProgress progress = feed(StandardCharsets.UTF_8, BANNER, COUNTERS);

        assertEquals(165051, progress.getProcessedItems());
        assertEquals(259026, progress.getDiscoveredItems());
        assertTrue("a counter line is a measurable phase", progress.isMeasurable());
    }

    @Test
    public void zeroPercentIsAbsentRatherThanZero() throws Exception {
        // The engine prints "(0%)" for minutes while discovery is still running. Reporting zero
        // there reads as "no progress made", which is a different and false statement.
        JobProgress progress = feed(StandardCharsets.UTF_8, COUNTERS);
        assertNull("a percentage of zero before discovery ends must be absent", progress.getPercent());

        JobProgress later = feed(StandardCharsets.UTF_8, COUNTERS, COUNTERS_WITH_PERCENT);
        assertEquals(Integer.valueOf(42), later.getPercent());
    }

    @Test
    public void thePhaseIsReadFromALineThatCarriesNoNumbers() throws Exception {
        // The half a numeric parser cannot supply. It is also the only thing that answers "is it
        // alive?" during commit and optimization, which emit no counters and last minutes.
        JobProgress progress = feed(StandardCharsets.UTF_8, COUNTERS, PHASE_COMMIT);

        assertEquals("Commit started", progress.getPhase());
        assertFalse("a phase with no counters is not measurable", progress.isMeasurable());
        assertNull("percent must not survive into a phase that does not measure", progress.getPercent());
    }

    @Test
    public void countersSurviveALocaleChangeAndSoDoesNothingElse() throws Exception {
        // Same numbers, Portuguese wording — what a machine with a different locale would emit if
        // the child's locale were inherited instead of pinned.
        String portuguese = "2026-08-22 18:46:57\t[MSG]\t[processing.ui.ProgressConsole]\t\t\t"
                + "Processando 165051/259026 (0%) 860GB/h";
        JobProgress progress = feed(StandardCharsets.UTF_8, portuguese);

        assertEquals("counters are anchored on shape, so they survive", 165051, progress.getProcessedItems());
        assertEquals(259026, progress.getDiscoveredItems());
    }

    @Test
    public void aPathWithASlashIsNotMistakenForCounters() throws Exception {
        // "iped-4.3.1/iped.jar" has a digit, a slash and a letter. A looser pattern would read it
        // as 1 of nothing and report a collection size that never existed.
        JobProgress progress = feed(StandardCharsets.UTF_8, CLASSPATH);

        assertEquals(0, progress.getProcessedItems());
        assertEquals(0, progress.getDiscoveredItems());
    }

    @Test
    public void nonAsciiSurvivesWhenTheStreamIsDecodedAsTheEngineWroteIt() throws Exception {
        // Measured, not assumed: on Windows the engine writes CP1252, so "Evidências" arrives as the
        // single byte 0xEA. Decoding as UTF-8 would replace it with U+FFFD — and the engine's output
        // is full of item names taken from the evidence, so the damage lands on the content that
        // matters, not on a banner.
        Charset cp1252 = Charset.forName("windows-1252");
        JobProgress progress = feed(cp1252, BANNER);

        assertNotNull(progress.getPhase());
        assertTrue("the accented character must survive decoding: " + progress.getPhase(),
                progress.getPhase().contains("Evidências"));
        assertFalse("no replacement character may appear", progress.getPhase().contains("\uFFFD"));
    }

    @Test
    public void theLogIsWrittenAsUtf8WhateverTheEngineWrote() throws Exception {
        File logFile = new File(temp.getRoot(), "processing.log");
        feed(Charset.forName("windows-1252"), logFile, BANNER);

        // Ours to choose, and chosen explicitly: the file we keep is UTF-8 regardless of what the
        // engine emitted.
        String written = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("the stored log must be UTF-8: " + written, written.contains("Evidências"));
    }

    @Test
    public void silenceBeyondTheThresholdIsReportedAsStalled() throws Exception {
        ProcessingJob job = newJob();
        File logFile = new File(temp.getRoot(), "stall.log");
        // Zero-second threshold: any silence counts, which is what makes this deterministic instead
        // of a test that waits.
        ProgressReader reader = new ProgressReader(job, logFile, StandardCharsets.UTF_8, 0);
        // A phase line before the counters, because that is the real sequence: the engine announces
        // itself and its configuration before it reports a single number. Feeding counters alone
        // would test an ordering the engine never produces.
        String stream = PHASE_COMMIT + "\n" + COUNTERS + "\n";
        reader.startPumping(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)));
        reader.stop();

        job.getProgress().setLastObservedAt(Instant.now().minusSeconds(120));
        JobProgress progress = reader.getProgress();

        assertTrue("silence past the threshold is a stall", progress.isStalled());
        // Never useful on its own: minutes of quiet during a commit are normal, and the same minutes
        // during item processing are not.
        assertNotNull("a stall must be reported alongside the phase", progress.getPhase());
    }

    private JobProgress feed(Charset charset, String... lines) throws Exception {
        return feed(charset, new File(temp.getRoot(), "log-" + System.nanoTime() + ".log"), lines);
    }

    private JobProgress feed(Charset charset, File logFile, String... lines) throws Exception {
        ProcessingJob job = newJob();
        ProgressReader reader = new ProgressReader(job, logFile, charset, 300);
        StringBuilder stream = new StringBuilder();
        for (String line : lines) {
            stream.append(line).append('\n');
        }
        reader.startPumping(new ByteArrayInputStream(stream.toString().getBytes(charset)));
        reader.stop();
        return job.getProgress();
    }

    private static ProcessingJob newJob() {
        return new ProcessingJob("test-job", new ProcessingRequest("source", "destination", "fastmode", null, null),
                "test-operator", Instant.now());
    }
}
