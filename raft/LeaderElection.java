package raft;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encapsulates Raft leader election logic:
 *
 * - Randomized election timeouts (to avoid split votes)
 * - Candidate transitions and vote counting
 * - Heartbeat-based follower reset of election timer
 *
 * This class is deliberately transport-agnostic. It depends on
 * a small callback interface ({@link RpcClient}) so we can later
 * plug in gRPC for cross-process communication without changing
 * the core election logic.
 */
public class LeaderElection {

    private static final Logger LOG = Logger.getLogger(LeaderElection.class.getName());

    /**
     * Interface the Raft node uses to talk to peers.
     *
     * For now this is a simple Java interface; later we will back it
     * with a gRPC client that sends Raft RPCs over the network.
     */
    public interface RpcClient {
        CompletableFuture<RequestVoteResponse> requestVote(String peerId, RequestVoteRequest request);
        CompletableFuture<AppendEntriesResponse> appendEntries(String peerId, AppendEntriesRequest request);
    }

    /**
     * Immutable RequestVote RPC request.
     *
     * In a production implementation this would be generated from a .proto
     * definition. For clarity in this first step we keep it as a POJO.
     */
    public static final class RequestVoteRequest {
        public final int term;
        public final String candidateId;
        public final int lastLogIndex;
        public final int lastLogTerm;

        public RequestVoteRequest(int term, String candidateId, int lastLogIndex, int lastLogTerm) {
            this.term = term;
            this.candidateId = Objects.requireNonNull(candidateId, "candidateId");
            this.lastLogIndex = lastLogIndex;
            this.lastLogTerm = lastLogTerm;
        }
    }

    /**
     * Immutable RequestVote RPC response.
     */
    public static final class RequestVoteResponse {
        public final int term;
        public final boolean voteGranted;

        public RequestVoteResponse(int term, boolean voteGranted) {
            this.term = term;
            this.voteGranted = voteGranted;
        }
    }

    /**
     * AppendEntries RPC request.
     *
     * For heartbeats, {@code entries} is empty. For log replication it
     * carries a suffix of the leader's log starting just after
     * {@code prevLogIndex}.
     *
     * This message is intentionally close to the Raft paper's definition
     * so that we can reason directly about the safety properties:
     * - Followers only accept entries when (prevLogIndex, prevLogTerm)
     *   match their local log.
     * - Conflicting entries are truncated before new ones are appended.
     */
    public static final class AppendEntriesRequest {
        public final int term;
        public final String leaderId;
        public final int prevLogIndex;
        public final int prevLogTerm;
        public final int leaderCommit;
        public final List<RaftLog.RaftLogEntry> entries;

        public AppendEntriesRequest(int term,
                                    String leaderId,
                                    int prevLogIndex,
                                    int prevLogTerm,
                                    int leaderCommit,
                                    List<RaftLog.RaftLogEntry> entries) {
            this.term = term;
            this.leaderId = Objects.requireNonNull(leaderId, "leaderId");
            this.prevLogIndex = prevLogIndex;
            this.prevLogTerm = prevLogTerm;
            this.leaderCommit = leaderCommit;
            this.entries = entries == null ? List.of() : new ArrayList<>(entries);
        }
    }

    /**
     * Minimal AppendEntries RPC response.
     */
    public static final class AppendEntriesResponse {
        public final int term;
        public final boolean success;

        public AppendEntriesResponse(int term, boolean success) {
            this.term = term;
            this.success = success;
        }
    }

    private final String localId;
    private final List<String> peerIds;
    private final RpcClient rpcClient;
    private final RaftLog log;
    private final ScheduledExecutorService scheduler;
    private final Duration minElectionTimeout;
    private final Duration maxElectionTimeout;
    private final Duration heartbeatInterval;
    private final Random random;

    // These fields are owned by the LeaderElection instance but updated by RaftNode
    private volatile int currentTerm = 0;
    private volatile String votedFor = null;
    private volatile RaftRole role = RaftRole.FOLLOWER;

    /**
     * Optional callback used to apply committed log entries to the
     * local state machine. This keeps the Raft core agnostic of the
     * concrete metadata API while still enforcing the critical safety
     * property: commands are applied in log index order and only after
     * they become committed.
     */
    public interface LogApplier {
        void apply(int index, RaftLog.RaftLogEntry entry);
    }

    private final LogApplier logApplier;

