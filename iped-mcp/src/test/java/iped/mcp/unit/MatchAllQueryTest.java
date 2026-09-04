package iped.mcp.unit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import iped.mcp.query.PagedSearcher;

/**
 * Which spellings mean "every item" (and therefore never reach the term dictionary).
 *
 * <p>
 * The defect behind this: an agent asked for one page of a bookmark and wrote {@code "*"} as the
 * query, because the tool required one. To the parser that is a wildcard over {@code name} and
 * {@code content} under {@code SCORING_BOOLEAN_REWRITE} — one boolean clause per term in the whole
 * index, with the clause ceiling raised to {@code Integer.MAX_VALUE} so nothing fails and it simply
 * takes minutes. {@code *:*} selects every item outright.
 *
 * <p>
 * The recognition is deliberately narrow. Anything beyond an expression that is <i>only</i> a star
 * belongs to the parser: guessing at {@code name:*} or {@code * AND x} would mean this server
 * deciding what the examiner asked, which is worse than a slow query.
 */
public class MatchAllQueryTest {

    @Test
    public void bothSpellingsOfEverythingAreRecognized() {
        assertTrue(PagedSearcher.isMatchAllExpression("*:*"));
        assertTrue(PagedSearcher.isMatchAllExpression("*"));
    }

    @Test
    public void surroundingWhitespaceDoesNotHideThem() {
        assertTrue(PagedSearcher.isMatchAllExpression("  *  "));
        assertTrue(PagedSearcher.isMatchAllExpression("\t*:*\n"));
    }

    @Test
    public void anythingThatIsMoreThanAStarIsLeftToTheParser() {
        // Each of these means something the server must not decide on the examiner's behalf.
        assertFalse(PagedSearcher.isMatchAllExpression("name:*"));
        assertFalse(PagedSearcher.isMatchAllExpression("* AND contract"));
        assertFalse(PagedSearcher.isMatchAllExpression("contract *"));
        assertFalse(PagedSearcher.isMatchAllExpression("*a*"));
        assertFalse(PagedSearcher.isMatchAllExpression("**"));
        assertFalse(PagedSearcher.isMatchAllExpression("*:"));
    }

    @Test
    public void nothingAtAllIsNotEverything() {
        // An empty expression keeps the engine's own handling; only a written star is rewritten here.
        assertFalse(PagedSearcher.isMatchAllExpression(""));
        assertFalse(PagedSearcher.isMatchAllExpression("   "));
        assertFalse(PagedSearcher.isMatchAllExpression(null));
    }

    @Test
    public void theCanonicalSpellingIsTheOneTheServerSubstitutes() {
        // What iped_search fills in when a bookmark comes with no query, and what a bare * is
        // reported as having been run as.
        assertTrue(PagedSearcher.isMatchAllExpression(PagedSearcher.MATCH_ALL));
        assertFalse("the substitute must not itself be the expensive spelling", "*".equals(PagedSearcher.MATCH_ALL));
    }
}
