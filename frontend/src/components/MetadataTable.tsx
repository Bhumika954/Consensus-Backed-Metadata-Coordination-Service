import React, { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';

interface MetadataTableProps {
  metadata: Record<string, string>;
}

export function MetadataTable({ metadata }: MetadataTableProps) {
  const [changedKeys, setChangedKeys] = useState<Set<string>>(new Set());
  const prevRef = React.useRef<Record<string, string>>({});

  useEffect(() => {
    const keys = new Set([...Object.keys(metadata), ...Object.keys(prevRef.current)]);
    const changed = new Set<string>();
    keys.forEach((k) => {
      if (metadata[k] !== prevRef.current[k]) changed.add(k);
    });
    prevRef.current = { ...metadata };
    setChangedKeys(changed);
    const t = setTimeout(() => setChangedKeys(new Set()), 800);
    return () => clearTimeout(t);
  }, [metadata]);

  const entries = Object.entries(metadata);

  return (
    <div className="rounded-xl border border-gray-200 dark:border-white/10 bg-white/80 dark:bg-black/20 overflow-hidden">
      <div className="px-4 py-3 border-b border-gray-200 dark:border-white/10 flex items-center gap-2">
        <span className="text-purple-600 dark:text-purple-400 font-mono font-semibold">
          Metadata Snapshot
        </span>
        <span className="text-gray-500 text-sm">({entries.length} keys)</span>
      </div>
      <div className="p-4 max-h-[200px] overflow-y-auto">
        {entries.length === 0 ? (
          <p className="text-gray-500 text-sm italic">Empty — no metadata yet</p>
        ) : (
          <AnimatePresence mode="popLayout">
            {entries.map(([key, value]) => (
              <motion.div
                key={key}
                layout
                initial={{ opacity: 0, y: 10 }}
                animate={{
                  opacity: 1,
                  y: 0,
                  backgroundColor: changedKeys.has(key) ? 'rgba(168,85,247,0.2)' : 'transparent',
                }}
                exit={{ opacity: 0 }}
                className="flex items-center gap-2 py-2 px-3 rounded-lg mb-1 font-mono text-sm"
              >
                <span className="text-purple-400 flex-1 truncate">{key}</span>
                <span className="text-white font-medium truncate max-w-[120px]">{String(value)}</span>
              </motion.div>
            ))}
          </AnimatePresence>
        )}
      </div>
    </div>
  );
}
