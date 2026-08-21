package iped.mcp.processing;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import iped.engine.data.IPEDSource;
import iped.mcp.export.PathConfinement;
import iped.mcp.export.PathConfinement.ResolvedDestination;

/**
 * Decides whether a case may be created at a destination (FR-009, FR-010, FR-040).
 *
 * <h2>Why this is not {@code exportRoots}</h2>
 *
 * <p>
 * An artifact is megabytes; a case is hundreds of gigabytes and a folder tree rather than a file.
 * Reusing one root list would silently let a folder an examiner declared for spreadsheets receive an
 * entire index — they authorized reports, not collections. So the list is separate, and only the
 * list is: the path rule itself is {@link PathConfinement}, unchanged, because a case destination
 * has exactly the shape an export destination has — it does not exist yet, so the deepest existing
 * ancestor is what gets realized before comparison.
 */
public final class CaseRootConfinement {

    /** Why a destination was accepted or refused. */
    public enum Verdict {
        ALLOWED,
        /** Resolves outside every declared case root. */
        OUTSIDE_ROOTS,
        /** The platform will not name a folder this way. */
        UNRESOLVABLE,
        /** Case roots are declared but none is present right now. */
        ROOT_UNAVAILABLE,
        /** Processing is enabled and no case root is declared: configuration error, not a grant. */
        NO_ROOTS_DECLARED,
        /**
         * The destination already holds a finished case. Distinct from {@link #DESTINATION_OCCUPIED}
         * because it is a scope boundary, not a collision: appending evidence to a finished case is
         * out of scope for this feature (FR-040), and merging the two would make a deliberate
         * decision read as a defect.
         */
        HAS_FINISHED_CASE,
        /** Something else is already there. */
        DESTINATION_OCCUPIED
    }

    /** The outcome of submitting one destination to the rule. Immutable. */
    public static final class ResolvedCaseRoot {

        private final String requested;
        private final Path resolved;
        private final Path root;
        private final Verdict verdict;
        private final String reason;
        private final List<String> declaredRoots;

        ResolvedCaseRoot(String requested, Path resolved, Path root, Verdict verdict, String reason,
                List<String> declaredRoots) {
            this.requested = requested;
            this.resolved = resolved;
            this.root = root;
            this.verdict = verdict;
            this.reason = reason;
            this.declaredRoots = declaredRoots;
        }

        public String getRequested() {
            return requested;
        }

        public Path getResolved() {
            return resolved;
        }

        public Path getRoot() {
            return root;
        }

        public Verdict getVerdict() {
            return verdict;
        }

        public String getReason() {
            return reason;
        }

        public List<String> getDeclaredRoots() {
            return declaredRoots;
        }

        public boolean isAllowed() {
            return verdict == Verdict.ALLOWED;
        }
    }

    private CaseRootConfinement() {
    }

    /**
     * Applies the rule. Never throws — a refusal is a verdict.
     *
     * @param requested
     *            the destination as the agent wrote it
     * @param declaredRoots
     *            {@code processingCaseRoots}, exactly as configured
     */
    public static ResolvedCaseRoot resolve(String requested, List<String> declaredRoots) {
        List<String> declared = declaredRoots == null ? Collections.emptyList() : declaredRoots;

        if (declared.isEmpty()) {
            return new ResolvedCaseRoot(requested, null, null, Verdict.NO_ROOTS_DECLARED,
                    "processingCaseRoots is empty, which is a configuration error rather than a grant of "
                            + "full access",
                    declared);
        }

        List<Path> present = new ArrayList<>();
        for (String root : declared) {
            Path real = realExistingOrNull(root);
            if (real != null) {
                present.add(real);
            }
        }
        if (present.isEmpty()) {
            return new ResolvedCaseRoot(requested, null, null, Verdict.ROOT_UNAVAILABLE,
                    "every declared case root is absent right now; this is a missing volume, not a refused "
                            + "destination",
                    declared);
        }

        // Delegated unchanged. The case folder argument is null on purpose: "inside the open case"
        // is a different rule with a different reason, applied on top by the caller.
        ResolvedDestination destination = PathConfinement.resolve(requested, present, null, false);
        switch (destination.getVerdict()) {
            case UNRESOLVABLE:
                return new ResolvedCaseRoot(requested, null, null, Verdict.UNRESOLVABLE, destination.getReason(),
                        declared);
            case OUTSIDE_ROOTS:
                return new ResolvedCaseRoot(requested, destination.getResolved(), null, Verdict.OUTSIDE_ROOTS, null,
                        declared);
            case INSIDE_CASE:
                // Unreachable with a null case folder, and mapped rather than ignored so that a
                // future caller passing one does not fall through to ALLOWED.
                return new ResolvedCaseRoot(requested, destination.getResolved(), null, Verdict.DESTINATION_OCCUPIED,
                        "the destination is inside a case folder", declared);
            case ALLOWED:
            default:
                break;
        }

        Path resolved = destination.getResolved();
        Verdict occupancy = inspectOccupancy(resolved);
        if (occupancy != Verdict.ALLOWED) {
            return new ResolvedCaseRoot(requested, resolved, destination.getRoot(), occupancy, null, declared);
        }
        return new ResolvedCaseRoot(requested, resolved, destination.getRoot(), Verdict.ALLOWED, null, declared);
    }

    /**
     * Tells an empty or absent destination from one that is already something.
     *
     * <p>
     * A finished case is reported separately from any other occupant because refusing it is a scope
     * decision the examiner should be able to read as such (FR-040).
     */
    private static Verdict inspectOccupancy(Path resolved) {
        File destination = resolved.toFile();
        if (!destination.exists()) {
            return Verdict.ALLOWED;
        }
        if (!destination.isDirectory()) {
            return Verdict.DESTINATION_OCCUPIED;
        }
        if (IPEDSource.checkIfIsCaseFolder(destination)) {
            return Verdict.HAS_FINISHED_CASE;
        }
        String[] entries = destination.list();
        if (entries != null && entries.length > 0) {
            return Verdict.DESTINATION_OCCUPIED;
        }
        return Verdict.ALLOWED;
    }

    private static Path realExistingOrNull(String path) {
        try {
            return new File(path).toPath().toRealPath();
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }
}
