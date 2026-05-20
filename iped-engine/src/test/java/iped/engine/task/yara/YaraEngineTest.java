package iped.engine.task.yara;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Integration-gated tests for {@link YaraEngine} (engine: YARA-X 1.x).
 *
 * <p>Estes testes só rodam se uma {@code libyara-x-capi} estiver carregável no
 * ambiente (via {@code jna.library.path}, variável de ambiente
 * {@code YARA_X_LIB_PATH} ou presença em path padrão do sistema). Caso
 * contrário, são pulados via {@link org.junit.Assume}.</p>
 *
 * <p>Para forçar a execução localmente:</p>
 * <pre>
 *   # Linux:
 *   # baixar o tarball oficial de https://github.com/VirusTotal/yara-x/releases
 *   # extrair em /usr/local/ (lib/libyara_x_capi.so) e:
 *   mvn -pl iped-engine -Dtest=YaraEngineTest test
 *
 *   # Windows:
 *   set YARA_X_LIB_PATH=C:\path\to\yara_x_capi.dll
 *   mvn -pl iped-engine -Dtest=YaraEngineTest test
 * </pre>
 *
 * <p>Os testes de {@code .yarc} (load + corrompido) que existiam na variante
 * libyara clássica foram removidos com a migração para YARA-X — pré-compilados
 * ficam fora de escopo na v1 (ver Clarifications Q3 revisada na spec).</p>
 */
public class YaraEngineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void ensureEngine() {
        String hint = System.getenv("YARA_X_LIB_PATH");
        boolean available = YaraEngine.ensureAvailable(hint);
        assumeTrue("libyara-x-capi not available — skipping integration tests", available);
    }

    @AfterClass
    public static void shutdown() {
        YaraEngine.shutdown();
    }

    @Test
    public void reports_yara_x_engine_version() {
        assertEquals("yara-x-1.16.0", YaraEngine.getEngineVersion());
    }

    @Test
    public void compiles_and_matches_simple_string_rule() throws Exception {
        File ruleFile = tmp.newFile("hello.yar");
        Files.write(ruleFile.toPath(),
                ("rule hello_world : demo { strings: $s = \"hello world\" condition: $s }")
                        .getBytes(StandardCharsets.UTF_8));

        List<File> sources = new ArrayList<>();
        sources.add(ruleFile);
        CapturingSink sink = new CapturingSink();
        try (YaraEngine engine = YaraEngine.compileSources(sources, sink)) {
            assertNotNull(engine);
            assertTrue("expected zero compile errors but got " + sink.errors, sink.errors.isEmpty());

            byte[] buffer = "the quick brown fox says hello world to YARA".getBytes(StandardCharsets.UTF_8);
            List<YaraMatch> matches = engine.scan(buffer, buffer.length, 5);

            assertEquals(1, matches.size());
            YaraMatch m = matches.get(0);
            assertEquals("hello_world", m.getName());
            assertEquals("hello", m.getNamespace());
        }
    }

    @Test
    public void compile_error_is_isolated_and_reported() throws Exception {
        File good = tmp.newFile("good.yar");
        Files.write(good.toPath(),
                "rule good_rule { strings: $a = \"abc\" condition: $a }".getBytes(StandardCharsets.UTF_8));

        File bad = tmp.newFile("bad.yar");
        Files.write(bad.toPath(),
                "rule bad_rule { strings: $a = \"missing-quote condition: $a }".getBytes(StandardCharsets.UTF_8));

        List<File> sources = new ArrayList<>();
        sources.add(good);
        sources.add(bad);
        CapturingSink sink = new CapturingSink();
        try (YaraEngine engine = YaraEngine.compileSources(sources, sink)) {
            // Engine pode existir (boa regra compilou) ou ser null se a libyara-x abortou tudo;
            // o importante é que o erro tenha sido reportado.
            assertFalse("compile error should have been reported via sink", sink.errors.isEmpty());

            if (engine != null) {
                byte[] buffer = "abc".getBytes(StandardCharsets.UTF_8);
                List<YaraMatch> matches = engine.scan(buffer, buffer.length, 5);
                // a "good_rule" deve casar
                assertEquals(1, matches.size());
                assertEquals("good_rule", matches.get(0).getName());
            }
        }
    }

    @Test
    public void cuckoo_import_is_rejected() throws Exception {
        File cuckoo = tmp.newFile("with_cuckoo.yar");
        Files.write(cuckoo.toPath(),
                ("import \"cuckoo\"\nrule needs_cuckoo { condition: cuckoo.network.host(/.*/) }")
                        .getBytes(StandardCharsets.UTF_8));

        List<File> sources = new ArrayList<>();
        sources.add(cuckoo);
        CapturingSink sink = new CapturingSink();
        YaraEngine engine = YaraEngine.compileSources(sources, sink);
        // Como cuckoo é banido em runtime via yrx_compiler_ban_module, esperamos erro.
        assertFalse("import \"cuckoo\" should have produced a compile error", sink.errors.isEmpty());
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    public void scan_returns_empty_on_no_match() throws Exception {
        File ruleFile = tmp.newFile("only_pe.yar");
        Files.write(ruleFile.toPath(),
                "rule pe_only { strings: $mz = { 4D 5A } condition: $mz at 0 }"
                        .getBytes(StandardCharsets.UTF_8));

        List<File> sources = new ArrayList<>();
        sources.add(ruleFile);
        try (YaraEngine engine = YaraEngine.compileSources(sources, new CapturingSink())) {
            assertNotNull(engine);
            byte[] buffer = "GIF89a not a PE file".getBytes(StandardCharsets.UTF_8);
            List<YaraMatch> matches = engine.scan(buffer, buffer.length, 5);
            assertEquals(Collections.emptyList(), matches);
        }
    }

    private static final class CapturingSink implements YaraEngine.CompileErrorSink {
        final List<String> errors = new ArrayList<>();

        @Override
        public void report(File source, int lineNumber, String message) {
            errors.add((source == null ? "?" : source.getName()) + ":" + lineNumber + " " + message);
        }
    }
}
