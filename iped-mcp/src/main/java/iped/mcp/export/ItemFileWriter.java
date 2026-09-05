package iped.mcp.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import iped.data.IItem;

/**
 * Writes one item to one file, and says what it wrote.
 *
 * <p>
 * <b>Whole file or nothing.</b> The bytes go to a sibling {@code .part} and are moved into place
 * once the stream ends. A disk that fills up, evidence that goes away mid-read or a cancelled
 * session leaves a {@code .part} behind rather than a truncated file carrying the name of the real
 * one — the failure a later reader cannot see is the one worth engineering against, because an
 * export that is short by a block still opens.
 *
 * <p>
 * <b>Digests are computed on the way through</b>, from the same bytes that reach the disk, so what
 * the result reports is what was written and not what the case remembers. Comparing them is the
 * caller's business: for an item's own bytes the case's recorded hash is the thing to check against,
 * and for extracted text there is nothing to compare to, because no hash of the text was ever taken.
 */
public class ItemFileWriter {

    /** How much to move per read. Large enough to keep the digests fed, small enough to be nothing. */
    private static final int BUFFER = 64 * 1024;

    private static final String PART_SUFFIX = ".part";

    /** What one write produced. */
    public static final class Written {

        private final Path path;
        private final long bytes;
        private final String md5;
        private final String sha256;

        Written(Path path, long bytes, String md5, String sha256) {
            this.path = path;
            this.bytes = bytes;
            this.md5 = md5;
            this.sha256 = sha256;
        }

        public Path getPath() {
            return path;
        }

        public long getBytes() {
            return bytes;
        }

        public String getMd5() {
            return md5;
        }

        public String getSha256() {
            return sha256;
        }
    }

    /**
     * Writes an item's own bytes.
     *
     * @throws IOException
     *             when the evidence cannot be read or the destination cannot be written; nothing is
     *             left at the destination
     */
    public Written writeContent(IItem item, Path destination) throws IOException {
        try (InputStream source = item.getBufferedInputStream()) {
            return write(source, destination);
        }
    }

    /**
     * Writes text as UTF-8.
     *
     * <p>
     * The charset is explicit and it is the one the result declares. Text extracted from evidence
     * carries whatever alphabet the material was written in, and a file written in the platform
     * default is a file that reads correctly on the machine that produced it and nowhere else.
     */
    public Written writeText(String text, Path destination) throws IOException {
        try (InputStream source = new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            return write(source, destination);
        }
    }

    private Written write(InputStream source, Path destination) throws IOException {
        Path partial = destination.resolveSibling(destination.getFileName() + PART_SUFFIX);
        Files.createDirectories(destination.getParent());
        MessageDigest md5 = digest("MD5");
        MessageDigest sha256 = digest("SHA-256");
        long total = 0;
        try {
            try (OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = source.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    md5.update(buffer, 0, read);
                    sha256.update(buffer, 0, read);
                    total += read;
                }
            }
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // Whatever went wrong, the half-written file must not survive under a name that claims
            // to be the item.
            try {
                Files.deleteIfExists(partial);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return new Written(destination, total, hex(md5.digest()), hex(sha256.digest()));
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            // MD5 and SHA-256 are required of every Java platform; absence is not a runtime condition.
            throw new IllegalStateException(algorithm + " is not available on this JVM", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
