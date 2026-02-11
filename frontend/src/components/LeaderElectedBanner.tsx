import React, { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';

interface LeaderElectedBannerProps {
  leaderId: string | null;
  prevLeaderId: string | null;
}

export function LeaderElectedBanner({ leaderId, prevLeaderId }: LeaderElectedBannerProps) {
  const [show, setShow] = useState(false);
  const [key, setKey] = useState(0);

  useEffect(() => {
    if (leaderId && prevLeaderId != null && leaderId !== prevLeaderId) {
      setShow(true);
      setKey((k) => k + 1);
      const t = setTimeout(() => setShow(false), 3000);
      return () => clearTimeout(t);
    }
  }, [leaderId, prevLeaderId]);

  return (
    <AnimatePresence>
      {show && leaderId && (
        <motion.div
          key={key}
          initial={{ opacity: 0, y: -50, scale: 0.8 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0, y: -30 }}
          transition={{ type: 'spring', damping: 15, stiffness: 300 }}
          className="fixed top-6 left-1/2 -translate-x-1/2 z-50"
        >
          <div className="px-8 py-5 rounded-2xl bg-emerald-500/20 border-2 border-emerald-500 shadow-2xl shadow-emerald-500/30 flex items-center gap-4">
            <motion.span
              animate={{ rotate: [0, 10, -10, 0] }}
              transition={{ duration: 0.5 }}
              className="text-4xl"
            >
              👑
            </motion.span>
            <div>
              <div className="font-bold text-emerald-400 text-sm uppercase tracking-wider">
                New Leader Elected
              </div>
              <div className="text-3xl font-mono font-bold text-white">{leaderId}</div>
            </div>
            <motion.span
              animate={{ scale: [1, 1.2, 1], opacity: [1, 0.5, 1] }}
              transition={{ duration: 0.8, repeat: Infinity }}
              className="w-4 h-4 rounded-full bg-emerald-400"
            />
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
