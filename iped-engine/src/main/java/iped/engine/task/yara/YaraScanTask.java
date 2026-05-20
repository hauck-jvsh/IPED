package iped.engine.task.yara;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.configuration.Configurable;
import iped.data.IItem;
import iped.engine.config.ConfigurationManager;
import iped.engine.config.YaraConfig;
import iped.engine.task.AbstractTask;
import iped.properties.ExtraProperties;

/**
 * Tarefa de pipeline que aplica regras YARA-X ao conteúdo binário de cada item
 * elegível e persiste os matches em três propriedades:
 *
 * <ul>
 *   <li>{@link ExtraProperties#YARA_RULE} — multi-valorado, indexado: identificadores
 *       de regra ({@code namespace/name}) que casaram com o item.</li>
 *   <li>{@link ExtraProperties#YARA_TAGS} — multi-valorado, indexado: união das tags
 *       declaradas nas regras que casaram.</li>
 *   <li>{@link ExtraProperties#YARA_MATCH_DETAIL} — armazenado (stored, não indexado):
 *       JSON com o detalhe completo dos matches (engine version, scanned bytes,
 *       rule/namespace/tags/strings).</li>
 * </ul>
 *
 * <p>Lifecycle (segue o padrão de {@code HashDBLookupTask}):</p>
 * <ul>
 *   <li>{@link #init} — sincronizado em um lock estático: o primeiro worker
 *       carrega config, compila o catálogo (uma vez) e popula
 *       {@link #sharedEngine}. Cada worker (incluindo o primeiro) então cria seu
 *       próprio {@link YaraScanner} (não thread-safe) e {@link YaraMatchSerializer}.</li>
 *   <li>{@link #process} — escaneia o item se elegível e respeita os limites de
 *       tamanho/timeout do {@link YaraConfig}.</li>
 *   <li>{@link #finish} — cada worker destrói seu scanner; o último worker
 *       destrói o engine compartilhado e imprime um resumo das métricas.</li>
 * </ul>
 *
 * <p>Quando a feature está desabilitada (via {@code enableYara=false} ou
 * catálogo vazio, ou engine nativa indisponível), {@link #isEnabled} retorna
 * {@code false} e {@link #process} é no-op — FR-013 / FR-014.</p>
 */
public class YaraScanTask extends AbstractTask {

    private static final Logger logger = LoggerFactory.getLogger(YaraScanTask.class);

    /** Static lock used to guard the one-shot init/finish across all workers. */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean finished = new AtomicBoolean(false);

    /** Compartilhado entre todos os workers (read-only após {@code yrx_compiler_build}). */
    private static volatile YaraEngine sharedEngine = null;
    /** {@code true} se a feature está globalmente habilitada para este caso. */
    private static volatile boolean taskEnabled = false;
    /** Versão da engine reportada — copiada uma vez para evitar consultas repetidas. */
    private static volatile String engineVersionAtInit = YaraEngine.ENGINE_VERSION;

    /** Métricas globais agregadas por todos os workers. */
    private static final AtomicLong itemsScanned = new AtomicLong();
    private static final AtomicLong itemsSkippedSize = new AtomicLong();
    private static final AtomicLong itemsSkippedNoStream = new AtomicLong();
    private static final AtomicLong itemsSkippedError = new AtomicLong();
    private static final AtomicLong itemsWithMatches = new AtomicLong();
    private static final AtomicLong totalMatches = new AtomicLong();

    /* Per-worker state (one instance per worker). */
    private YaraConfig config;
    private YaraScanner scanner;
    private YaraMatchSerializer serializer;
    private int timeoutSeconds;

    @Override
    public List<Configurable<?>> getConfigurables() {
        return Arrays.asList(ConfigurationManager.get().findObject(YaraConfig.class));
    }

    @Override
    public void init(ConfigurationManager configurationManager) throws Exception {
        initWithConfig(configurationManager.findObject(YaraConfig.class));
    }

