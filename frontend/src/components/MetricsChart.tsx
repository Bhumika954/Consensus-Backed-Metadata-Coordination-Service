import React, { useMemo } from 'react';
import {
  LineChart,
  Line,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from 'recharts';
import type { RaftMetrics } from '../types';

interface MetricsChartProps {
  metrics: RaftMetrics;
  history?: Array<{ ts: number; electionsWon: number; uptimeSec: number }>;
}

export function MetricsChart({ metrics, history = [] }: MetricsChartProps) {
  const uptimeSec = Math.floor((metrics.leaderUptimeMs || 0) / 1000);

  const barData = useMemo(
    () => [
      { name: 'Elections', value: metrics.electionsWon ?? 0, fill: '#10b981' },
      { name: 'Uptime (s)', value: uptimeSec, fill: '#f59e0b' },
    ],
    [metrics.electionsWon, uptimeSec]
  );

  return (
    <div className="rounded-xl border border-gray-200 dark:border-white/10 bg-white/80 dark:bg-black/20 p-4">
      <div className="text-cyan-600 dark:text-cyan-400 font-mono font-semibold mb-4">
        Metrics
      </div>
      <div className="space-y-4">
        <div className="text-sm text-gray-600 dark:text-gray-400">
          Leader: <span className="font-bold text-white">{metrics.currentLeaderId || '—'}</span>
        </div>
        <ResponsiveContainer width="100%" height={140}>
          <BarChart data={barData} layout="vertical" margin={{ left: 0, right: 20 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.1)" />
            <XAxis type="number" stroke="#6b7280" fontSize={10} />
            <YAxis type="category" dataKey="name" stroke="#6b7280" fontSize={10} width={60} />
            <Tooltip
              contentStyle={{
                backgroundColor: 'rgba(0,0,0,0.8)',
                border: '1px solid rgba(255,255,255,0.2)',
                borderRadius: '8px',
              }}
            />
            <Bar dataKey="value" radius={[0, 4, 4, 0]} />
          </BarChart>
        </ResponsiveContainer>
        {history.length > 1 && (
          <ResponsiveContainer width="100%" height={80}>
            <LineChart data={history}>
              <Line type="monotone" dataKey="electionsWon" stroke="#10b981" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="uptimeSec" stroke="#f59e0b" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
