# Raft Consensus Demo – React + TypeScript Frontend

Interactive React + TypeScript + Vite frontend for the Raft consensus backend with framer-motion animations, Recharts metrics, and WebSocket live updates.

## Features

- **NodeCard**: Node id, role, term, lastLogIndex; leader glow; crashed nodes blink red
- **LeaderElectedBanner**: Animated banner when a new leader is elected
- **LogReplicationVisualizer**: Pulsing arrows/dots from leader → followers
- **LogTable**: Index, term, command (PUT=green, DEL=red); newest entry highlighted
- **MetadataTable**: Live key-value snapshot with change highlight
- **MetricsChart**: Recharts bar chart for elections won, leader uptime
- **ControlPanel**: Crash/Restore per node, Append Demo button
- **ThemeToggle**: Dark/light mode with localStorage persistence
- **WebSocket**: Live push updates (falls back to polling)

## Tech Stack

- React 18 + TypeScript
- Vite
- Tailwind CSS
- framer-motion (animations)
- Recharts (metrics)
- Axios (HTTP)
- WebSocket (live updates)

## Quick Start

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173

## Project Structure

```
frontend/src/
├── components/       # NodeCard, LeaderElectedBanner, MetricsChart, etc.
├── context/          # ThemeContext, RaftContext
├── hooks/            # useWebSocket, useRaftData
├── api/              # Axios client, raft endpoints
├── types/            # RaftNode, LogEntry, RaftMetrics
├── App.tsx
├── main.tsx
└── index.css
```

## Backend API

Expects backend at `http://localhost:8080`. Override with `VITE_API_BASE`.

| Method | Path | Description |
|--------|------|-------------|
| GET | /nodes | List of nodes |
| GET | /logs | Log entries |
| GET | /metadata | Metadata snapshot |
| GET | /metrics | Elections won, leader uptime |
| WS | /ws | Live push |
| POST | /control | Crash/restore node |
| POST | /control/demo | Append demo metadata |
