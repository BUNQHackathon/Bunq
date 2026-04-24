import { useEffect, useRef, useState } from 'react';
import { NavLink } from 'react-router-dom';
import {
  IconFolders,
  IconSearch,
  IconChevron,
  IconDoc,
  IconChatBubble,
  IconExternal,
  IconSettings,
  IconMoon,
  IconStar,
} from './icons';

// ─── Data ──────────────────────────────────────────────────────────────────────

const navItems = [
  { to: '/launches', label: 'Launches', Icon: IconStar, end: false },
  { to: '/library', label: 'Library', Icon: IconFolders, end: false },
];

interface Jurisdiction {
  flag: string;
  name: string;
  status: string;
}

const JURISDICTIONS: Jurisdiction[] = [
  { flag: '🇳🇱', name: 'Netherlands', status: 'Active' },
  { flag: '🇩🇪', name: 'Germany', status: 'Active' },
  { flag: '🇫🇷', name: 'France', status: 'Active' },
  { flag: '🇪🇸', name: 'Spain', status: 'Active' },
  { flag: '🇮🇹', name: 'Italy', status: 'Active' },
  { flag: '🇮🇪', name: 'Ireland', status: 'Active' },
  { flag: '🇧🇪', name: 'Belgium', status: 'Active' },
  { flag: '🇬🇧', name: 'United Kingdom', status: 'EMI' },
];

// ─── Shared dropdown styles ────────────────────────────────────────────────────

const DROPDOWN_STYLE: React.CSSProperties = {
  background: '#141414',
  border: '1px solid rgba(255,255,255,0.08)',
  boxShadow: '0 10px 40px rgba(0,0,0,0.5)',
};

const SECTION_HEADER = 'font-mono uppercase tracking-widest text-[10px] text-white/30 px-3.5 pt-3 pb-1.5 select-none';
const DIVIDER = <div className="my-1" style={{ borderTop: '1px solid rgba(255,255,255,0.05)' }} />;
const ITEM_BASE = 'flex items-center gap-2.5 px-3.5 py-2 hover:bg-white/[0.04] cursor-pointer text-[13px] text-white/80 w-full text-left transition-colors';

// ─── Sub-components ────────────────────────────────────────────────────────────

interface JurisdictionDropdownProps {
  current: Jurisdiction;
  onSelect: (j: Jurisdiction) => void;
}

function JurisdictionDropdown({ current, onSelect }: JurisdictionDropdownProps) {
  return (
    <div
      className="absolute top-full left-0 mt-2 min-w-[240px] rounded-xl overflow-hidden z-50"
      style={DROPDOWN_STYLE}
    >
      <div
        className="font-mono uppercase tracking-widest text-[10px] text-white/40 px-[14px] py-3 select-none"
        style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}
      >
        Select jurisdiction
      </div>
      {JURISDICTIONS.map((j) => {
        const isSelected = j.name === current.name;
        return (
          <button
            key={j.name}
            type="button"
            onClick={() => onSelect(j)}
            className={`${ITEM_BASE} relative`}
            style={isSelected ? { background: 'rgba(255,255,255,0.04)' } : undefined}
          >
            {isSelected && (
              <span className="absolute left-1.5 w-1 h-1 rounded-full bg-[#FF7819]" />
            )}
            <span className="w-4 text-base leading-none">{j.flag}</span>
            <span className="flex-1">{j.name}</span>
            <span className="font-mono text-[10px] text-white/30 ml-auto">{j.status}</span>
          </button>
        );
      })}
      {DIVIDER}
      <button type="button" className={ITEM_BASE}>
        <span className="w-4 text-base leading-none">🌍</span>
        <span className="flex-1">All EU</span>
      </button>
    </div>
  );
}

interface SearchDropdownProps {
  onClose: () => void;
}

