package iped.engine.task.yara;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Descobre regras YARA-X em formato fonte ({@code .yar}, {@code .yara}) nos
 * diretórios configurados em {@link iped.engine.config.YaraConfig#getRuleDirectories()}.
 *
 * <p>Walks recursivos seguem symlinks (mesma semântica de {@code Files.walk} com
 * {@link FileVisitOption#FOLLOW_LINKS}). Diretórios inexistentes geram WARN e
 * são pulados — não fazem o caso falhar. Formatos pré-compilados ({@code .yarc}
 * ou serialização própria do YARA-X) ficam fora de escopo na v1 — ver
 * {@code research.md} §R-01 e Clarifications Q3 revisada.</p>
 */
public final class YaraRulesetLoader {

    private static final Logger logger = LoggerFactory.getLogger(YaraRulesetLoader.class);

    private YaraRulesetLoader() {
        // Static utility — no instantiation.
    }

    /**
     * Lista todos os arquivos {@code .yar}/{@code .yara} encontrados, em ordem
     * determinística (lexicográfica pelo caminho absoluto) — facilita auditoria
     * forense (mesmo input + mesmo catálogo → mesmo namespace order).
     */
    public static List<File> discover(List<File> directories) {
        if (directories == null || directories.isEmpty()) {
            return Collections.emptyList();
        }
        List<File> out = new ArrayList<>();
        for (File dir : directories) {
            if (dir == null) {
                continue;
            }
            if (!dir.exists()) {
                logger.warn("YARA rule directory does not exist: {}", dir.getAbsolutePath());
                continue;
            }
            if (!dir.isDirectory()) {
                logger.warn("YARA rule path is not a directory: {}", dir.getAbsolutePath());
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir.toPath(), FileVisitOption.FOLLOW_LINKS)) {
                walk.filter(Files::isRegularFile)
                        .filter(YaraRulesetLoader::isYaraSource)
                        .map(Path::toFile)
                        .forEach(out::add);
            } catch (IOException | UncheckedIOException e) {
                logger.warn("Failed to walk YARA rule directory {}: {}", dir.getAbsolutePath(), e.getMessage());
            }
        }
        out.sort(Comparator.comparing(File::getAbsolutePath));
        return out;
    }

    private static boolean isYaraSource(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yar") || name.endsWith(".yara");
    }
}
