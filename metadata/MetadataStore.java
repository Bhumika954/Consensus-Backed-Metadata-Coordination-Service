package metadata;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hierarchical in-memory key-value metadata store.
 *
 * Keys are POSIX-style paths such as:
 *   /config/payment/timeout
 *
 * For this initial version we model the hierarchy implicitly via the
 * key strings themselves and store values in a sorted map to make
 * demos and debugging easier to read.
 *
 * Concurrency model:
 * - All mutations happen on the Raft node's event thread when
 *   log entries are applied, so we keep this class deliberately
 *   simple and single-threaded (synchronized).
 */
public class MetadataStore {

    private final TreeMap<String, String> data = new TreeMap<>();

    public synchronized void apply(MetadataCommand command) {
        switch (command.type) {
            case PUT -> data.put(command.key, command.value);
            case DELETE -> data.remove(command.key);
        }
    }

    /**
     * Leader-only read path for linearizable GET.
     */
    public synchronized String get(String key) {
        return data.get(key);
    }

    /**
     * Read-only snapshot of current metadata for debugging / demo.
     */
    public synchronized Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(data));
    }
}

