package iped.engine.task.yara;

import java.util.Objects;

/**
 * Trecho de bytes que casou uma string específica dentro de uma regra YARA.
 *
 * <p>{@code id} é o identificador da string como declarado na regra (ex.: {@code $s1},
 * {@code $re1_3}). {@code offset} é em bytes, relativo ao início do stream do item.
 * {@code hex} é a representação dos bytes brutos do trecho em lowercase, sem espaços;
 * pode estar truncado conforme {@link YaraConfig#getMatchHexMaxBytes()}, com
 * {@code truncated == true} sinalizando o corte.</p>
 */
public final class MatchedString {

    private final String id;
    private final long offset;
    private final String hex;
    private final boolean truncated;

    public MatchedString(String id, long offset, String hex, boolean truncated) {
        this.id = Objects.requireNonNull(id, "id");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0, got " + offset);
        }
        this.offset = offset;
        this.hex = (hex == null) ? "" : hex;
        this.truncated = truncated;
    }

    public String getId() {
        return id;
    }

    public long getOffset() {
        return offset;
    }

    public String getHex() {
        return hex;
    }

    public boolean isTruncated() {
        return truncated;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MatchedString)) {
            return false;
        }
        MatchedString other = (MatchedString) o;
        return offset == other.offset
                && truncated == other.truncated
                && id.equals(other.id)
                && hex.equals(other.hex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, offset, hex, truncated);
    }

    @Override
    public String toString() {
        return "MatchedString[" + id + "@" + offset + ", " + hex.length() / 2 + " bytes"
                + (truncated ? " (truncated)" : "") + "]";
    }
}
