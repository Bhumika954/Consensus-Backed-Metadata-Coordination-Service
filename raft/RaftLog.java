package raft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal Raft log implementation.
 *
 * Safety properties this class helps enforce:
 * - Log entries are indexed starting at 1 (index 0 is an implicit
 *   "null" entry with term 0).
 * - Follower-side AppendEntries processing uses the standard Raft
 *   consistency rules:
 *     - Reject if (prevLogIndex, prevLogTerm) do not match.
 *     - If an existing entry conflicts with a new one (same index
 *       but different term), truncate the existing entry and all
 *       that follow it.
 *     - Append any new entries that do not conflict.
 *
 * This guarantees that:
 * - The log is a prefix of the leader's log or vice versa.
 * - Once an entry is committed in a given term, no future leader
 *   can overwrite it with a different command at the same index.
 */
public class RaftLog {

    /**
     * Single log entry: term + opaque command payload.
     *
     * The command is deliberately left as a byte array so that the
     * metadata state machine can later interpret it however it likes
     * (key/value operations, etc.).
     */
    public static final class RaftLogEntry {
        public final int term;
        public final byte[] command;

        public RaftLogEntry(int term, byte[] command) {
            this.term = term;
            this.command = command;
        }
    }

    // Index 0 is a sentinel "empty" entry with term 0.
    private final List<RaftLogEntry> entries = new ArrayList<>();

    public RaftLog() {
        entries.add(new RaftLogEntry(0, new byte[0]));
    }

    /**
     * Returns a defensive copy of the entire log, including the
     * sentinel entry at index 0. This is intended purely for
     * observability / debugging (e.g. demo output) and should not
     * be used by correctness-critical code.
     */
    public synchronized List<RaftLogEntry> getAllEntriesSnapshot() {
        return new ArrayList<>(entries);
    }

    /**
     * @return index of the last log entry (0 if only sentinel exists).
     */
    public synchronized int getLastIndex() {
        return entries.size() - 1;
    }

    /**
     * @return term of the last log entry (0 if log is empty except sentinel).
     */
    public synchronized int getLastTerm() {
        return getTerm(getLastIndex());
    }

    /**
     * Returns the entry at the given index. Callers must treat the
     * returned instance as immutable.
     */
    public synchronized RaftLogEntry getEntry(int index) {
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("index=" + index + " size=" + entries.size());
        }
        return entries.get(index);
    }

    /**
     * Returns the term at a given index (0 for index 0).
     */
    public synchronized int getTerm(int index) {
        if (index < 0 || index >= entries.size()) {
            return 0;
        }
        return entries.get(index).term;
    }

    /**
     * Returns a defensive copy of all entries starting at fromIndex
     * (inclusive) up to the end of the log.
     */
    public synchronized List<RaftLogEntry> getEntriesFrom(int fromIndex) {
        if (fromIndex >= entries.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(entries.subList(fromIndex, entries.size()));
    }

    /**
     * Appends a single entry to the end of the log. Used by the current
     * leader when accepting a new command from a client (or demo harness).
     *
     * @return index of the newly appended entry.
     */
    public synchronized int appendLocal(int term, byte[] command) {
        entries.add(new RaftLogEntry(term, command));
        return getLastIndex();
    }

    /**
     * Follower-side AppendEntries handling as defined by the Raft paper.
     *
     * @param prevLogIndex index immediately preceding the new entries
     * @param prevLogTerm  term at prevLogIndex on the leader
     * @param newEntries   new entries to append (may be empty for pure heartbeat)
     * @return true if the follower's log is now consistent with the leader's
     *         up to the last of the new entries, false if the follower rejected
     *         the request because (prevLogIndex, prevLogTerm) did not match.
     */
    public synchronized boolean appendEntries(int prevLogIndex,
                                              int prevLogTerm,
                                              List<RaftLogEntry> newEntries) {
        // 1. Fail if log does not contain an entry at prevLogIndex whose term matches prevLogTerm.
        if (prevLogIndex >= entries.size()) {
            return false;
        }
        if (getTerm(prevLogIndex) != prevLogTerm) {
            return false;
        }

        // 2. For each new entry:
        //    - If an existing entry conflicts with a new one (same index but different term),
        //      delete the existing entry and all that follow it.
        //    - Append any new entries not already in the log.
        int index = prevLogIndex + 1;
        for (int i = 0; i < newEntries.size(); i++, index++) {
            RaftLogEntry newEntry = newEntries.get(i);
            if (index < entries.size()) {
                // Existing entry at this index: check for conflict.
                RaftLogEntry existing = entries.get(index);
                if (existing.term != newEntry.term) {
                    // Conflict – remove existing entry and all following entries.
                    while (entries.size() > index) {
                        entries.remove(entries.size() - 1);
                    }
                    // Append the remaining new entries starting at this one.
                    entries.add(newEntry);
                    for (int j = i + 1; j < newEntries.size(); j++) {
                        entries.add(newEntries.get(j));
                    }
                    return true;
                }
                // Same term, same index – entry already present; no-op.
            } else {
                // No existing entry: append.
                entries.add(newEntry);
            }
        }
        return true;
    }
}

