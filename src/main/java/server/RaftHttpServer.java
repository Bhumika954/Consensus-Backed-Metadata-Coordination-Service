package server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.websocket.WsContext;
import raft.RaftNode;
import raft.RaftRole;
import raft.SimulatedRpcClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HTTP server exposing Raft cluster state for the React frontend.
 * Supports WebSocket for live push, metrics API, configurable network simulation.
 */
public final class RaftHttpServer {

    private static final String CORS_ORIGIN = "http://localhost:5173";
    private static final Duration MIN_ELECTION_TIMEOUT = Duration.ofMillis(500);
    private static final Duration MAX_ELECTION_TIMEOUT = Duration.ofMillis(900);

    private final ServerConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final List<String> nodeIds = List.of("n1", "n2", "n3");
    private final Map<String, RaftNode> cluster = new ConcurrentHashMap<>();
    private final Set<String> crashedNodeIds = ConcurrentHashMap.newKeySet();
    private final Set<WsContext> wsClients = ConcurrentHashMap.newKeySet();
    private final MetricsCollector metrics = new MetricsCollector();
    private final ScheduledExecutorService broadcastScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "raft-broadcast");
        t.setDaemon(true);
        return t;
    });
    private volatile String lastLeaderId = null;
    private volatile long lastLeaderChangeMs = 0;
    private RaftNode.InProcessRpcClient rawRpcClient;
    private raft.LeaderElection.RpcClient rpcClient;
    private Javalin app;

    public RaftHttpServer(ServerConfig config) {
        this.config = config != null ? config : ServerConfig.defaults();
        this.rawRpcClient = new RaftNode.InProcessRpcClient(cluster);
        this.rpcClient = wrapWithNetworkSimulation(rawRpcClient);
        bootstrapCluster();
    }

    public RaftHttpServer() {
        this(ServerConfig.defaults());
    }

    private raft.LeaderElection.RpcClient wrapWithNetworkSimulation(raft.LeaderElection.RpcClient client) {
        int min = config.getLatencyMsMin();
        int max = config.getLatencyMsMax();
        double drop = config.getDropProbability();
        if (min == 0 && max == 0 && drop == 0) {
            return client;
        }
        return new SimulatedRpcClient(client, min, max, drop);
    }

    private void bootstrapCluster() {
        Path baseDataDir = config.getDataDir();
        boolean persist = config.isPersistenceEnabled() && baseDataDir != null;

        for (String nodeId : nodeIds) {
            List<String> peers = nodeIds.stream()
                    .filter(id -> !id.equals(nodeId))
                    .toList();
            RaftNode node = createNode(nodeId, peers, persist, baseDataDir);
            cluster.put(nodeId, node);
            node.start();
        }
    }

    private RaftNode createNode(String nodeId, List<String> peers, boolean persist, Path baseDataDir) {
        Path nodeDataDir = (persist && baseDataDir != null) ? baseDataDir.resolve(nodeId) : null;
        return new RaftNode(nodeId, peers, rpcClient, MIN_ELECTION_TIMEOUT, MAX_ELECTION_TIMEOUT, nodeDataDir);
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson(objectMapper));
            cfg.bundledPlugins.enableCors(cors -> {
            cors.addRule(rule -> {
                rule.allowHost(CORS_ORIGIN, "http://localhost", "http://localhost:80", "http://127.0.0.1", "http://127.0.0.1:80");
                rule.allowMethods("GET", "POST", "OPTIONS");
                rule.allowHeaders("*");
            });
            });
        });

        app.get("/nodes", ctx -> ctx.json(getNodes()));
        app.get("/logs", ctx -> ctx.json(getLogs()));
        app.get("/metadata", ctx -> ctx.json(getMetadata()));
        app.get("/metrics", ctx -> ctx.json(getMetrics()));
        app.post("/control", this::handleControl);
        app.post("/control/demo", this::handleDemo);
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> wsClients.add(ctx));
            ws.onClose(ctx -> wsClients.remove(ctx));
        });

        broadcastScheduler.scheduleAtFixedRate(this::broadcastState, 500, 500, TimeUnit.MILLISECONDS);

        app.events(event -> {
            event.serverStarting(() -> System.out.println("Raft HTTP server on port " + config.getPort() + "..."));
            event.serverStarted(() -> System.out.println("Ready. CORS: " + CORS_ORIGIN + " | WebSocket: /ws | Metrics: /metrics"));
        });

        app.start(config.getPort());
    }

    public void stop() {
        broadcastScheduler.shutdownNow();
        if (app != null) app.stop();
        cluster.values().forEach(node -> {
            try { node.close(); } catch (Exception ignored) {}
        });
        cluster.clear();
        crashedNodeIds.clear();
    }

    private void broadcastState() {
        if (wsClients.isEmpty()) return;
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("nodes", getNodes());
            state.put("logs", getLogs());
            state.put("metadata", getMetadata());
            state.put("metrics", getMetrics());
            state.put("ts", System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(state);
            wsClients.forEach(ctx -> {
                try { ctx.send(json); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private void updateMetrics() {
        String currentLeader = null;
        for (RaftNode node : cluster.values()) {
            if (node.getRole() == RaftRole.LEADER) {
                currentLeader = node.getNodeId();
                break;
            }
        }
        if (currentLeader != null && !currentLeader.equals(lastLeaderId)) {
            if (lastLeaderId != null) metrics.onLeaderSteppedDown();
            lastLeaderId = currentLeader;
            lastLeaderChangeMs = System.currentTimeMillis();
            metrics.onLeaderElected(currentLeader);
        } else if (currentLeader == null && lastLeaderId != null) {
            metrics.onLeaderSteppedDown();
            lastLeaderId = null;
        }
    }

    private List<Map<String, Object>> getNodes() {
        updateMetrics();
        List<Map<String, Object>> result = new CopyOnWriteArrayList<>();
        for (String nodeId : nodeIds) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", nodeId);
            if (crashedNodeIds.contains(nodeId)) {
                info.put("role", "CRASHED");
                info.put("term", 0);
                info.put("lastLogIndex", 0);
            } else {
                RaftNode node = cluster.get(nodeId);
                if (node != null) {
                    info.put("role", node.getRole().name());
                    info.put("term", node.getCurrentTerm());
                    info.put("lastLogIndex", node.getLastLogIndex());
                } else {
                    info.put("role", "CRASHED");
                    info.put("term", 0);
                    info.put("lastLogIndex", 0);
                }
            }
            result.add(info);
        }
        return result;
    }

    private List<Map<String, Object>> getLogs() {
        RaftNode source = pickLogSource();
        if (source == null) return List.of();
        var entries = source.getLogEntriesSnapshot();
        List<Map<String, Object>> result = new ArrayList<>(entries.size());
        for (var e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", e.index());
            m.put("term", e.term());
            m.put("command", e.command());
            result.add(m);
        }
        return result;
    }

    private Map<String, String> getMetadata() {
        RaftNode source = pickLogSource();
        if (source == null) return Map.of();
        return new LinkedHashMap<>(source.dumpMetadataSnapshot());
    }

    private Map<String, Object> getMetrics() {
        updateMetrics();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("electionsWon", metrics.getLeaderElectionsWon());
        m.put("heartbeatsSent", metrics.getHeartbeatsSent());
        m.put("leaderUptimeMs", metrics.getLeaderUptimeMs());
        m.put("currentLeaderId", metrics.getCurrentLeaderId());
        return m;
    }

    private void handleControl(io.javalin.http.Context ctx) {
        Map<?, ?> body;
        try { body = ctx.bodyAsClass(Map.class); } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON"));
            return;
        }
        Object nodeIdObj = body != null ? body.get("nodeId") : null;
        Object actionObj = body != null ? body.get("action") : null;
        String nodeId = nodeIdObj != null ? nodeIdObj.toString().trim() : null;
        String action = actionObj != null ? actionObj.toString().trim() : null;

        if (nodeId == null || nodeId.isEmpty() || action == null || action.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing nodeId or action"));
            return;
        }
        if (!nodeIds.contains(nodeId)) {
            ctx.status(400).json(Map.of("error", "Unknown node: " + nodeId));
            return;
        }
        if (!action.equalsIgnoreCase("crash") && !action.equalsIgnoreCase("restore")) {
            ctx.status(400).json(Map.of("error", "Action must be crash or restore"));
            return;
        }

        try {
            if (action.equalsIgnoreCase("crash")) crashNode(nodeId);
            else restoreNode(nodeId);
            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleDemo(io.javalin.http.Context ctx) {
        RaftNode leader = pickLogSource();
        if (leader == null || leader.getRole() != RaftRole.LEADER) {
            ctx.status(503).json(Map.of("error", "No leader yet"));
            return;
        }
        try {
            leader.putMetadata("/config/service/a", "v1");
            leader.putMetadata("/config/service/b", "v2");
            ctx.json(Map.of("ok", true, "message", "Demo metadata appended"));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private RaftNode pickLogSource() {
        RaftNode leader = null;
        RaftNode best = null;
        int bestIdx = -1;
        for (RaftNode node : cluster.values()) {
            if (node.getRole() == RaftRole.LEADER) {
                leader = node;
                break;
            }
            int idx = node.getLastLogIndex();
            if (idx > bestIdx) { bestIdx = idx; best = node; }
        }
        return leader != null ? leader : best;
    }

    private synchronized void crashNode(String nodeId) {
        if (crashedNodeIds.contains(nodeId)) return;
        RaftNode node = cluster.remove(nodeId);
        if (node != null) {
            try { node.close(); } catch (Exception ignored) {}
            crashedNodeIds.add(nodeId);
        }
    }

    private synchronized void restoreNode(String nodeId) {
        if (!crashedNodeIds.contains(nodeId)) return;
        crashedNodeIds.remove(nodeId);
        List<String> peers = nodeIds.stream().filter(id -> !id.equals(nodeId)).toList();
        RaftNode node = createNode(nodeId, peers, config.isPersistenceEnabled(), config.getDataDir());
        cluster.put(nodeId, node);
        node.start();
    }

    public static void main(String[] args) {
        RaftHttpServer server = new RaftHttpServer();
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.stop();
        }));
    }
}
