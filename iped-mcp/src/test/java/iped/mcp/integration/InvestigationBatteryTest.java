package iped.mcp.integration;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.protocol.JsonRpcCodec;

/**
 * The thirty-question battery of Scenario 12, run against the reference case (SC-001).
 *
 * <p>
 * <b>Scope, stated plainly.</b> This verifies the <i>retrieval</i> half: each prescribed query
 * returns the items the answer key names. It cannot verify SC-008 ("zero false positives presented
 * as a conclusion") or SC-009 ("every conclusion carries cited items"), because those are properties
 * of what an agent writes, not of what a tool returns. Those require a live agent run, recorded
 * manually. A green run here is necessary and not sufficient.
 *
 * <p>
 * The answer key is expressed in file names rather than item ids, because ids are assigned at
 * processing time and change every time the reference case is rebuilt.
 */
public class InvestigationBatteryTest {

    /** SC-001: at least 90% of the questions must resolve correctly. */
    private static final double PASS_RATE = 0.90;

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void theBatteryResolvesAtLeastNinetyPercentOfItsQuestions() throws Exception {
        File caseDir = McpTestSupport.requireReferenceCase();
        String caseId = session.openCase(caseDir);

        JsonNode questions = loadBattery();
        List<String> failures = new ArrayList<>();
        int total = 0;

        for (JsonNode question : questions) {
            total++;
            String id = question.path("id").asText();
            String query = question.path("query").asText();
            try {
                Set<String> names = namesMatching(caseId, query);
                String problem = check(question, names);
                if (problem != null) {
                    failures.add(id + " (" + query + "): " + problem);
                }
            } catch (AssertionError e) {
                failures.add(id + " (" + query + ") could not be run: " + e.getMessage());
            }
        }

        double rate = (total - failures.size()) / (double) total;
        assertTrue(String.format("battery pass rate %.0f%% is below the %.0f%% bar; failures:%n%s", rate * 100,
                PASS_RATE * 100, String.join("\n", failures)), rate >= PASS_RATE);
    }

    @Test
    public void theBatteryHasThirtyQuestionsAndEveryOneCarriesAnExpectation() throws Exception {
        JsonNode questions = loadBattery();

        assertTrue("the battery must hold 30 questions, found " + questions.size(), questions.size() == 30);
        for (JsonNode question : questions) {
            String id = question.path("id").asText();
            assertTrue("question " + id + " has no query", !question.path("query").asText().isEmpty());
            assertTrue("question " + id + " has no expectation, so it cannot fail and proves nothing",
                    question.has("expect_names") || question.has("expect_contains") || question.has("expect_count")
                            || question.has("expect_min"));
            assertTrue("question " + id + " has no note explaining what it protects",
                    !question.path("note").asText().isEmpty());
        }
    }

    /** Applies whichever expectation the question declares. */
    private static String check(JsonNode question, Set<String> names) {
        if (question.has("expect_names")) {
            Set<String> expected = toSet(question.path("expect_names"));
            if (!expected.equals(names)) {
                return "expected exactly " + expected + ", got " + names;
            }
        }
        if (question.has("expect_contains")) {
            Set<String> missing = toSet(question.path("expect_contains"));
            missing.removeAll(names);
            if (!missing.isEmpty()) {
                return "missing " + missing + " from " + names;
            }
        }
        if (question.has("expect_count") && names.size() != question.path("expect_count").asInt()) {
            return "expected " + question.path("expect_count").asInt() + " items, got " + names.size();
        }
        if (question.has("expect_min") && names.size() < question.path("expect_min").asInt()) {
            return "expected at least " + question.path("expect_min").asInt() + " items, got " + names.size();
        }
        return null;
    }

    /** Every matching item name, paging so a broad question is not judged on its first page. */
    private Set<String> namesMatching(String caseId, String query) {
        Set<String> names = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = session.call("iped_search", "case_id", caseId, "query", query, "page_size", 200,
                    "cursor", cursor, "include_snippets", false);
            for (JsonNode item : page.path("items")) {
                String name = item.path("name").asText(null);
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
            cursor = page.has("next_cursor") ? page.path("next_cursor").asText() : null;
        } while (cursor != null && ++pages < 100);
        return names;
    }

    private static Set<String> toSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    /**
     * Reads the battery from the fenced JSON block inside {@code questions.md}. Keeping the answer
     * key and its human-readable form in one file is what stops them drifting apart.
     */
    private static JsonNode loadBattery() throws Exception {
        File file = new File("src/test/resources/evaluation/questions.md");
        assertTrue("the battery must be versioned at " + file.getPath(), file.isFile());

        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int start = content.indexOf("```json");
        int end = content.indexOf("```", start + "```json".length());
        assertTrue("questions.md must carry a fenced json block with the answer key", start >= 0 && end > start);

        String json = content.substring(start + "```json".length(), end);
        return JsonRpcCodec.mapper().readTree(json).path("questions");
    }
}
