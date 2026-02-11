import React, { createContext, useContext, useState, useCallback } from 'react';
import type { RaftNode, LogEntry, RaftMetrics } from '../types';

interface RaftState {
  nodes: RaftNode[];
  logs: LogEntry[];
  metadata: Record<string, string>;
  metrics: RaftMetrics;
  currentLeader: string | null;
  electionsWon: number;
}

type RaftPayload = {
  nodes?: RaftNode[];
  logs?: LogEntry[];
  metadata?: Record<string, string>;
  metrics?: Partial<RaftMetrics>;
};

interface RaftContextValue extends RaftState {
  setNodes: React.Dispatch<React.SetStateAction<RaftNode[]>>;
  setLogs: React.Dispatch<React.SetStateAction<LogEntry[]>>;
  setMetadata: React.Dispatch<React.SetStateAction<Record<string, string>>>;
  setMetrics: React.Dispatch<React.SetStateAction<RaftMetrics>>;
  updateFromPayload: (payload: RaftPayload) => void;
}

const initialState: RaftState = {
  nodes: [],
  logs: [],
  metadata: {},
  metrics: {
    electionsWon: 0,
    leaderUptimeMs: 0,
    currentLeaderId: null,
  },
  currentLeader: null,
  electionsWon: 0,
};

const RaftContext = createContext<RaftContextValue | null>(null);

export function RaftProvider({ children }: { children: React.ReactNode }) {
  const [nodes, setNodes] = useState<RaftNode[]>(initialState.nodes);
  const [logs, setLogs] = useState<LogEntry[]>(initialState.logs);
  const [metadata, setMetadata] = useState<Record<string, string>>(initialState.metadata);
  const [metrics, setMetrics] = useState<RaftMetrics>(initialState.metrics);

  const currentLeader =
    nodes.find((n) => n.role === 'LEADER')?.id ?? metrics.currentLeaderId ?? null;
  const electionsWon = metrics.electionsWon ?? 0;

  const updateFromPayload = useCallback((payload: RaftPayload) => {
    if (payload.nodes) setNodes(payload.nodes);
    if (payload.logs) setLogs(payload.logs);
    if (payload.metadata) setMetadata(payload.metadata);
    if (payload.metrics) {
      setMetrics((prev) => ({
        ...prev,
        ...payload.metrics,
      }));
    }
  }, []);

  const value: RaftContextValue = {
    nodes,
    logs,
    metadata,
    metrics,
    currentLeader,
    electionsWon,
    setNodes,
    setLogs,
    setMetadata,
    setMetrics,
    updateFromPayload,
  };

  return <RaftContext.Provider value={value}>{children}</RaftContext.Provider>;
}

export function useRaft(): RaftContextValue {
  const ctx = useContext(RaftContext);
  if (!ctx) throw new Error('useRaft must be used within RaftProvider');
  return ctx;
}
