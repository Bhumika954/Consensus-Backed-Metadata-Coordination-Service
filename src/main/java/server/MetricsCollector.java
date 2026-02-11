package server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects Raft metrics for API exposure.
 */
public final class MetricsCollector {

    private final AtomicLong electionsStarted = new AtomicLong(0);
    private final AtomicLong leaderElectionsWon = new AtomicLong(0);
    private final AtomicLong heartbeatsSent = new AtomicLong(0);
    private final AtomicLong leaderUptimeMs = new AtomicLong(0);
    private volatile long leaderElectedAtMs = 0;
    private volatile String currentLeaderId = null;

    public void onElectionStarted() {
        electionsStarted.incrementAndGet();
    }

    public void onLeaderElected(String nodeId) {
        long now = System.currentTimeMillis();
        if (currentLeaderId != null && leaderElectedAtMs > 0) {
            leaderUptimeMs.addAndGet(now - leaderElectedAtMs);
        }
        currentLeaderId = nodeId;
        leaderElectedAtMs = now;
        leaderElectionsWon.incrementAndGet();
    }

    public void onLeaderSteppedDown() {
        long now = System.currentTimeMillis();
        if (leaderElectedAtMs > 0) {
            leaderUptimeMs.addAndGet(now - leaderElectedAtMs);
        }
        currentLeaderId = null;
        leaderElectedAtMs = 0;
    }

    public void onHeartbeatSent() {
        heartbeatsSent.incrementAndGet();
    }

    public long getElectionsStarted() {
        return electionsStarted.get();
    }

    public long getLeaderElectionsWon() {
        return leaderElectionsWon.get();
    }

    public long getHeartbeatsSent() {
        return heartbeatsSent.get();
    }

    public long getLeaderUptimeMs() {
        long uptime = leaderUptimeMs.get();
        if (currentLeaderId != null && leaderElectedAtMs > 0) {
            uptime += System.currentTimeMillis() - leaderElectedAtMs;
        }
        return uptime;
    }

    public String getCurrentLeaderId() {
        return currentLeaderId;
    }
}
