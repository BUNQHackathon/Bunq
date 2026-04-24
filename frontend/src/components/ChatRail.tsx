import { useMemo, useState } from 'react';
import { IconPanel, IconPlus, IconSettings, IconSearch } from './icons';
import { useChatList } from '../hooks/useChatList';
import type { ChatSummary } from '../api/chat';

export interface ChatRailProps {
  expanded: boolean;
  onToggle: () => void;
  onHoverChange: (hovered: boolean) => void;
  activeChatId: string | null;
  onSelect: (chatId: string) => void;
  onNewChat: () => void;
}

// ─── Date grouping helpers ────────────────────────────────────────────────────

function getRelativeGroup(iso: string): 'TODAY' | 'EARLIER' {
  if (!iso) return 'EARLIER';
  const then = new Date(iso);
  const now = new Date();
  if (
    then.getFullYear() === now.getFullYear() &&
    then.getMonth() === now.getMonth() &&
    then.getDate() === now.getDate()
  ) {
    return 'TODAY';
  }
  return 'EARLIER';
}

function formatRelative(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const diffMs = Date.now() - then;
  const min = Math.floor(diffMs / 60_000);
  if (min < 1) return 'now';
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const d = Math.floor(hr / 24);
  if (d < 7) return `${d}d ago`;
  return new Date(iso).toLocaleDateString();
}

// ─── Collapsed state ──────────────────────────────────────────────────────────

interface CollapsedProps {
  chats: ChatSummary[];
  activeChatId: string | null;
  onSelect: (chatId: string) => void;
  onNewChat: () => void;
  onToggle: () => void;
}

function CollapsedRail({ chats, activeChatId, onSelect, onNewChat, onToggle }: CollapsedProps) {
  const preview = chats.slice(0, 8);
  return (
    <aside
      className="chatrail chatrail--collapsed"
      onMouseEnter={() => {/* hover handled by AppShell */}}
    >
      <button
        type="button"
        className="chatrail__toggle"
        title="Expand chat history"
        onClick={onToggle}
      >
        <IconPanel size={16} />
      </button>

      <button
        type="button"
        className="chatrail__new"
        title="New chat"
        onClick={onNewChat}
      >
        <IconPlus size={14} />
      </button>

      {preview.length > 0 && <div className="chatrail__divider" />}

      <div className="chatrail__stack">
        {preview.map((chat) => {
          const isActive = chat.chatId === activeChatId;
          return (
            <button
              key={chat.chatId}
              type="button"
              className={`chatrail__dot${isActive ? ' chatrail__dot--active' : ''}`}
              title={chat.title || 'Untitled chat'}
              onClick={() => onSelect(chat.chatId)}
            >
              <span className="chatrail__dot-inner" />
            </button>
          );
        })}
      </div>

      <div className="chatrail__spacer" />

      <button type="button" className="chatrail__toggle" title="Settings">
        <IconSettings size={14} />
      </button>
    </aside>
  );
}

// ─── Expanded state ───────────────────────────────────────────────────────────

interface ExpandedProps {
  chats: ChatSummary[];
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
  activeChatId: string | null;
  onSelect: (chatId: string) => void;
  onNewChat: () => void;
  onToggle: () => void;
}

