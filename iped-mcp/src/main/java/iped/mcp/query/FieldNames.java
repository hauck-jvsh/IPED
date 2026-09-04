package iped.mcp.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Writing a field name into a query expression.
 *
 * <p>
 * Much of an IPED case's vocabulary is namespaced with colons — {@code p2p:fileType},
 * {@code ufed:UserID}, {@code image:Width}, {@code dc:title}, and script-produced names such as
 * {@code ai:csamDetector:status} carrying two of them. The query parser reads a colon as the
 * separator between field and value, so a name has to be spelled {@code p2p\:fileType} inside an
 * expression while staying {@code p2p:fileType} everywhere else: in {@code iped_check_field}, in
 * the keys of {@code iped_item_fields}, in an aggregation dimension.
 *
 * <p>
 * That asymmetry is the whole reason this class exists. It was the defect behind an agent that
 * looped between {@code QUERY_SYNTAX} and {@code UNKNOWN_FIELD} and concluded the query engine was
 * limited: the vocabulary tools handed it a name it could not paste into a query, and nothing in
 * the diagnostics said so.
 *
 * <p>
 * Quoting the name is <b>not</b> an alternative — {@code "p2p:fileType":"mp3"} is a parse error.
 * The backslash is the only form the parser accepts.
 */
public final class FieldNames {

    /**
     * Characters the query parser gives a meaning to. Same set the Lucene escaper covers, plus
     * whitespace, which can appear in metadata field names produced by a parser.
     */
    private static final String SPECIAL = "\\+-!():^[]\"{}~*?|&/ \t";

    private FieldNames() {
    }

    /** Whether this name means something other than itself when written into an expression. */
    public static boolean needsEscaping(String field) {
        if (field == null || field.isEmpty()) {
            return false;
        }
        for (int i = 0; i < field.length(); i++) {
            if (SPECIAL.indexOf(field.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The name as it has to be spelled inside a query expression.
     *
     * @return {@code p2p\:fileType} for {@code p2p:fileType}; the name unchanged when it carries
     *         nothing special
     */
    public static String toQueryForm(String field) {
        if (!needsEscaping(field)) {
            return field;
        }
        StringBuilder escaped = new StringBuilder(field.length() + 4);
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (SPECIAL.indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /**
     * The name as it is spelled everywhere <b>except</b> inside a query expression: the escapes the
     * parser needs, removed.
     *
     * <p>
     * The inverse of {@link #toQueryForm(String)}, for arguments that take a name rather than an
     * expression — {@code iped_check_field}, an aggregation dimension, the {@code fields} of
     * {@code iped_get_items}. An agent that has just written {@code p2p\:fileType} into a query
     * pastes the same spelling into the next call, and refusing it there over one backslash sends it
     * looking for the problem in the case instead of in the punctuation.
     *
     * @return {@code p2p:fileType} for {@code p2p\:fileType}; the name unchanged when it carries no
     *         escape
     */
    public static String fromQueryForm(String field) {
        if (field == null || field.indexOf('\\') < 0) {
            return field;
        }
        StringBuilder plain = new StringBuilder(field.length());
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '\\' && i + 1 < field.length() && SPECIAL.indexOf(field.charAt(i + 1)) >= 0) {
                // The backslash is punctuation of the query language, not a character of the name.
                continue;
            }
            plain.append(c);
        }
        return plain.toString();
    }

    /**
     * Rewrites an expression so that every field name of this case that appears in it unescaped is
     * spelled the way the parser needs.
     *
     * <p>
     * Only a name the index actually has is touched, and only where it is used as a field — at a
     * token boundary and followed by a colon. Text inside a quoted phrase is never rewritten: the
     * examiner's phrase is the evidence being searched for, not syntax. A name already written
     * escaped does not match its raw form, so applying this twice changes nothing.
     *
     * @return the rewritten expression, or the original instance when nothing needed it
     */
    public static String escapeKnownFieldNames(String expression, FieldVocabulary vocabulary) {
        if (expression == null || expression.isEmpty() || vocabulary == null) {
            return expression;
        }
        List<String> candidates = escapableFields(vocabulary);
        if (candidates.isEmpty()) {
            return expression;
        }
        StringBuilder rewritten = new StringBuilder(expression.length() + 16);
        boolean changed = false;
        boolean inQuotes = false;
        boolean atTokenStart = true;
        for (int i = 0; i < expression.length();) {
            char c = expression.charAt(i);
            if (c == '\\' && i + 1 < expression.length()) {
                // An escape carries its next character through untouched, whatever it is.
                rewritten.append(c).append(expression.charAt(i + 1));
                i += 2;
                atTokenStart = false;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                rewritten.append(c);
                i++;
                atTokenStart = false;
                continue;
            }
            if (!inQuotes && atTokenStart) {
                String match = longestFieldAt(expression, i, candidates);
                if (match != null) {
                    rewritten.append(toQueryForm(match));
                    i += match.length();
                    changed = true;
                    atTokenStart = false;
                    continue;
                }
            }
            rewritten.append(c);
            atTokenStart = isTokenBoundary(c);
            i++;
        }
        return changed ? rewritten.toString() : expression;
    }

    /** Field names of this case that cannot be written into an expression as they are. */
    private static List<String> escapableFields(FieldVocabulary vocabulary) {
        List<String> candidates = new ArrayList<>();
        for (String field : vocabulary.getFields()) {
            if (needsEscaping(field)) {
                candidates.add(field);
            }
        }
        // Longest first, so 'ai:csamDetector:status' wins over a shorter name sharing its prefix.
        candidates.sort((a, b) -> {
            int byLength = Integer.compare(b.length(), a.length());
            return byLength != 0 ? byLength : a.compareTo(b);
        });
        return candidates;
    }

    /**
     * The longest field name starting at {@code position} and used there as a field, meaning a
     * colon follows it. Without that colon the text is a term, not a field reference, and rewriting
     * it would change what the examiner asked.
     */
    private static String longestFieldAt(String expression, int position, List<String> candidates) {
        for (String candidate : candidates) {
            int end = position + candidate.length();
            if (end < expression.length() && expression.startsWith(candidate, position)
                    && expression.charAt(end) == ':') {
                return candidate;
            }
        }
        return null;
    }

    /** After these, a field name may begin. */
    private static boolean isTokenBoundary(char c) {
        return Character.isWhitespace(c) || c == '(' || c == '+' || c == '-' || c == '!';
    }
}
