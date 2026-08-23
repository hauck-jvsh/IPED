package iped.mcp.processing;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Warns when the destination probably will not hold the case — and never refuses (FR-044).
 *
 * <h2>Why it warns instead of refusing</h2>
 *
 * <p>
 * How much an index occupies depends on the profile, on the material, and on whether item export is
 * on. A refusal resting on that estimate would block legitimate work on a Friday evening, and the
 * examiner is the one who knows the material. So the requirement is computed from something they can
 * check — a percentage of the source size — and the decision to go ahead stays theirs.
 *
 * <p>
 * The consequence is worth stating rather than assuming: because nothing is blocked, the reactive
 * "disk filled at hour ten" path stays load-bearing. FR-017's promise to fail early is scoped to
 * configuration, path and profile; it does not extend to space.
 *
 * <h2>The size is the set's, not the first file's</h2>
 *
 * <p>
 * Forensic images almost always arrive segmented. Measuring only the {@code .E01} would give a
 * fraction of the real size, and the warning would then fail to appear in exactly the case that most
 * needs it. A logical folder is summed recursively.
 *
 * <p>
 * The measurement is bounded by the accept budget (FR-018, SC-002). Summing a large folder on a
 * network share does not fit in five seconds, and when it does not, the requirement is declared
 * <b>unavailable</b> rather than guessed — absence is not zero.
 */
public final class DiskPreflight {

    /** What the preflight concluded. Never a refusal. */
    public static final class Assessment {

        private final boolean measured;
        private final long sourceBytes;
        private final long requiredBytes;
        private final long freeBytes;
        private final String warning;

        Assessment(boolean measured, long sourceBytes, long requiredBytes, long freeBytes, String warning) {
            this.measured = measured;
            this.sourceBytes = sourceBytes;
            this.requiredBytes = requiredBytes;
            this.freeBytes = freeBytes;
            this.warning = warning;
        }

        /** False when the source could not be sized inside the budget — unavailable, not zero. */
        public boolean isMeasured() {
            return measured;
        }

        public long getSourceBytes() {
            return sourceBytes;
        }

        public long getRequiredBytes() {
            return requiredBytes;
        }

        public long getFreeBytes() {
            return freeBytes;
        }

        /** The warning to carry in the accept and the trail, or {@code null} when there is room. */
        public String getWarning() {
            return warning;
        }

        public boolean hasWarning() {
            return warning != null;
        }
    }

    /** How long the source may be measured for before the requirement is declared unavailable. */
    static final long MEASURE_BUDGET_MILLIS = 3000;

    private final int percentOfSource;

    public DiskPreflight(int percentOfSource) {
        this.percentOfSource = percentOfSource;
    }

    /**
     * Compares free space at the destination with {@code source size × declared percent}.
     *
     * @param source
     *            the evidence, a file (possibly segmented) or a folder
     * @param destination
     *            where the case would be created; need not exist yet
     */
    public Assessment assess(File source, File destination) {
        long sourceBytes = measureWithinBudget(source);
        if (sourceBytes < 0) {
            return new Assessment(false, 0, 0, 0, null);
        }
        long required = requiredFor(sourceBytes);
        long free = usableSpaceAt(destination);
        if (free < 0) {
            return new Assessment(true, sourceBytes, required, 0, null);
        }
        if (free >= required) {
            return new Assessment(true, sourceBytes, required, free, null);
        }
        String warning = String.format(Locale.ROOT,
                "The destination has %s free and this evidence asks for at least %s (%d%% of its %s). "
                        + "The job was accepted anyway — this is a warning, not a refusal, and the decision to "
                        + "go ahead is yours. If it runs out of space the job will end with a readable outcome "
                        + "saying so.",
                gigabytes(free), gigabytes(required), percentOfSource, gigabytes(sourceBytes));
        return new Assessment(true, sourceBytes, required, free, warning);
    }

    /**
     * The requirement, saturating instead of wrapping.
     *
     * <p>
     * A percentage large enough to overflow is a misconfiguration, and wrapping would turn it into a
     * <i>negative</i> requirement that every destination satisfies — the failure mode that suppresses
     * the warning entirely, which is the direction that matters.
     */
    private long requiredFor(long sourceBytes) {
        long perPercent = sourceBytes / 100;
        if (perPercent > 0 && percentOfSource > Long.MAX_VALUE / perPercent) {
            return Long.MAX_VALUE;
        }
        return perPercent * percentOfSource;
    }

