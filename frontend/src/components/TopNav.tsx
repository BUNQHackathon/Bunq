import { useEffect, useMemo, useState } from 'react';
import { Link, NavLink, useNavigate } from 'react-router-dom';
import {
  IconAsk,
  IconFolder,
  IconHelp,
  IconClose,
  IconFolders,
} from './icons';

const NAV_SHORTCUTS: { key: '1' | '2' | '3' | '4'; path: string }[] = [
  { key: '1', path: '/ask' },
  { key: '2', path: '/launches' },
  { key: '3', path: '/jurisdictions' },
  { key: '4', path: '/data' },
];
import HeaderSearch from './HeaderSearch';
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
  shortcut?: string;
  modKey?: string;
}

function ViewTab({ to, icon, label, shortcut, modKey }: ViewTabProps) {
  const showTip = shortcut && modKey;
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        'viewtab' + (isActive ? ' viewtab--active' : '')
      }
      aria-keyshortcuts={showTip ? `${modKey === '⌘' ? 'Meta' : 'Control'}+${shortcut}` : undefined}
    >
      <span className="viewtab__icon">{icon}</span>
      <span>{label}</span>
      {showTip && (
        <span className="viewtab__tip" role="tooltip" aria-hidden="true">
          <span className="viewtab__tip-label">Open</span>
          <span className="viewtab__tip-keys">{modKey}<span className="viewtab__tip-key">{shortcut}</span></span>
        </span>
      )}
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
  const navigate = useNavigate();
  const isMac = useMemo(
    () =>
      typeof navigator !== 'undefined' &&
      /Mac|iPhone|iPad|iPod/.test(navigator.platform),
    [],
  );
  const modKey = isMac ? '⌘' : 'Ctrl';

  // Cmd/Ctrl + 1/2/3 → switch top-nav view
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return;
      const match = NAV_SHORTCUTS.find((s) => s.key === e.key);
      if (!match) return;
      const target = e.target as HTMLElement | null;
      const tag = target?.tagName;
      if (
        tag === 'INPUT' ||
        tag === 'TEXTAREA' ||
        tag === 'SELECT' ||
        target?.isContentEditable
      ) {
        return;
      }
      e.preventDefault();
      navigate(match.path);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [navigate]);

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
          <ViewTab to="/ask" icon={<IconAsk size={14} />} label="Ask" shortcut="1" modKey={modKey} />
          <ViewTab to="/launches" icon={<IconFolders size={14} />} label="Launches" shortcut="2" modKey={modKey} />
          <ViewTab to="/data" icon={<IconFolder size={14} />} label="Data" shortcut="4" modKey={modKey} />
          <ViewTab to="/jurisdictions" icon={<IconGlobe size={14} />} label="Jurisdictions" shortcut="3" modKey={modKey} />
        </nav>

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
          <ViewTab to="/ask" icon={<IconAsk size={14} />} label="Ask" shortcut="1" modKey={modKey} />
          <ViewTab to="/launches" icon={<IconFolders size={14} />} label="Launches" shortcut="2" modKey={modKey} />
          <ViewTab to="/data" icon={<IconFolder size={14} />} label="Data" shortcut="4" modKey={modKey} />
          <ViewTab to="/jurisdictions" icon={<IconGlobe size={14} />} label="Jurisdictions" shortcut="3" modKey={modKey} />
        </nav >
        <div className="drawer__search">
        </div>
      </div >
    </>
  );
}
