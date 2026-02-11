export interface RaftNode {
  id: string;
  role: 'LEADER' | 'FOLLOWER' | 'CANDIDATE' | 'CRASHED';
  term: number;
  lastLogIndex: number;
}

export interface LogEntry {
  index: number;
  term: number;
  command: string;
}

export interface RaftMetrics {
  electionsWon: number;
  heartbeatsSent?: number;
  leaderUptimeMs: number;
  currentLeaderId: string | null;
}