    /**
     * Total bytes of the evidence set, or -1 when the budget ran out.
     *
     * <p>
     * For a segmented image the siblings sharing the base name are summed: {@code RockPi4.E01} plus
     * {@code .E02}, {@code .E03} and so on. Sidecar files an acquisition tool leaves next to the
     * image — a hash list, a log — are not part of the evidence and are left out, which is why the
     * match is on the segment shape rather than on any file with the same stem.
     */
    long measureWithinBudget(File source) {
        if (source == null || !source.exists()) {
            return -1;
        }
        long deadline = System.currentTimeMillis() + MEASURE_BUDGET_MILLIS;
        if (source.isFile()) {
            return sumSegments(source);
        }
        AtomicBoolean overBudget = new AtomicBoolean(false);
        long total = sumFolder(source.toPath(), deadline, overBudget);
        return overBudget.get() ? -1 : total;
    }

    private static long sumSegments(File first) {
        String name = first.getName();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return first.length();
        }
        String stem = name.substring(0, dot);
        String extension = name.substring(dot + 1);
        File folder = first.getParentFile();
        File[] siblings = folder == null ? null : folder.listFiles();
        if (siblings == null) {
            return first.length();
        }
        long total = 0;
        for (File sibling : siblings) {
            String siblingName = sibling.getName();
            int siblingDot = siblingName.lastIndexOf('.');
            if (siblingDot <= 0 || !siblingName.substring(0, siblingDot).equals(stem)) {
                continue;
            }
            if (isSiblingSegment(extension, siblingName.substring(siblingDot + 1))) {
                total += sibling.length();
            }
        }
        return total == 0 ? first.length() : total;
    }

    /**
     * Whether a sibling belongs to the same segment set as the file that was named.
     *
     * <p>
     * Anchored on the named file's own extension rather than on a general pattern, and that is the
     * whole point. A general "letter plus two alphanumerics" matches {@code E01} — and equally
     * matches {@code csv}, {@code txt} and {@code log}, which is what acquisition tools leave beside
     * an image. Counting those inflates the requirement with data that is not evidence.
     *
     * <p>
     * So a sibling qualifies only when it shares the reference's length and first character and
     * differs after it: {@code E01} accepts {@code E02} and {@code EAA}, which is how EWF numbers
     * its overflow, and {@code 001} accepts {@code 002}. Neither accepts {@code csv}.
     */
    private static boolean isSiblingSegment(String reference, String candidate) {
        if (reference.length() != candidate.length() || reference.length() != 3) {
            return false;
        }
        String referenceUpper = reference.toUpperCase(Locale.ROOT);
        String candidateUpper = candidate.toUpperCase(Locale.ROOT);
        if (referenceUpper.charAt(0) != candidateUpper.charAt(0)) {
            return false;
        }
        for (int i = 1; i < 3; i++) {
            if (!Character.isLetterOrDigit(candidateUpper.charAt(i))) {
                return false;
            }
        }
        // The reference itself must look like a segment, or a plain ".dat" file would drag in every
        // three-character sibling sharing its first letter.
        return Character.isDigit(referenceUpper.charAt(1)) || Character.isDigit(referenceUpper.charAt(2));
    }

    private static long sumFolder(Path folder, long deadline, AtomicBoolean overBudget) {
        long total = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (System.currentTimeMillis() > deadline) {
                    overBudget.set(true);
                    return total;
                }
                if (Files.isRegularFile(path)) {
                    total += path.toFile().length();
                }
            }
        } catch (IOException e) {
            overBudget.set(true);
        }
        return total;
    }

    /**
     * Usable space on the volume the destination lands on.
     *
     * <p>
     * {@code getUsableSpace}, not {@code getFreeSpace}: the second ignores quotas and reserved
     * blocks, so it would report room that this account cannot actually use — an error in the
     * direction that suppresses the warning, which is the direction that matters.
     */
    private static long usableSpaceAt(File destination) {
        File existing = destination;
        while (existing != null && !existing.exists()) {
            existing = existing.getParentFile();
        }
        if (existing == null) {
            return -1;
        }
        try {
            FileStore store = Files.getFileStore(existing.toPath());
            return store.getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    private static String gigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
