package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * The guidance loaded in every harness is identical (FR-063).
 *
 * <p>
 * This is not a tidiness check. Divergent guidance between harnesses would produce divergent
 * analyses of the same evidence — two examiners, same case, different conclusions, for a reason
 * neither of them can see. Harnesses differ in packaging, never in what the agent is told.
 *
 * <p>
 * The wrappers are generated at {@code prepare-package}, so when they are not on disk yet this
 * suite checks the canonical source instead of failing on build ordering.
 */
public class SkillParityTest {

    private static final String[] HARNESSES = { "claude-code", "codex", "opencode" };

    private static File canonicalSkillDir() {
        return new File("src/main/resources/skill");
    }

    private static File wrapperRoot() {
        return new File("../iped-app/resources/skills");
    }

    @Test
    public void canonicalSkillExistsAndCarriesTheFiveRules() throws Exception {
        File skill = new File(canonicalSkillDir(), "SKILL.md");
        assertTrue("the canonical skill must exist at " + skill.getAbsolutePath(), skill.isFile());

        String content = new String(Files.readAllBytes(skill.toPath()), StandardCharsets.UTF_8);
        // FR-044 to FR-048, each one a rule whose loss changes what the agent does.
        assertTrue("orient before querying (FR-044)", content.contains("Orient before you query"));
        assertTrue("narrow progressively (FR-045)", content.contains("Narrow progressively"));
        assertTrue("sample at high volume (FR-045)", content.contains("Sample when the volume is high"));
        assertTrue("cite items in every conclusion (FR-046)", content.contains("Cite items in every conclusion"));
        assertTrue("validate vocabulary before claiming absence (FR-047)",
                content.contains("Never claim absence without validating vocabulary"));
        assertTrue("do not extrapolate (FR-048)", content.contains("Do not extrapolate"));
        // FR-029 and FR-052, added by the curation and polish phases.
        assertTrue("write discipline (FR-029)", content.contains("State the exact effect before you apply it"));
        assertTrue("sensitive material (FR-052)", content.contains("Handling the material itself"));
    }

    @Test
    public void referencesAndInstallGuidesArePresent() {
        assertTrue("query syntax reference (FR-050)",
                new File(canonicalSkillDir(), "references/query-syntax.md").isFile());
        assertTrue("workflows reference (FR-049)",
                new File(canonicalSkillDir(), "references/workflows.md").isFile());
        for (String harness : HARNESSES) {
            assertTrue("install guide for " + harness + " (FR-062)",
                    new File(canonicalSkillDir(), "install/" + harness + ".md").isFile());
        }
    }

    @Test
    public void everyGeneratedWrapperIsByteIdenticalToTheCanonicalSource() throws Exception {
        File root = wrapperRoot();
        org.junit.Assume.assumeTrue(
                "Wrappers are generated at prepare-package; run 'mvn package' to check them here.",
                root.isDirectory());

        String canonical = digestOfTree(canonicalSkillDir().toPath());
        for (String harness : HARNESSES) {
            File wrapper = new File(root, harness + "/iped-forensics");
            assertTrue("wrapper missing for " + harness, wrapper.isDirectory());
            assertEquals("guidance for " + harness + " diverges from the canonical source", canonical,
                    digestOfTree(wrapper.toPath()));
        }
    }

    /** SHA-256 over every file in the tree, in stable path order. */
    private static String digestOfTree(Path root) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort((a, b) -> root.relativize(a).toString().replace('\\', '/')
                .compareTo(root.relativize(b).toString().replace('\\', '/')));
        for (Path file : files) {
            sha.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            sha.update(Files.readAllBytes(file));
        }
        return Arrays.toString(sha.digest());
    }
}
