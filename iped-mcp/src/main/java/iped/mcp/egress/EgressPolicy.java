package iped.mcp.egress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import iped.data.IItemReader;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.ContentClass;
import iped.mcp.protocol.McpError;

/**
 * Optional restriction on which evidence-derived content may leave the workstation, <b>inactive by
 * default</b> (scope decision D3, FR-038).
 *
 * <p>
 * With the policy inactive, metadata, text, thumbnail and raw content are all available, subject
 * only to the volume ceilings. That means evidence content — potentially illicit material, personal
 * data, material under seal — reaches the language model provider. The safeguard in that
 * configuration is operational, not technical: run against a local model (D4, FR-065). The session
 * says so out loud at open (FR-043).
 *
 * <p>
 * Enforcement is on the server, not in the guidance, so that the agent cannot get around it by
 * choosing a different tool or parameter (FR-040). The policy is consultable even when inactive
 * (FR-042) — an examiner has to be able to ask what is in force without having to turn it on.
 */
public class EgressPolicy {

    /** Outcome of a policy check. */
    public static class Decision {
        public final boolean allowed;
        public final String rule;
        public final String detail;

        private Decision(boolean allowed, String rule, String detail) {
            this.allowed = allowed;
            this.rule = rule;
            this.detail = detail;
        }

        static Decision allow() {
            return new Decision(true, null, null);
        }

        static Decision block(String rule, String detail) {
            return new Decision(false, rule, detail);
        }
    }

    private final McpServerConfig config;

    public EgressPolicy(McpServerConfig config) {
        this.config = config;
    }

    public boolean isActive() {
        return config.isEgressPolicyActive();
    }

    /**
     * Checks whether one class of content may be returned for one item.
     *
     * @param item
     *            the item the content belongs to; may be {@code null} when the check is about a
     *            class alone
     */
    public Decision check(ContentClass contentClass, IItemReader item) {
        if (!config.isEgressPolicyActive()) {
            return Decision.allow();
        }
        if (!config.getEgressAllowedClasses().contains(contentClass)) {
            return Decision.block("allowedClasses",
                    "content class '" + contentClass + "' is not among the allowed classes "
                            + config.getEgressAllowedClasses());
        }
        if (item == null) {
            return Decision.allow();
        }
        for (String restricted : config.getEgressRestrictedCategories()) {
            for (String category : item.getCategorySet()) {
                if (category.toLowerCase(Locale.ROOT).contains(restricted.toLowerCase(Locale.ROOT))) {
                    return Decision.block("restrictedCategories",
                            "the item is in the restricted category '" + category + "'");
                }
            }
        }
        for (String marker : config.getEgressRestrictedSensitivity()) {
            Object value = item.getExtraAttribute(marker);
            if (value != null) {
                return Decision.block("restrictedSensitivity",
                        "the item carries the sensitivity marker '" + marker + "' assigned during processing");
            }
            for (Object attribute : item.getExtraAttributeMap().values()) {
                if (attribute != null && attribute.toString().equalsIgnoreCase(marker)) {
                    return Decision.block("restrictedSensitivity",
                            "the item carries the sensitivity value '" + marker + "' assigned during processing");
                }
            }
        }
        return Decision.allow();
    }

    /**
     * Applies the policy, throwing when the content is blocked.
     *
     * @throws McpError
     *             with {@link McpError#BLOCKED_BY_POLICY}
     */
    public void enforce(ContentClass contentClass, IItemReader item) {
        Decision decision = check(contentClass, item);
        if (!decision.allowed) {
            throw new McpError(McpError.BLOCKED_BY_POLICY,
                    "The egress policy in force blocks this content: " + decision.detail + ".",
                    "This restriction is applied by the server and is not negotiable from the conversation. "
                            + "Work from the metadata, which remains available, or ask the examiner to review "
                            + "the egress policy in conf/McpServerConfig.txt.").with("rule", decision.rule)
                                    .with("contentClass", contentClass.name())
                                    .with("itemId", item == null ? null : item.getId());
        }
    }

    /** Describes the policy for {@code iped_session_info}, active or not (FR-042). */
    public Map<String, Object> describe() {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("active", config.isEgressPolicyActive());
        List<String> allowed = new ArrayList<>();
        for (ContentClass contentClass : config.getEgressAllowedClasses()) {
            allowed.add(contentClass.name());
        }
        description.put("allowedClasses", allowed);
        description.put("restrictedCategories", config.getEgressRestrictedCategories());
        description.put("restrictedSensitivity", config.getEgressRestrictedSensitivity());
        return description;
    }

    /**
     * The warning shown at session open about what may be transmitted under the current
     * configuration (FR-043).
     */
    public String openingWarning() {
        if (config.isEgressPolicyActive()) {
            List<String> allowed = new ArrayList<>();
            for (ContentClass contentClass : config.getEgressAllowedClasses()) {
                allowed.add(contentClass.name());
            }
            return "Egress policy ACTIVE. The agent may receive: " + String.join(", ", allowed)
                    + ". Blocked content never reaches the conversation, and every block is recorded in the "
                    + "audit trail with the item and the rule that blocked it.";
        }
        return "Egress policy INACTIVE — the default. Evidence content, including extracted text, thumbnails "
                + "and raw bytes, will be transmitted to whatever language model this agent runs on. That may "
                + "include illicit material, personal data and material under seal. If the model is not local "
                + "to this workstation, that content leaves this machine. Running against a local model is the "
                + "recommended configuration; see the OpenCode install guide.";
    }
}
