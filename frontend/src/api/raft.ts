import { api, getWsUrl } from './client';
import type { RaftNode, LogEntry, RaftMetrics } from '../types';

export async function fetchNodes(): Promise<RaftNode[]> {
  const { data } = await api.get<RaftNode[]>('/nodes');
  return Array.isArray(data) ? data : [];
}

export async function fetchLogs(): Promise<LogEntry[]> {
  const { data } = await api.get<LogEntry[]>('/logs');
  return Array.isArray(data) ? data : [];
}

export async function fetchMetadata(): Promise<Record<string, string>> {
  const { data } = await api.get<Record<string, string>>('/metadata');
  return data && typeof data === 'object' ? data : {};
}

export async function fetchMetrics(): Promise<RaftMetrics> {
  const { data } = await api.get<RaftMetrics>('/metrics');
  return data ?? { electionsWon: 0, leaderUptimeMs: 0, currentLeaderId: null };
}

export async function fetchAll() {
  const [nodes, logs, metadata, metrics] = await Promise.all([
    fetchNodes(),
    fetchLogs(),
    fetchMetadata(),
    fetchMetrics(),
  ]);
  return { nodes, logs, metadata, metrics };
}

export async function controlNode(nodeId: string, action: 'crash' | 'restore'): Promise<void> {
  await api.post('/control', { nodeId, action });
}

export async function appendDemoMetadata(): Promise<void> {
  await api.post('/control/demo');
}

export { getWsUrl };
