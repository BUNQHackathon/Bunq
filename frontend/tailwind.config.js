/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        prism: {
          bg: '#080808',
          panel: '#0D0D0D',
          card: '#141414',
          elev: '#171717',
          line: 'rgba(255,255,255,0.08)',
          orange: '#FF7819',
          'orange-soft': '#FF9F55',
          purple: '#B08AFF',
          teal: '#5ECFA0',
          red: '#E05050',
          cream: '#FAF7F2',
          'cream-2': '#F7F3EE',
          'ink-0': '#FAF7F2',
          'ink-1': 'rgba(255,255,255,0.82)',
          'ink-2': 'rgba(255,255,255,0.55)',
          'ink-3': 'rgba(255,255,255,0.3)',
          'bg-0': '#080808',
          aml: '#FF9F55',
          privacy: '#B08AFF',
          licensing: '#A8D66C',
          terms: '#5ECFA0',
          sanctions: '#6EB7E8',
          reports: '#E05050',
          concept: '#445566',
        }
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        serif: ['"Instrument Serif"', '"Playfair Display"', 'Georgia', 'serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
      borderRadius: {
        pill: '999px',
      },
      fontSize: {
        '2xs': ['10px', '1.2'],
        'mono-xs': ['11px', '1.3'],
      },
      keyframes: {
        blink: { '0%,80%,100%': { opacity: 0.2 }, '40%': { opacity: 1 } },
        spin: { to: { transform: 'rotate(360deg)' } },
      },
      animation: {
        blink: 'blink 1.2s infinite',
        spin: 'spin 0.8s linear infinite',
      },
    },
  },
  plugins: [],
}
