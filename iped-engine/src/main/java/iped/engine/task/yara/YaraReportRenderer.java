package iped.engine.task.yara;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.localization.Messages;

/**
 * Helper estático para renderizar o JSON do campo {@code yara:matches} como
 * HTML estruturado. Consumido pelo {@code HTMLReportTask} (FR-010) — todos
 * os valores derivados do item são escapados via {@link #htmlEscape(String)}
 * para impedir injeção HTML a partir do conteúdo casado.
 *
 * <p>Extraído como classe própria porque o {@code HTMLReportTask} possui um
 * static initializer que depende do {@code ConfigurationManager} singleton,
 * impossibilitando carregar a classe em unit tests sem bootstrap completo do
 * caso. Este helper não tem dependência estática nenhuma além de SLF4J + Messages.</p>
 */
public final class YaraReportRenderer {

    private static final Logger logger = LoggerFactory.getLogger(YaraReportRenderer.class);

    private YaraReportRenderer() {
        // Static utility — no instantiation.
    }

    /**
     * Renderiza o JSON do campo {@code yara:matches} como HTML estruturado:
     * bloco por regra com identificador, tags e tabela de strings (id, offset,
     * hex truncado). Retorna {@code null} se o input é vazio / inválido /
     * sem matches — o caller pode então decidir não emitir nada no relatório.
     */
    public static String renderHtml(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        List<YaraMatch> matches;
        try {
            // matchHexMaxBytes não é usado pelo fromJson — só pelo toJson.
            // Usamos um número qualquer válido (>0).
            matches = new YaraMatchSerializer(256).fromJson(json);
        } catch (Exception e) {
            logger.debug("YaraReportRenderer: failed to parse yara:matches JSON: {}", e.toString());
            return null;
        }
        if (matches.isEmpty()) {
            return null;
        }
        String labelRule = Messages.getString("HTMLReportTask.YaraRule");
        String labelTag = Messages.getString("HTMLReportTask.YaraTag");
        String labelString = Messages.getString("HTMLReportTask.YaraString");
        String labelOffset = Messages.getString("HTMLReportTask.YaraOffset");
        String labelHex = Messages.getString("HTMLReportTask.YaraHex");

        StringBuilder html = new StringBuilder(512);
        for (YaraMatch m : matches) {
            html.append("<div style=\"margin-bottom:6px;\">");
            html.append("<b>").append(htmlEscape(labelRule)).append(":</b> ");
            html.append(htmlEscape(m.getIdentifier()));
            if (!m.getTags().isEmpty()) {
                html.append(" &nbsp; <b>").append(htmlEscape(labelTag)).append(":</b> ");
                html.append(htmlEscape(String.join(", ", m.getTags())));
            }
            if (!m.getStrings().isEmpty()) {
                html.append("<table style=\"margin-top:2px;border-collapse:collapse;font-family:monospace;font-size:11px;\">");
                html.append("<tr>")
                        .append("<th style=\"text-align:left;padding:1px 6px;border-bottom:1px solid #ccc;\">").append(htmlEscape(labelString)).append("</th>")
                        .append("<th style=\"text-align:right;padding:1px 6px;border-bottom:1px solid #ccc;\">").append(htmlEscape(labelOffset)).append("</th>")
                        .append("<th style=\"text-align:left;padding:1px 6px;border-bottom:1px solid #ccc;\">").append(htmlEscape(labelHex)).append("</th>")
                        .append("</tr>");
                for (MatchedString s : m.getStrings()) {
                    html.append("<tr>");
                    html.append("<td style=\"padding:1px 6px;\">").append(htmlEscape(s.getId())).append("</td>");
                    html.append("<td style=\"text-align:right;padding:1px 6px;\">").append(s.getOffset()).append("</td>");
                    html.append("<td style=\"padding:1px 6px;\">").append(htmlEscape(s.getHex()));
                    if (s.isTruncated()) {
                        html.append("&hellip;");
                    }
                    html.append("</td>");
                    html.append("</tr>");
                }
                html.append("</table>");
            }
            html.append("</div>");
        }
        return html.toString();
    }

    /**
     * HTML escaping mínimo para os 5 caracteres críticos. Evita adicionar uma
     * dependência (commons-text) só para isso.
     */
    static String htmlEscape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&#39;");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }
}
