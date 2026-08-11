package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.TransportMode;
import iped.mcp.session.CasePool;
import iped.mcp.session.Session;
import iped.mcp.session.WriteClaims;
import iped.mcp.transport.Transport;

/**
 * FR-022, FR-023 and SC-008: what is exposed can be established from inside the session.
 *
 * <p>
 * A security posture that can only be verified by reading the configuration file or the operating
 * system is one the examiner has to take on trust, and the examiner is the person who signs the
 * report. So the server answers for itself, and answers with the network transport inactive too —
 * the absence of a listening endpoint is the fact most worth confirming.
 */
public class PostureVisibilityTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> posture(Map<String, Object> info) {
        return (Map<String, Object>) info.get("posture");
    }

    @Test
    public void theLocalTransportReportsNoEndpointAndNoClaim() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        try (Session session = new Session(config)) {
            Map<String, Object> posture = posture(session.describe());
            assertEquals("STDIO", posture.get("transport"));
            assertNull("nothing is listening, and that is the fact worth confirming",
                    posture.get("listen_endpoint"));
            assertNull(posture.get("origin"));

            @SuppressWarnings("unchecked")
            Map<String, Object> operator = (Map<String, Object>) session.describe().get("operator");
            assertNotNull(operator.get("authoritative"));
            assertNull("no claim was made over stdio, and none may be invented", operator.get("claimed"));
        }
    }

    @Test
    public void theDeclaredWriteRootsAreVisibleWithTheirState() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setExportRoots(Collections.singletonList(temp.newFolder("laudos").getAbsolutePath()));
        try (Session session = new Session(config)) {
            Map<String, Object> posture = posture(session.describe());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> roots = (List<Map<String, Object>>) posture.get("write_roots");
            assertEquals(1, roots.size());
            assertEquals("USABLE", roots.get(0).get("state"));
            assertNotNull("the resolved path is what the rule actually compares against",
                    roots.get(0).get("resolved"));
            assertEquals(Boolean.TRUE, posture.get("write_roots_are_declared"));
        }
    }

    @Test
    public void anUndeclaredRootIsReportedAsTheDefaultRatherThanAsNothing() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        try (Session session = new Session(config)) {
            Map<String, Object> posture = posture(session.describe());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> roots = (List<Map<String, Object>>) posture.get("write_roots");
            assertFalse("an installation that declares nothing is still confined", roots.isEmpty());
            assertEquals("and the examiner can tell it was not their choice", Boolean.FALSE,
                    posture.get("write_roots_are_declared"));
        }
    }

    @Test
    public void aNetworkSessionReportsItsEndpointAndSaysTheChannelIsNotProtected() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        config.setTransport(TransportMode.SOCKET);
        config.setListenEndpoint("127.0.0.1", 8737);
        CasePool pool = new CasePool();
        try (Session session = new Session(config, pool, new WriteClaims(), Transport.Kind.SOCKET, "10.0.0.5:51000",
                "perito.silva")) {
            Map<String, Object> posture = posture(session.describe());
            assertEquals("SOCKET", posture.get("transport"));
            assertEquals("127.0.0.1:8737", posture.get("listen_endpoint"));
            assertEquals("10.0.0.5:51000", posture.get("origin"));
            assertEquals("the examiner has to know this before opening a case", Boolean.FALSE,
                    posture.get("channel_protected"));

            String warnings = String.valueOf(session.getWarnings());
            assertTrue("the opening warning must say evidence content crosses the network",
                    warnings.contains("network connection"));
            assertTrue("and that the channel is unprotected", warnings.contains("not encrypted"));
        }
        pool.close();
    }

    @Test
    public void aClaimedIdentityIsNeverPresentedAsAVerifiedOne() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        CasePool pool = new CasePool();
        try (Session session = new Session(config, pool, new WriteClaims(), Transport.Kind.SOCKET, "10.0.0.5:51000",
                "perito.silva")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> operator = (Map<String, Object>) session.describe().get("operator");
            assertEquals("perito.silva", operator.get("claimed"));
            assertEquals(Boolean.FALSE, operator.get("claimed_is_verified"));
            assertFalse("the two must not be merged", operator.get("claimed").equals(operator.get("authoritative")));

            // And the word travels with the value into the trail, which is what survives being
            // copied into a report (FR-032).
            String recorded = session.getOperator().describe();
            assertTrue(recorded.contains("perito.silva"));
            assertTrue("a claim that reads as fact is worse than no claim", recorded.contains("unverified"));
        }
        pool.close();
    }
}
