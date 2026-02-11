## Consensus-Backed Metadata & Coordination Service (Raft-based, Single-Developer Scale)

This project is a minimal, production-style implementation of a Raft-backed metadata service. It focuses on **correctness, clarity, and observability** rather than completeness or performance.

The core goal is to model how a FAANG-grade metadata / coordination service (think etcd/ZooKeeper) would be structured, but with enough constraints that a single engineer can implement, reason about, and demo it.

---

## What the system is

- **Consensus layer**: A Raft implementation supporting:
  - Leader election
  - Heartbeats via AppendEntries
  - Log replication
  - Commit index and application to a state machine
- **State machine**: A hierarchical, in-memory metadata key-value store with:
  - `PUT` and `DELETE` operations replicated through the Raft log
  - Leader-only, linearizable `GET` operations
- **Demo harness**: An in-JVM 3-node “cluster” using an in-process RPC client to:
  - Elect a leader
  - Replicate log entries
  - Apply metadata updates
  - Simulate leader failure and re-election
  - Show state convergence across nodes

The code is structured to be **interview-defensible**: the Raft logic is transport-agnostic and the metadata layer is cleanly separated as a state machine on top of the log.

---

## Implemented Raft components

**Roles and terms**

- `RaftNode` represents a single Raft server.
- `RaftRole` tracks `FOLLOWER`, `CANDIDATE`, and `LEADER`.
- `LeaderElection` manages:
  - `currentTerm`, `votedFor`, and role transitions
  - Randomized election timeouts with a single-threaded scheduler
  - Majority-based vote counting

**Leader election**

- Followers start with an election timeout.
- On timeout, a follower becomes a candidate, increments its term, votes for itself, and sends `RequestVote` to peers.
- Peers enforce:
  - **Monotonic terms**: higher term causes step-down to follower.
  - **One vote per term per node**.
  - **Log up-to-date rule**: candidate’s `(lastLogTerm, lastLogIndex)` must be at least as up to date as the voter’s.
- A candidate becomes leader on majority vote; single-node clusters become leader immediately.

**Heartbeats (AppendEntries without entries)**

- The leader periodically sends empty `AppendEntries` as heartbeats.
- Followers:
  - Step down if they see a higher term in the heartbeat.
  - Reset their election timeout on valid heartbeats.
- This stabilizes leadership and prevents unnecessary elections while the leader is healthy.

**Raft log and replication**

- `RaftLog` is an in-memory log of `RaftLogEntry(term, commandBytes)`:
  - Indexing starts at 1; index 0 is a sentinel with term 0.
  - APIs:
    - `getLastIndex()`, `getLastTerm()`
    - `appendLocal(term, command)`
    - `appendEntries(prevLogIndex, prevLogTerm, entries)` (follower side)
- Follower-side `AppendEntries` enforces Raft’s **log safety invariants**:
  - Reject if `prevLogIndex` is past the end or `prevLogTerm` doesn’t match.
  - Truncate on conflict: if a new entry’s term differs from an existing entry at the same index, delete the conflicting suffix and append the leader’s entries.
  - Append new entries that extend the log.
- Leader-side replication (for this demo) is intentionally simple:
  - When the leader appends a new entry, it:
    - Appends locally.
    - Treats the entry as committed locally.
    - Sends a single `AppendEntries` containing **the full log from index 1** to all followers.
  - This is inefficient but easy to reason about: any successful `AppendEntries` makes the follower’s log a prefix-equal copy of the leader’s log.

**Commit index and application**

- Each `LeaderElection` instance tracks:
  - `commitIndex`: highest log index known to be committed.
  - `lastApplied`: highest index applied to the local state machine.
- On follower `AppendEntries`:
  - After a successful log update, `commitIndex` advances to `min(leaderCommit, lastLogIndex)`.
  - Entries between `lastApplied+1` and `commitIndex` are applied in order.
- On the leader:
  - The demo treats locally appended entries as immediately committed (no quorum tracking yet), then applies them in order.
- Application to the state machine is delegated via a `LogApplier` callback so that Raft remains agnostic of the metadata details.

---

## Metadata state machine

**Command model**

