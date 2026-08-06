package iped.mcp.integration;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.AfterClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;

/**
 * Scale behaviour on a ~10 M item case (SC-002, SC-015).
 *
 * <p>
 * <b>This suite is not optional and cannot be substituted by the small case.</b> The difference
 * between paging and materializing does not show up on a small case: an implementation that calls
 * {@code IPEDSearcher.searchAll()} passes every other suite in this module and then falls over on a
 * real collection. That failure mode is documented in R3, and this is the only place that catches
 * it.
 *
 * <p>
 * Skips when the large case is not configured. A skip here means SC-002 and SC-015 are unverified,
 * not satisfied.
 */
public class ScalePerformanceTest {

    /** SC-002: first page of a broad query. */
    private static final long FIRST_PAGE_BUDGET_MS = 5000;

    /** SC-015: open plus overview. */
    private static final long OPEN_AND_OVERVIEW_BUDGET_MS = 30000;

    /** SC-015: aggregation. */
    private static final long AGGREGATION_BUDGET_MS = 15000;

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    /**
     * What each measurement actually was, printed once at the end.
     *
     * <p>
     * A scale suite that only answers pass/fail cannot show a run drifting toward its budget until
     * the day it crosses it. These numbers are the evidence SC-002 and SC-015 are claimed on, so
     * they belong in the run's output rather than only in a failure message nobody sees while it
     * passes.
     */
    private static final List<String> MEASUREMENTS = new ArrayList<>();

    private static void measured(String what, long elapsedMs, long budgetMs, String scale) {
        MEASUREMENTS.add(String.format("  %-34s %7d ms   (budget %6d ms)   %s", what, elapsedMs, budgetMs, scale));
    }

    @AfterClass
    public static void reportMeasurements() {
        if (MEASUREMENTS.isEmpty()) {
            return;
        }
        // stdout is the protocol in the server and is forbidden there; here there is no protocol,
        // and surefire captures this into the test report where the evidence is wanted.
        StringBuilder report = new StringBuilder("\nSC-002 / SC-015 measurements on the large case:\n");
        MEASUREMENTS.forEach(line -> report.append(line).append('\n'));
        System.out.println(report);
    }

    @Test
    public void openingAndOrientingStaysWithinBudget() {
        File caseDir = McpTestSupport.requireLargeCase();

        long start = System.currentTimeMillis();
        String caseId = session.openCase(caseDir);
        JsonNode overview = session.call("iped_case_overview", "case_id", caseId);
        long elapsed = System.currentTimeMillis() - start;

        long total = overview.path("total_items").asLong();
        measured("open + overview", elapsed, OPEN_AND_OVERVIEW_BUDGET_MS, total + " items in the case");
        assertTrue("the large case should hold millions of items, got " + total, total > 1_000_000);
        assertTrue("open + overview took " + elapsed + " ms, budget is " + OPEN_AND_OVERVIEW_BUDGET_MS,
                elapsed < OPEN_AND_OVERVIEW_BUDGET_MS);
    }

    @Test
    public void firstPageOfABroadQueryStaysWithinBudget() {
        File caseDir = McpTestSupport.requireLargeCase();
        String caseId = session.openCase(caseDir);

        long start = System.currentTimeMillis();
        JsonNode page = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 50,
                "include_snippets", false);
        long elapsed = System.currentTimeMillis() - start;

        long total = page.path("total_matches").asLong();
        measured("first page of *:* (50 items)", elapsed, FIRST_PAGE_BUDGET_MS, total + " matches");
        assertTrue("the query must be broad enough to be meaningful, matched " + total, total > 1_000_000);
        assertTrue("the page must stay bounded regardless of the total", page.path("items").size() <= 50);
        assertTrue("first page took " + elapsed + " ms over " + total + " matches, budget is "
                + FIRST_PAGE_BUDGET_MS, elapsed < FIRST_PAGE_BUDGET_MS);
    }

    @Test
    public void pagingDeepIntoALargeSetDoesNotDegrade() {
        File caseDir = McpTestSupport.requireLargeCase();
        String caseId = session.openCase(caseDir);

        String cursor = null;
        long worst = 0;
        long first = 0;
        long last = 0;
        int walked = 0;
        for (int page = 0; page < 10; page++) {
            long start = System.currentTimeMillis();
            JsonNode result = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 50,
                    "cursor", cursor, "include_snippets", false);
            last = System.currentTimeMillis() - start;
            if (page == 0) {
                first = last;
            }
            worst = Math.max(worst, last);
            walked++;
            cursor = result.has("next_cursor") ? result.path("next_cursor").asText() : null;
            if (cursor == null) {
                break;
            }
        }
        // Cost must track the page, not the depth. A materializing implementation degrades here,
        // so the first and last page times are what the claim rests on, not the average.
        measured("deep paging, worst of " + walked, worst, FIRST_PAGE_BUDGET_MS,
                "page 1 " + first + " ms, page " + walked + " " + last + " ms");
        assertTrue("the slowest of ten pages took " + worst + " ms, budget is " + FIRST_PAGE_BUDGET_MS,
                worst < FIRST_PAGE_BUDGET_MS);
    }

    @Test
    public void aggregationStaysWithinBudget() {
        File caseDir = McpTestSupport.requireLargeCase();
        String caseId = session.openCase(caseDir);

        long start = System.currentTimeMillis();
        JsonNode aggregation = session.call("iped_aggregate", "case_id", caseId, "dimension", "category");
        long elapsed = System.currentTimeMillis() - start;

        measured("aggregate by category", elapsed, AGGREGATION_BUDGET_MS,
                aggregation.path("distinct_values").asInt() + " distinct values");
        assertTrue("aggregation must produce buckets", aggregation.path("buckets").size() > 0);
        // If this fails, R4 names the remedy: cache the overview per case, invalidated by index
        // version. It is not a reason to change aggregation strategy.
        assertTrue("aggregation took " + elapsed + " ms, budget is " + AGGREGATION_BUDGET_MS,
                elapsed < AGGREGATION_BUDGET_MS);
    }

    @Test
    public void anExactTotalIsCheapEvenForMillionsOfMatches() {
        File caseDir = McpTestSupport.requireLargeCase();
        String caseId = session.openCase(caseDir);

        long start = System.currentTimeMillis();
        JsonNode page = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 1,
                "include_snippets", false);
        long elapsed = System.currentTimeMillis() - start;

        // FR-012: the total is exact and independent of how many items came back. Producing it by
        // collecting every match would be exactly the defect this feature was built to remove.
        measured("exact total, 1 item returned", elapsed, FIRST_PAGE_BUDGET_MS,
                page.path("total_matches").asLong() + " matches counted");
        assertTrue(page.path("total_matches").asLong() > 1_000_000);
        assertTrue("counting took " + elapsed + " ms while returning one item", elapsed < FIRST_PAGE_BUDGET_MS);
    }
}
