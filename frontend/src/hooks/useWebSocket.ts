import { useEffect, useRef, useCallback } from 'react';
import { getWsUrl } from '../api/client';

interface WsPayload {
  nodes?: unknown[];
  logs?: unknown[];
  metadata?: Record<string, string>;
  metrics?: { electionsWon?: number; leaderUptimeMs?: number; currentLeaderId?: string | null };
}

interface UseWebSocketOptions {
  onMessage: (payload: WsPayload) => void;
  onConnect?: () => void;
  onDisconnect?: () => void;
}

export function useWebSocket({ onMessage, onConnect, onDisconnect }: UseWebSocketOptions) {
  const onMessageRef = useRef(onMessage);
  const onConnectRef = useRef(onConnect);
  const onDisconnectRef = useRef(onDisconnect);
  onMessageRef.current = onMessage;
  onConnectRef.current = onConnect;
  onDisconnectRef.current = onDisconnect;

  const connect = useCallback(() => {
    const url = getWsUrl();
    const ws = new WebSocket(url);

    ws.onopen = () => {
      onConnectRef.current?.();
    };

    ws.onmessage = (event) => {
      try {
        const payload = JSON.parse(event.data) as WsPayload;
        onMessageRef.current(payload);
      } catch {
        // ignore
      }
    };

    ws.onclose = () => {
      onDisconnectRef.current?.();
      setTimeout(connect, 2000);
    };

    return ws;
  }, []);

  useEffect(() => {
    const ws = connect();
    return () => ws.close();
  }, [connect]);
}
