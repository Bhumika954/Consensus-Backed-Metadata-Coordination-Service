import React from 'react';
import { motion } from 'framer-motion';
import type { LogEntry } from '../types';

interface LogTableProps {
  logs: LogEntry[];
}

function parseCommand(cmd: string) {
  if (!cmd) return { type: 'unknown', key: '', value: '' };
  const lines = cmd.split('\n');
  const type = lines[0]?.toUpperCase() || 'unknown';
  const key = lines[1] || '';
  const value = lines[2] ?? '';
  return { type, key, value };
}

function CommandBadge({ cmd }: { cmd: string }) {
  const { type, key, value } = parseCommand(cmd);
  if (type === 'PUT') {
    return (
      <span className="inline-flex items-center gap-2 px-2 py-0.5 rounded-md bg-emerald-500/20 text-emerald-400 text-xs font-mono">
        <span className="font-semibold">PUT</span>
        <span className="text-gray-400">{key}</span>
        <span className="text-white">→</span>
        <span>{value}</span>
      </span>
    );
  }
  if (type === 'DEL') {
    return (
      <span className="inline-flex items-center gap-2 px-2 py-0.5 rounded-md bg-red-500/20 text-red-400 text-xs font-mono">
        <span className="font-semibold">DEL</span>
        <span className="text-gray-400">{key}</span>
      </span>
    );
  }
  return <code className="text-xs text-gray-500 font-mono">{cmd}</code>;
}

export function LogTable({ logs }: LogTableProps) {
  const entries = Array.isArray(logs) ? logs : [];
  const lastIndex = entries.length > 0 ? Math.max(...entries.map((e) => e.index ?? 0)) : 0;

  return (
    <div className="rounded-xl border border-gray-200 dark:border-white/10 bg-white/80 dark:bg-black/20 overflow-hidden">
      <div className="px-4 py-3 border-b border-gray-200 dark:border-white/10 flex items-center gap-2">
        <span className="text-cyan-600 dark:text-cyan-400 font-mono font-semibold">Raft Log</span>
        <span className="text-gray-500 text-sm">({entries.length} entries)</span>
      </div>
      <div className="max-h-[220px] overflow-y-auto">
        <table className="w-full text-sm font-mono">
          <thead className="sticky top-0 bg-gray-100 dark:bg-black/40 text-gray-600 dark:text-gray-400">
            <tr>
              <th className="text-left px-4 py-2 w-16">Index</th>
              <th className="text-left px-4 py-2 w-16">Term</th>
              <th className="text-left px-4 py-2">Command</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry, i) => {
              const idx = entry.index ?? i + 1;
              const isNewest = idx === lastIndex && lastIndex > 0;
              return (
                <motion.tr
                  key={idx}
                  layout
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  className={`border-t border-gray-100 dark:border-white/5 ${
                    isNewest ? 'bg-emerald-500/10' : 'hover:bg-white/5'
                  }`}
                >
                  <td className="px-4 py-2 text-cyan-400">{idx}</td>
                  <td className="px-4 py-2 text-amber-400">{entry.term ?? 0}</td>
                  <td className="px-4 py-2">
                    <CommandBadge cmd={entry.command} />
                  </td>
                </motion.tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