function SearchDropdown({ onClose }: SearchDropdownProps) {
  const DOCS = [
    { title: 'AML & KYC Framework 2025', meta: 'AML · Nov 2025' },
    { title: 'Privacy Policy & GDPR', meta: 'Privacy · Dec 2025' },
    { title: 'General T&C v4.2', meta: 'Terms · Jan 2026' },
  ];
  const CHATS = [
    { title: 'AML framework — Germany', meta: '2h ago' },
    { title: 'GDPR retention question', meta: 'Yesterday' },
  ];
  const CONCEPTS = [
    { title: 'GDPR', meta: 'EU Regulation' },
    { title: 'FATF Recommendations', meta: 'International' },
  ];

  return (
    <div
      className="absolute top-full right-0 mt-2 w-[380px] rounded-xl overflow-hidden z-50 flex flex-col"
      style={DROPDOWN_STYLE}
    >
      <input
        autoFocus
        type="text"
        placeholder="Search documents, chats, concepts..."
        className="w-full bg-transparent text-[13px] text-white/85 placeholder-white/30 px-[14px] py-[10px] outline-none"
        style={{
          borderBottom: '1px solid rgba(255,255,255,0.06)',
          caretColor: '#FF7819',
        }}
      />

      <div className="overflow-y-auto max-h-[360px]">
        <div className={SECTION_HEADER}>Documents</div>
        {DOCS.map((d) => (
          <button key={d.title} type="button" onClick={onClose}
            className="flex items-center gap-3 px-4 py-2 hover:bg-white/[0.04] cursor-pointer w-full text-left transition-colors">
            <IconDoc size={12} className="text-white/50 shrink-0" />
            <span className="flex-1 text-[13px] text-white/85 truncate">{d.title}</span>
            <span className="font-mono text-[10px] text-white/40 shrink-0">{d.meta}</span>
          </button>
        ))}

        <div className={SECTION_HEADER}>Chats</div>
        {CHATS.map((c) => (
          <button key={c.title} type="button" onClick={onClose}
            className="flex items-center gap-3 px-4 py-2 hover:bg-white/[0.04] cursor-pointer w-full text-left transition-colors">
            <IconChatBubble size={12} className="text-white/50 shrink-0" />
            <span className="flex-1 text-[13px] text-white/85 truncate">{c.title}</span>
            <span className="font-mono text-[10px] text-white/40 shrink-0">{c.meta}</span>
          </button>
        ))}

        <div className={SECTION_HEADER}>Concepts</div>
        {CONCEPTS.map((c) => (
          <button key={c.title} type="button" onClick={onClose}
            className="flex items-center gap-3 px-4 py-2 hover:bg-white/[0.04] cursor-pointer w-full text-left transition-colors">
            <IconExternal size={12} className="text-white/50 shrink-0" />
            <span className="flex-1 text-[13px] text-white/85 truncate">{c.title}</span>
            <span className="font-mono text-[10px] text-white/40 shrink-0">{c.meta}</span>
          </button>
        ))}
      </div>

      <div
        className="font-mono text-[10px] text-white/30 px-[14px] py-[10px] shrink-0"
        style={{ borderTop: '1px solid rgba(255,255,255,0.05)' }}
      >
        ↑↓ to navigate · ↵ to select · esc to close
      </div>
    </div>
  );
}

interface AvatarMenuProps {
  variant: 'me' | 'them';
  onClose: () => void;
}

