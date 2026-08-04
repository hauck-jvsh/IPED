package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import iped.mcp.query.FieldVocabulary;

/**
 * The edit-distance core behind field-name suggestion (FR-008, SC-006).
 *
 * <p>
 * This runs without a case because the behaviour it protects is pure: the ranking that turns
 * {@code mediaType} into {@code contentType}. The end-to-end path over a real index is covered by
 * {@code VocabularyTest}.
 */
public class VocabularySuggestionTest {

    @Test
    public void identicalStringsHaveDistanceZero() {
        assertEquals(0, FieldVocabulary.levenshtein("contentType", "contentType"));
    }

    @Test
    public void distanceCountsSingleEdits() {
        assertEquals(1, FieldVocabulary.levenshtein("size", "sizes"));
        assertEquals(1, FieldVocabulary.levenshtein("hash", "hasi"));
        assertEquals(2, FieldVocabulary.levenshtein("created", "craeted"));
    }

    @Test
    public void distanceIsSymmetric() {
        assertEquals(FieldVocabulary.levenshtein("mediatype", "contenttype"),
                FieldVocabulary.levenshtein("contenttype", "mediatype"));
    }

    @Test
    public void emptyStringCostsTheOtherLength() {
        assertEquals(4, FieldVocabulary.levenshtein("", "hash"));
        assertEquals(4, FieldVocabulary.levenshtein("hash", ""));
    }

    @Test
    public void theClassicMistakesResolveToTheRightField() {
        // These are the names a model brings from another product's vocabulary. Every one of them
        // returns zero results rather than an error, which is why the suggestion matters.
        assertTopSuggestion("mediaType", "contentType");
        assertTopSuggestion("mimeType", "contentType");
        assertTopSuggestion("filename", "name");
        assertTopSuggestion("isDeleted", "deleted");
    }

    @Test
    public void aNearTypoResolvesToTheIntendedField() {
        assertTopSuggestion("contenType", "contentType");
        assertTopSuggestion("creatd", "created");
    }

    @Test
    public void suggestionsAreCappedAtTheRequestedLimit() {
        assertTrue(vocabulary().similar("xyz", 3).size() <= 3);
    }

    private static void assertTopSuggestion(String asked, String expected) {
        java.util.List<String> similar = vocabulary().similar(asked, 8);
        assertTrue("no suggestion offered for '" + asked + "'", !similar.isEmpty());
        assertTrue("'" + expected + "' must be suggested for '" + asked + "', got " + similar,
                similar.contains(expected));
        assertEquals("'" + expected + "' should rank first for '" + asked + "', got " + similar, expected,
                similar.get(0));
    }

    /** A vocabulary shaped like a real 4.x index. */
    private static FieldVocabulary vocabulary() {
        return FieldVocabulary.of("id", "parentId", "parentIds", "evidenceUUID", "name", "ext", "type",
                "contentType", "category", "size", "path", "created", "modified", "accessed", "changed", "hash",
                "deleted", "carved", "subitem", "isDir", "isRoot", "hasChildren", "timeStamp", "timeEvent");
    }
}
