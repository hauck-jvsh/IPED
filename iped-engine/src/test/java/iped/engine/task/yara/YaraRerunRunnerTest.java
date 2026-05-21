package iped.engine.task.yara;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.engine.config.ConfigurationManager;
import iped.exception.IPEDException;

/**
 * Unit tests for {@link YaraRerunRunner}'s validation paths.
 *
 * <p>O end-to-end completo do {@code --yara-only} (caso pequeno processado +
 * rerun + assertions sobre `yara:*` no índice) precisa de uma fixture de caso
 * IPED real — bloqueio recorrente de T026/T032/T033, fora do escopo aqui.
 * O caminho funcional é validado manualmente pelo perito conforme o roteiro
 * em {@code specs/001-yara-rules-engine/quickstart.md §6}.</p>
 */
public class YaraRerunRunnerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test(expected = IllegalArgumentException.class)
    public void constructor_rejects_null_caseRoot() {
        new YaraRerunRunner(null, anyConfigManager());
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_rejects_null_configManager() {
        new YaraRerunRunner(tmp.getRoot(), null);
    }

    @Test
    public void run_rejects_case_root_without_iped_index() {
        // caseRoot exists but has no `iped/index` inside.
        File emptyCaseRoot = tmp.getRoot();
        YaraRerunRunner runner = new YaraRerunRunner(emptyCaseRoot, anyConfigManager());
        try {
            runner.run();
            fail("expected IPEDException for missing index");
        } catch (IPEDException expected) {
            assertTrue("error must reference index folder: " + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("index"));
        } catch (Exception other) {
            fail("expected IPEDException but got " + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    @Test
    public void rerun_stats_default_values_and_toString() {
        YaraRerunRunner.RerunStats stats = new YaraRerunRunner.RerunStats();
        assertEquals(0L, stats.itemsScanned);
        assertEquals(0L, stats.itemsWithMatches);
        assertEquals(0L, stats.itemsUpdated);
        assertEquals(0L, stats.itemsSkippedSize);
        assertEquals(0L, stats.itemsSkippedNoStream);
        assertEquals(0L, stats.itemsSkippedError);
        assertEquals(0L, stats.totalMillis);

        String repr = stats.toString();
        assertNotNull(repr);
        assertTrue(repr.startsWith("RerunStats["));
        assertTrue(repr.contains("scanned=0"));
    }

    /**
     * Retorna o {@link ConfigurationManager} singleton — criando-o em modo nulo
     * se ainda não existir. Como o {@code run_rejects_case_root_without_iped_index}
     * falha antes de tocar no manager (a check da `iped/index` é primeiro), o
     * conteúdo do manager é irrelevante para esses testes de validação.
     */
    private static ConfigurationManager anyConfigManager() {
        ConfigurationManager existing = ConfigurationManager.get();
        if (existing != null) {
            return existing;
        }
        return ConfigurationManager.createInstance(/* IConfigurationDirectory */ null);
    }
}
