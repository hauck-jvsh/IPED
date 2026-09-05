package iped.mcp.export;

/**
 * Turns the name an item carries inside the evidence into a name this filesystem will accept.
 *
 * <p>
 * <b>Why this exists.</b> An item's name comes from seized material: it was chosen by whoever made
 * the file, not by anyone this server trusts. Writing it to disk unexamined is how a name becomes a
 * path — {@code ../../autoexec.bat}, {@code report.txt:hidden} naming an alternate data stream,
 * {@code CON} naming a device on Windows, a trailing space or dot that Windows silently strips so
 * the file lands somewhere other than where the answer says it did.
 * {@link PathConfinement} is the wall that stops the write from escaping; this is what keeps a
 * legitimate export from hitting that wall in the first place, and what keeps the name in the result
 * equal to the name on disk.
 *
 * <p>
 * Every name produced here begins with the item id, which is what makes the result traceable — the
 * file says which item of which case it came from without opening it — and it is also what defuses
 * the Windows device names for free: {@code CON} is a device, {@code 4711-CON} is a file.
 */
public final class EvidenceFileName {

    /**
     * How much of the item's own name to keep. Kept well under the path limit because the name is
     * only part of the path: a write root, a case folder and the item id go in front of it.
     */
    private static final int MAX_NAME_CHARS = 120;

    /** How much of a long extension to preserve when the name has to be cut. */
    private static final int MAX_EXTENSION_CHARS = 16;

    private EvidenceFileName() {
    }

    /**
     * The file name to write for an item.
     *
     * @param itemId
     *            the item's id in its case, always the prefix
     * @param name
     *            the item's name as recorded in the case, which may be anything at all
     * @param suffix
     *            appended after the sanitized name, or {@code null} — used to mark a file that holds
     *            the extracted text rather than the item's own bytes
     * @return a name safe to write, never empty and never a bare id-less string
     */
    public static String forItem(int itemId, String name, String suffix) {
        String safe = sanitize(name);
        StringBuilder built = new StringBuilder().append(itemId);
        if (!safe.isEmpty()) {
            built.append('-').append(safe);
        }
        if (suffix != null && !suffix.isEmpty()) {
            built.append(suffix);
        }
        return built.toString();
    }

    /**
     * Strips from a name everything that would make it mean something other than a name.
     *
     * @return the sanitized name, possibly empty when nothing of it survives
     */
    private static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // Separators and the drive/stream colon first: these are the characters that turn a name
            // into a path. Then the rest of what Windows refuses, so a name that works on Linux does
            // not produce an export that fails only on the examiner's workstation. Control
            // characters go too — they are invisible in the result and legal in a Linux file name.
            if (c == '/' || c == '\\' || c == ':' || c == '<' || c == '>' || c == '"' || c == '|' || c == '?'
                    || c == '*' || c < 0x20 || c == 0x7F) {
                cleaned.append('_');
            } else {
                cleaned.append(c);
            }
        }
        String trimmed = trimTrailingDotsAndSpaces(cleaned.toString().trim());
        return truncate(trimmed);
    }

    /**
     * Removes trailing dots and spaces, which Windows drops without saying so.
     *
     * <p>
     * A name ending in one of them names a different file than the string suggests, and the result
     * this server returns would point at a path that does not exist.
     */
    private static String trimTrailingDotsAndSpaces(String name) {
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) {
            end--;
        }
        return name.substring(0, end);
    }

    /** Cuts an over-long name while keeping its extension, which is what tells a viewer what it is. */
    private static String truncate(String name) {
        if (name.length() <= MAX_NAME_CHARS) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = "";
        if (dot > 0 && name.length() - dot - 1 <= MAX_EXTENSION_CHARS) {
            extension = name.substring(dot);
        }
        return name.substring(0, MAX_NAME_CHARS - extension.length()) + extension;
    }
}
