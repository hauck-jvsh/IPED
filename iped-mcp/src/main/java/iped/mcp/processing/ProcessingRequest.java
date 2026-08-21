package iped.mcp.processing;

/**
 * What the examiner wants processed, after the whole request has been validated (FR-012, FR-017).
 *
 * <p>
 * Immutable, and deliberately closed: there is no free-form pass-through of engine options (FR-016).
 * An unknown argument is a refusal, not something ignored. Without that, {@code -X} and engine flags
 * would arrive through the back door and the confinement rules would be bypassable by parameter.
 *
 * <p>
 * <b>There is no password field here, and there must not be one.</b> The request carries a secret
 * <i>reference</i>, resolved server-side by {@link SecretResolver}. A resolved password is never a
 * field of anything persisted.
 */
public final class ProcessingRequest {

    private final String sourcePath;
    private final String destinationPath;
    private final String profile;
    private final String displayName;
    private final String secretRef;

    public ProcessingRequest(String sourcePath, String destinationPath, String profile, String displayName,
            String secretRef) {
        this.sourcePath = sourcePath;
        this.destinationPath = destinationPath;
        this.profile = profile;
        this.displayName = displayName;
        this.secretRef = secretRef;
    }

    /** Evidence source, a path on the machine running the server (FR-036). */
    public String getSourcePath() {
        return sourcePath;
    }

    /** Where the case is to be created, also a server-side path. */
    public String getDestinationPath() {
        return destinationPath;
    }

    public String getProfile() {
        return profile;
    }

    /**
     * Display name for the evidence inside the case (FR-014), or {@code null}.
     *
     * <p>
     * Absent means absent: the engine falls back to the file name, which is valid behaviour. It must
     * not be turned into an empty string handed to the engine.
     */
    public String getDisplayName() {
        return displayName;
    }

    /** Name the server resolves to a container password, or {@code null}. Never the password. */
    public String getSecretRef() {
        return secretRef;
    }

    public boolean hasSecretRef() {
        return secretRef != null && !secretRef.trim().isEmpty();
    }
}
