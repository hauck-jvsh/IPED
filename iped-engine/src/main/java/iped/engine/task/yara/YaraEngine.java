package iped.engine.task.yara;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Bindings JNA finos para a engine YARA-X via {@code libyara-x-capi}.
 *
 * <p>YARA-X é a reescrita oficial em Rust do YARA por Victor M. Alvarez (mesmo
 * autor do YARA clássico). IPED migrou de libyara 4.x clássica para YARA-X
 * em 2026-05-19 — ver {@code specs/001-yara-rules-engine/research.md} §R-01.</p>
 *
 * <p>Superfície intencionalmente mínima (ver
 * {@code specs/001-yara-rules-engine/research.md} → R-02):</p>
 * <ul>
 *   <li>{@code yrx_compiler_create/destroy/ban_module/new_namespace/add_source_with_origin/errors_json/build}
 *       — compilação do catálogo;</li>
 *   <li>{@code yrx_rules_destroy} — cleanup do ruleset;</li>
 *   <li>{@code yrx_scanner_create/destroy/set_timeout/on_matching_rule/scan} — scan por item;</li>
 *   <li>{@code yrx_rule_identifier/namespace/iter_tags} — leitura no callback;</li>
 *   <li>{@code yrx_buffer_destroy}, {@code yrx_last_error} — utilidades.</li>
 * </ul>
 *
 * <p>O módulo {@code cuckoo} é banido em runtime via {@code yrx_compiler_ban_module}.
 * Regras com {@code import "cuckoo"} produzem erro de compilação isolado e a regra
 * é descartada; as demais continuam (FR-002 + FR-005 + R-09).</p>
 *
 * <p><b>Limitação conhecida desta v1 do binding:</b> a extração detalhada de
 * matched-strings (offsets + bytes) ainda não é feita — apesar de o YARA-X expor
 * iteradores limpos ({@code yrx_rule_iter_patterns} / {@code yrx_pattern_iter_matches})
 * que tornam isso muito mais simples do que era na libyara clássica, esta iteração
 * (slice "Foundation + JNA") entrega apenas a coleta de identificadores + tags.
 * O contrato em {@code lucene-fields.contract.md} permite {@code strings: []} sem
 * quebra.</p>
 */
