import React, { useRef, useState, useCallback } from 'react';
import { ThemeProvider } from './context/ThemeContext';
import { RaftProvider, useRaft } from './context/RaftContext';
import { useWebSocket } from './hooks/useWebSocket';
import { useRaftData } from './hooks/useRaftData';
import { fetchAll } from './api/raft';
import { NodeCard } from './components/NodeCard';
import { LeaderElectedBanner } from './components/LeaderElectedBanner';
import { LogReplicationVisualizer } from './components/LogReplicationVisualizer';
import { LogTable } from './components/LogTable';
import { MetadataTable } from './components/MetadataTable';
import { MetricsChart } from './components/MetricsChart';
import { ControlPanel } from './components/ControlPanel';
import { ThemeToggle } from './components/ThemeToggle';

function AppContent() {
  const { nodes, logs, metadata, metrics, currentLeader, updateFromPayload } = useRaft();
  const [loading, setLoading] = useState(false);
  const [wsConnected, setWsConnected] = useState(false);
  const prevLeaderRef = useRef<string | null>(null);

  const handlePayload = useCallback(
    (payload: {
      nodes?: unknown[];
      logs?: unknown[];
      metadata?: Record<string, string>;
      metrics?: { electionsWon?: number; leaderUptimeMs?: number; currentLeaderId?: string | null };
    }) => {
      const next: Record<string, unknown> = {};
      if (Array.isArray(payload.nodes)) next.nodes = payload.nodes;
      if (Array.isArray(payload.logs)) next.logs = payload.logs;
      if (payload.metadata && typeof payload.metadata === 'object') next.metadata = payload.metadata;
      if (payload.metrics) {
        next.metrics = {
          electionsWon: payload.metrics.electionsWon ?? 0,
          leaderUptimeMs: payload.metrics.leaderUptimeMs ?? 0,
          currentLeaderId: payload.metrics.currentLeaderId ?? null,
        };
      }
      updateFromPayload(next);
    },
    [updateFromPayload]
  );

  useWebSocket({
    onMessage: handlePayload,
    onConnect: () => setWsConnected(true),
    onDisconnect: () => setWsConnected(false),
  });

  useRaftData({
    enabled: !wsConnected,
    intervalMs: 2000,
    onSuccess: (data) => {
      updateFromPayload({
        nodes: data.nodes,
        logs: data.logs,
        metadata: data.metadata,
        metrics: data.metrics,
      });
    },
  });

  const handleRefetch = useCallback(async () => {
    try {
      const data = await fetchAll();
      updateFromPayload(data);
    } catch {
      // ignore
    }
  }, [updateFromPayload]);

  if (currentLeader) prevLeaderRef.current = currentLeader;

  const sortedNodes = [...nodes].sort((a, b) => {
    if (a.id === currentLeader) return -1;
    if (b.id === currentLeader) return 1;
    return a.id.localeCompare(b.id);
  });

  return (
    <div className="min-h-screen p-6 md:p-8 bg-gray-50 dark:bg-transparent transition-colors">
      {/* Header */}
      <header className="mb-8 flex justify-between items-center flex-wrap gap-4">
        <div>
          <h1 className="text-4xl md:text-5xl font-bold font-mono bg-gradient-to-r from-cyan-400 via-emerald-400 to-cyan-400 bg-clip-text text-transparent">
            Raft Consensus Demo
          </h1>
          <p className="mt-2 text-gray-500">
            Leader election · Log replication · Metadata store · Failure recovery
          </p>
          {wsConnected && (
            <p className="mt-2 text-emerald-500 text-sm">● Live (WebSocket)</p>
          )}
        </div>
        <div className="flex items-center gap-4">
          <span className="text-sm text-gray-500">
            Leader: <span className="font-mono font-bold text-emerald-400">{currentLeader || '—'}</span>
          </span>
          <ThemeToggle />
        </div>
      </header>

      <LeaderElectedBanner leaderId={currentLeader} prevLeaderId={prevLeaderRef.current} />

      {/* Node cards */}
      <section className="mb-6">
        <h2 className="text-lg font-mono text-cyan-600 dark:text-cyan-400 mb-4">Cluster Nodes</h2>
        <div className="flex flex-wrap justify-center gap-6">
          {sortedNodes.map((node) => (
            <NodeCard key={node.id} node={node} isLeader={node.id === currentLeader} />
          ))}
        </div>
      </section>

      <LogReplicationVisualizer nodes={nodes} leaderId={currentLeader} />

      {/* Main grid */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-6 max-w-7xl mx-auto mt-8">
        <div className="lg:col-span-3 space-y-6">
          <LogTable logs={logs} />
          <MetadataTable metadata={metadata} />
        </div>
        <div className="lg:col-span-1 space-y-4">
          <ControlPanel
            nodes={nodes}
            onAction={handleRefetch}
            loading={loading}
            setLoading={setLoading}
          />
          <MetricsChart metrics={metrics} />
        </div>
      </div>

      <footer className="mt-12 text-center text-gray-600 text-sm">
        {wsConnected ? 'WebSocket' : 'Polling'} · n1, n2, n3 · PUT=green DEL=red
      </footer>
    </div>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <RaftProvider>
        <AppContent />
      </RaftProvider>
    </ThemeProvider>
  );
}
