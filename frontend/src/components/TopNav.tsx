import { useEffect, useState } from 'react';
import { Link, NavLink } from 'react-router-dom';
import {
  IconAsk,
  IconFolders,
  IconSearch,
  IconClose,
} from './icons';
// ─── Globe SVG (inline, no dedicated icon exists) ────────────────────────────

function IconGlobe({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 14 14" fill="none">
      <circle cx="7" cy="7" r="5.5" stroke="currentColor" strokeWidth="1.2" />
      <path
        d="M7 1.5C5.5 3 4.5 5 4.5 7s1 4 2.5 5.5M7 1.5C8.5 3 9.5 5 9.5 7s-1 4-2.5 5.5M1.5 7h11"
        stroke="currentColor"
        strokeWidth="1.1"
        strokeLinecap="round"
      />
    </svg>
  );
}

// ─── Jurisdiction state (persisted to localStorage) ──────────────────────────

const JURIS_KEY = 'launchlens.currentJurisdiction';

function readJurisCode(): string {
  if (typeof window === 'undefined') return 'NL';
  return localStorage.getItem(JURIS_KEY) ?? 'NL';
}

export function useCurrentJurisdiction(): [string, (code: string) => void] {
  const [code, setCode] = useState<string>(readJurisCode);
  const set = (c: string) => {
    localStorage.setItem(JURIS_KEY, c);
    setCode(c);
  };
  return [code, set];
}

// ─── ViewTab ──────────────────────────────────────────────────────────────────

interface ViewTabProps {
  to: string;
  icon: React.ReactNode;
  label: string;
}

function ViewTab({ to, icon, label }: ViewTabProps) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        'viewtab' + (isActive ? ' viewtab--active' : '')
      }
    >
      <span className="viewtab__icon">{icon}</span>
      <span>{label}</span>
    </NavLink>
  );
}

// ─── Hamburger icon (inline, no dedicated icon exists) ───────────────────────

function IconHamburger() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <rect x="2" y="4" width="14" height="1.5" rx="0.75" fill="currentColor" />
      <rect x="2" y="8.25" width="14" height="1.5" rx="0.75" fill="currentColor" />
      <rect x="2" y="12.5" width="14" height="1.5" rx="0.75" fill="currentColor" />
    </svg>
  );
}

// ─── TopNav ────────────────────────────────────────────────────────────────────

export default function TopNav() {
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Close drawer on Escape
  useEffect(() => {
    if (!drawerOpen) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setDrawerOpen(false);
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [drawerOpen]);

  return (
    <>
      <header className="topnav hairline-b">
        {/* Left */}
        <div className="topnav__left">
          <Link to="/" className="wordmark">
            <span className="wordmark__text">K.V.A.S</span>
            <span className="wordmark__dot">.</span>
          </Link>
        </div>

        {/* Center nav */}
        <nav className="topnav__center">
          <ViewTab to="/ask" icon={<IconAsk size={14} />} label="Ask" />
          <ViewTab to="/launches" icon={<IconFolders size={14} />} label="Launches" />
          <ViewTab to="/jurisdictions" icon={<IconGlobe size={14} />} label="Jurisdictions" />
        </nav>

        {/* Right */}
        <div className="topnav__right">
          <button type="button" className="btn btn--ghost btn--sm">
            <IconSearch size={14} />
            {' '}Search
          </button>
          <div className="kbd">⌘K</div>
        </div>

        {/* Hamburger (mobile only) */}
        <button
          type="button"
          className="topnav__hamburger"
          aria-label="Open menu"
          onClick={() => setDrawerOpen(true)}
        >
          <IconHamburger />
        </button>
      </header>

      {/* Mobile drawer */}
      {drawerOpen && (
        <div className="drawer-overlay" onClick={() => setDrawerOpen(false)} />
      )}
      <div
        className={'drawer' + (drawerOpen ? ' drawer--open' : '')}
        aria-hidden={!drawerOpen}
      >
        <div className="drawer__header">
          <Link to="/" className="wordmark" onClick={() => setDrawerOpen(false)}>
            <span className="wordmark__text">K.V.A.S</span>
            <span className="wordmark__dot">.</span>
          </Link>
          <button
            type="button"
            className="drawer__close"
            aria-label="Close menu"
            onClick={() => setDrawerOpen(false)}
          >
            <IconClose size={16} />
          </button>
        </div>
        <nav className="drawer__nav" onClick={() => setDrawerOpen(false)}>
          <ViewTab to="/ask" icon={<IconAsk size={14} />} label="Ask" />
          <ViewTab to="/launches" icon={<IconFolders size={14} />} label="Launches" />
          <ViewTab to="/jurisdictions" icon={<IconGlobe size={14} />} label="Jurisdictions" />
        </nav>
        <div className="drawer__search">
          <button type="button" className="btn btn--ghost btn--sm">
            <IconSearch size={14} />
            {' '}Search
          </button>
          <div className="kbd">⌘K</div>
        </div>
      </div>
    </>
  );
}