function AvatarMenu({ variant, onClose }: AvatarMenuProps) {
  const isMe = variant === 'me';
  const name = isMe ? 'Michael Kerr' : 'Anna Klimov';
  const email = isMe ? 'mike@bunq.com' : 'anna@bunq.com';

  return (
    <div
      className="absolute top-full right-0 mt-2 min-w-[200px] rounded-xl overflow-hidden z-50"
      style={DROPDOWN_STYLE}
    >
      <div
        className="px-[14px] py-3"
        style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}
      >
        <div className="text-[13px] text-white/85">{name}</div>
        <div className="font-mono text-[10px] text-white/40 mt-0.5">{email}</div>
        {!isMe && (
          <div className="font-mono uppercase tracking-widest text-[10px] text-white/40 mt-1">
            Compliance Team
          </div>
        )}
      </div>

      {isMe ? (
        <>
          <button type="button" onClick={onClose} className={ITEM_BASE}>
            <IconSettings size={12} className="text-white/50 shrink-0" />
            Profile settings
          </button>
          <button type="button" onClick={onClose} className={ITEM_BASE}>
            <IconMoon size={12} className="text-white/50 shrink-0" />
            Theme: Dark
          </button>
          <button type="button" onClick={onClose} className={ITEM_BASE}>
            <span className="w-3 h-3 shrink-0" />
            Keyboard shortcuts
          </button>
          {DIVIDER}
          <button type="button" onClick={onClose}
            className={`${ITEM_BASE} text-[#E05050]`}>
            <span className="w-3 h-3 shrink-0" />
            Sign out
          </button>
        </>
      ) : (
        <>
          <button type="button" onClick={onClose} className={ITEM_BASE}>
            <span className="w-3 h-3 shrink-0" />
            View profile
          </button>
          <button type="button" onClick={onClose} className={ITEM_BASE}>
            <IconChatBubble size={12} className="text-white/50 shrink-0" />
            Send message
          </button>
          <button type="button" onClick={onClose} className={ITEM_BASE}>
            <span className="w-3 h-3 shrink-0" />
            Assign task
          </button>
        </>
      )}
    </div>
  );
}

// ─── NLFlag ────────────────────────────────────────────────────────────────────

const NLFlag = () => (
  <span
    className="inline-block w-4 h-3 rounded-[2px] shrink-0"
    style={{
      background:
        'linear-gradient(rgb(174,28,40) 33%, rgb(255,255,255) 33%, rgb(255,255,255) 66%, rgb(33,70,139) 66%)',
    }}
  />
);

// ─── TopNav ────────────────────────────────────────────────────────────────────

type OpenMenu = 'jurisdiction' | 'search' | 'me' | 'them' | null;

