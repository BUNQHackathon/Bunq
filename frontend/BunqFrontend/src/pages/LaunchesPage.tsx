import { Link } from 'react-router-dom';

export default function LaunchesPage() {
  return (
    <div className="min-h-screen px-6 py-10 max-w-6xl mx-auto" style={{ color: '#E8E8E8' }}>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-white mb-1">Launches</h1>
          <p className="font-mono text-[11px] uppercase tracking-wider" style={{ color: '#6B6B6B' }}>
            Product launches across markets
          </p>
        </div>
        <Link
          to="/launches/new"
          className="px-4 py-2 rounded-xl text-[13px] font-medium transition-all"
          style={{
            background: 'rgba(255,120,25,0.14)',
            border: '1px solid rgba(255,120,25,0.35)',
            color: '#FF9F55',
          }}
        >
          + New Launch
        </Link>
      </div>

      <div
        className="rounded-xl px-6 py-12 text-center"
        style={{ background: '#0D0D0D', border: '1px solid rgba(255,255,255,0.06)' }}
      >
        <p className="text-[13px] mb-4" style={{ color: '#6B6B6B' }}>
          No launches yet.
        </p>
        <Link to="/launches/new" className="text-[13px] font-medium" style={{ color: '#FF9F55' }}>
          Create your first launch →
        </Link>
      </div>
    </div>
  );
}
