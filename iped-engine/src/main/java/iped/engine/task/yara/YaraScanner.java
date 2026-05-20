package iped.engine.task.yara;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Per-worker scanner wrapper. Cada {@code YaraScanner} é dono de um
 * {@code YRX_SCANNER*} criado a partir do {@link YaraEngine} compartilhado.
 *
 * <p>O scanner do YARA-X <b>não é thread-safe</b>: cada thread/worker que
 * scaneia precisa do seu próprio. O {@code YRX_RULES} subjacente (compartilhado
 * via {@link YaraEngine}) é seguro para uso concorrente read-only.</p>
 *
 * <p>A instância instala uma única vez o callback de match via
 * {@code yrx_scanner_on_matching_rule}; a cada chamada de {@link #scan} a lista
 * acumulada é zerada antes do scan e o scanner é reusado.</p>
 */
public final class YaraScanner implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(YaraScanner.class);

    private final Pointer scannerPtr;
    private final MatchCollector collector;
    private boolean closed = false;

    YaraScanner(Pointer scannerPtr) {
        this.scannerPtr = scannerPtr;
        this.collector = new MatchCollector();
        // O callback é instalado uma única vez na vida do scanner — o estado
        // (lista de matches) é zerado antes de cada scan().
        YaraEngine.LibYaraX.INSTANCE.yrx_scanner_on_matching_rule(scannerPtr, collector, Pointer.NULL);
    }

    /**
     * Escaneia um buffer e retorna a lista de matches da chamada atual.
     *
     * @param buffer bytes a escanear
     * @param length bytes válidos no buffer (≤ {@code buffer.length})
     * @param timeoutSeconds {@code 0} = sem timeout; {@code > 0} = limite em segundos
     */
    public List<YaraMatch> scan(byte[] buffer, int length, int timeoutSeconds) {
        if (closed || scannerPtr == null || buffer == null || length <= 0) {
            return Collections.emptyList();
        }
        collector.reset();
        if (timeoutSeconds > 0) {
            YaraEngine.LibYaraX.INSTANCE.yrx_scanner_set_timeout(scannerPtr, (long) timeoutSeconds);
        }
        Memory native_buf = new Memory(length);
        native_buf.write(0, buffer, 0, length);
        int rc = YaraEngine.LibYaraX.INSTANCE.yrx_scanner_scan(scannerPtr, native_buf, (long) length);
        if (rc != YaraEngine.YRX_SUCCESS && rc != YaraEngine.YRX_SCAN_TIMEOUT) {
            logger.debug("yrx_scanner_scan returned {}", rc);
        }
        return collector.getMatches();
    }

    @Override
    public void close() {
        if (!closed && scannerPtr != null && YaraEngine.isAvailable()) {
            try {
                YaraEngine.LibYaraX.INSTANCE.yrx_scanner_destroy(scannerPtr);
            } catch (Throwable t) {
                logger.warn("yrx_scanner_destroy threw {}: {}", t.getClass().getSimpleName(), t.getMessage());
            }
        }
        closed = true;
    }

    /**
     * Lê o identifier ou namespace de uma regra via
     * {@code yrx_rule_identifier}/{@code yrx_rule_namespace}. Os ponteiros
     * retornados pela libyara-x-capi <b>não são NUL-terminados</b> — o
     * comprimento vem em {@code len}.
     */
    private static String readPointerSlice(Pointer rule, boolean identifierMode) {
        PointerByReference out = new PointerByReference();
        LongByReference len = new LongByReference();
        int rc = identifierMode
                ? YaraEngine.LibYaraX.INSTANCE.yrx_rule_identifier(rule, out, len)
                : YaraEngine.LibYaraX.INSTANCE.yrx_rule_namespace(rule, out, len);
        if (rc != YaraEngine.YRX_SUCCESS || out.getValue() == null || len.getValue() <= 0) {
            return "";
        }
        byte[] bytes = out.getValue().getByteArray(0, (int) len.getValue());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Coleta {@link YaraMatch}es no callback de scan do YARA-X. A lista interna
     * é zerada a cada {@link YaraScanner#scan} para evitar acumular matches
     * de scans anteriores.
     */
    private static final class MatchCollector implements YaraEngine.RuleCallback {
        private final List<YaraMatch> matches = new ArrayList<>();

        void reset() {
            matches.clear();
        }

        List<YaraMatch> getMatches() {
            return matches;
        }

        @Override
        public void invoke(Pointer rule, Pointer userData) {
            if (rule == null) {
                return;
            }
            try {
                String name = readPointerSlice(rule, true);
                String ns = readPointerSlice(rule, false);
                final List<String> tags = new ArrayList<>();
                YaraEngine.LibYaraX.INSTANCE.yrx_rule_iter_tags(rule, (tag, ud) -> {
                    if (tag != null && !tag.isEmpty()) {
                        tags.add(tag);
                    }
                }, Pointer.NULL);
                matches.add(new YaraMatch(ns, name, tags, java.util.Collections.emptyMap(), java.util.Collections.emptyList()));
            } catch (Throwable t) {
                logger.debug("MatchCollector failed to read YRX_RULE: {}", t.toString());
            }
        }
    }
}
