package iped.mcp.query;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.task.index.IndexItem;
import iped.mcp.config.McpServerConfig;
import iped.mcp.item.ContentAccess;
import iped.mcp.session.OpenCase;

/**
 * Builds the excerpt that shows <i>why</i> an item matched (FR-015).
 *
 * <p>
 * Without it the agent gets a list of file names and has to open every one to understand the hit —
 * exactly the N-calls pattern this feature exists to remove.
 *
 * <p>
 * <b>The cost, stated plainly.</b> The indexed {@code content} field is indexed but not stored, so
 * the text has to be re-extracted from the item to be highlighted. That is per-item work, which is
 * why every snippet is taken under three budgets: how many items per page get one, how much text is
 * read per item, and how long the whole page may spend. Items past a budget come back with the
 * snippet declared absent and the reason given (FR-022) — never blank, which would read as "this
 * item has no matching text" and be wrong.
 */
public class SnippetBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnippetBuilder.class);

    private final McpServerConfig config;
    private final ContentAccess contentAccess;

    public SnippetBuilder(McpServerConfig config, ContentAccess contentAccess) {
        this.config = config;
        this.contentAccess = contentAccess;
    }

    /** Per-page budget holder. One instance per search page. */
    public static class Budget {
        private final long deadline;
        private int remainingItems;

        Budget(int maxItems, long budgetMs) {
            this.remainingItems = maxItems;
            this.deadline = System.currentTimeMillis() + budgetMs;
        }

        boolean consume() {
            if (remainingItems <= 0 || System.currentTimeMillis() >= deadline) {
                return false;
            }
            remainingItems--;
            return true;
        }
    }

    public Budget newBudget() {
        return new Budget(config.getSnippetMaxItemsPerPage(), config.getSnippetBudgetMs());
    }

    /**
     * @param query
     *            the rewritten IPED query, whose terms on the content field are what get scored
     * @return the excerpt, or {@code null} when the item has no matching text or a budget ran out
     */
    public String build(OpenCase openCase, IItem item, Query query, Analyzer analyzer, Budget budget) {
        if (query == null || item == null || item.isDir() || !budget.consume()) {
            return null;
        }
        ContentAccess.ExtractedText extracted = contentAccess.extractText(openCase, item,
                config.getSnippetMaxTextBytes());
        if (extracted.failure != null || extracted.text.isEmpty()) {
            return null;
        }
        try {
            QueryScorer scorer = new QueryScorer(query, IndexItem.CONTENT);
            Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter("", ""), scorer);
            highlighter.setTextFragmenter(new SimpleFragmenter(config.getSnippetLength()));
            highlighter.setMaxDocCharsToAnalyze(config.getSnippetMaxTextBytes());
            String fragment = highlighter.getBestFragment(analyzer, IndexItem.CONTENT, extracted.text);
            return fragment == null ? null : fragment.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            LOGGER.debug("Snippet could not be built for item {}", item.getId(), e);
            return null;
        }
    }
}
