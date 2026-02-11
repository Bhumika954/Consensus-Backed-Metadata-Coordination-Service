package raft;

/**
 * Raft server role.
 *
 * Kept intentionally simple so the leader election logic
 * can be understood and tested in isolation.
 */
public enum RaftRole {
    FOLLOWER,
    CANDIDATE,
    LEADER
}

