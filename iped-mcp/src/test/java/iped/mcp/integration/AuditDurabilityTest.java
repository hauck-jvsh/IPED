package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.audit.AuditRecord;
import iped.mcp.audit.AuditTrail;

/**
 * Durability against an abnormal termination (Scenario 8, step 5).
 *
 * <p>
 * This is the test that validates the R7 decision. A server process is started in a child JVM, made
 * to record a known number of operations, and then <b>killed outright</b> — no close, no flush, no
 * shutdown hook. Everything recorded up to that point must be on disk and the chain must verify.
 *
 * <p>
 * A shutdown hook would make this pass without proving anything, which is exactly why the child is
 * destroyed forcibly rather than asked to stop.
 */
public class AuditDurabilityTest {

    /** Operations the child writes before it hangs, waiting to be killed. */
    private static final int OPERATIONS = 25;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void killingTheProcessLosesNothingAlreadyRecorded() throws Exception {
        File trailFile = new File(temp.newFolder("staging"), "session-killed.jsonl");

        Process child = startChild(trailFile);
        try {
            waitForRecords(trailFile, OPERATIONS * 2);
            // No graceful stop. This is the failure the design claims to survive.
            child.destroyForcibly();
            child.waitFor();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }

        List<AuditRecord> records = AuditTrail.read(trailFile);
        assertTrue("operations completed before the kill must be present, got " + records.size(),
                records.size() >= OPERATIONS * 2);
        assertNull("the chain must verify after an abnormal termination", AuditTrail.verify(records));
        assertEquals("the last record must be a completed operation, not a half-written line",
                AuditRecord.Outcome.OK, records.get(records.size() - 1).getOutcome());
    }

    private Process startChild(File trailFile) throws Exception {
        // The classpath of this module runs to hundreds of jars, well past the Windows command
        // line limit, so it goes through a @argfile instead of an argument. Forward slashes
        // because a backslash is an escape character inside an argfile.
        File argFile = new File(temp.getRoot(), "child-classpath.args");
        Files.write(argFile.toPath(),
                ("-cp \"" + System.getProperty("java.class.path").replace('\\', '/') + "\"")
                        .getBytes(StandardCharsets.UTF_8));

        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath());
        command.add("@" + argFile.getAbsolutePath());
        command.add(DurabilityChild.class.getName());
        command.add(trailFile.getAbsolutePath());
        command.add(String.valueOf(OPERATIONS));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(temp.getRoot(), "child.log"));
        return builder.start();
    }

    private void waitForRecords(File trailFile, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            if (trailFile.isFile()
                    && Files.readAllLines(trailFile.toPath(), StandardCharsets.UTF_8).size() >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the child never wrote " + expected + " records; see child.log");
    }

    /**
     * Child process: writes a fixed number of audited operations, then blocks forever so the parent
     * can kill it mid-session.
     */
    public static class DurabilityChild {
        public static void main(String[] args) throws Exception {
            File trailFile = new File(args[0]);
            int operations = Integer.parseInt(args[1]);
            AuditTrail trail = new AuditTrail(trailFile, "child-session", "tester");
            for (int i = 0; i < operations; i++) {
                AuditRecord start = trail.recordStart("iped_search",
                        java.util.Collections.singletonMap("query", "term" + i), "case-1", "binding-1", null);
                trail.recordEnd(start, AuditRecord.Outcome.OK, i, null);
            }
            // Deliberately no close(): the parent kills us here.
            Thread.sleep(600000);
        }
    }
}
