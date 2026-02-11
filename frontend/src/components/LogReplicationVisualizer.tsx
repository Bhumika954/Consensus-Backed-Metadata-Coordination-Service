import React from 'react';
import { motion } from 'framer-motion';
import type { RaftNode } from '../types';

interface LogReplicationVisualizerProps {
  nodes: RaftNode[];
  leaderId: string | null;
}

export function LogReplicationVisualizer({ nodes, leaderId }: LogReplicationVisualizerProps) {
  const followers = nodes.filter((n) => n.role !== 'CRASHED' && n.id !== leaderId);

  if (!leaderId || followers.length === 0) return null;

  return (
    <div className="flex items-center justify-center gap-4 py-6">
      <motion.div
        animate={{ scale: [1, 1.1, 1] }}
        transition={{ duration: 1.5, repeat: Infinity }}
        className="text-emerald-500 text-xl font-mono font-bold"
      >
        {leaderId}
      </motion.div>
      {followers.map((follower, i) => (
        <React.Fragment key={follower.id}>
          <motion.div
            className="flex items-center gap-1"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: i * 0.1 }}
          >
            <motion.span
              animate={{ opacity: [0.5, 1, 0.5], scale: [1, 1.2, 1] }}
              transition={{ duration: 0.8, repeat: Infinity, delay: i * 0.2 }}
              className="w-2 h-2 rounded-full bg-emerald-400"
            />
            <motion.span
              animate={{ opacity: [0.5, 1, 0.5], scale: [1, 1.2, 1] }}
              transition={{ duration: 0.8, repeat: Infinity, delay: i * 0.2 + 0.15 }}
              className="w-2 h-2 rounded-full bg-emerald-400"
            />
            <motion.span
              animate={{ opacity: [0.5, 1, 0.5], scale: [1, 1.2, 1] }}
              transition={{ duration: 0.8, repeat: Infinity, delay: i * 0.2 + 0.3 }}
              className="w-2 h-2 rounded-full bg-emerald-400"
            />
            <svg className="w-12 h-6 text-emerald-500/70" viewBox="0 0 48 24" fill="none">
              <path
                d="M0 12 L36 12 M32 6 L48 12 L32 18"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </motion.div>
          <motion.div
            initial={{ opacity: 0.5 }}
            animate={{ opacity: 1 }}
            className="text-blue-400 text-xl font-mono font-bold"
          >
            {follower.id}
          </motion.div>
        </React.Fragment>
      ))}
    </div>
  );
}
