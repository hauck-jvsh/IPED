package iped.mcp.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;

/**
 * The text of an item is its content, not its source (FR-021, FR-022).
 *
 * <p>
 * Written after a field report on a WhatsApp case. Asking for the text of a chat returned the markup
 * of its preview — {@code <!DOCTYPE html>}, meta tags, a base64 favicon — with the character ceiling
 * spent before a single message. The cause was ours: the item's media type was pinned as the type to
 * parse as, and a type IPED <i>assigns</i> to a decoded chat has no parser of its own, so
 * {@code StandardParser} fell through to the raw-string parser. That fallback never fails, which is
 * why nothing said anything was wrong.
 *
 * <p>
 * These tests do not name a decoder. They ask the case which items it has of each kind, because a
 * case may carry chats from any of them and a suite that hardcodes WhatsApp stops protecting the
 * property the moment the evidence changes.
 */
public class ItemTextTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void anItemWhoseTypeHasNoParserIsStillParsedByTheRightOne() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode chat = aChatContainer(caseId);
        Assume.assumeTrue("this case carries no chat containers", chat != null);

        JsonNode text = session.call("iped_item_text", "case_id", caseId, "item_id",
                chat.path("item_id").asInt(), "max_chars", 4000);
        Assume.assumeTrue("the chat container selected has no text of its own", text.path("available").asBoolean());

        String extracted = text.path("text").asText();
        assertFalse("the text of an item must not be the markup of its preview: " + head(extracted),
                extracted.trim().startsWith("<!DOCTYPE") || extracted.trim().startsWith("<html"));
        assertFalse("the raw-string fallback means no parser understood the item; it must not be the answer "
                + "when one does", "RawStringParser".equals(text.path("extracted_by").asText()));
        // Reading a base64 data URI as if it were evidence is the symptom this fixes.
        assertFalse("markup leaked into the extracted text: " + head(extracted),
                extracted.contains("data:image/png;base64"));
    }

    @Test
    public void ipedsOwnMediaTypesResolveToAParserRatherThanToTheFallback() {
        // The registration McpServerMain performs at startup, seen from the outside. IPED declares
        // application/x-whatsapp-chat as a subtype of text/html in conf/CustomSignatures.xml; with that
        // registered, Tika walks the hierarchy and the chat parses as HTML. Without it every one of
        // these types lands on the raw-string parser, which succeeds on anything — so this asserts on
        // the parser that ran, not on whether text came back.
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode chat = aChatContainer(caseId);
        Assume.assumeTrue("this case carries no chat containers", chat != null);

        JsonNode text = session.call("iped_item_text", "case_id", caseId, "item_id", chat.path("item_id").asInt(),
                "max_chars", 500);
        Assume.assumeTrue("the chat container selected has no text of its own", text.path("available").asBoolean());

        assertFalse("a type IPED assigns must resolve through its declared hierarchy, not fall back",
                "RawStringParser".equals(text.path("extracted_by").asText()));
        // With the registration in force the item's own type is the one that parses, so there is
        // nothing to declare about having parsed as another. If this ever starts appearing, the
        // registration is not reaching the server and the fallback is carrying the feature.
        assertFalse("the type should not have needed detecting: " + text.path("parsed_as_note").asText(),
                text.has("parsed_as"));
    }

    @Test
    public void aDecodedRecordSaysWhereItsContentIsInsteadOfDenyingIt() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode record = anItemMatching(caseId, "isDecodedData:true", false);
        Assume.assumeTrue("this case has no decoded records", record != null);

        int itemId = record.path("item_id").asInt();
        JsonNode text = session.call("iped_item_text", "case_id", caseId, "item_id", itemId);
        Assume.assumeTrue("this decoded record does have its own text", !text.path("available").asBoolean());

        String reason = text.path("reason").asText();
        assertTrue("a decoded record must be named as one, not guessed at: " + reason,
                reason.contains("decoded data"));
        assertFalse("the old answer listed hypotheses; this one must not", reason.contains("it may be"));

        String remedy = text.path("remedy").asText();
        assertTrue("the remedy must point at the tool that does hold the content: " + remedy,
                remedy.contains("iped_item_metadata"));
        // The pointer has to be worth following: the tool it names must actually answer.
        JsonNode metadata = session.call("iped_item_metadata", "case_id", caseId, "item_id", itemId);
        assertTrue("the remedy sends the agent to iped_item_metadata, which must have something to give",
                metadata.path("available").asBoolean());
    }

    @Test
    public void anOrdinaryDocumentIsUnaffected() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode pdf = anItemMatching(caseId, "contentType:\"application/pdf\"", true);
        Assume.assumeTrue("this case has no PDF with text", pdf != null);

        JsonNode text = session.call("iped_item_text", "case_id", caseId, "item_id", pdf.path("item_id").asInt(),
                "max_chars", 2000);
        Assume.assumeTrue("the PDF selected has no extractable text", text.path("available").asBoolean());

        assertTrue("a PDF must still be read by the PDF parser, not by detection fallout",
                "PDFParser".equals(text.path("extracted_by").asText()));
        assertFalse("its type was not overridden, so nothing is declared about parsing as another type",
                text.has("parsed_as"));
    }

    /**
     * A conversation container of this case — the item that holds messages, whatever decoder produced
     * it.
     *
     * <p>
     * Found the way the skill teaches an agent to find anything case-specific: ask the case. An
     * aggregation over the media types inside the chat categories names them, and the container types
     * are the ones IPED spells with {@code chat} in the type itself. What is deliberately not done
     * here is naming a decoder: a suite that looks for WhatsApp stops protecting this property the
     * moment the evidence is a different app. Message records are excluded by the same rule — they
     * are {@code message/...}, and they have no bytes of their own.
     *
     * @return the item view, or {@code null} when this case has no conversation containers
     */
    private JsonNode aChatContainer(String caseId) {
        JsonNode buckets = session.call("iped_aggregate", "case_id", caseId, "dimension", "contentType", "query",
                "category:\"Chats\" OR category:\"Instant Messages\"", "max_buckets", 30).path("buckets");
        for (JsonNode bucket : buckets) {
            String type = bucket.path("value").asText();
            if (!type.contains("chat") || type.startsWith("message/")) {
                continue;
            }
            JsonNode items = session.call("iped_search", "case_id", caseId, "query",
                    "contentType:\"" + type + "\"", "page_size", 3, "include_snippets", false).path("items");
            if (items.size() > 0) {
                return items.get(0);
            }
        }
        return null;
    }

    /**
     * The first item matching a query, optionally one large enough to plausibly carry text.
     *
     * @return the item view, or {@code null} when this case has none
     */
    private JsonNode anItemMatching(String caseId, String query, boolean wantSize) {
        JsonNode items = session.call("iped_search", "case_id", caseId, "query", query, "page_size", 20,
                "include_snippets", false).path("items");
        for (JsonNode item : items) {
            if (!wantSize || item.path("size").asLong(0) > 1000) {
                return item;
            }
        }
        return items.size() > 0 ? items.get(0) : null;
    }

    private static String head(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120);
    }
}