    // Log commitment state, as defined by Raft.
    // - commitIndex: highest log index known to be committed (safe to apply).
    // - lastApplied: highest log index actually applied to the state machine.
    private volatile int commitIndex = 0;
    private volatile int lastApplied = 0;

    private final AtomicInteger currentElectionRound = new AtomicInteger(0);
    private volatile ScheduledFuture<?> electionTimeoutFuture;
    private volatile ScheduledFuture<?> heartbeatFuture;

    public LeaderElection(
            String localId,
            List<String> peerIds,
            RpcClient rpcClient,
            RaftLog log,
            LogApplier logApplier,
            ScheduledExecutorService scheduler,
            Duration minElectionTimeout,
            Duration maxElectionTimeout) {
        if (peerIds == null) {
            peerIds = Collections.emptyList();
        }
        this.localId = Objects.requireNonNull(localId, "localId");
        this.peerIds = new ArrayList<>(peerIds);
        this.rpcClient = Objects.requireNonNull(rpcClient, "rpcClient");
        this.log = Objects.requireNonNull(log, "log");
        this.logApplier = logApplier;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.minElectionTimeout = Objects.requireNonNull(minElectionTimeout, "minElectionTimeout");
        this.maxElectionTimeout = Objects.requireNonNull(maxElectionTimeout, "maxElectionTimeout");
        // Derive heartbeat interval from election timeout: heartbeats must be
        // frequent enough that followers refresh their election timers
        // well before they expire, but not so frequent that they dominate logs.
        this.heartbeatInterval = this.minElectionTimeout.dividedBy(3);

        // Seed per-node RNG from its ID to keep demo behaviour deterministic
        // across runs while still giving each node different timeouts.
        this.random = new Random(Objects.hash(localId));
    }

    // ------------ External lifecycle hooks ------------

    /**
     * Called when the node starts and should behave as a follower.
     */
    public synchronized void start() {
        LOG.info(() -> logPrefix() + "starting as FOLLOWER");
        role = RaftRole.FOLLOWER;
        scheduleNewElectionTimeout();
    }

    /**
     * Called when the node is shutting down.
     */
    public synchronized void stop() {
        cancelElectionTimeout();
        cancelHeartbeat();
    }

    /**
     * Called by RaftNode when an AppendEntries (heartbeat) is received
     * from a valid leader in the current term or newer.
     *
     * We simply reset the election timeout to remain a follower.
     */
    public synchronized void onHeartbeatFromLeader(int leaderTerm) {
        if (leaderTerm < currentTerm) {
            // Old leader; ignore.
            return;
        }
        if (leaderTerm > currentTerm) {
            currentTerm = leaderTerm;
            votedFor = null;
            // If we believed we were the leader in a lower term, we must step down.
            if (role != RaftRole.FOLLOWER) {
                LOG.info(() -> logPrefix() + "step down to FOLLOWER due to higher-term heartbeat from "
                        + leaderTerm);
                role = RaftRole.FOLLOWER;
                cancelHeartbeat();
            }
        }
        if (role != RaftRole.FOLLOWER) {
            LOG.info(() -> logPrefix() + "step down to FOLLOWER due to heartbeat from leader with term=" + leaderTerm);
            role = RaftRole.FOLLOWER;
        }
        scheduleNewElectionTimeout();
    }

    /**
     * Handles an incoming RequestVote RPC. This enforces Raft's
     * single-vote-per-term and "up-to-date log" rules.
     */
    public synchronized RequestVoteResponse handleRequestVote(RequestVoteRequest request,
                                                              int lastLogIndex,
                                                              int lastLogTerm) {
        if (request.term < currentTerm) {
            return new RequestVoteResponse(currentTerm, false);
        }

        if (request.term > currentTerm) {
            // Discover new term; convert to follower and stop any leader duties.
            currentTerm = request.term;
            votedFor = null;
            if (role != RaftRole.FOLLOWER) {
                LOG.info(() -> logPrefix() + "step down to FOLLOWER due to higher-term RequestVote from "
                        + request.candidateId + " term=" + request.term);
                role = RaftRole.FOLLOWER;
                cancelHeartbeat();
            }
        }

        boolean logIsUpToDate = isCandidateLogUpToDate(
                request.lastLogIndex,
                request.lastLogTerm,
                lastLogIndex,
                lastLogTerm);

        boolean voteGranted = false;
        if ((votedFor == null || votedFor.equals(request.candidateId)) && logIsUpToDate) {
            votedFor = request.candidateId;
            voteGranted = true;

            // Voting for a candidate resets our election timeout, as
            // we optimistically expect them to become leader.
            scheduleNewElectionTimeout();
        }

        final boolean granted = voteGranted;
        LOG.info(() -> logPrefix()
                + "RequestVote from=" + request.candidateId
                + " reqTerm=" + request.term
                + " currentTerm=" + currentTerm
                + " lastLogIndex=" + lastLogIndex + " lastLogTerm=" + lastLogTerm
                + " candidateLastLogIndex=" + request.lastLogIndex
                + " candidateLastLogTerm=" + request.lastLogTerm
                + " voteGranted=" + granted);

        return new RequestVoteResponse(currentTerm, voteGranted);
    }

