package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.queryparser.flexible.standard.StandardQueryParser;
import org.apache.lucene.search.Query;

import org.junit.Test;

import iped.mcp.query.FieldNames;
import iped.mcp.query.FieldVocabulary;

/**
 * Writing a namespaced field name into a query expression (the defect behind the QUERY_SYNTAX /
 * UNKNOWN_FIELD loop).
 *
 * <p>
 * The parser is exercised directly here, with the same configuration {@code QueryBuilder} gives it,
 * so the suite proves the property that matters: <b>the spelling this server hands out is one the
 * parser accepts</b>. Asserting the string shape alone would pass while the form remained
 * unparseable.
 */
public class FieldNamesTest {

    @Test
    public void aPlainNameIsLeftAlone() {
        assertFalse(FieldNames.needsEscaping("contentType"));
        assertEquals("contentType", FieldNames.toQueryForm("contentType"));
    }

    @Test
    public void aColonInsideANameIsEscaped() {
        assertTrue(FieldNames.needsEscaping("p2p:fileType"));
        assertEquals("p2p\\:fileType", FieldNames.toQueryForm("p2p:fileType"));
        assertEquals("ai\\:csamDetector\\:status", FieldNames.toQueryForm("ai:csamDetector:status"));
    }

    @Test
    public void otherSyntaxCharactersInANameAreEscapedToo() {
        // Parser-produced metadata names are not curated; whitespace and brackets do occur.
        assertEquals("Content\\-Type", FieldNames.toQueryForm("Content-Type"));
        assertEquals("odd\\ name", FieldNames.toQueryForm("odd name"));
    }

    @Test
    public void theQueryFormReadsBackAsThePlainName() {
        // Round trip, because an agent hands the spelling it just used to the next call: a parameter
        // that takes a name has to accept the form the parameter that takes an expression required.
        assertEquals("p2p:fileType", FieldNames.fromQueryForm("p2p\\:fileType"));
        assertEquals("ai:csamDetector:status", FieldNames.fromQueryForm("ai\\:csamDetector\\:status"));
        assertEquals("odd name", FieldNames.fromQueryForm("odd\\ name"));
        for (String name : new String[] { "p2p:fileType", "contentType", "Content-Type", "odd name" }) {
            assertEquals(name, FieldNames.fromQueryForm(FieldNames.toQueryForm(name)));
        }
    }

    @Test
    public void aNameWithNoEscapeSurvivesUnescapingUntouched() {
        assertSame("contentType", FieldNames.fromQueryForm("contentType"));
        // A backslash that escapes nothing the parser cares about is part of the name, not syntax.
        assertEquals("odd\\name", FieldNames.fromQueryForm("odd\\name"));
    }

    @Test
    public void theFormHandedOutIsOneTheParserAccepts() throws Exception {
        assertParsesAsField("p2p:fileType", "mp3");
        assertParsesAsField("ufed:UserID", "12345");
        assertParsesAsField("ai:csamDetector:status", "done");
        assertParsesAsField("dc:title", "contract");
    }

    @Test
    public void theUnescapedFormIsWhatFails() {
        // The premise of the whole fix: pasting the plain name into an expression is a syntax
        // error, and quoting it instead is not an alternative.
        assertDoesNotParse("p2p:fileType:\"mp3\"");
        assertDoesNotParse("\"p2p:fileType\":\"mp3\"");
    }

    @Test
    public void aKnownFieldNameInAnExpressionIsRepaired() {
        assertEquals("p2p\\:fileType:\"mp3\"", escape("p2p:fileType:\"mp3\""));
        assertEquals("p2p\\:shared:true", escape("p2p:shared:true"));
    }

    @Test
    public void everyOccurrenceInABooleanExpressionIsRepaired() {
        assertEquals("p2p\\:fileType:\"mp3\" OR p2p\\:fileType:\"mp4\"",
                escape("p2p:fileType:\"mp3\" OR p2p:fileType:\"mp4\""));
        assertEquals("(p2p\\:shared:true AND ufed\\:UserID:12345)", escape("(p2p:shared:true AND ufed:UserID:12345)"));
    }

    @Test
    public void theLongestMatchingNameWins() {
        // 'ai:csamDetector' and 'ai:csamDetector:status' both exist; taking the shorter one would
        // leave a stray colon and produce a different query.
        assertEquals("ai\\:csamDetector\\:status:done", escape("ai:csamDetector:status:done"));
    }

    @Test
    public void repairIsIdempotent() {
        String once = escape("p2p:fileType:\"mp3\"");
        assertEquals(once, escape(once));
    }

    @Test
    public void aQuotedPhraseIsNeverRewritten() {
        // The phrase is the evidence being searched for, not syntax. Rewriting inside it would
        // change what the examiner asked to find.
        String expression = "content:\"p2p:fileType: see attachment\"";
        assertSame(expression, escape(expression));
    }

    @Test
    public void textThatIsNotUsedAsAFieldIsNeverRewritten() {
        // Without a following colon the text is a term, not a field reference.
        assertSame("p2p:fileType", escape("p2p:fileType"));
        assertSame("name:relatorio", escape("name:relatorio"));
    }

    @Test
    public void aNameThisCaseDoesNotHaveIsNeverRewritten() {
        // Only the vocabulary of the case in front of us licenses a rewrite; guessing would invent
        // a field restriction nobody asked for.
        assertSame("unknown:ns:value", escape("unknown:ns:value"));
    }

    @Test
    public void vocabularyReportsNamesUnderANamespace() {
        FieldVocabulary vocabulary = vocabulary();
        assertTrue(vocabulary.namesUnder("p2p").contains("p2p:fileType"));
        assertTrue(vocabulary.namesUnder("p2p").contains("p2p:shared"));
        assertTrue("a plain name is not a namespace", vocabulary.namesUnder("contentType").isEmpty());
        assertTrue(vocabulary.namesNeedingEscape().contains("ufed:UserID"));
        assertFalse(vocabulary.namesNeedingEscape().contains("contentType"));
    }

    private static String escape(String expression) {
        return FieldNames.escapeKnownFieldNames(expression, vocabulary());
    }

    private static void assertParsesAsField(String field, String value) throws Exception {
        String expression = FieldNames.toQueryForm(field) + ":\"" + value + "\"";
        Query query = parser().parse(expression, null);
        assertEquals("the escaped form must reach the parser as one field name: " + expression,
                field + ":" + value, query.toString());
    }

    private static void assertDoesNotParse(String expression) {
        try {
            parser().parse(expression, null);
            org.junit.Assert.fail("expected '" + expression + "' to be rejected by the parser");
        } catch (Exception expected) {
            // The premise holds: this is why the escaping exists.
        }
    }

    /** Configured as {@code QueryBuilder} configures it, minus what needs an open case. */
    private static StandardQueryParser parser() {
        StandardQueryParser parser = new StandardQueryParser(new WhitespaceAnalyzer());
        parser.setMultiFields(new String[] { "name", "content" });
        return parser;
    }

    /** A vocabulary shaped like a real 4.x index, namespaced names included. */
    private static FieldVocabulary vocabulary() {
        return FieldVocabulary.of("id", "name", "path", "ext", "type", "contentType", "category", "size", "hash",
                "created", "modified", "deleted", "carved", "p2p:fileType", "p2p:shared", "p2p:hashSha1",
                "ufed:UserID", "dc:title", "image:Width", "ai:csamDetector", "ai:csamDetector:status");
    }
}
