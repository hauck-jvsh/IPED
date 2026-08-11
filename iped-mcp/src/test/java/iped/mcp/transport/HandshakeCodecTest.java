package iped.mcp.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;


/** The line that stands between a connection and the tool surface (FR-013). */
public class HandshakeCodecTest {

    private static InputStream line(String text) {
        return new ByteArrayInputStream((text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aWellFormedLineWithTheRightSecretIsAccepted() throws Exception {
        HandshakeCodec.Accepted accepted = HandshakeCodec.accept(line("IPED-MCP/1 s3cr3t"), "s3cr3t");
        assertNotNull(accepted);
        assertNull("no operator was claimed, and none may be invented", accepted.getClaimedOperator());
    }

    @Test
    public void theClaimedOperatorIsOptionalAndCarriedThrough() throws Exception {
        HandshakeCodec.Accepted accepted = HandshakeCodec.accept(line("IPED-MCP/1 s3cr3t perito.silva"), "s3cr3t");
        assertNotNull(accepted);
        assertEquals("perito.silva", accepted.getClaimedOperator());
    }

    @Test
    public void aWrongSecretIsRefused() throws Exception {
        assertNull(HandshakeCodec.accept(line("IPED-MCP/1 wrong"), "s3cr3t"));
    }

    @Test
    public void aMissingSecretIsRefused() throws Exception {
        assertNull(HandshakeCodec.accept(line("IPED-MCP/1"), "s3cr3t"));
    }

    @Test
    public void anUnknownProtocolIsRefused() throws Exception {
        assertNull(HandshakeCodec.accept(line("SOMETHING/9 s3cr3t"), "s3cr3t"));
    }

    @Test
    public void anEmptyConnectionIsRefused() throws Exception {
        assertNull(HandshakeCodec.accept(new ByteArrayInputStream(new byte[0]), "s3cr3t"));
    }

    @Test
    public void aServerWithNoSecretAcceptsNothing() throws Exception {
        // Belt and braces: SocketTransport refuses to bind without a secret, so this line should be
        // unreachable. If it ever becomes reachable, it must not mean "everyone is welcome".
        assertNull(HandshakeCodec.accept(line("IPED-MCP/1 anything"), null));
        assertNull(HandshakeCodec.accept(line("IPED-MCP/1 "), ""));
    }

    @Test
    public void theRefusalSaysNothingBeyondTheWord() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HandshakeCodec.writeDenied(out);
        String answer = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        assertEquals("IPED-MCP/1 DENIED", answer);
        assertFalse("a refusal that explains itself is a refusal that helps",
                answer.toLowerCase().contains("secret") || answer.toLowerCase().contains("case"));
    }

    @Test
    public void comparisonDoesNotShortCircuitOnTheFirstDifference() {
        // Not a timing measurement — those are unreliable in a unit test. This pins the contract the
        // implementation has to keep: equality is decided over the whole input, and a prefix of the
        // real secret is worth no more than anything else.
        assertTrue(HandshakeCodec.constantTimeEquals("abcdef", "abcdef"));
        assertFalse(HandshakeCodec.constantTimeEquals("abcdeX", "abcdef"));
        assertFalse("a correct prefix must not be accepted", HandshakeCodec.constantTimeEquals("abc", "abcdef"));
        assertFalse(HandshakeCodec.constantTimeEquals("Xbcdef", "abcdef"));
        assertFalse(HandshakeCodec.constantTimeEquals("", ""));
    }
}
