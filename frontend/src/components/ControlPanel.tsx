import React from 'react';
import { motion } from 'framer-motion';
import { controlNode, appendDemoMetadata } from '../api/raft';
import type { RaftNode } from '../types';

interface ControlPanelProps {
  nodes: RaftNode[];
  onAction: () => void;
  loading: boolean;
  setLoading: (v: boolean) => void;
}

export function ControlPanel({ nodes, onAction, loading, setLoading }: ControlPanelProps) {
  const handleCrash = async (nodeId: string) => {
    setLoading(true);
    try {
      await controlNode(nodeId, 'crash');
      onAction();
    } finally {
      setLoading(false);
    }
  };

  const handleRestore = async (nodeId: string) => {
    setLoading(true);
    try {
      await controlNode(nodeId, 'restore');
      onAction();
    } finally {
      setLoading(false);
    }
  };

  const handleDemo = async () => {
    setLoading(true);
    try {
      await appendDemoMetadata();
      onAction();
    } finally {
      setLoading(false);
    }
  };

  const nodeIds = ['n1', 'n2', 'n3'];
  const nodeMap = nodes.reduce<Record<string, RaftNode>>((acc, n) => {
    acc[n.id] = n;
    return acc;
  }, {});

  return (
    <div className="rounded-xl border border-gray-200 dark:border-white/10 bg-white/80 dark:bg-black/20 p-4">
      <div className="text-cyan-600 dark:text-cyan-400 font-mono font-semibold mb-4">
        Control Panel
      </div>
      <div className="flex flex-wrap gap-4">
        {nodeIds.map((id) => {
          const node = nodeMap[id];
          const isCrashed = node?.role === 'CRASHED';
          const btnClass = isCrashed
            ? 'px-4 py-2 rounded-lg font-mono text-sm font-medium transition-colors bg-emerald-500/20 text-emerald-400 border border-emerald-500/50 hover:bg-emerald-500/30'
            : 'px-4 py-2 rounded-lg font-mono text-sm font-medium transition-colors bg-red-500/20 text-red-400 border border-red-500/50 hover:bg-red-500/30';

          return (
            <div key={id} className="flex flex-col gap-2">
              <span className="text-xs text-gray-500 uppercase">{id}</span>
              <motion.button
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.98 }}
                onClick={() => (isCrashed ? handleRestore(id) : handleCrash(id))}
                disabled={loading}
                className={btnClass}
              >
                {isCrashed ? 'Restore' : 'Crash'}
              </motion.button>
            </div>
          );
        })}
        <div className="flex flex-col gap-2">
          <span className="text-xs text-gray-500 uppercase">Demo</span>
          <motion.button
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.98 }}
            onClick={handleDemo}
            disabled={loading}
            className="px-4 py-2 rounded-lg font-mono text-sm font-medium bg-cyan-500/20 text-cyan-400 border border-cyan-500/50 hover:bg-cyan-500/30"
          >
            Append Demo
          </motion.button>
        </div>
      </div>
    </div>
  );
}
