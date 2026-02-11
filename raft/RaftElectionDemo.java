package raft;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-JVM harness to validate Raft leader election using
 * {@link RaftNode.InProcessRpcClient}.
 *
 * This is intentionally small and deterministic, so it can be
 * reasoned about in an interview:
 *
 * - 3 Raft nodes (n1, n2, n3) in a single JVM
 * - Wait for an initial leader to emerge
 * - Stop the leader to simulate a crash
 * - Wait for a new leader with a higher term
 *
 * No AppendEntries / heartbeat logic is wired yet – we are only
 * exercising the RequestVote-based election path.
 */
public final class RaftElectionDemo {

    private RaftElectionDemo() {
        // utility class
    }

    public static void main(String[] args) throws Exception {
        // Shared cluster view used by the in-process RPC client.
        Map<String, RaftNode> cluster = new ConcurrentHashMap<>();

        // Election timeouts are deliberately wide enough that the first
        // leader typically emerges before others time out.
        Duration minTimeout = Duration.ofMillis(500);
        Duration maxTimeout = Duration.ofMillis(900);

        // Placeholder RPC client – will see the fully populated cluster map
        // once all nodes are constructed.
        RaftNode.InProcessRpcClient rpcClient = new RaftNode.InProcessRpcClient(cluster);

        // Build 3-node cluster: n1, n2, n3.
        List<String> nodeIds = Arrays.asList("n1", "n2", "n3");
        for (String nodeId : nodeIds) {
            List<String> peers = nodeIds.stream()
                    .filter(id -> !id.equals(nodeId))
                    .toList();
            RaftNode node = new RaftNode(nodeId, peers, rpcClient, minTimeout, maxTimeout);
            cluster.put(nodeId, node);
        }

        // Start all nodes.
        cluster.values().forEach(RaftNode::start);

        // Phase 1: wait for initial leader.
        LeaderSnapshot firstLeader = waitForLeader(cluster, /*minTerm*/ 1, Duration.ofSeconds(10));
        if (firstLeader == null) {
            System.out.println("No leader elected within timeout – check election configuration.");
            shutdownCluster(cluster);
            return;
        }

        System.out.println("Initial leader elected: node=" + firstLeader.nodeId
                + " term=" + firstLeader.term);

        // Append demo metadata commands on the leader and replicate via Raft.
        RaftNode leaderNode = cluster.get(firstLeader.nodeId);
        if (leaderNode == null) {
            System.out.println("Error: elected leader not found in cluster map.");
            shutdownCluster(cluster);
            return;
        }
        leaderNode.putMetadata("/config/service/a", "v1");
        leaderNode.putMetadata("/config/service/b", "v2");

        // Give the asynchronous replication RPCs a moment to complete.
        Thread.sleep(1000L);

        System.out.println("=== After first metadata replication (initial leader term=" + firstLeader.term + ") ===");
        for (RaftNode node : cluster.values()) {
            System.out.println("Node " + node.getNodeId()
                    + " role=" + node.getRole()
                    + " lastIndex=" + node.getLastLogIndex()
                    + " lastTerm=" + node.getLastLogTerm());
            System.out.print(node.dumpLog());
            System.out.println("  Metadata snapshot: " + node.dumpMetadataSnapshot());
        }

        // With heartbeat-based AppendEntries enabled, followers should
        // not start their own elections while the leader is healthy.
        // We wait for several election timeouts and then confirm that:
        // - there is still exactly one leader
        // - its id has not changed
        // - followers remain followers
        Thread.sleep(3000L);

        System.out.println("=== Cluster roles after heartbeat stabilization window (leader healthy) ===");
        int leaderCount = 0;
        for (RaftNode node : cluster.values()) {
            System.out.println("  node=" + node.getNodeId()
                    + " role=" + node.getRole()
                    + " term=" + node.getCurrentTerm());
            if (node.getRole() == RaftRole.LEADER) {
                leaderCount++;
            }
        }
        System.out.println("Observed leader count=" + leaderCount
                + " (expected 1 while original leader is healthy)");

        // Simulate leader crash: stop node and remove it from the cluster
        // so that no further votes are sent to it.
        RaftNode failed = cluster.remove(firstLeader.nodeId);
        if (failed != null) {
            failed.close();
        }

        // Phase 2: wait for a new leader with strictly higher term.
        LeaderSnapshot secondLeader = waitForLeader(cluster, firstLeader.term + 1, Duration.ofSeconds(10));
        if (secondLeader == null) {
            System.out.println("No new leader elected after simulating failure of " + firstLeader.nodeId);
            shutdownCluster(cluster);
            return;
        }

        System.out.println("=== New leader after simulated failure ===");
        System.out.println("New leader: node=" + secondLeader.nodeId
                + " term=" + secondLeader.term
                + " (previous term was " + firstLeader.term + ")");

        // Append a second round of metadata changes on the new leader and replicate again.
        RaftNode newLeaderNode = cluster.get(secondLeader.nodeId);
        if (newLeaderNode == null) {
            System.out.println("Error: new leader not found in cluster map.");
            shutdownCluster(cluster);
            return;
        }
        newLeaderNode.putMetadata("/config/service/c", "v3");
        newLeaderNode.deleteMetadata("/config/service/a");

        Thread.sleep(1000L);

        System.out.println("=== Final log and metadata state after re-election and second replication (leader term=" + secondLeader.term + ") ===");
        for (RaftNode node : cluster.values()) {
            System.out.println("Node " + node.getNodeId()
                    + " role=" + node.getRole()
                    + " lastIndex=" + node.getLastLogIndex()
                    + " lastTerm=" + node.getLastLogTerm());
            System.out.print(node.dumpLog());
            System.out.println("  Metadata snapshot: " + node.dumpMetadataSnapshot());
        }

        shutdownCluster(cluster);
    }

    /**
     * Wait until any node in the cluster is a LEADER with term &gt;= minTerm.
     * Returns a snapshot of that leader's id and term, or null on timeout.
     */
    private static LeaderSnapshot waitForLeader(Map<String, RaftNode> cluster,
                                                int minTerm,
                                                Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            LeaderSnapshot candidate = null;
            for (RaftNode node : cluster.values()) {
                if (node.getRole() == RaftRole.LEADER && node.getCurrentTerm() >= minTerm) {
                    // If multiple leaders exist briefly, pick the one with the highest term.
                    if (candidate == null || node.getCurrentTerm() > candidate.term) {
                        candidate = new LeaderSnapshot(node.getNodeId(), node.getCurrentTerm());
                    }
                }
            }
            if (candidate != null) {
                return candidate;
            }

            Thread.sleep(100L);
        }
        return null;
    }

    private static void shutdownCluster(Map<String, RaftNode> cluster) {
        cluster.values().forEach(node -> {
            try {
                node.close();
            } catch (Exception ignored) {
                // best-effort shutdown for demo
            }
        });
    }

    private record LeaderSnapshot(String nodeId, int term) {
    }
}

