package iped.mcp.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * The line exchanged before the JSON-RPC starts, and the only thing standing between a connection
 * and the tool surface (FR-013).
 *
 * <pre>
 * client → IPED-MCP/1 &lt;secret&gt; [&lt;claimed-operator&gt;]
 * server → IPED-MCP/1 OK &lt;sessionId&gt;      or      IPED-MCP/1 DENIED
 * </pre>
 *
 * <p>
 * <b>Why this is not part of the protocol.</b> If the secret travelled in {@code initialize}, the
 * dispatcher would gain an authenticated-or-not state and every tool path would have to consult it —
 * and the first one that forgot would be a leak. Out here, a connection that fails the handshake
 * never reaches the dispatcher at all, which is what FR-013 asks for: no tool answers, and nothing
 * reveals whether cases even exist.
 *
 * <p>
 * Charset is explicit in both directions, as Principle V requires of anything that varies by
 * machine. The comparison is constant-time so that response latency does not become an oracle for
 * how much of the secret was right.
 */
public final class HandshakeCodec {

    public static final String PROTOCOL = "IPED-MCP/1";
    private static final String OK = "OK";
    private static final String DENIED = "DENIED";

    /** What a client sent, once it has been accepted. */
    public static final class Accepted {

        private final String claimedOperator;

        Accepted(String claimedOperator) {
            this.claimedOperator = claimedOperator;
        }

        /** Unverified. The secret proves the connection was authorized, not who is at the keyboard. */
        public String getClaimedOperator() {
            return claimedOperator;
        }
    }

    private HandshakeCodec() {
    }

    /**
     * Reads and checks the opening line.
     *
     * @return what the client declared, or {@code null} when the connection must be refused
     */
    public static Accepted accept(InputStream in, String expectedSecret) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        // Three fields at most: the claimed operator is optional and may not contain a space.
        String[] parts = line.trim().split(" ", 3);
        if (parts.length < 2 || !PROTOCOL.equals(parts[0])) {
            return null;
        }
        if (!constantTimeEquals(parts[1], expectedSecret)) {
            return null;
        }
        return new Accepted(parts.length >= 3 ? parts[2] : null);
    }

    /** Answers a connection that authenticated. */
    public static void writeAccepted(OutputStream out, String sessionId) throws IOException {
        write(out, PROTOCOL + " " + OK + " " + sessionId);
    }

    /**
     * Answers a connection that did not.
     *
     * <p>
     * One word, and nothing else. Not why, not how close the secret was, not whether the server has
     * any case open — a refusal that explains itself is a refusal that helps.
     */
    public static void writeDenied(OutputStream out) throws IOException {
        write(out, PROTOCOL + " " + DENIED);
    }

    private static void write(OutputStream out, String line) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    /**
     * Compares without short-circuiting on the first differing character.
     *
     * <p>
     * The length is allowed to leak — it always is, through the size of the packet — but the
     * position of the first mismatch is not, because that is what turns repeated timing into a
     * character-by-character search.
     */
    static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        byte[] a = actual.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        int difference = a.length ^ b.length;
        for (int i = 0; i < a.length; i++) {
            difference |= a[i] ^ b[i % Math.max(b.length, 1)];
        }
        return difference == 0 && b.length > 0;
    }
}