export default function TopNav() {
  const [openMenu, setOpenMenu] = useState<OpenMenu>(null);
  const [currentJuris, setCurrentJuris] = useState<Jurisdiction>(JURISDICTIONS[0]);

  const jurisRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLDivElement>(null);
  const meRef = useRef<HTMLDivElement>(null);
  const themRef = useRef<HTMLDivElement>(null);

  const toggle = (menu: Exclude<OpenMenu, null>) =>
    setOpenMenu((prev) => (prev === menu ? null : menu));

  // Close on outside click
  useEffect(() => {
    if (!openMenu) return;
    const handler = (e: MouseEvent) => {
      const t = e.target as Node;
      const refs = [jurisRef, searchRef, meRef, themRef];
      if (refs.every((r) => !r.current?.contains(t))) {
        setOpenMenu(null);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [openMenu]);

  // Close on Escape
  useEffect(() => {
    if (!openMenu) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpenMenu(null);
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [openMenu]);

  return (
    <header
      className="fixed top-0 left-0 right-0 z-50 h-[56px] flex items-center px-3 md:px-4 gap-2 md:gap-4"
      style={{
        background: '#080808',
        borderBottom: '1px solid rgba(255,255,255,0.06)',
      }}
    >
      {/* Left cluster */}
      <div className="flex items-center gap-3 shrink-0">
        <NavLink
          to="/launches"
          className="font-serif italic text-white text-[22px] leading-none select-none hover:opacity-80 transition-opacity"
        >
          prism.
        </NavLink>

        <div className="hidden md:block w-px h-4" style={{ background: 'rgba(255,255,255,0.1)' }} />

        <span
          className="hidden md:inline font-mono uppercase tracking-wider text-[11px]"
          style={{ color: '#6B6B6B' }}
        >
          COMPLIANCE
        </span>

        <span className="hidden md:inline text-[11px]" style={{ color: '#6B6B6B' }}>
          /
        </span>

        {/* Jurisdiction pill */}
        <div ref={jurisRef} className="relative hidden md:block">
          <button
            type="button"
            onClick={() => toggle('jurisdiction')}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[13px] text-white/80 hover:text-white transition-colors"
            style={{
              background: '#141414',
              border: '1px solid rgba(255,255,255,0.08)',
            }}
          >
            {currentJuris.flag === '🇳🇱' ? <NLFlag /> : (
              <span className="text-base leading-none">{currentJuris.flag}</span>
            )}
            <span>{currentJuris.name}</span>
            <IconChevron size={12} className="opacity-50" />
          </button>
          {openMenu === 'jurisdiction' && (
            <JurisdictionDropdown
              current={currentJuris}
              onSelect={(j) => { setCurrentJuris(j); setOpenMenu(null); }}
            />
          )}
        </div>
      </div>

      {/* Spacer */}
      <div className="flex-1" />

      {/* Center nav */}
      <nav className="flex items-center gap-1 overflow-x-auto no-scrollbar">
        {navItems.map(({ to, label, Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            title={label}
            className={({ isActive }) => {
              const base =
                'flex items-center gap-1.5 px-2.5 md:px-3 py-1.5 rounded-full text-[13px] font-medium transition-all border border-transparent shrink-0';
              if (!isActive) return `${base} text-[#A8A8A8] hover:bg-white/[0.04]`;
              if (to === '/launches') return `${base} text-[#FF7819]`;
              return `${base} text-white`;
            }}
            style={({ isActive }) => {
              if (!isActive) return {};
              if (to === '/launches')
                return {
                  background: 'rgba(255,120,25,0.14)',
                  borderColor: 'rgba(255,120,25,0.35)',
                };
              return { background: '#1C1C1C', borderColor: 'rgba(255,255,255,0.1)' };
            }}
          >
            {({ isActive }) => (
              <>
                <Icon
                  size={14}
                  className={
                    isActive
                      ? to === '/launches'
                        ? 'text-[#FF7819]'
                        : 'text-white'
                      : 'text-[#A8A8A8]'
                  }
                />
                <span className="hidden md:inline">{label}</span>
              </>
            )}
          </NavLink>
        ))}
      </nav>

      {/* Spacer */}
      <div className="flex-1" />

      {/* Right cluster */}
      <div className="flex items-center gap-2 shrink-0">

        {/* Search */}
        <div ref={searchRef} className="relative">
          <button
            type="button"
            onClick={() => toggle('search')}
            className="flex items-center gap-1.5 px-2.5 md:px-3 py-1.5 rounded-full text-[13px] text-[#A8A8A8] hover:text-white hover:bg-white/[0.04] transition-all"
            title="Search"
          >
            <IconSearch size={14} />
            <span className="hidden md:inline">Search</span>
          </button>
          {openMenu === 'search' && (
            <SearchDropdown onClose={() => setOpenMenu(null)} />
          )}
        </div>

        {/* Avatar MK */}
        <div ref={meRef} className="relative">
          <button
            type="button"
            onClick={() => toggle('me')}
            className="w-7 h-7 rounded-full flex items-center justify-center text-[10px] font-medium text-white/70 hover:text-white/90 transition-colors select-none"
            style={{ background: '#1C1C1C' }}
          >
            MK
          </button>
          {openMenu === 'me' && (
            <AvatarMenu variant="me" onClose={() => setOpenMenu(null)} />
          )}
        </div>

        {/* Avatar AK */}
        <div ref={themRef} className="relative hidden md:block">
          <button
            type="button"
            onClick={() => toggle('them')}
            className="w-7 h-7 rounded-full flex items-center justify-center text-[10px] font-medium text-white/70 hover:text-white/90 transition-colors select-none"
            style={{ background: '#1C1C1C' }}
          >
            AK
          </button>
          {openMenu === 'them' && (
            <AvatarMenu variant="them" onClose={() => setOpenMenu(null)} />
          )}
        </div>
      </div>
    </header>
  );
}
