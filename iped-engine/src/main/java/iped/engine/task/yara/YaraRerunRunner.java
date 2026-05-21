package iped.engine.task.yara;

import java.io.File;
import java.io.IOException;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.config.ConfigurationManager;
import iped.engine.config.YaraConfig;
import iped.engine.data.IPEDSource;
import iped.engine.task.index.IndexItem;
import iped.exception.IPEDException;
import iped.properties.ExtraProperties;

/**
 * Caminho de execução {@code --yara-only}: aplica o catálogo YARA-X atual sobre
 * um caso já processado, atualizando os campos {@code yara:rule}/{@code yara:tag}/
 * {@code yara:matches} no índice Lucene existente, sem ingerir nova evidência
 * nem rodar o pipeline completo.
 *
 * <p>Design (FR-011, ver {@code research.md} §R-08):</p>
 * <ol>
 *   <li>Abre o índice Lucene do caso em RW via {@link IndexWriter} direto sobre
 *       o diretório {@code casePath/iped/index} (a sintaxe NRT permite leitura
 *       concorrente com escrita).</li>
 *   <li>Constrói uma {@link IPEDSource} passando o writer, aproveitando toda a
 *       infraestrutura existente para reconstruir {@link IItem}s a partir de
 *       Lucene docs.</li>
 *   <li>Compila o catálogo YARA-X via {@link YaraEngine#compileSources}.</li>
 *   <li>Itera pelos {@code maxDoc()} documentos: reconstrói o {@link IItem},
 *       roda o pipeline da {@link YaraScanTask} sobre ele, e — para os itens
 *       cujo estado YARA mudou (ganhou matches, perdeu matches, ou mudou
 *       conjunto de matches) — chama {@link IndexWriter#updateDocument(Term, Iterable)}
 *       com a nova versão completa do documento. Substituição integral evita
 *       matches "fantasma" de catálogos antigos.</li>
 *   <li>Commit + close.</li>
 * </ol>
 *
 * <p>Esta classe é deliberadamente <b>independente do {@code Manager}</b>: não
 * usa {@code ProcessingQueues}, {@code ItemProducer} nem {@code Worker} porque
 * o caminho rerun-only não precisa deles (não há ingestão nova nem subitens
 * novos). Princípio II da constituição preservado — nenhuma linha de Manager
 * muda.</p>
 *
 * <p><b>Limitação conhecida:</b> esta v1 é single-threaded. Em casos grandes a
 * iteração serial é mais lenta que o multi-worker do {@code Manager}; aceitável
 * porque (a) o rerun é uma operação ad-hoc rara e (b) SC-006 (≤ 25% do tempo
 * original) continua atingível com single thread em casos médios. Paralelização
 * fica para iteração futura se o gargalo aparecer.</p>
 */
public final class YaraRerunRunner {

    private static final Logger logger = LoggerFactory.getLogger(YaraRerunRunner.class);

    private final File caseRoot;
    private final ConfigurationManager configManager;

    /**
     * @param caseRoot diretório de saída do caso (mesmo path passado em {@code -o}/{@code --output}).
     * @param configManager o {@link ConfigurationManager} singleton, já com {@link YaraConfig} populado.
     */
    public YaraRerunRunner(File caseRoot, ConfigurationManager configManager) {
        if (caseRoot == null) {
            throw new IllegalArgumentException("caseRoot must not be null");
        }
        if (configManager == null) {
            throw new IllegalArgumentException("configManager must not be null");
        }
        this.caseRoot = caseRoot;
        this.configManager = configManager;
    }

