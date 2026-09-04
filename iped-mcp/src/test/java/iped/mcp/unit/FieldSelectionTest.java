package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import iped.mcp.item.FieldSelection;
import iped.mcp.protocol.McpError;
import iped.mcp.query.FieldVocabulary;

/**
 * Resolving the field names of a projection, before any item is read.
 *
 * <p>
 * The property under test is the one that keeps a projection from producing a wrong negative: a name
 * this case does not have must fail the call. Answering it with items that merely lack the field is
 * indistinguishable from items that do not have it, and that is the shape of a confident, wrong
 * "no evidence found".
 */
public class FieldSelectionTest {

    private static final FieldVocabulary CASE = FieldVocabulary.of("id", "name", "path", "size", "contentType",
            "category", "hash", "parentId", "isDir", "isRoot", "deleted", "carved", "subitem", "hasChildren",
            "evidenceUUID", "created", "modified", "p2p:fileType", "ufed:UserID");

    @Test
    public void aNameTheCaseHasResolvesToItself() {
        FieldSelection selection = FieldSelection.resolve(CASE, Arrays.asList("name", "hash", "p2p:fileType"));

        assertEquals(Arrays.asList("name", "hash", "p2p:fileType"), selection.askedFields());
        assertTrue("nothing was rewritten, so nothing is declared", selection.renamedFields().isEmpty());
        assertTrue(selection.storedFields().containsAll(Arrays.asList("name", "hash", "p2p:fileType")));
        assertEquals("only what was asked for is read", 3, selection.storedFields().size());
    }

    @Test
    public void theKeysThisServerPublishesAreAcceptedBack() {
        // Everything an agent receives in an item can be handed straight back in a request. Refusing
        // content_type after having published it is a trap laid by the server itself.
        FieldSelection selection = FieldSelection.resolve(CASE,
                Arrays.asList("content_type", "parent_id", "is_dir", "has_children", "evidence_uuid", "item_id"));

        assertEquals("contentType", selection.renamedFields().get("content_type"));
        assertEquals("parentId", selection.renamedFields().get("parent_id"));
        assertEquals("hasChildren", selection.renamedFields().get("has_children"));
        assertEquals("evidenceUUID", selection.renamedFields().get("evidence_uuid"));
        assertEquals("id", selection.renamedFields().get("item_id"));
        assertTrue(selection.storedFields().contains("contentType"));
    }

    @Test
    public void aNameSpelledForAQueryIsAcceptedHereToo() {
        // The agent that just escaped the colon for iped_search pastes the same spelling into the
        // next call. That backslash is query punctuation, not part of the name.
        FieldSelection selection = FieldSelection.resolve(CASE, Arrays.asList("p2p\\:fileType"));

        assertEquals("p2p:fileType", selection.renamedFields().get("p2p\\:fileType"));
        assertEquals(java.util.Collections.singleton("p2p:fileType"), selection.storedFields());
    }

    @Test
    public void aNameDifferingOnlyInCaseIsResolvedAndDeclared() {
        FieldSelection selection = FieldSelection.resolve(CASE, Arrays.asList("ContentType"));

        assertEquals("contentType", selection.renamedFields().get("ContentType"));
    }

    @Test
    public void theSameNameTwiceIsReadOnce() {
        FieldSelection selection = FieldSelection.resolve(CASE, Arrays.asList("name", "name", "hash"));

        assertEquals(Arrays.asList("name", "hash"), selection.askedFields());
    }

    @Test
    public void bookmarksAndSelectionAreAnsweredWithoutReadingAField() {
        FieldSelection selection = FieldSelection.resolve(CASE, Arrays.asList("bookmarks", "selected", "case_id"));

        assertTrue("these come from case state, not from the document", selection.storedFields().isEmpty());
        assertTrue(selection.renamedFields().isEmpty());
    }

    @Test
    public void anUnknownNameRefusesTheWholeCallWithTheNearNames() {
        try {
            FieldSelection.resolve(CASE, Arrays.asList("name", "mediaType"));
            fail("a name this case does not have must refuse the projection");
        } catch (McpError error) {
            assertEquals(McpError.UNKNOWN_FIELD, error.getCode());
            assertEquals(Arrays.asList("mediaType"), error.getDetails().get("unknown_fields"));
            assertEquals("the names that did resolve are named, so the retry is one call",
                    Arrays.asList("name"), error.getDetails().get("recognized_fields"));
            @SuppressWarnings("unchecked")
            List<String> similar = ((java.util.Map<String, List<String>>) error.getDetails().get("similar"))
                    .get("mediaType");
            assertTrue("contentType must be offered, got " + similar, similar.contains("contentType"));
            assertTrue("the refusal must say nothing was read: " + error.getRemedy(),
                    error.getRemedy().contains("Nothing was read"));
        }
    }

    @Test
    public void everyUnknownNameIsReportedAtOnce() {
        // Correcting a ten-field projection must not cost ten round trips.
        try {
            FieldSelection.resolve(CASE, Arrays.asList("mediaType", "filename", "hash"));
            fail("expected a refusal");
        } catch (McpError error) {
            assertEquals(Arrays.asList("mediaType", "filename"), error.getDetails().get("unknown_fields"));
        }
    }

    @Test
    public void aNamespacedNameCutAtItsColonIsAnsweredWithTheFullName() {
        // Asking for 'p2p' is not a typo: it is the name of a namespace, and the useful answer is the
        // full name rather than a near miss.
        try {
            FieldSelection.resolve(CASE, Arrays.asList("p2p"));
            fail("expected a refusal");
        } catch (McpError error) {
            assertEquals(McpError.UNKNOWN_FIELD, error.getCode());
            @SuppressWarnings("unchecked")
            List<String> under = ((java.util.Map<String, List<String>>) error.getDetails().get("namespaced_fields"))
                    .get("p2p");
            assertEquals(Arrays.asList("p2p:fileType"), under);
            assertTrue(error.getRemedy().contains("p2p:fileType"));
        }
    }

    @Test
    public void theTextOfAnItemIsNotAProjectableField() {
        // 'content' is indexed for searching and not stored. Reporting it as an absence in every item
        // would read as a case with no text in it.
        try {
            FieldSelection.resolve(CASE, Arrays.asList("content"));
            fail("expected a refusal");
        } catch (McpError error) {
            assertEquals(McpError.UNKNOWN_FIELD, error.getCode());
            assertTrue("the remedy must name the tool that does return text: " + error.getRemedy(),
                    error.getRemedy().contains("iped_item_text"));
        }
    }

    @Test
    public void aSnippetIsNotAFieldEither() {
        try {
            FieldSelection.resolve(CASE, Arrays.asList("snippet"));
            fail("expected a refusal");
        } catch (McpError error) {
            assertEquals(McpError.UNKNOWN_FIELD, error.getCode());
            assertTrue(error.getRemedy().contains("include_snippets"));
            assertFalse("and it must not be offered as a near name of something else",
                    error.getDetails().containsKey("similar"));
        }
    }
}
