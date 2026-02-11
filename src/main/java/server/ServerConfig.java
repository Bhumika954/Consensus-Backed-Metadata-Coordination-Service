package server;

import java.nio.file.Path;

/**
 * Configuration for Raft HTTP server.
 */
public final class ServerConfig {

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_LATENCY_MS_MIN = 0;
    public static final int DEFAULT_LATENCY_MS_MAX = 50;
    public static final double DEFAULT_DROP_PROBABILITY = 0;

    private final int port;
    private final Path dataDir;
    private final int latencyMsMin;
    private final int latencyMsMax;
    private final double dropProbability;
    private final boolean persistenceEnabled;

    public ServerConfig(int port, Path dataDir, int latencyMsMin, int latencyMsMax, double dropProbability, boolean persistenceEnabled) {
        this.port = port;
        this.dataDir = dataDir;
        this.latencyMsMin = latencyMsMin;
        this.latencyMsMax = latencyMsMax;
        this.dropProbability = dropProbability;
        this.persistenceEnabled = persistenceEnabled;
    }

    public int getPort() { return port; }
    public Path getDataDir() { return dataDir; }
    public int getLatencyMsMin() { return latencyMsMin; }
    public int getLatencyMsMax() { return latencyMsMax; }
    public double getDropProbability() { return dropProbability; }
    public boolean isPersistenceEnabled() { return persistenceEnabled; }

    public static ServerConfig defaults() {
        return new ServerConfig(
                DEFAULT_PORT,
                Path.of("data"),
                DEFAULT_LATENCY_MS_MIN,
                DEFAULT_LATENCY_MS_MAX,
                DEFAULT_DROP_PROBABILITY,
                true
        );
    }
}
