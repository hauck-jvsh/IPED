package iped.mcp.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The processing profiles the agent is allowed to name (FR-013).
 *
 * <p>
 * No profile is compiled in. The permitted set is whatever {@code processingProfiles} declares,
 * which is Principle IV applied to the one parameter that decides how the evidence gets examined:
 * the agent chooses <i>between</i> profiles, never <i>inside</i> one.
 *
 * <p>
 * The distributed default leaves {@code blind} and {@code pedo} out. Both have effects an examiner
 * should switch on deliberately rather than inherit from a default someone else wrote.
 */
public final class ProfileRegistry {

    private final List<String> permitted;

    public ProfileRegistry(List<String> declared) {
        List<String> normalized = new ArrayList<>();
        if (declared != null) {
            for (String profile : declared) {
                if (profile != null && !profile.trim().isEmpty()) {
                    normalized.add(profile.trim());
                }
            }
        }
        this.permitted = Collections.unmodifiableList(normalized);
    }

    /** The permitted profiles, in declaration order, for a refusal that lists them (FR-013). */
    public List<String> getPermitted() {
        return permitted;
    }

    /**
     * Whether a profile may be named.
     *
     * <p>
     * Compared case-insensitively in the root locale: profile names are ASCII identifiers, and
     * matching them under the machine's locale would make {@code TRIAGE} resolve differently on a
     * Turkish system — the kind of environment-dependent behaviour Principle V rules out.
     */
    public boolean isPermitted(String profile) {
        if (profile == null || profile.trim().isEmpty()) {
            return false;
        }
        String candidate = profile.trim().toLowerCase(Locale.ROOT);
        for (String allowed : permitted) {
            if (allowed.toLowerCase(Locale.ROOT).equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return permitted.isEmpty();
    }
}