    /**
     * Executa o rerun e retorna métricas agregadas. Lança {@link IPEDException}
     * quando o caso não existe / não tem índice / a engine YARA-X não pôde ser
     * carregada / não há regras configuradas.
     */
    public RerunStats run() throws Exception {
        File indexDir = new File(caseRoot, IPEDSource.MODULE_DIR + "/" + IPEDSource.INDEX_DIR);
        if (!indexDir.isDirectory()) {
            throw new IPEDException("YARA rerun: index folder not found at " + indexDir.getAbsolutePath());
        }
        YaraConfig yaraConfig = configManager.findObject(YaraConfig.class);
        if (yaraConfig == null) {
            throw new IPEDException("YARA rerun: YaraConfig not loaded by ConfigurationManager.");
        }
        if (!yaraConfig.isEnabled()) {
            throw new IPEDException(
                    "YARA rerun: enableYara must be true (in IPEDConfig.txt or via -profile). "
                            + "Current configuration has YARA disabled.");
        }
        if (yaraConfig.getRuleDirectories().isEmpty()) {
            throw new IPEDException("YARA rerun: ruleDirectories is empty in YaraConfig.txt.");
        }
        if (!YaraEngine.ensureAvailable(yaraConfig.getEngineLibraryHint())) {
            throw new IPEDException("YARA rerun: libyara-x-capi could not be loaded — see logs for details.");
        }

        long t0 = System.nanoTime();
        Directory dir = FSDirectory.open(indexDir.toPath());
        IndexWriterConfig iwc = new IndexWriterConfig();
        // OpenMode.APPEND: assume o índice existe e abre para leitura/escrita
        // (não cria índice novo). Se o índice estiver corrompido, Lucene lança.
        iwc.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
        IndexWriter writer = new IndexWriter(dir, iwc);
        IPEDSource source = null;
        YaraEngine engine = null;
        YaraScanner scanner = null;
        YaraMatchSerializer serializer = new YaraMatchSerializer(yaraConfig.getMatchHexMaxBytes());
        RerunStats stats = new RerunStats();
        try {
            // Compile rules ONCE, before opening the source (so a bad catalog
            // fails fast before we touch the index).
            engine = YaraRulesetLoaderFacade.compile(yaraConfig);
            if (engine == null) {
                throw new IPEDException("YARA rerun: no rules compiled successfully.");
            }
            scanner = engine.createScanner();
            if (scanner == null) {
                throw new IPEDException("YARA rerun: yrx_scanner_create failed.");
            }
            int timeoutSeconds = Math.max(0, yaraConfig.getPerItemTimeoutMs() / 1000);

            // Open the case with our existing writer so IPEDSource uses it
            // (avoids a second writer fighting for the index lock).
            source = new IPEDSource(caseRoot, writer, /* askImagePathIfNotFound */ false);

            // Use NRT reader so we see the writer's live view. Lucene 9.2 doesn't
            // expose getLiveDocs()/storedFields() on the composite reader — iterate
            // per-segment via LeafReaderContext.
            try (DirectoryReader nrtReader = DirectoryReader.open(writer)) {
                logger.info("YARA rerun: starting over {} indexed documents (engine {}).",
                        nrtReader.maxDoc(), YaraEngine.getEngineVersion());

                for (LeafReaderContext leafCtx : nrtReader.leaves()) {
                    LeafReader leaf = leafCtx.reader();
                    Bits liveDocs = leaf.getLiveDocs();
                    int leafMaxDoc = leaf.maxDoc();
                    for (int leafDocId = 0; leafDocId < leafMaxDoc; leafDocId++) {
                        if (liveDocs != null && !liveDocs.get(leafDocId)) {
                            // deleted doc; skip
                            continue;
                        }
                        Document oldDoc;
                        try {
                            oldDoc = leaf.document(leafDocId);
                        } catch (IOException ioe) {
                            stats.itemsSkippedError++;
                            logger.debug("YARA rerun: failed to read doc {}: {}", leafDocId, ioe.getMessage());
                            continue;
                        }
                        String oldRule = oldDoc.get(ExtraProperties.YARA_RULE);
                        boolean hadYaraBefore = oldRule != null && !oldRule.isEmpty();

                        IItem item;
                        try {
                            item = IndexItem.getItem(oldDoc, source, /* viewItem */ false);
                        } catch (Throwable t) {
                            stats.itemsSkippedError++;
                            logger.debug("YARA rerun: failed to rebuild IItem for doc {}: {}", leafDocId, t.toString());
                            continue;
                        }
                        if (item == null) {
                            stats.itemsSkippedError++;
                            continue;
                        }

                        boolean hasNewMatches = applyYaraToItem(item, yaraConfig, scanner, serializer, timeoutSeconds, stats);
                        if (!hadYaraBefore && !hasNewMatches) {
                            // No previous and no current YARA state — leave the document untouched.
                            continue;
                        }

                        // The item now has yara:* extra attributes (if it matched). Build a new
                        // Document and substitute the existing one. We use a Term on
                        // IndexItem.ID — the existing index already enforced uniqueness on it.
                        String idValue = oldDoc.get(IndexItem.ID);
                        if (idValue == null || idValue.isEmpty()) {
                            stats.itemsSkippedError++;
                            logger.debug("YARA rerun: doc has no IndexItem.ID — cannot update");
                            continue;
                        }
                        Document newDoc;
                        try {
                            newDoc = IndexItem.Document(item, source.getModuleDir());
                        } catch (Throwable t) {
                            stats.itemsSkippedError++;
                            logger.debug("YARA rerun: failed to rebuild Document: {}", t.toString());
                            continue;
                        }
                        Term idTerm = new Term(IndexItem.ID, idValue);
                        writer.updateDocument(idTerm, newDoc);
                        stats.itemsUpdated++;
                    }
                }
            }

            writer.commit();
            stats.totalMillis = (System.nanoTime() - t0) / 1_000_000L;
            logger.info("YARA rerun summary:");
            logger.info("  itemsScanned    : {}", stats.itemsScanned);
            logger.info("  itemsWithMatches: {}", stats.itemsWithMatches);
            logger.info("  itemsUpdated    : {} (includes wipes of stale matches)", stats.itemsUpdated);
            logger.info("  itemsSkipped    : {} (size={}, no-stream={}, error={})",
                    stats.itemsSkippedSize + stats.itemsSkippedNoStream + stats.itemsSkippedError,
                    stats.itemsSkippedSize, stats.itemsSkippedNoStream, stats.itemsSkippedError);
            logger.info("  totalSeconds    : {}", stats.totalMillis / 1000.0);
            return stats;
        } finally {
            if (scanner != null) {
                try {
                    scanner.close();
                } catch (Throwable ignore) {
                    // never lets cleanup fail the run
                }
            }
            if (engine != null) {
                try {
                    engine.close();
                } catch (Throwable ignore) {
                }
            }
            if (source != null) {
                try {
                    source.close();
                } catch (Throwable ignore) {
                }
            }
            try {
                writer.close();
            } catch (Throwable t) {
                logger.warn("YARA rerun: error closing IndexWriter: {}", t.toString());
            }
            try {
                dir.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Lê o conteúdo do {@code item}, escaneia com o {@code scanner} e popula
     * os atributos {@code yara:*} no item via {@link IItem#setExtraAttribute}.
     * Atualiza as métricas conforme o destino do item (escaneado / pulado /
     * com matches).
     *
     * @return {@code true} se o item ficou com pelo menos um match.
     */
    private boolean applyYaraToItem(IItem item, YaraConfig yaraConfig, YaraScanner scanner,
            YaraMatchSerializer serializer, int timeoutSeconds, RerunStats stats) {
        if (item.isDir()) {
            return false;
        }
        Long lengthBoxed = item.getLength();
        long length = (lengthBoxed == null) ? 0L : lengthBoxed;
        if (length <= 0 && !yaraConfig.isScanAllItems()) {
            return false;
        }
        if (length > yaraConfig.getMaxFileSizeBytes()) {
            stats.itemsSkippedSize++;
            return false;
        }
        byte[] buffer;
        try (java.io.InputStream in = item.getBufferedInputStream()) {
            if (in == null) {
                stats.itemsSkippedNoStream++;
                return false;
            }
            buffer = in.readAllBytes();
        } catch (Throwable t) {
            stats.itemsSkippedError++;
            return false;
        }
        if (buffer.length == 0) {
            stats.itemsSkippedNoStream++;
            return false;
        }
        java.util.List<YaraMatch> matches;
        try {
            matches = scanner.scan(buffer, buffer.length, timeoutSeconds);
        } catch (Throwable t) {
            stats.itemsSkippedError++;
            return false;
        }
        stats.itemsScanned++;
        if (matches.isEmpty()) {
            return false;
        }
        // Persist into the in-memory IItem (the writer will pick these up from
        // the new Document construction).
        java.util.List<String> ruleIds = new java.util.ArrayList<>(matches.size());
        java.util.Set<String> tagSet = new java.util.LinkedHashSet<>();
        for (YaraMatch m : matches) {
            ruleIds.add(m.getIdentifier());
            tagSet.addAll(m.getTags());
        }
        java.util.Collections.sort(ruleIds);
        item.setExtraAttribute(ExtraProperties.YARA_RULE, ruleIds);
        if (!tagSet.isEmpty()) {
            item.setExtraAttribute(ExtraProperties.YARA_TAGS, new java.util.ArrayList<>(tagSet));
        }
        try {
            String json = serializer.toJson(matches, YaraEngine.getEngineVersion(), buffer.length);
            if (json != null) {
                item.setExtraAttribute(ExtraProperties.YARA_MATCH_DETAIL, json);
            }
        } catch (IOException ioe) {
            // identifiers + tags still persisted; only detail lost
            logger.debug("YARA rerun: failed to serialize match detail for item {}: {}",
                    item.getId(), ioe.getMessage());
        }
        stats.itemsWithMatches++;
        return true;
    }

    /**
     * Bridge para {@link YaraRulesetLoader#discover(java.util.List)} +
     * {@link YaraEngine#compileSources}. Vive em uma classe inner para evitar
     * tornar as duas chamadas internas públicas (mantém superfície de API
     * mínima nas classes principais).
     */
    private static final class YaraRulesetLoaderFacade {
        static YaraEngine compile(YaraConfig yaraConfig) {
            java.util.List<File> sources = YaraRulesetLoader.discover(yaraConfig.getRuleDirectories());
            if (sources.isEmpty()) {
                return null;
            }
            return YaraEngine.compileSources(sources, (src, line, msg) -> {
                String where = (src == null) ? "?" : src.getName();
                if (line > 0) {
                    logger.warn("YARA rerun: rule compile error at {}:{} — {}", where, line, msg);
                } else {
                    logger.warn("YARA rerun: rule compile error at {} — {}", where, msg);
                }
            });
        }
    }

    /** Métricas agregadas retornadas por {@link #run()}. */
    public static final class RerunStats {
        public long itemsScanned;
        public long itemsWithMatches;
        public long itemsUpdated;
        public long itemsSkippedSize;
        public long itemsSkippedNoStream;
        public long itemsSkippedError;
        public long totalMillis;

        @Override
        public String toString() {
            return "RerunStats[scanned=" + itemsScanned
                    + ", withMatches=" + itemsWithMatches
                    + ", updated=" + itemsUpdated
                    + ", skipped=" + (itemsSkippedSize + itemsSkippedNoStream + itemsSkippedError)
                    + ", totalMillis=" + totalMillis + "]";
        }
    }
}
