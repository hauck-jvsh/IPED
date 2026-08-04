package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;

/**
 * Aggregation coherence (Scenario 5).
 *
 * <p>
 * The check that matters is arithmetic, not performance: the buckets have to agree with what a
 * search reports, or the agent uses the aggregation to decide how to narrow and narrows toward the
 * wrong place. Timing on the large case is {@code ScalePerformanceTest}.
 */
public class AggregationTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void categoryBucketsSumToSomethingConsistentWithTheCase() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        long totalItems = session.call("iped_case_overview", "case_id", caseId).path("total_items").asLong();

        JsonNode aggregation = session.call("iped_aggregate", "case_id", caseId, "dimension", "category");

        long summed = aggregation.path("summed_count").asLong();
        assertTrue("aggregation must produce buckets", aggregation.path("buckets").size() > 0);
        // An item can carry more than one category, so the sum is a lower bound on coverage rather
        // than an equality. What must not happen is a sum that exceeds nothing or dwarfs the case.
        assertTrue("summed count must be positive", summed > 0);
        assertTrue("summed count must stay in the same order of magnitude as the case: " + summed + " vs "
                + totalItems, summed <= totalItems * 4);
    }

    @Test
    public void aRestrictedAggregationAgreesWithTheSearchTotal() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        String restrictive = "name:*";

        long searchTotal = session.call("iped_search", "case_id", caseId, "query", restrictive, "page_size", 1,
                "include_snippets", false).path("total_matches").asLong();
        JsonNode aggregation = session.call("iped_aggregate", "case_id", caseId, "dimension", "contentType",
                "query", restrictive);

        long summed = aggregation.path("summed_count").asLong();
        assertTrue("a restricted aggregation must not count more items than the query matches: " + summed + " vs "
                + searchTotal, summed <= searchTotal);
    }

    @Test
    public void everyContractDimensionIsAnswerable() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        for (String dimension : new String[] { "category", "contentType", "evidence", "bookmark" }) {
            JsonNode aggregation = session.call("iped_aggregate", "case_id", caseId, "dimension", dimension);
            assertEquals(dimension, aggregation.path("dimension").asText());
            assertTrue("buckets must be an array for " + dimension, aggregation.path("buckets").isArray());
        }
    }

    @Test
    public void anUnknownDimensionListsTheValidOnes() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode error = session.expectError("INVALID_ARGUMENT", "iped_aggregate", "case_id", caseId, "dimension",
                "colour");

        assertTrue("the diagnostic must list the valid dimensions",
                error.path("details").path("valid").size() >= 5);
    }

    @Test
    public void bucketsAreOrderedByCountAndTruncationIsDeclared() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        JsonNode aggregation = session.call("iped_aggregate", "case_id", caseId, "dimension", "contentType",
                "max_buckets", 2);

        JsonNode buckets = aggregation.path("buckets");
        assertTrue(buckets.size() <= 2);
        for (int i = 1; i < buckets.size(); i++) {
            assertTrue("buckets must be ordered by count, descending",
                    buckets.get(i - 1).path("count").asLong() >= buckets.get(i).path("count").asLong());
        }
        if (aggregation.path("distinct_values").asInt() > 2) {
            assertTrue("truncation must be declared, never silent", aggregation.path("buckets_truncated")
                    .asBoolean());
        }
    }
}