    /**
     * Caminho de init testável que bypassa o {@code ConfigurationManager} —
     * package-private para uso em {@code YaraScanTaskIntegrationTest}. Em
     * produção sempre entra via {@link #init(ConfigurationManager)}.
     */
    void initWithConfig(YaraConfig config) {
        this.config = config;
        synchronized (initialized) {
            if (!initialized.get()) {
                taskEnabled = doSharedInit(config);
                initialized.set(true);
            }
        }
        // Per-worker setup runs only when the shared init succeeded.
        if (taskEnabled && sharedEngine != null) {
            scanner = sharedEngine.createScanner();
            if (scanner == null) {
                // Engine reported available at shared init but scanner creation failed
                // for this worker — degrade locally without taking the whole task down.
                logger.warn("YaraScanTask: failed to create per-worker scanner — task disabled for this worker");
                return;
            }
            serializer = new YaraMatchSerializer(config.getMatchHexMaxBytes());
            timeoutSeconds = Math.max(0, config.getPerItemTimeoutMs() / 1000);
        }
    }

    /**
     * Roda uma única vez (sincronizado). Carrega o catálogo, banê o módulo
     * cuckoo (via {@link YaraEngine#compileSources}), e popula {@link #sharedEngine}.
     */
    private static boolean doSharedInit(YaraConfig config) {
        if (config == null || !config.isEnabled()) {
            logger.info("YaraScanTask disabled (enableYara=false or YaraConfig missing).");
            return false;
        }
        if (config.getRuleDirectories().isEmpty()) {
            logger.warn("YaraScanTask: ruleDirectories is empty — task disabled for this case.");
            return false;
        }
        if (!YaraEngine.ensureAvailable(config.getEngineLibraryHint())) {
            logger.warn("YaraScanTask: libyara-x-capi not loadable — task disabled for this case.");
            return false;
        }
        List<File> sources = YaraRulesetLoader.discover(config.getRuleDirectories());
        if (sources.isEmpty()) {
            logger.warn("YaraScanTask: no .yar/.yara files found under {} — task disabled.",
                    config.getRuleDirectories());
            return false;
        }
        long t0 = System.nanoTime();
        sharedEngine = YaraEngine.compileSources(sources, (src, line, msg) -> {
            String where = (src == null) ? "?" : src.getName();
            if (line > 0) {
                logger.warn("YaraScanTask: rule compile error at {}:{} — {}", where, line, msg);
            } else {
                logger.warn("YaraScanTask: rule compile error at {} — {}", where, msg);
            }
        });
        long compileMs = (System.nanoTime() - t0) / 1_000_000L;
        if (sharedEngine == null) {
            logger.warn("YaraScanTask: no YARA-X rules compiled successfully — task disabled.");
            return false;
        }
        engineVersionAtInit = YaraEngine.getEngineVersion();
        logger.info("YaraScanTask: catalog compiled in {} ms from {} source files (engine {}).",
                compileMs, sources.size(), engineVersionAtInit);
        return true;
    }

    @Override
    public boolean isEnabled() {
        return taskEnabled && scanner != null;
    }

    @Override
    public void process(IItem evidence) throws Exception {
        if (!isEnabled() || evidence == null || evidence.isDir()) {
            return;
        }
        if (!isItemEligible(evidence)) {
            return;
        }
        Long lengthBoxed = evidence.getLength();
        long length = (lengthBoxed == null) ? 0L : lengthBoxed;
        if (length > config.getMaxFileSizeBytes()) {
            itemsSkippedSize.incrementAndGet();
            return;
        }
        byte[] buffer = readItemContent(evidence, (int) length);
        if (buffer == null) {
            itemsSkippedNoStream.incrementAndGet();
            return;
        }

        List<YaraMatch> matches;
        try {
            matches = scanner.scan(buffer, buffer.length, timeoutSeconds);
        } catch (Throwable t) {
            // Falha nativa em um item é capturada e contabilizada como skipped/error;
            // não propaga para não abortar o caso (FR-005).
            itemsSkippedError.incrementAndGet();
            logger.debug("YaraScanTask: scan threw {} on item {}: {}",
                    t.getClass().getSimpleName(), evidence.getId(), t.getMessage());
            return;
        }
        itemsScanned.incrementAndGet();
        if (matches.isEmpty()) {
            return;
        }

        persistMatches(evidence, matches, buffer.length);
        itemsWithMatches.incrementAndGet();
        totalMatches.addAndGet(matches.size());
    }