    /**
     * Handles an incoming AppendEntries RPC.
     *
     * This method now enforces the Raft log consistency rules in addition
     * to term / leadership handling:
     *
     * - Rejects the request if (prevLogIndex, prevLogTerm) do not match.
     * - Truncates conflicting entries and appends new ones on success.
     * - Advances commitIndex to min(leaderCommit, lastLogIndex).
     * - Advances lastApplied up to commitIndex (no-op "state machine").
     */
    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        if (request.term < currentTerm) {
            // Leader is stale; reject.
            return new AppendEntriesResponse(currentTerm, false);
        }

        if (request.term > currentTerm) {
            currentTerm = request.term;
            votedFor = null;
            if (role != RaftRole.FOLLOWER) {
                LOG.info(() -> logPrefix() + "step down to FOLLOWER due to higher-term AppendEntries from "
                        + request.leaderId + " term=" + request.term);
                role = RaftRole.FOLLOWER;
                cancelHeartbeat();
            }
        }

        // At this point we accept the sender as leader for this term.
        if (role != RaftRole.FOLLOWER) {
            LOG.info(() -> logPrefix() + "step down to FOLLOWER due to AppendEntries from "
                    + request.leaderId + " term=" + request.term);
            role = RaftRole.FOLLOWER;
            cancelHeartbeat();
        }

        // Apply Raft's log consistency check.
        boolean logOk = log.appendEntries(
                request.prevLogIndex,
                request.prevLogTerm,
                request.entries
        );

        if (!logOk) {
            // Inconsistent log prefix – follower asks leader to retry with
            // a smaller prevLogIndex. We still reset the election timeout
            // because we learned about a valid leader in the current term.
            scheduleNewElectionTimeout();
            LOG.fine(() -> logPrefix() + "AppendEntries rejected due to log inconsistency from leader="
                    + request.leaderId + " term=" + request.term
                    + " prevLogIndex=" + request.prevLogIndex
                    + " prevLogTerm=" + request.prevLogTerm);
            return new AppendEntriesResponse(currentTerm, false);
        }

        // Successful AppendEntries acts as heartbeat.
        scheduleNewElectionTimeout();

        // Advance commit index to the minimum of leaderCommit and our last index.
        int lastIndex = log.getLastIndex();
        if (request.leaderCommit > commitIndex) {
            commitIndex = Math.min(request.leaderCommit, lastIndex);
        }

        // Apply newly committed entries to the local state machine.
        while (lastApplied < commitIndex) {
            lastApplied++;
            applyEntry(lastApplied);
        }

        LOG.fine(() -> logPrefix() + "AppendEntries from leader=" + request.leaderId
                + " term=" + request.term
                + " newLastIndex=" + lastIndex
                + " commitIndex=" + commitIndex
                + " lastApplied=" + lastApplied);

