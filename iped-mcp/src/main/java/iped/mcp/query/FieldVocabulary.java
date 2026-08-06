package iped.mcp.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import iped.engine.data.IPEDSource;
import iped.engine.search.LoadIndexFields;

/**
 * The field names actually present in one case's index, and the source of truth about them.
 *
 * <p>
 * Built over {@link LoadIndexFields}, which already reads the {@code FieldInfos} of every segment
 * and returns the real names, internal fields excluded (R6). Documentation about a canonical
 * vocabulary is subordinate to this in case of conflict (FR-050): a case processed years ago has
 * the vocabulary it has, not the one the docs describe.
 *
 * <p>
 * The suggestion of near names is what closes the self-correction loop: an agent that asks for
 * {@code mediaType} on an index that calls it {@code contentType} gets the right name back instead
 * of an empty result set it might report as "no evidence found".
 */
public class FieldVocabulary {

    private final TreeSet<String> fields = new TreeSet<>();
    private final long loadedAt = System.currentTimeMillis();

    public FieldVocabulary(IPEDSource source) {
        fields.addAll(Arrays.asList(LoadIndexFields.getFields(Collections.singletonList(source))));
    }

    private FieldVocabulary(List<String> names) {
        fields.addAll(names);
    }

    /**
     * Builds a vocabulary from an explicit list, for reasoning about field names without opening a
     * case.
     */
    public static FieldVocabulary of(String... names) {
        return new FieldVocabulary(Arrays.asList(names));
    }

    public List<String> getFields() {
        return new ArrayList<>(fields);
    }

    public long getLoadedAt() {
        return loadedAt;
    }

    public boolean exists(String field) {
        return fields.contains(field);
    }

    /**
     * Field names of this case that are namespaced under the given one, as {@code p2p:fileType} is
     * under {@code p2p}.
     *
     * <p>
     * This is what identifies the missing-escape case with certainty rather than by guess. When a
     * query asks for the unknown field {@code p2p}, the parser did not misread a typo: it consumed
     * the colon of {@code p2p:fileType} as its own separator. A name that exists under the asked-for
     * one is the proof, and it turns a vague "unknown field" into the exact spelling to retry with.
     *
     * @return the names found, in vocabulary order; empty when the name is not a namespace here
     */
    public List<String> namesUnder(String field) {
        List<String> found = new ArrayList<>();
        if (field == null || field.isEmpty()) {
            return found;
        }
        String prefix = field + ":";
        for (String candidate : fields) {
            if (candidate.startsWith(prefix)) {
                found.add(candidate);
            }
        }
        return found;
    }

    /** Names that cannot be written into a query expression as they stand. */
    public List<String> namesNeedingEscape() {
        List<String> found = new ArrayList<>();
        for (String candidate : fields) {
            if (FieldNames.needsEscaping(candidate)) {
                found.add(candidate);
            }
        }
        return found;
    }

    /**
     * Field names close to the one given, ranked by edit distance then alphabetically.
     *
     * <p>
     * Case-insensitive containment is considered first: an agent that asks for {@code MediaType}
     * where the index has {@code contentType} is not making a typo, it is using another product's
     * vocabulary, and the useful answer is the field that carries that meaning.
     *
     * @param limit
     *            maximum number of suggestions
     */
    public List<String> similar(String field, int limit) {
        if (field == null || field.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> candidates = new ArrayList<>(fields);
        candidates.sort((a, b) -> {
            int cmp = Integer.compare(rank(field, a), rank(field, b));
            return cmp != 0 ? cmp : a.compareToIgnoreCase(b);
        });
        // A tier match is always offered; a match decided purely by edit distance has to be close,
        // scaled to the length of the name asked for. Offering a distant name would send the agent
        // to a field that means something else, which is worse than offering nothing.
        int maxDistance = Math.max(2, field.length() / 3);
        List<String> scored = new ArrayList<>();
        for (String candidate : candidates) {
            int rank = rank(field, candidate);
            if (rank <= DISTANCE_TIER + maxDistance && scored.size() < limit) {
                scored.add(candidate);
            }
        }
        return scored;
    }

    /**
     * Lower is closer. The tiers matter more than the numbers, and the order between them is the
     * whole point of this method.
     *
     * <p>
     * The case that drives the design: an agent asks for {@code mediaType} on an index that calls
     * it {@code contentType}. A plain substring rule would answer {@code type} — which also exists,
     * means something else, and would send the investigation somewhere wrong. So two compound names
     * sharing a final segment ({@code mediaType} / {@code contentType}) rank <b>above</b> a bare
     * name that merely happens to be a substring.
     */
    private static int rank(String asked, String candidate) {
        String needle = asked.toLowerCase(Locale.ROOT);
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.equals(needle)) {
            return 0;
        }
        String askedTail = tail(asked);
        String candidateTail = tail(candidate);
        boolean sameTail = !askedTail.isEmpty() && askedTail.equals(candidateTail);
        if (sameTail && isCompound(asked) && isCompound(candidate)) {
            return 1;
        }
        if (lower.contains(needle) || needle.contains(lower)) {
            return 2;
        }
        if (sameTail) {
            return 3;
        }
        return DISTANCE_TIER + levenshtein(needle, lower);
    }

    /** Rank at which the tiers end and plain edit distance takes over. */
    private static final int DISTANCE_TIER = 4;

    /** Whether a name has a qualifier before its final segment: {@code contentType}, not {@code type}. */
    private static boolean isCompound(String name) {
        if (name.indexOf(':') > 0 || name.indexOf('_') > 0 || name.indexOf('.') > 0) {
            return true;
        }
        for (int i = 1; i < name.length(); i++) {
            if (Character.isUpperCase(name.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Last camelCase or namespaced segment of a field name, lowercased. */
    private static String tail(String name) {
        int separator = Math.max(name.lastIndexOf(':'), Math.max(name.lastIndexOf('_'), name.lastIndexOf('.')));
        String base = separator >= 0 ? name.substring(separator + 1) : name;
        for (int i = base.length() - 1; i > 0; i--) {
            if (Character.isUpperCase(base.charAt(i))) {
                return base.substring(i).toLowerCase(Locale.ROOT);
            }
        }
        return base.toLowerCase(Locale.ROOT);
    }

    /** Standard iterative Levenshtein distance, two-row variant. */
    public static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