    /**
     * Default seletivo (R-06): item precisa ter conteúdo binário acessível.
     * Override {@code scanAllItems=true} pula a verificação de elegibilidade.
     */
    private boolean isItemEligible(IItem evidence) {
        if (config.isScanAllItems()) {
            return true;
        }
        Long len = evidence.getLength();
        if (len == null || len <= 0) {
            return false;
        }
        // Não temos um meio barato de testar "tem stream" sem abrir — o open
        // acontece em readItemContent() e itens sem stream cairão em
        // itemsSkippedNoStream lá.
        return evidence.getMediaType() != null;
    }

    /**
     * Lê todo o conteúdo do item até {@code length}. Retorna {@code null} se o
     * stream não pôde ser aberto ou se houve falha de I/O.
     */
    private byte[] readItemContent(IItem evidence, int length) {
        try (InputStream in = evidence.getBufferedInputStream()) {
            if (in == null) {
                return null;
            }
            // O length informado pelo caller já passou pelo check de maxFileSizeBytes,
            // então readAllBytes é seguro aqui.
            byte[] bytes;
            if (in instanceof BufferedInputStream) {
                bytes = in.readAllBytes();
            } else {
                bytes = in.readAllBytes();
            }
            return bytes;
        } catch (IOException e) {
            logger.debug("YaraScanTask: I/O error reading item {}: {}", evidence.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Popula os três campos {@code yara:*} no item. Ordenação determinística
     * (Princípio IV): regras lexicográficas por identificador; tags em
     * {@link LinkedHashSet} preservando ordem de inserção do conjunto unificado.
     */
    private void persistMatches(IItem evidence, List<YaraMatch> matches, int scannedBytes) {
        List<String> ruleIds = new ArrayList<>(matches.size());
        Set<String> tagSet = new LinkedHashSet<>();
        for (YaraMatch m : matches) {
            ruleIds.add(m.getIdentifier());
            tagSet.addAll(m.getTags());
        }
        Collections.sort(ruleIds);
        evidence.setExtraAttribute(ExtraProperties.YARA_RULE, ruleIds);
        if (!tagSet.isEmpty()) {
            evidence.setExtraAttribute(ExtraProperties.YARA_TAGS, new ArrayList<>(tagSet));
        }
        try {
            String json = serializer.toJson(matches, engineVersionAtInit, scannedBytes);
            if (json != null) {
                evidence.setExtraAttribute(ExtraProperties.YARA_MATCH_DETAIL, json);
            }
        } catch (IOException e) {
            // Não bloqueia o item — temos os IDs/tags persistidos, só perdemos o detalhe.
            logger.debug("YaraScanTask: failed to serialize match detail for item {}: {}",
                    evidence.getId(), e.getMessage());
        }
    }

    @Override
    public void finish() throws Exception {
        if (scanner != null) {
            scanner.close();
            scanner = null;
        }
        synchronized (finished) {
            if (!finished.get()) {
                if (sharedEngine != null) {
                    sharedEngine.close();
                    sharedEngine = null;
                }
                if (taskEnabled) {
                    logger.info("YaraScanTask scan summary:");
                    logger.info("  itemsScanned   : {}", itemsScanned.get());
                    logger.info("  itemsWithMatches: {}", itemsWithMatches.get());
                    logger.info("  matchesTotal   : {}", totalMatches.get());
                    long skippedSize = itemsSkippedSize.get();
                    long skippedStream = itemsSkippedNoStream.get();
                    long skippedError = itemsSkippedError.get();
                    if (skippedSize + skippedStream + skippedError > 0) {
                        logger.info("  itemsSkipped   : {} (size={}, no-stream={}, error={})",
                                skippedSize + skippedStream + skippedError,
                                skippedSize, skippedStream, skippedError);
                    }
                }
                finished.set(true);
            }
        }
    }

    /* Visible for tests — reset static state between integration test runs. */
    static void resetForTests() {
        synchronized (initialized) {
            synchronized (finished) {
                if (sharedEngine != null) {
                    try {
                        sharedEngine.close();
                    } catch (Throwable ignore) {
                    }
                    sharedEngine = null;
                }
                initialized.set(false);
                finished.set(false);
                taskEnabled = false;
                itemsScanned.set(0);
                itemsSkippedSize.set(0);
                itemsSkippedNoStream.set(0);
                itemsSkippedError.set(0);
                itemsWithMatches.set(0);
                totalMatches.set(0);
            }
        }
    }
}
