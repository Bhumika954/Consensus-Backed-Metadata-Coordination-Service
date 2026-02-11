/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        display: ['JetBrains Mono', 'Fira Code', 'monospace'],
        sans: ['DM Sans', 'system-ui', 'sans-serif'],
      },
      colors: {
        raft: {
          leader: '#22c55e',
          follower: '#3b82f6',
          candidate: '#eab308',
          crashed: '#6b7280',
        },
        neon: {
          green: '#00ff88',
          blue: '#00d4ff',
          yellow: '#ffeb3b',
          purple: '#b388ff',
        }
      },
      animation: {
        'crash-blink': 'crash-blink 1s ease-in-out infinite',
        'bounce-in': 'bounceIn 0.5s ease-out',
        'pulse-heartbeat': 'pulse-heartbeat 1.5s ease-in-out infinite',
        'glow': 'glow 2s ease-in-out infinite alternate',
        'float': 'float 3s ease-in-out infinite',
      },
      keyframes: {
        'crash-blink': {
          '0%, 100%': { opacity: '0.7' },
          '50%': { opacity: '0.4' },
        },
        'bounceIn': {
          '0%': { transform: 'translate(-50%, -20px)', opacity: '0' },
          '100%': { transform: 'translate(-50%, 0)', opacity: '1' },
        },
        'pulse-heartbeat': {
          '0%, 100%': { boxShadow: '0 0 20px rgba(34, 197, 94, 0.6)' },
          '50%': { boxShadow: '0 0 40px rgba(34, 197, 94, 0.9)' },
        },
        'glow': {
          '0%': { boxShadow: '0 0 10px rgba(0, 255, 136, 0.4)' },
          '100%': { boxShadow: '0 0 25px rgba(0, 255, 136, 0.8)' },
        },
        'float': {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-4px)' },
        },
      },
      boxShadow: {
        'neon-green': '0 0 20px rgba(0, 255, 136, 0.5)',
        'neon-blue': '0 0 20px rgba(0, 212, 255, 0.5)',
        'neon-yellow': '0 0 20px rgba(255, 235, 59, 0.5)',
      }
    },
  },
  plugins: [],
}
