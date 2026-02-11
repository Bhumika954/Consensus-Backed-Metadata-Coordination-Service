import React from 'react';
import { motion } from 'framer-motion';
import type { RaftNode } from '../types';

interface NodeCardProps {
  node: RaftNode;
  isLeader: boolean;
}

const roleStyles: Record<string, { bg: string; border: string; text: string; animate?: string }> = {
  LEADER: {
    bg: 'bg-emerald-500/20 dark:bg-emerald-500/20',
    border: 'border-emerald-500',
    text: 'text-emerald-400',
  },
  FOLLOWER: {
    bg: 'bg-blue-500/20 dark:bg-blue-500/20',
    border: 'border-blue-500',
    text: 'text-blue-400',
  },
  CANDIDATE: {
    bg: 'bg-amber-500/20 dark:bg-amber-500/20',
    border: 'border-amber-500',
    text: 'text-amber-400',
  },
  CRASHED: {
    bg: 'bg-gray-600/20 dark:bg-gray-600/20',
    border: 'border-red-500',
    text: 'text-red-400',
  },
};

export function NodeCard({ node, isLeader }: NodeCardProps) {
  const role = (node.role || 'FOLLOWER').toUpperCase();
  const style = roleStyles[role] ?? roleStyles.FOLLOWER;
  const isCrashed = role === 'CRASHED';

  return (
    <motion.div
      layout
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      className={`
        relative rounded-2xl border-2 p-6 min-w-[140px] flex flex-col items-center justify-center
        ${style.bg} ${style.border} transition-colors
        ${isLeader ? 'shadow-[0_0_30px_rgba(34,197,94,0.5)]' : ''}
        ${isCrashed ? 'animate-pulse' : ''}
      `}
    >
      {isLeader && (
        <motion.div
          animate={{ opacity: [0.6, 1, 0.6] }}
          transition={{ duration: 1.5, repeat: Infinity }}
          className="absolute inset-0 rounded-2xl border-2 border-emerald-500/50 pointer-events-none"
        />
      )}
      {isCrashed && (
        <motion.div
          animate={{ opacity: [0.3, 0.8, 0.3] }}
          transition={{ duration: 1, repeat: Infinity }}
          className="absolute inset-0 rounded-2xl bg-red-500/20 pointer-events-none"
        />
      )}
      <div className="text-3xl font-bold font-mono">{node.id}</div>
      <div className={`text-xs font-semibold uppercase tracking-wider mt-1 mb-3 ${style.text}`}>
        {role}
      </div>
      <div className="flex flex-col gap-1 text-sm text-gray-400 font-mono">
        <span>term: <span className="text-white">{node.term ?? 0}</span></span>
        <span>lastIdx: <span className="text-white">{node.lastLogIndex ?? 0}</span></span>
      </div>
    </motion.div>
  );
}