public final class YaraEngine implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(YaraEngine.class);

    /** Flags do compilador (yara-x: {@code YRX_RELAXED_RE_SYNTAX}). */
    public static final int YRX_RELAXED_RE_SYNTAX = 2;

    /** Códigos de YRX_RESULT em ordem do header upstream. */
    public static final int YRX_SUCCESS = 0;
    public static final int YRX_SYNTAX_ERROR = 1;
    public static final int YRX_VARIABLE_ERROR = 2;
    public static final int YRX_SCAN_ERROR = 3;
    public static final int YRX_SCAN_TIMEOUT = 4;
    public static final int YRX_INVALID_ARGUMENT = 5;
    public static final int YRX_INVALID_UTF8 = 6;
    public static final int YRX_INVALID_STATE = 7;
    public static final int YRX_SERIALIZATION_ERROR = 8;
    public static final int YRX_NO_METADATA = 9;
    public static final int YRX_NOT_SUPPORTED = 10;

    /**
     * Identifier reportado em {@code yara:matches.engineVersion}.
     *
     * <p>O C API do YARA-X não expõe a versão programaticamente, então é fixada
     * aqui para casar exatamente com o binário {@code libyara-x-capi} bundled
     * em {@code tools/yara-x/<os>/}. Ao atualizar o binário, atualizar esta
     * constante, o {@code YARAX_VERSION} no workflow de CI e o
     * {@code tools/yara-x/README.md}.</p>
     */
    public static final String ENGINE_VERSION = "yara-x-1.16.0";

    private static volatile boolean libraryAvailable = false;
    private static volatile String libraryLoadError = null;

    private final Pointer rulesPtr;

    private YaraEngine(Pointer rulesPtr) {
        this.rulesPtr = rulesPtr;
    }

    /**
     * Ativa a biblioteca nativa {@code libyara-x-capi}, opcionalmente usando
     * {@code hint} como primeiro caminho a tentar. Idempotente.
     *
     * @return {@code true} se a engine está disponível para uso.
     */
    public static synchronized boolean ensureAvailable(String hint) {
        if (libraryAvailable) {
            return true;
        }
        if (libraryLoadError != null) {
            return false;
        }
        try {
            if (hint != null && !hint.isEmpty()) {
                File hintFile = new File(hint);
                if (hintFile.exists()) {
                    NativeLibrary.addSearchPath("yara_x_capi", hintFile.getParentFile().getAbsolutePath());
                }
            }
            // Força o link e qualquer falha vira UnsatisfiedLinkError aqui.
            LibYaraX lib = LibYaraX.INSTANCE;
            // Chama uma função inócua para garantir que o linker resolveu o símbolo.
            lib.yrx_last_error();
            libraryAvailable = true;
            logger.info("YARA-X engine loaded (platform: {})", Platform.RESOURCE_PREFIX);
            return true;
        } catch (UnsatisfiedLinkError e) {
            libraryLoadError = "libyara-x-capi not loadable: " + e.getMessage();
            logger.warn("YARA-X engine disabled — {}", libraryLoadError);
            return false;
        } catch (Throwable t) {
            libraryLoadError = "libyara-x-capi init error: " + t.getMessage();
            logger.warn("YARA-X engine disabled — {}", libraryLoadError);
            return false;
        }
    }

    /** Shutdown idempotente (no-op para o YARA-X — a libyara clássica precisava de {@code yr_finalize}). */
    public static synchronized void shutdown() {
        libraryAvailable = false;
    }

    public static boolean isAvailable() {
        return libraryAvailable;
    }

    /**
     * Versão da engine no formato {@code yara-x-<MAJOR.MINOR.PATCH>}. Fixada em
     * {@link #ENGINE_VERSION} — ver a constante para política de atualização.
     */
    public static String getEngineVersion() {
        return libraryAvailable ? ENGINE_VERSION : "yara-x-unavailable";
    }

    /**
     * Compila um catálogo de regras fonte. Para cada arquivo {@code .yar}/{@code .yara}:
     * (1) define um namespace = basename sem extensão; (2) faz
     * {@code yrx_compiler_add_source_with_origin}; (3) em caso de falha, extrai
     * erros via {@code yrx_compiler_errors_json} e reporta no {@code errorSink}.
     * Ao final, chama {@code yrx_compiler_build}. O módulo {@code cuckoo} é banido
     * antes da compilação.
     *
     * @return ruleset compilado, ou {@code null} se nenhuma regra compilou.
     */
    public static YaraEngine compileSources(List<File> sourceFiles, CompileErrorSink errorSink) {
        if (!ensureAvailable(null) || sourceFiles == null || sourceFiles.isEmpty()) {
            return null;
        }
        PointerByReference compilerRef = new PointerByReference();
        int rc = LibYaraX.INSTANCE.yrx_compiler_create(YRX_RELAXED_RE_SYNTAX, compilerRef);
        if (rc != YRX_SUCCESS) {
            logger.warn("yrx_compiler_create returned {}: {}", rc, lastError());
            return null;
        }
        Pointer compiler = compilerRef.getValue();
        try {
            // Bane o módulo cuckoo para que regras importando-o falhem com mensagem clara.
            int banRc = LibYaraX.INSTANCE.yrx_compiler_ban_module(compiler,
                    "cuckoo",
                    "Cuckoo module unsupported",
                    "IPED does not bundle the cuckoo module; rules importing it are rejected.");
            if (banRc != YRX_SUCCESS) {
                logger.debug("yrx_compiler_ban_module(cuckoo) returned {} — continuing", banRc);
            }

            int sourcesAdded = 0;
            for (File f : sourceFiles) {
                if (!f.isFile()) {
                    if (errorSink != null) {
                        errorSink.report(f, -1, "not a regular file");
                    }
                    continue;
                }
                String ns = stripExt(f.getName());
                int nsRc = LibYaraX.INSTANCE.yrx_compiler_new_namespace(compiler, ns);
                if (nsRc != YRX_SUCCESS) {
                    if (errorSink != null) {
                        errorSink.report(f, -1, "yrx_compiler_new_namespace returned " + nsRc);
                    }
                    continue;
                }
                String src;
                try {
                    src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } catch (IOException ioe) {
                    if (errorSink != null) {
                        errorSink.report(f, -1, "read error: " + ioe.getMessage());
                    }
                    continue;
                }
                int addRc = LibYaraX.INSTANCE.yrx_compiler_add_source_with_origin(compiler, src, f.getAbsolutePath());
                if (addRc != YRX_SUCCESS) {
                    reportCompilerErrors(compiler, f, errorSink);
                    // Continua mesmo com erro — outras regras do catálogo podem ter compilado.
                    continue;
                }
                sourcesAdded++;
            }
            if (sourcesAdded == 0) {
                return null;
            }
            Pointer rules = LibYaraX.INSTANCE.yrx_compiler_build(compiler);
            if (rules == null) {
                logger.warn("yrx_compiler_build returned NULL: {}", lastError());
                return null;
            }
            return new YaraEngine(rules);
        } finally {
            LibYaraX.INSTANCE.yrx_compiler_destroy(compiler);
        }
    }

    /**
     * Escaneia um buffer e retorna a lista de matches. A lista de matched-strings
     * dentro de cada {@link YaraMatch} pode estar vazia nesta v1 (ver classdoc).
     *
     * @param buffer bytes a escanear
     * @param length bytes válidos no buffer (≤ {@code buffer.length})
     * @param timeoutSeconds {@code 0} = sem timeout; {@code > 0} = limite em segundos
     */
    public List<YaraMatch> scan(byte[] buffer, int length, int timeoutSeconds) {
        if (rulesPtr == null || buffer == null || length <= 0 || !libraryAvailable) {
            return Collections.emptyList();
        }
        PointerByReference scannerRef = new PointerByReference();
        int rc = LibYaraX.INSTANCE.yrx_scanner_create(rulesPtr, scannerRef);
        if (rc != YRX_SUCCESS) {
            logger.debug("yrx_scanner_create returned {}: {}", rc, lastError());
            return Collections.emptyList();
        }
        Pointer scanner = scannerRef.getValue();
        try {
            if (timeoutSeconds > 0) {
                LibYaraX.INSTANCE.yrx_scanner_set_timeout(scanner, (long) timeoutSeconds);
            }
            MatchCollector collector = new MatchCollector();
            LibYaraX.INSTANCE.yrx_scanner_on_matching_rule(scanner, collector, Pointer.NULL);

            Memory native_buf = new Memory(length);
            native_buf.write(0, buffer, 0, length);
            int scanRc = LibYaraX.INSTANCE.yrx_scanner_scan(scanner, native_buf, (long) length);
            if (scanRc != YRX_SUCCESS && scanRc != YRX_SCAN_TIMEOUT) {
                logger.debug("yrx_scanner_scan returned {}", scanRc);
            }
            return collector.getMatches();
        } finally {
            LibYaraX.INSTANCE.yrx_scanner_destroy(scanner);
        }
    }

    @Override
    public void close() {
        if (rulesPtr != null && libraryAvailable) {
            try {
                LibYaraX.INSTANCE.yrx_rules_destroy(rulesPtr);
            } catch (Throwable t) {
                logger.warn("yrx_rules_destroy threw {}: {}", t.getClass().getSimpleName(), t.getMessage());
            }
        }
    }

    /* ---------------------------------------------------------------------- */
    /*                    Helpers de erro / introspecção                       */
    /* ---------------------------------------------------------------------- */

    private static String lastError() {
        try {
            String e = LibYaraX.INSTANCE.yrx_last_error();
            return (e == null || e.isEmpty()) ? "(no detail)" : e;
        } catch (Throwable t) {
            return "(yrx_last_error unavailable)";
        }
    }

    /**
     * Extrai os erros acumulados do compilador via {@code yrx_compiler_errors_json}
     * e os reporta ao sink um a um.
     */
    private static void reportCompilerErrors(Pointer compiler, File source, CompileErrorSink sink) {
        if (sink == null) {
            return;
        }
        PointerByReference bufRef = new PointerByReference();
        int rc = LibYaraX.INSTANCE.yrx_compiler_errors_json(compiler, bufRef);
        if (rc != YRX_SUCCESS || bufRef.getValue() == null) {
            sink.report(source, -1, "compile error (yrx_compiler_errors_json failed, last_error=" + lastError() + ")");
            return;
        }
        Pointer bufPtr = bufRef.getValue();
        try {
            YRX_BUFFER buf = Structure.newInstance(YRX_BUFFER.class, bufPtr);
            buf.read();
            if (buf.data == null || buf.length <= 0) {
                sink.report(source, -1, "compile error (empty errors_json buffer)");
                return;
            }
            byte[] jsonBytes = buf.data.getByteArray(0, (int) buf.length);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            parseErrorsJson(json, source, sink);
        } catch (Throwable t) {
            sink.report(source, -1, "compile error (failed to parse errors_json: " + t.getMessage() + ")");
        } finally {
            LibYaraX.INSTANCE.yrx_buffer_destroy(bufPtr);
        }
    }

    private static void parseErrorsJson(String json, File source, CompileErrorSink sink) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            // O JSON do yara-x é uma lista de objetos com campos como
            // {"code","title","origin":{"file","line",...},"text",...}.
            // Aceitamos tanto array-raiz quanto objeto com "errors":[...].
            JsonNode errors = root.isArray() ? root : root.path("errors");
            if (errors == null || !errors.isArray() || errors.size() == 0) {
                // Sem erros estruturados — reporta o JSON cru como fallback.
                sink.report(source, -1, json.length() > 500 ? json.substring(0, 500) : json);
                return;
            }
            for (JsonNode err : errors) {
                int line = -1;
                JsonNode lineNode = err.path("origin").path("line");
                if (lineNode.isInt()) {
                    line = lineNode.asInt();
                } else if (err.path("line").isInt()) {
                    line = err.path("line").asInt();
                }
                String title = err.path("title").asText("");
                String text = err.path("text").asText("");
                String message = title.isEmpty() ? text : (text.isEmpty() ? title : title + " — " + text);
                if (message.isEmpty()) {
                    message = err.toString();
                }
                sink.report(source, line, message);
            }
        } catch (Throwable t) {
            sink.report(source, -1, "compile error (parse failed: " + t.getMessage() + "): " + json);
        }
    }

    private static String stripExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }

    /* ---------------------------------------------------------------------- */
    /*                          Internal — JNA binding                         */
    /* ---------------------------------------------------------------------- */

    private interface LibYaraX extends Library {
        LibYaraX INSTANCE = Native.load("yara_x_capi", LibYaraX.class);

        // Compiler lifecycle
        int yrx_compiler_create(int flags, PointerByReference compiler);

        void yrx_compiler_destroy(Pointer compiler);

        int yrx_compiler_ban_module(Pointer compiler, String moduleName, String errorTitle, String errorMsg);

        int yrx_compiler_new_namespace(Pointer compiler, String namespaceName);

        int yrx_compiler_add_source_with_origin(Pointer compiler, String src, String origin);

        int yrx_compiler_errors_json(Pointer compiler, PointerByReference buf);

        Pointer yrx_compiler_build(Pointer compiler);

        // Rules
        void yrx_rules_destroy(Pointer rules);

        // Scanner
        int yrx_scanner_create(Pointer rules, PointerByReference scanner);

        void yrx_scanner_destroy(Pointer scanner);

        int yrx_scanner_set_timeout(Pointer scanner, long timeoutSeconds);

        int yrx_scanner_on_matching_rule(Pointer scanner, RuleCallback callback, Pointer userData);

        int yrx_scanner_scan(Pointer scanner, Pointer data, long len);

        // Rule introspection (callback context)
        int yrx_rule_identifier(Pointer rule, PointerByReference ident, LongByReference len);

        int yrx_rule_namespace(Pointer rule, PointerByReference ns, LongByReference len);

        int yrx_rule_iter_tags(Pointer rule, TagCallback callback, Pointer userData);

        // Buffer
        void yrx_buffer_destroy(Pointer buf);

        // Misc
        String yrx_last_error();
    }

    /** Layout do {@code YRX_BUFFER} ({@code uint8_t *data; size_t length;}). */
    public static class YRX_BUFFER extends Structure {
        public Pointer data;
        public long length;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("data", "length");
        }
    }

    /** Callback nativo para matches: {@code void (*)(const YRX_RULE *rule, void *user_data)}. */
    public interface RuleCallback extends Callback {
        void invoke(Pointer rule, Pointer userData);
    }

    /** Callback nativo para tags: {@code void (*)(const char *tag, void *user_data)}. */
    public interface TagCallback extends Callback {
        void invoke(String tag, Pointer userData);
    }

    /**
     * Coleta {@link YaraMatch}es no callback de scan do YARA-X. Para cada
     * regra que casa, lê identifier + namespace via os getters e popula as tags
     * via {@code yrx_rule_iter_tags}.
     */
    private static final class MatchCollector implements RuleCallback {
        private final List<YaraMatch> matches = new ArrayList<>();

        @Override
        public void invoke(Pointer rule, Pointer userData) {
            if (rule == null) {
                return;
            }
            try {
                String name = readPointerSlice(rule, true);
                String ns = readPointerSlice(rule, false);
                final List<String> tags = new ArrayList<>();
                LibYaraX.INSTANCE.yrx_rule_iter_tags(rule, (tag, ud) -> {
                    if (tag != null && !tag.isEmpty()) {
                        tags.add(tag);
                    }
                }, Pointer.NULL);
                matches.add(new YaraMatch(ns, name, tags, Collections.emptyMap(), Collections.emptyList()));
            } catch (Throwable t) {
                logger.debug("MatchCollector failed to read YRX_RULE: {}", t.toString());
            }
        }

        List<YaraMatch> getMatches() {
            return matches;
        }
    }

    /**
     * Lê o identifier (se {@code identifierMode}) ou namespace de uma regra
     * via {@code yrx_rule_identifier}/{@code yrx_rule_namespace}. Os ponteiros
     * retornados NÃO são NUL-terminados — o comprimento vem em {@code len}.
     */
    private static String readPointerSlice(Pointer rule, boolean identifierMode) {
        PointerByReference out = new PointerByReference();
        LongByReference len = new LongByReference();
        int rc = identifierMode
                ? LibYaraX.INSTANCE.yrx_rule_identifier(rule, out, len)
                : LibYaraX.INSTANCE.yrx_rule_namespace(rule, out, len);
        if (rc != YRX_SUCCESS || out.getValue() == null || len.getValue() <= 0) {
            return "";
        }
        byte[] bytes = out.getValue().getByteArray(0, (int) len.getValue());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Sink simples para reportar erros de compilação individuais (FR-002 + FR-005). */
    public interface CompileErrorSink {
        void report(File source, int lineNumber, String message);
    }
}
