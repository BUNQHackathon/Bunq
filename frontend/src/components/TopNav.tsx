import { useEffect, useRef, useState } from 'react';
import { Link, NavLink } from 'react-router-dom';
import {
  IconAsk,
  IconGraph,
  IconFolders,
  IconSearch,
  IconChevron,
} from './icons';
import ModeToggle from './ModeToggle';
import {
  JURISDICTION_CATALOG,
  jurisdictionFlag,
  jurisdictionLabel,
} from '../api/launch';

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

// ─── Flag component ───────────────────────────────────────────────────────────

function Flag({ code }: { code: string }) {
  return <span className="flag">{jurisdictionFlag(code)}</span>;
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

// ─── Jurisdiction Dropdown ────────────────────────────────────────────────────

interface JurisdictionDropdownProps {
  currentCode: string;
  onSelect: (code: string) => void;
}

function JurisdictionDropdown({ currentCode, onSelect }: JurisdictionDropdownProps) {
  return (
    <div
      className="absolute top-full left-0 mt-1 z-50 overflow-hidden"
      style={{
        background: 'var(--bg-1)',
        border: '1px solid var(--line-1)',
        borderRadius: 'var(--r-md)',
        minWidth: '200px',
        boxShadow: '0 8px 32px rgba(0,0,0,0.4)',
      }}
    >
      {JURISDICTION_CATALOG.map((j) => {
        const isActive = j.code === currentCode;
        return (
          <button
            key={j.code}
            type="button"
            onClick={() => onSelect(j.code)}
            className="w-full flex items-center gap-2.5 px-3 py-2 text-left text-[12.5px] transition-colors"
            style={{
              color: isActive ? 'var(--orange)' : 'var(--ink-1)',
              background: isActive ? 'var(--orange-wash)' : 'transparent',
            }}
          >
            <span>{jurisdictionFlag(j.code)}</span>
            <span className="flex-1">{j.name}</span>
            {isActive && (
              <span
                className="w-1.5 h-1.5 rounded-full shrink-0"
                style={{ background: 'var(--orange)' }}
              />
            )}
          </button>
        );
      })}
    </div>
  );
}

// ─── TopNav ────────────────────────────────────────────────────────────────────

export default function TopNav() {
  const [jurisOpen, setJurisOpen] = useState(false);
  const [currentCode, setCurrentCode] = useCurrentJurisdiction();
  const jurisRef = useRef<HTMLDivElement>(null);

  // Close on outside click
  useEffect(() => {
    if (!jurisOpen) return;
    const handler = (e: MouseEvent) => {
      if (!jurisRef.current?.contains(e.target as Node)) {
        setJurisOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [jurisOpen]);

  // Close on Escape
  useEffect(() => {
    if (!jurisOpen) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setJurisOpen(false);
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [jurisOpen]);

  return (
    <header className="topnav hairline-b">
      {/* Left */}
      <div className="topnav__left">
        <Link to="/" className="wordmark">
          <span className="wordmark__text">prism</span>
          <span className="wordmark__dot">.</span>
        </Link>

        <div className="topnav__ctx">
          <span className="mono-label">COMPLIANCE /</span>
          <div ref={jurisRef} className="relative">
            <button
              type="button"
              className="topnav__ctx-country"
              onClick={() => setJurisOpen((v) => !v)}
            >
              <Flag code={currentCode} />
              <span>{jurisdictionLabel(currentCode)}</span>
              <IconChevron size={12} />
            </button>
            {jurisOpen && (
              <JurisdictionDropdown
                currentCode={currentCode}
                onSelect={(code) => {
                  setCurrentCode(code);
                  setJurisOpen(false);
                }}
              />
            )}
          </div>
        </div>
      </div>

      {/* Center nav */}
      <nav className="topnav__center">
        <ViewTab to="/ask" icon={<IconAsk size={14} />} label="Ask" />
        <ViewTab to="/graph" icon={<IconGraph size={14} />} label="Graph" />
        <ViewTab to="/launches" icon={<IconFolders size={14} />} label="Launches" />
        <ViewTab to="/jurisdictions" icon={<IconGlobe size={14} />} label="Jurisdictions" />
      </nav>

      {/* Right */}
      <div className="topnav__right">
        <ModeToggle />
        <button type="button" className="btn btn--ghost btn--sm">
          <IconSearch size={14} />
          {' '}Search
        </button>
        <div className="kbd">⌘K</div>
        <div className="avatar">MK</div>
      </div>
    </header>
  );
}
