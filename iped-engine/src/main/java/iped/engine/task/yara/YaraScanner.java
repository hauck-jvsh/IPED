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
 *
 * <p>Para cada regra que casa, o collector itera os patterns ({@code yrx_rule_iter_patterns})
 * e dentro de cada pattern itera os matches ({@code yrx_pattern_iter_matches})
 * recortando os bytes do buffer atual do scan e codificando em hex lowercase
 * (cap configurável em {@code matchHexMaxBytes}). Esses bytes alimentam tanto o
 * JSON {@code yara:matches} quanto o highlight de texto na UI (FR-008a).</p>
 */
public final class YaraScanner implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(YaraScanner.class);

    private static final char[] HEX_LC = "0123456789abcdef".toCharArray();

    private final Pointer scannerPtr;
    private final int matchHexMaxBytes;
    private final MatchCollector collector;
    private boolean closed = false;

    YaraScanner(Pointer scannerPtr, int matchHexMaxBytes) {
        this.scannerPtr = scannerPtr;
        this.matchHexMaxBytes = Math.max(1, matchHexMaxBytes);
        this.collector = new MatchCollector(this.matchHexMaxBytes);
        // O callback é instalado uma única vez na vida do scanner — o estado
        // (lista de matches + buffer corrente) é zerado/configurado antes de cada scan().
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
        collector.reset(buffer, length);
        if (timeoutSeconds > 0) {
            YaraEngine.LibYaraX.INSTANCE.yrx_scanner_set_timeout(scannerPtr, (long) timeoutSeconds);
        }
        Memory native_buf = new Memory(length);
        native_buf.write(0, buffer, 0, length);
        try {
            int rc = YaraEngine.LibYaraX.INSTANCE.yrx_scanner_scan(scannerPtr, native_buf, (long) length);
            if (rc != YaraEngine.YRX_SUCCESS && rc != YaraEngine.YRX_SCAN_TIMEOUT) {
                logger.debug("yrx_scanner_scan returned {}", rc);
            }
            return collector.takeMatches();
        } finally {
            // Libera a referência ao buffer Java; o callback nativo NÃO mantém
            // ponteiros após o retorno de yrx_scanner_scan.
            collector.clearBuffer();
        }
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

    /** Mesmo padrão do {@link #readPointerSlice} para o pattern identifier ({@code $name}). */
    private static String readPatternIdentifier(Pointer pattern) {
        PointerByReference out = new PointerByReference();
        LongByReference len = new LongByReference();
        int rc = YaraEngine.LibYaraX.INSTANCE.yrx_pattern_identifier(pattern, out, len);
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
     *
     * <p>Mantém uma referência ao buffer Java do scan corrente para que os
     * callbacks de pattern/match consigam recortar os bytes que casaram. A
     * referência é limpa após {@code yrx_scanner_scan} retornar.</p>
     */
    private static final class MatchCollector implements YaraEngine.RuleCallback {
        private final List<YaraMatch> matches = new ArrayList<>();
        private final int matchHexMaxBytes;
        private byte[] currentBuffer;
        private int currentLength;

        MatchCollector(int matchHexMaxBytes) {
            this.matchHexMaxBytes = matchHexMaxBytes;
        }

        void reset(byte[] buffer, int length) {
            matches.clear();
            this.currentBuffer = buffer;
            this.currentLength = length;
        }

        void clearBuffer() {
            this.currentBuffer = null;
            this.currentLength = 0;
        }

        List<YaraMatch> takeMatches() {
            return new ArrayList<>(matches);
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

                List<MatchedString> strings = collectMatchedStrings(rule);
                matches.add(new YaraMatch(ns, name, tags, java.util.Collections.emptyMap(), strings));
            } catch (Throwable t) {
                logger.debug("MatchCollector failed to read YRX_RULE: {}", t.toString());
            }
        }

        /**
         * Itera os patterns da regra que casaram (apenas patterns com pelo menos
         * um match são entregues pelo {@code yrx_rule_iter_patterns}) e, para
         * cada pattern, itera os matches recortando os bytes do buffer corrente.
         */
        private List<MatchedString> collectMatchedStrings(Pointer rule) {
            final List<MatchedString> out = new ArrayList<>();
            try {
                YaraEngine.LibYaraX.INSTANCE.yrx_rule_iter_patterns(rule, (pattern, ud) -> {
                    if (pattern == null) {
                        return;
                    }
                    try {
                        final String id = readPatternIdentifier(pattern);
                        if (id.isEmpty()) {
                            return;
                        }
                        YaraEngine.LibYaraX.INSTANCE.yrx_pattern_iter_matches(pattern, (match, ud2) -> {
                            if (match == null) {
                                return;
                            }
                            try {
                                long offset = match.offset;
                                long length = match.length;
                                if (offset < 0 || length <= 0) {
                                    return;
                                }
                                boolean truncated = length > matchHexMaxBytes;
                                String hex = extractHex(offset, length);
                                out.add(new MatchedString(id, offset, hex, truncated));
                            } catch (Throwable t) {
                                logger.debug("Match iteration failed for {}: {}", id, t.toString());
                            }
                        }, Pointer.NULL);
                    } catch (Throwable t) {
                        logger.debug("Pattern iteration failed: {}", t.toString());
                    }
                }, Pointer.NULL);
            } catch (Throwable t) {
                logger.debug("Pattern listing failed: {}", t.toString());
            }
            return out;
        }

        /**
         * Recorta {@code length} bytes do buffer corrente a partir de {@code offset}
         * e codifica em hex lowercase. Clampa em {@link #matchHexMaxBytes} para
         * proteger contra patterns gigantes; o caller marca
         * {@link MatchedString#isTruncated()} quando isso ocorre.
         */
        private String extractHex(long offset, long length) {
            if (currentBuffer == null || currentLength <= 0) {
                return "";
            }
            if (offset >= currentLength) {
                return "";
            }
            long available = currentLength - offset;
            long take = Math.min(Math.min(length, available), (long) matchHexMaxBytes);
            if (take <= 0) {
                return "";
            }
            int o = (int) offset;
            int n = (int) take;
            char[] hex = new char[n * 2];
            for (int i = 0; i < n; i++) {
                int b = currentBuffer[o + i] & 0xFF;
                hex[i * 2] = HEX_LC[b >>> 4];
                hex[i * 2 + 1] = HEX_LC[b & 0xF];
            }
            return new String(hex);
        }
    }
}
