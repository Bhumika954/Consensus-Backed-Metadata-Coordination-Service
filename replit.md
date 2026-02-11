# Raft Consensus Demo

## Overview
A minimal, production-style implementation of a Raft-backed metadata service in Java. Demonstrates leader election, heartbeats, log replication, commit/apply, and a hierarchical key-value metadata state machine. Runs as a 3-node in-JVM cluster demo.

## Project Structure
- `raft/` - Core Raft implementation
  - `RaftNode.java` - Single Raft server with metadata state machine
  - `LeaderElection.java` - Election logic, heartbeats, log replication RPCs
  - `RaftLog.java` - In-memory Raft log with safety invariants
  - `RaftRole.java` - FOLLOWER, CANDIDATE, LEADER enum
  - `RaftElectionDemo.java` - Demo harness (main entry point)
- `metadata/` - Metadata state machine
  - `MetadataCommand.java` - PUT/DELETE/GET command serialization
  - `MetadataStore.java` - Hierarchical in-memory key-value store
- `out/` - Compiled class files (generated, gitignored)

## Build & Run
- Language: Java 19 (GraalVM CE 22.3.1)
- Compile: `javac -d out metadata/*.java raft/*.java`
- Run: `java -cp out raft.RaftElectionDemo`
- Workflow: "Run Raft Demo" compiles and runs the demo

## Key Details
- Console-only application (no frontend/backend web server)
- All nodes run in-process using `InProcessRpcClient`
- No external dependencies beyond the JDK