function ExpandedRail({
  chats,
  loading,
  error,
  onRefresh,
  activeChatId,
  onSelect,
  onNewChat,
  onToggle,
}: ExpandedProps) {
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return chats;
    return chats.filter((c) => (c.title ?? '').toLowerCase().includes(q));
  }, [chats, query]);

  // Group: PINNED (none from API currently), TODAY, EARLIER
  const grouped = useMemo(() => {
    const today: ChatSummary[] = [];
    const earlier: ChatSummary[] = [];
    for (const c of filtered) {
      if (getRelativeGroup(c.updatedAt) === 'TODAY') today.push(c);
      else earlier.push(c);
    }
    return [
      { label: 'TODAY', items: today },
      { label: 'EARLIER', items: earlier },
    ].filter((g) => g.items.length > 0);
  }, [filtered]);

  return (
    <aside className="chatrail chatrail--expanded">
      {/* Head */}
      <div className="chatrail__head">
        <div className="chatrail__head-title">
          <span className="mono-label mono-label--ink">CHATS</span>
          <span className="chatrail__count">{chats.length}</span>
        </div>
        <button
          type="button"
          className="btn btn--icon btn--sm"
          title="Collapse"
          onClick={onToggle}
        >
          <IconPanel size={14} />
        </button>
      </div>

      {/* New chat */}
      <button type="button" className="chatrail__new-btn" onClick={onNewChat}>
        <IconPlus size={12} />
        <span>New chat</span>
        <span className="kbd kbd--sm">⌘N</span>
      </button>

      {/* Search */}
      <div className="chatrail__search">
        <IconSearch size={12} />
        <input
          type="text"
          placeholder="Search chats..."
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>

      {/* List */}
      <div className="chatrail__list thin-scroll">
        {loading && chats.length === 0 && (
          <div
            className="px-3 py-6 text-center"
            style={{ fontSize: '12px', color: 'var(--ink-2)' }}
          >
            Loading…
          </div>
        )}
        {error && (
          <div
            className="px-3 py-4 text-center"
            style={{ fontSize: '12px', color: '#E05050' }}
          >
            {error}
            <button
              type="button"
              onClick={onRefresh}
              className="ml-2 underline underline-offset-2 hover:opacity-80"
            >
              Retry
            </button>
          </div>
        )}
        {!loading && !error && filtered.length === 0 && (
          <div
            className="px-3 py-6 text-center"
            style={{ fontSize: '12px', color: 'var(--ink-2)' }}
          >
            {chats.length === 0 ? 'No chats yet — start one above.' : 'No matches.'}
          </div>
        )}

        {grouped.map((group) => (
          <div key={group.label} className="chatrail__group">
            <div className="mono-label chatrail__group-label">{group.label}</div>
            {group.items.map((chat) => {
              const isActive = chat.chatId === activeChatId;
              return (
                <button
                  key={chat.chatId}
                  type="button"
                  className={`chatitem${isActive ? ' chatitem--active' : ''}`}
                  onClick={() => onSelect(chat.chatId)}
                >
                  <div className="chatitem__title">
                    {chat.title || 'Untitled chat'}
                  </div>
                  <div className="chatitem__meta">
                    <span>{formatRelative(chat.updatedAt)}</span>
                    {chat.messageCount > 0 && (
                      <span>· {chat.messageCount}</span>
                    )}
                  </div>
                </button>
              );
            })}
          </div>
        ))}
      </div>
    </aside>
  );
}

// ─── ChatRail (outer) ─────────────────────────────────────────────────────────

export default function ChatRail({
  expanded,
  onToggle,
  onHoverChange,
  activeChatId,
  onSelect,
  onNewChat,
}: ChatRailProps) {
  const { chats, loading, error, refresh } = useChatList();

  if (!expanded) {
    return (
      <div
        onMouseEnter={() => onHoverChange(true)}
        onMouseLeave={() => onHoverChange(false)}
      >
        <CollapsedRail
          chats={chats}
          activeChatId={activeChatId}
          onSelect={onSelect}
          onNewChat={onNewChat}
          onToggle={onToggle}
        />
      </div>
    );
  }

  return (
    <div
      onMouseEnter={() => onHoverChange(true)}
      onMouseLeave={() => onHoverChange(false)}
    >
      <ExpandedRail
        chats={chats}
        loading={loading}
        error={error}
        onRefresh={refresh}
        activeChatId={activeChatId}
        onSelect={onSelect}
        onNewChat={onNewChat}
        onToggle={onToggle}
      />
    </div>
  );
}
