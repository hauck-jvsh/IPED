package iped.mcp.processing;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import iped.mcp.export.PathConfinement;

/**
 * Decides whether an evidence source may be read (FR-006, FR-007, FR-008).
 *
 * <h2>Why reading needed confining at all</h2>
 *
 * <p>
 * Feature 006 confined what the server <i>writes</i>. Nothing confined what it reads, because the
 * only path it read was the case an examiner named — and a case is, by definition, material somebody
 * already chose to expose. Processing breaks that symmetry: it turns any path the server account can
 * read into indexed, queryable, exportable content. Pointing it at the server's own documents folder
 * produces a case containing that folder, and the write allowlist sees nothing wrong, because the
 * artifact lands inside a permitted root. What came from the wrong place is the content, not the
 * file.
 *
 * <h2>Two rules that are easy to weaken by accident</h2>
 *
 * <p>
 * <b>It is an allowlist, not a denylist.</b> An empty area list is not "anywhere" — it is a
 * misconfiguration, reported as one.
 *
 * <p>
 * <b>Areas resolve at request time, not at startup</b> (FR-039). A volume mounted after the server
 * came up has to work without restarting it. A declared area that is not there right now is
 * {@link Verdict#AREA_UNAVAILABLE}, which is a different answer from "not permitted": merging the
 * two sends the examiner looking for a configuration error when a disk simply is not plugged in.
 */
public final class SourceConfinement {

    /** Why a source was accepted or refused. */
    public enum Verdict {
        ALLOWED,
        /** Resolves outside every declared area. */
        OUTSIDE_AREAS,
        /** The platform will not name a file this way, or it does not exist. */
        UNRESOLVABLE,
        /** Areas are declared but none of them is present right now (FR-039). */
        AREA_UNAVAILABLE,
        /** Processing is enabled and no area is declared: configuration error, not a grant. */
        NO_AREAS_DECLARED
    }

    /** The outcome of submitting one source path to the rule. Immutable. */
    public static final class ResolvedSource {

        private final String requested;
        private final Path resolved;
        private final Path area;
        private final Verdict verdict;
        private final String reason;
        private final List<String> declaredAreas;

        ResolvedSource(String requested, Path resolved, Path area, Verdict verdict, String reason,
                List<String> declaredAreas) {
            this.requested = requested;
            this.resolved = resolved;
            this.area = area;
            this.verdict = verdict;
            this.reason = reason;
            this.declaredAreas = declaredAreas;
        }

        public String getRequested() {
            return requested;
        }

        /** The real path, or {@code null} when it could not be resolved. */
        public Path getResolved() {
            return resolved;
        }

        /** The area that contains the source, or {@code null} unless {@link Verdict#ALLOWED}. */
        public Path getArea() {
            return area;
        }

        public Verdict getVerdict() {
            return verdict;
        }

        public String getReason() {
            return reason;
        }

        /** The areas as declared, for a refusal that names where reading is permitted (FR-008). */
        public List<String> getDeclaredAreas() {
            return declaredAreas;
        }

        public boolean isAllowed() {
            return verdict == Verdict.ALLOWED;
        }
    }

    private SourceConfinement() {
    }

    /**
     * Applies the rule. Never throws for a bad source — a refusal is a verdict, and turning it into
     * an error message is the caller's job.
     *
     * @param requested
     *            the source as the agent wrote it
     * @param declaredAreas
     *            {@code processingSourceAreas}, exactly as configured
     */
    public static ResolvedSource resolve(String requested, List<String> declaredAreas) {
        List<String> declared = declaredAreas == null ? Collections.emptyList() : declaredAreas;

        if (declared.isEmpty()) {
            return new ResolvedSource(requested, null, null, Verdict.NO_AREAS_DECLARED,
                    "processingSourceAreas is empty, which is a configuration error rather than a grant of "
                            + "full access",
                    declared);
        }

        // Resolved here, at request time, so media mounted after startup works without a restart.
        List<Path> present = new ArrayList<>();
        for (String area : declared) {
            Path real = realExistingOrNull(area);
            if (real != null) {
                present.add(real);
            }
        }
        if (present.isEmpty()) {
            return new ResolvedSource(requested, null, null, Verdict.AREA_UNAVAILABLE,
                    "every declared area is absent right now; this is a missing volume, not a refused source",
                    declared);
        }

        // The source must exist to be read, so the whole path is realized rather than the deepest
        // existing ancestor the way a not-yet-created destination is.
        Path resolved = realExistingOrNull(requested);
        if (resolved == null) {
            return new ResolvedSource(requested, null, null, Verdict.UNRESOLVABLE,
                    "the path does not exist on the server, or the platform will not name a file this way",
                    declared);
        }

        for (Path area : present) {
            // Path.startsWith compares whole name elements, so an area of "D:\evidence" does not
            // match "D:\evidences" — the bug a String.startsWith would have. And the comparison is
            // real path against real path, so a junction inside an area that points outside it does
            // not escape.
            if (resolved.startsWith(area)) {
                return new ResolvedSource(requested, resolved, area, Verdict.ALLOWED, null, declared);
            }
        }
        return new ResolvedSource(requested, resolved, null, Verdict.OUTSIDE_AREAS, null, declared);
    }

    /**
     * The real path of something that must already exist, or {@code null}.
     *
     * <p>
     * Not delegated to {@link PathConfinement}: its {@code realize} is package-private, and it
     * resolves the deepest <i>existing ancestor</i> of a path that does not exist yet, which is what
     * an export destination needs and a source does not. A source has to exist to be read, so
     * {@code toRealPath()} alone is the complete primitive here — and it is the same one, so a
     * junction, a symlink or a {@code ..} inside a declared area still resolves to where it really
     * lands before any comparison happens.
     */
    private static Path realExistingOrNull(String path) {
        try {
            return new File(path).toPath().toRealPath();
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }
}