- `MetadataCommand` encodes logical operations:
  - `PUT(key, value)`
  - `DELETE(key)`
- Commands are encoded as small, self-describing UTF-8 payloads:
  - `PUT\n<key>\n<value>`
  - `DEL\n<key>`

**State store**

- `MetadataStore` is a single-node, in-memory hierarchical key-value map:
  - Backed by a `TreeMap<String,String>` with keys like `/config/service/a`.
  - Operations:
    - `apply(MetadataCommand)` mutates state.
    - `get(key)` returns the current value.
    - `snapshot()` returns a read-only view (used in the demo).

**Integration with Raft**

- `RaftNode` holds both a `RaftLog` and a `MetadataStore`.
- `LeaderElection` is constructed with a `LogApplier` that:
  - Deserializes each committed `RaftLogEntry.command` to a `MetadataCommand`.
  - Applies it to the local `MetadataStore`.
- **Leader-only API** on `RaftNode`:
  - `putMetadata(key, value)`:
    - Ensures the node is leader.
    - Serializes a `MetadataCommand.PUT`.
    - Calls `appendEntryAndReplicate(...)` on the Raft layer.
  - `deleteMetadata(key)`:
    - Same pattern with `MetadataCommand.DELETE`.
  - `getMetadata(key)`:
    - Ensures the node is leader.
    - Reads directly from `MetadataStore` after all committed entries have been applied.
- This ensures:
  - **All writes go through the Raft log**.
  - **Reads are leader-only**, aligned with linearizable semantics.

---

## What the demo shows

The `RaftElectionDemo` class runs a fully in-memory 3-node cluster using an in-process RPC client:

1. **Initial leader election**
   - Starts 3 `RaftNode` instances (`n1`, `n2`, `n3`).
   - Waits for a stable leader to emerge.
2. **First metadata replication**
   - Leader performs:
     - `PUT /config/service/a = v1`
     - `PUT /config/service/b = v2`
   - Log entries are replicated to all followers.
   - Demo prints, for each node:
     - Role, term
     - Full Raft log (index, term, command)
     - Full metadata snapshot
   - You can see all nodes converge to the same log and metadata state.
3. **Heartbeat stability**
   - Waits a bit while the leader sends heartbeats.
   - Prints roles to show:
     - Exactly one leader remains.
     - Followers do not start elections while the leader is healthy.
4. **Leader failure and re-election**
   - Simulates leader failure by closing and removing the leader from the cluster map.
   - Remaining nodes elect a new leader in a higher term.
5. **Second metadata replication**
   - New leader performs:
     - `PUT /config/service/c = v3`
     - `DELETE /config/service/a`
   - Again, log entries replicate and apply across nodes.
   - Demo prints final:
     - Roles, terms
     - Full logs
     - Final metadata snapshots
   - This shows that:
     - Metadata state remains consistent across nodes.
     - State evolves correctly even across leader failure and re-election.

---

## Intentionally NOT implemented (by design)

To keep the project focused and interviewable, several production features are explicitly **not** implemented:

- **Persistence / WAL / RocksDB**
  - The Raft log and metadata store are in-memory only.
  - There is no on-disk write-ahead log or durable state; nodes “forget” state on JVM restart.

- **Snapshots**
  - No log compaction or snapshots.
  - The log grows unbounded in this demo.

- **Networking / RPC stack**
  - No gRPC or real network transport yet.
  - Nodes communicate via an in-process `InProcessRpcClient` that directly invokes methods on other `RaftNode` instances.

- **Quorum-based commit / matchIndex / nextIndex**
  - The leader does **not** track per-follower replication indices.
  - It does **not** wait for a majority of followers to ack entries before treating them as committed.
  - Instead, for this demo:
    - The leader treats a locally appended entry as committed immediately.
    - Replication is “best effort” but still **safe**: followers enforce full Raft log consistency, and replay is idempotent.

- **Client API and multi-process deployment**
  - There is no external gRPC/HTTP client-facing API.
  - Nodes only exist inside a single JVM and are addressed by in-process IDs.

These omissions are **intentional**. The focus is to demonstrate a correct, understandable Raft core and state machine wiring, not to fully match etcd/ZooKeeper in scope.
