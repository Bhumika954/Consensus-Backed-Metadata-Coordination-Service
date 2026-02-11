package metadata;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Single metadata operation to be replicated via the Raft log.
 *
 * We intentionally keep the wire format simple and self-describing:
 *
 *   PUT:  "PUT\n<key>\n<value>"
 *   DEL:  "DEL\n<key>"
 *
 * encoded as UTF-8 bytes.
 *
 * This makes inspection in logs and demos easy while keeping the
 * command semantics explicit and future-proof.
 */
public final class MetadataCommand {

    public enum Type {
        PUT,
        DELETE
    }

    public final Type type;
    public final String key;
    public final String value; // null for DELETE

    private MetadataCommand(Type type, String key, String value) {
        this.type = Objects.requireNonNull(type, "type");
        this.key = Objects.requireNonNull(key, "key");
        this.value = value;
    }

    public static MetadataCommand put(String key, String value) {
        return new MetadataCommand(Type.PUT, key, Objects.requireNonNull(value, "value"));
    }

    public static MetadataCommand delete(String key) {
        return new MetadataCommand(Type.DELETE, key, null);
    }

    /**
     * Serialize this command into a UTF-8 payload suitable for
     * embedding into a RaftLogEntry.
     */
    public byte[] serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(type == Type.PUT ? "PUT" : "DEL").append('\n');
        sb.append(key);
        if (type == Type.PUT) {
            sb.append('\n').append(value);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parse a command that was previously serialized with {@link #serialize()}.
     */
    public static MetadataCommand deserialize(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = s.split("\n", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid MetadataCommand encoding: " + s);
        }
        String op = parts[0];
        String key = parts[1];
        if ("PUT".equals(op)) {
            String value = parts.length >= 3 ? parts[2] : "";
            return put(key, value);
        } else if ("DEL".equals(op)) {
            return delete(key);
        } else {
            throw new IllegalArgumentException("Unknown MetadataCommand op: " + op);
        }
    }
}