        return new AppendEntriesResponse(currentTerm, true);
    }

    // ------------ Internal election logic ------------

    private void scheduleNewElectionTimeout() {
        cancelElectionTimeout();
        int round = currentElectionRound.incrementAndGet();
        long delayMillis = randomTimeoutMillis();
        LOG.fine(() -> logPrefix() + "scheduling election timeout in " + delayMillis + " ms (round=" + round + ")");
        electionTimeoutFuture = scheduler.schedule(
                () -> onElectionTimeout(round),
                delayMillis,
                TimeUnit.MILLISECONDS);
    }

    private void cancelElectionTimeout() {
        if (electionTimeoutFuture != null) {
            electionTimeoutFuture.cancel(false);
            electionTimeoutFuture = null;
        }
    }

    private void cancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private long randomTimeoutMillis() {
        long minMillis = minElectionTimeout.toMillis();
        long maxMillis = maxElectionTimeout.toMillis();
        if (maxMillis <= minMillis) {
            return minMillis;
        }
        long range = maxMillis - minMillis;
        return minMillis + random.nextInt((int) range + 1);
    }

    private void onElectionTimeout(int round) {
        synchronized (this) {
            // Outdated timeout; ignore.
            if (round != currentElectionRound.get()) {
                return;
            }
            if (role == RaftRole.LEADER) {
                // Leaders do not trigger elections; they drive heartbeats instead.
                return;
            }
            startElectionRound();
        }
    }

    private void startElectionRound() {
        // Transition to candidate and increment term.
        currentTerm++;
        role = RaftRole.CANDIDATE;
        votedFor = localId;

        final int electionTerm = currentTerm;
        LOG.info(() -> logPrefix() + "starting election for term=" + electionTerm + " peers=" + peerIds);

        // Vote for self.
        int totalVotes = 1; // self vote
        int majority = (peerIds.size() + 1) / 2 + 1; // +1 for local node

        if (peerIds.isEmpty()) {
            // Single-node cluster special case: becomes leader immediately.
            becomeLeader(electionTerm);
            return;
        }

        // We must use effectively final variables for lambdas.
        List<String> peersSnapshot = new ArrayList<>(peerIds);
        AtomicInteger votes = new AtomicInteger(totalVotes);

        // Immediately schedule a new election timeout in case this round fails.
        scheduleNewElectionTimeout();

        int lastLogIndex = log.getLastIndex();
        int lastLogTerm = log.getLastTerm();

        RequestVoteRequest request = new RequestVoteRequest(
                electionTerm,
                localId,
                lastLogIndex,
                lastLogTerm
        );

        for (String peerId : peersSnapshot) {
            rpcClient.requestVote(peerId, request)
                    .whenComplete((resp, error) -> {
                        if (error != null) {
                            LOG.log(Level.WARNING, logPrefix() + "RequestVote to " + peerId + " failed", error);
                            return;
                        }
                        handleVoteResponse(electionTerm, majority, votes, peerId, resp);
                    });
        }
    }

    private synchronized void handleVoteResponse(int electionTerm,
                                                 int majority,
                                                 AtomicInteger votes,
                                                 String peerId,
                                                 RequestVoteResponse resp) {
        if (resp == null) {
            return;
        }

        if (resp.term > currentTerm) {
            // We have learned of a higher term; step down.
            LOG.info(() -> logPrefix() + "step down due to higher term from " + peerId + ": " + resp.term);
            currentTerm = resp.term;
            role = RaftRole.FOLLOWER;
            votedFor = null;
            scheduleNewElectionTimeout();
            return;
        }

        // Ignore responses for past/future terms (stale or future elections).
        if (electionTerm != currentTerm || role != RaftRole.CANDIDATE) {
            return;
        }

        if (resp.voteGranted) {
            int newVotes = votes.incrementAndGet();
            LOG.info(() -> logPrefix() + "received vote from " + peerId
                    + " electionTerm=" + electionTerm
                    + " votes=" + newVotes
                    + " majority=" + majority);
            if (newVotes >= majority && role == RaftRole.CANDIDATE && electionTerm == currentTerm) {
                becomeLeader(electionTerm);
            }
        }
    }

    private void becomeLeader(int term) {
        role = RaftRole.LEADER;
        LOG.info(() -> logPrefix() + "became LEADER for term=" + term);
        // When we wire in log replication, this is where we will start sending
        // periodic AppendEntries heartbeats to followers.
        cancelElectionTimeout();
        startHeartbeats();
    }

    private void startHeartbeats() {
        cancelHeartbeat();
        long intervalMillis = Math.max(heartbeatInterval.toMillis(), 10L);
        LOG.fine(() -> logPrefix() + "starting heartbeats every " + intervalMillis + " ms");
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                0L,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private void sendHeartbeats() {
        // Take a snapshot of term/role under lock to keep behaviour well-defined.
        final int termSnapshot;
        final RaftRole roleSnapshot;
        synchronized (this) {
            termSnapshot = currentTerm;
            roleSnapshot = role;
        }

        if (roleSnapshot != RaftRole.LEADER) {
            // Leader stepped down between scheduling and execution.
            return;
        }

        // Heartbeat carries no log entries but still advertises the
        // leader's commit index so followers can advance their own
        // commitIndex / lastApplied when they are already up to date.
        int lastIndex = log.getLastIndex();
        int lastTerm = log.getLastTerm();
        AppendEntriesRequest request = new AppendEntriesRequest(
                termSnapshot,
                localId,
                /*prevLogIndex*/ lastIndex,
                /*prevLogTerm*/ lastTerm,
                /*leaderCommit*/ commitIndex,
                /*entries*/ List.of()
        );

        for (String peerId : peerIds) {
            rpcClient.appendEntries(peerId, request)
                    .whenComplete((resp, error) -> {
                        if (error != null) {
                            LOG.log(Level.FINE, logPrefix() + "AppendEntries to " + peerId + " failed", error);
                            return;
                        }
                        handleAppendEntriesResponse(termSnapshot, peerId, resp);
                    });
        }
    }

    private synchronized void handleAppendEntriesResponse(int requestTerm,
                                                          String peerId,
                                                          AppendEntriesResponse resp) {
        if (resp == null) {
            return;
        }

        if (resp.term > currentTerm) {
            // We have discovered a higher term; step down.
            LOG.info(() -> logPrefix() + "step down due to higher term from AppendEntriesResponse by "
                    + peerId + ": " + resp.term);
            currentTerm = resp.term;
            role = RaftRole.FOLLOWER;
            votedFor = null;
            cancelHeartbeat();
            scheduleNewElectionTimeout();
            return;
        }

        // For heartbeat-only support we ignore the "success" flag; once
        // log replication tracking (nextIndex / matchIndex) is implemented
        // this is where we would drive leader-side commit advancement.
    }

    /**
     * Leader-side helper for the demo and, eventually, for client commands.
     *
     * This method is intentionally simple and correct rather than fast:
     * it always sends the FULL log to every follower, relying on the
     * Raft log-consistency rules to converge follower logs to the
     * leader's log.
     */
    public int appendEntryAndReplicate(byte[] command) {
        final int index;
        final int termSnapshot;
        synchronized (this) {
            if (role != RaftRole.LEADER) {
                throw new IllegalStateException("appendEntryAndReplicate called on non-leader: role=" + role);
            }
            termSnapshot = currentTerm;
            index = log.appendLocal(termSnapshot, command);
            // For this stage of the project we pessimistically treat the
            // newly appended entry as committed on the leader as soon as
            // it is durably in the local log. Future iterations will
            // refine this to wait for a majority of followers.
            commitIndex = index;
            while (lastApplied < commitIndex) {
                lastApplied++;
                applyEntry(lastApplied);
            }
        }
        // Fire-and-forget replication attempt; for the purposes of this
        // step we do not wait for a majority ACK before considering the
        // entry "committed".
        replicateLogOnce(termSnapshot);
        return index;
    }

    private void replicateLogOnce(int termSnapshot) {
        // Build a request that starts from the very beginning of the log.
        // This is inefficient but greatly simplifies reasoning about safety:
        // every successful AppendEntries call makes the follower's log
        // exactly equal to the leader's log.
        List<RaftLog.RaftLogEntry> allEntries = log.getEntriesFrom(1);
        AppendEntriesRequest request = new AppendEntriesRequest(
                termSnapshot,
                localId,
                /*prevLogIndex*/ 0,
                /*prevLogTerm*/ 0,
                /*leaderCommit*/ commitIndex,
                allEntries
        );

        for (String peerId : peerIds) {
            rpcClient.appendEntries(peerId, request)
                    .whenComplete((resp, error) -> {
                        if (error != null) {
                            LOG.log(Level.FINE, logPrefix() + "replication AppendEntries to " + peerId + " failed", error);
                            return;
                        }
                        handleAppendEntriesResponse(termSnapshot, peerId, resp);
                    });
        }
    }

    private void applyEntry(int index) {
        if (logApplier == null) {
            return;
        }
        RaftLog.RaftLogEntry entry = log.getEntry(index);
        logApplier.apply(index, entry);
    }

    private boolean isCandidateLogUpToDate(int candLastIdx, int candLastTerm,
                                           int localLastIdx, int localLastTerm) {
        // Standard Raft "log is at least as up to date" rule:
        // 1. Higher lastLogTerm is better
        // 2. If equal term, higher index is better
        if (candLastTerm != localLastTerm) {
            return candLastTerm > localLastTerm;
        }
        return candLastIdx >= localLastIdx;
    }

    private String logPrefix() {
        return "[node=" + localId + " term=" + currentTerm + " role=" + role + "] ";
    }

    // ------------ Introspection helpers (useful for tests) ------------

    public synchronized int getCurrentTerm() {
        return currentTerm;
    }

    public synchronized String getVotedFor() {
        return votedFor;
    }

    public synchronized RaftRole getRole() {
        return role;
    }
}

