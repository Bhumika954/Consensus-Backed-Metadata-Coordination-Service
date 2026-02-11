import { useEffect, useCallback } from 'react';
import {
  fetchNodes,
  fetchLogs,
  fetchMetadata,
  fetchMetrics,
} from '../api/raft';
import type { RaftNode, LogEntry, RaftMetrics } from '../types';

interface UseRaftDataOptions {
  enabled?: boolean;
  intervalMs?: number;
  onSuccess?: (data: RaftData) => void;
  onError?: (err: unknown) => void;
}

interface RaftData {
  nodes: RaftNode[];
  logs: LogEntry[];
  metadata: Record<string, string>;
  metrics: RaftMetrics;
}

export function useRaftData(options: UseRaftDataOptions = {}) {
  const { enabled = true, intervalMs = 2000, onSuccess, onError } = options;

  const load = useCallback(async () => {
    try {
      const [nodes, logs, metadata, metrics] = await Promise.all([
        fetchNodes(),
        fetchLogs(),
        fetchMetadata(),
        fetchMetrics(),
      ]);
      const data: RaftData = { nodes, logs, metadata, metrics };
      onSuccess?.(data);
      return data;
    } catch (err) {
      onError?.(err);
    }
  }, [onSuccess, onError]);

  useEffect(() => {
    if (!enabled) return;
    load();
    const id = setInterval(load, intervalMs);
    return () => clearInterval(id);
  }, [enabled, intervalMs, load]);

  return { load };
}
