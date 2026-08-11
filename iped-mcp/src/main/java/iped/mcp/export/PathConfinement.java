package iped.mcp.export;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides whether a destination asked for by the agent is inside a declared write root (FR-001).
 *
 * <p>
 * <b>Why this is not a string comparison.</b> The rule has to hold against a path that looks like it
 * is inside a root and resolves somewhere else, and on Windows the mechanisms for that are real and
 * measured (see {@code research.md} R1):
 *
 * <ul>
 * <li>A <b>directory junction</b> inside a root pointing outside it. {@code File.getCanonicalPath()}
 * does <i>not</i> traverse one — it hands back the path with the junction still in it, which passes
 * any prefix check. {@link Path#toRealPath} resolves it. This single fact is why the check moved off
 * {@code java.io.File}.</li>
 * <li><b>8.3 short names</b> ({@code JOAOPA~1}) and <b>case differences</b>, which name the same
 * file with a different string.</li>
 * <li><b>Extended-length prefixes</b> ({@code \\?\}), likewise.</li>
 * <li><b>Alternate data streams</b> ({@code report.xlsx:hidden}), where the host file is inside the
 * root and the bytes go somewhere invisible. {@code java.io.File} accepts these and
 * {@code FileOutputStream} writes them; {@link Paths#get} rejects them outright, so moving to NIO
 * turns a silent escape into a named refusal.</li>
 * </ul>
 *
 * <p>
 * <b>Nothing here touches the filesystem beyond reading.</b> No directory is created, which is what
 * lets FR-002 hold: a refused destination must leave nothing behind, and the current code created
 * the intermediate folders before deciding anything.
 */
public final class PathConfinement {

    /** Why a destination was accepted or refused. */
    public enum Verdict {
        /** Inside a declared root, and not inside the case folder. */
        ALLOWED,
        /** Resolves outside every declared root. */
        OUTSIDE_ROOTS,
        /** Resolves inside the case folder, which stays refused even when a root contains it. */
        INSIDE_CASE,
        /** The platform will not name a file this way at all. */
        UNRESOLVABLE
    }

    /** The outcome of submitting one requested path to the rule. Immutable. */
    public static final class ResolvedDestination {

        private final String requested;
        private final Path resolved;
        private final Path root;
        private final Verdict verdict;
        private final String reason;
        private final List<Path> roots;

        ResolvedDestination(String requested, Path resolved, Path root, Verdict verdict, String reason,
                List<Path> roots) {
            this.requested = requested;
            this.resolved = resolved;
            this.root = root;
            this.verdict = verdict;
            this.reason = reason;
            this.roots = roots;
        }

        /** The path exactly as the agent wrote it, kept for the diagnostic and the audit trail. */
        public String getRequested() {
            return requested;
        }

        /** The real path the write would reach, or {@code null} when the platform refused the name. */
        public Path getResolved() {
            return resolved;
        }

        /** The root that contains the destination, or {@code null} unless {@link Verdict#ALLOWED}. */
        public Path getRoot() {
            return root;
        }

        public Verdict getVerdict() {
            return verdict;
        }

        /** Detail behind a refusal, in the platform's own words where there is one. */
        public String getReason() {
            return reason;
        }

        /** The roots in force, so a refusal can name where writing is permitted (FR-008). */
        public List<Path> getRoots() {
            return roots;
        }

        public boolean isAllowed() {
            return verdict == Verdict.ALLOWED;
        }
    }

    private PathConfinement() {
    }

    /**
     * Applies the rule. Never throws for a bad destination — a refusal is a verdict, and turning it
     * into an error message is the caller's job, which keeps this testable without exceptions.
     *
     * @param requested
     *            the destination as the agent wrote it
     * @param roots
     *            the write roots in force, already resolved to real paths
     * @param casePath
     *            the open case's folder, refused as a destination
     * @param allowInsideCase
     *            the {@code allowExportIntoCaseFolder} escape hatch. It suppresses
     *            {@link Verdict#INSIDE_CASE} <b>only</b>; a destination outside every root stays
     *            refused with it on
     */
    public static ResolvedDestination resolve(String requested, List<Path> roots, File casePath,
            boolean allowInsideCase) {
        List<Path> declaredRoots = roots == null ? Collections.emptyList() : roots;

        Path requestedPath;
        try {
            requestedPath = Paths.get(requested);
        } catch (InvalidPathException e) {
            // This is where an alternate data stream and a trailing space arrive. Naming it beats
            // letting an unchecked exception out of a path check.
            return new ResolvedDestination(requested, null, null, Verdict.UNRESOLVABLE, e.getReason(), declaredRoots);
        }

        if (!requestedPath.isAbsolute()) {
            // A relative path resolves against whatever directory the server process happens to
            // have, which varies by how it was launched. Principle V: nothing implicit in what
            // varies by environment.
            return new ResolvedDestination(requested, null, null, Verdict.UNRESOLVABLE,
                    "the path is relative, and what it would resolve against depends on how the server was "
                            + "launched",
                    declaredRoots);
        }

        Path resolved;
        try {
            resolved = realize(requestedPath);
        } catch (IOException e) {
            return new ResolvedDestination(requested, null, null, Verdict.UNRESOLVABLE, e.getMessage(), declaredRoots);
        }

        if (!allowInsideCase && casePath != null) {
            Path caseFolder = realizeQuietly(casePath.toPath());
            if (caseFolder != null && resolved.startsWith(caseFolder)) {
                return new ResolvedDestination(requested, resolved, null, Verdict.INSIDE_CASE, null, declaredRoots);
            }
        }

        for (Path root : declaredRoots) {
            // Path.startsWith compares whole name elements, so a root of "C:\work" does not match
            // "C:\workspace" — the bug a String.startsWith would have.
            if (resolved.startsWith(root)) {
                return new ResolvedDestination(requested, resolved, root, Verdict.ALLOWED, null, declaredRoots);
            }
        }
        return new ResolvedDestination(requested, resolved, null, Verdict.OUTSIDE_ROOTS, null, declaredRoots);
    }

    /**
     * Resolves a path that does not exist yet to where it would really land.
     *
     * <p>
     * {@link Path#toRealPath} throws for a file that is not there, and the destination of an export
     * never is. So the walk goes up to the deepest ancestor that <i>does</i> exist, resolves that —
     * which is what defeats a junction, a short name or a case difference — and puts the remaining
     * names back on top.
     */
    static Path realize(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        List<Path> pending = new ArrayList<>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name != null) {
                pending.add(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("no part of the path exists, so where it would be created cannot be determined");
        }
        Path real = existing.toRealPath();
        for (int i = pending.size() - 1; i >= 0; i--) {
            real = real.resolve(pending.get(i));
        }
        return real;
    }

    /** Resolves for comparison, yielding {@code null} rather than failing the whole check. */
    static Path realizeQuietly(Path path) {
        try {
            return realize(path);
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }
}
