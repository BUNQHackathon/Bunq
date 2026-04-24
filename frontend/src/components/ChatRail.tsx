import { useMemo, useState } from 'react';
import { IconPanel, IconPlus, IconSettings } from './icons';
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

export default function ChatRail({
  expanded,
  onToggle,
  onHoverChange,
  activeChatId,
  onSelect,
  onNewChat,
}: ChatRailProps) {
  const { chats, loading, error, refresh } = useChatList();

  return (
    <aside
      className="fixed left-0 top-14 bottom-0 z-40 flex flex-col"
      style={{
        width: expanded ? '300px' : '48px',
        background: '#0D0D0D',
        borderRight: '1px solid rgba(255,255,255,0.05)',
        transition: 'width 200ms ease',
        overflow: 'hidden',
      }}
      onMouseEnter={() => onHoverChange(true)}
      onMouseLeave={() => onHoverChange(false)}
    >
      {expanded ? (
        <ExpandedContent
          chats={chats}
          loading={loading}
          error={error}
          onRefresh={refresh}
          activeChatId={activeChatId}
          onSelect={onSelect}
          onNewChat={onNewChat}
          onToggle={onToggle}
        />
      ) : (
        <CollapsedContent
          chats={chats}
          activeChatId={activeChatId}
          onSelect={onSelect}
          onNewChat={onNewChat}
          onToggle={onToggle}
        />
      )}
    </aside>
  );
}

interface InnerProps {
  chats: ChatSummary[];
  activeChatId: string | null;
  onSelect: (chatId: string) => void;
  onNewChat: () => void;
  onToggle: () => void;
}

interface ExpandedInnerProps extends InnerProps {
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
}

function CollapsedContent({ chats, activeChatId, onSelect, onNewChat, onToggle }: InnerProps) {
  const preview = chats.slice(0, 8);
  return (
    <>
      {/* Toggle button */}
      <button
        onClick={onToggle}
        className="w-12 h-10 flex items-center justify-center text-white/30 hover:text-white/60 transition-colors shrink-0"
        title="Expand chat history"
        type="button"
      >
        <IconPanel size={16} />
      </button>

      {/* New chat */}
      <button
        onClick={onNewChat}
        className="mx-auto mt-1 w-8 h-8 flex items-center justify-center rounded-[10px] transition-all hover:opacity-80 shrink-0"
        style={{
          background: 'rgba(255,120,25,0.14)',
          border: '1px solid rgba(255,120,25,0.35)',
          color: '#FF7819',
        }}
        title="New chat"
        type="button"
      >
        <IconPlus size={14} />
      </button>

      {/* Divider */}
      {preview.length > 0 && (
        <div
          className="mx-auto mt-3 w-6 shrink-0"
          style={{ height: '1px', background: 'rgba(255,255,255,0.06)' }}
        />
      )}

      {/* Chat dots */}
      <div className="flex flex-col items-center gap-[14px] mt-4 overflow-hidden">
        {preview.map((chat) => {
          const isActive = chat.chatId === activeChatId;
          return (
            <button
              key={chat.chatId}
              title={chat.title || 'Untitled chat'}
              type="button"
              onClick={() => onSelect(chat.chatId)}
              className="w-6 h-6 flex items-center justify-center hover:opacity-80 transition-opacity"
            >
              <span
                className="w-1.5 h-1.5 rounded-full"
                style={{ background: isActive ? '#FF7819' : '#2A2A2A' }}
              />
            </button>
          );
        })}
      </div>

      <div className="flex-1" />

      {/* Settings */}
      <button
        className="w-12 h-10 flex items-center justify-center text-white/30 hover:text-white/60 transition-colors shrink-0 mb-2"
        title="Settings"
        type="button"
      >
        <IconSettings size={14} />
      </button>
    </>
  );
}

function ExpandedContent({
  chats,
  loading,
  error,
  onRefresh,
  activeChatId,
  onSelect,
  onNewChat,
  onToggle,
}: ExpandedInnerProps) {
  const [query, setQuery] = useState('');
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return chats;
    return chats.filter((c) => c.title.toLowerCase().includes(q));
  }, [chats, query]);

  return (
    <div className="flex flex-col h-full min-w-[300px]">
      {/* Header */}
      <div className="flex items-center px-4 h-10 shrink-0">
        <span
          className="font-mono uppercase tracking-wider text-[11px]"
          style={{ color: 'rgba(255,255,255,0.3)' }}
        >
          CHATS
        </span>
        <span
          className="ml-2 text-[11px]"
          style={{ color: 'rgba(255,255,255,0.3)' }}
        >
          {chats.length}
        </span>
        <div className="flex-1" />
        <button
          onClick={onToggle}
          className="w-7 h-7 flex items-center justify-center text-white/30 hover:text-white/60 transition-colors"
          title="Collapse"
          type="button"
        >
          <IconPanel size={16} />
        </button>
      </div>

      <div className="px-3 pb-2 shrink-0">
        {/* New chat button */}
        <button
          onClick={onNewChat}
          className="w-full flex items-center justify-center gap-1.5 py-2 rounded-lg text-[13px] font-medium transition-all hover:opacity-90"
          style={{
            background: 'rgba(255,120,25,0.14)',
            border: '1px solid rgba(255,120,25,0.35)',
            color: '#FF7819',
          }}
          type="button"
        >
          <IconPlus size={13} />
          New chat
        </button>

        {/* Search */}
        <input
          className="w-full mt-2 px-3 py-1.5 rounded-full text-[13px] text-white/80 placeholder:text-white/30 outline-none focus:ring-0"
          style={{
            background: 'rgba(255,255,255,0.03)',
            border: '1px solid rgba(255,255,255,0.06)',
            caretColor: '#FF7819',
          }}
          placeholder="Search chats"
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>

      {/* Chat list */}
      <div className="flex-1 overflow-y-auto px-2">
        {loading && chats.length === 0 && (
          <div className="px-3 py-6 text-center text-[12px] text-white/35">Loading…</div>
        )}
        {error && (
          <div className="px-3 py-4 text-center text-[12px]" style={{ color: '#E05050' }}>
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
          <div className="px-3 py-6 text-center text-[12px] text-white/35">
            {chats.length === 0 ? 'No chats yet — start one above.' : 'No matches.'}
          </div>
        )}
        {filtered.map((chat) => {
          const isActive = chat.chatId === activeChatId;
          return (
            <button
              key={chat.chatId}
              type="button"
              onClick={() => onSelect(chat.chatId)}
              className="w-full flex flex-col gap-0.5 px-3 py-2 rounded-lg text-left transition-colors cursor-pointer hover:bg-white/[0.03]"
              style={{
                background: isActive ? 'rgba(255,120,25,0.08)' : undefined,
                border: isActive ? '1px solid rgba(255,120,25,0.25)' : '1px solid transparent',
              }}
            >
              <span
                className="text-[13px] truncate"
                style={{
                  color: isActive ? 'rgba(255,255,255,0.95)' : 'rgba(255,255,255,0.85)',
                }}
              >
                {chat.title || 'Untitled chat'}
              </span>
              <span
                className="text-[11px] flex items-center gap-2"
                style={{ color: 'rgba(255,255,255,0.3)' }}
              >
                <span>{formatRelative(chat.updatedAt)}</span>
                {chat.messageCount > 0 && (
                  <span className="font-mono">· {chat.messageCount}</span>
                )}
              </span>
            </button>
          );
        })}
      </div>

      {/* Footer */}
      <div
        className="shrink-0 px-2 py-2"
        style={{ borderTop: '1px solid rgba(255,255,255,0.05)' }}
      >
        <button
          className="w-full h-9 flex items-center gap-2 px-3 rounded-lg text-white/30 hover:text-white/60 hover:bg-white/[0.04] transition-colors text-[13px]"
          title="Settings"
          type="button"
        >
          <IconSettings size={14} />
          <span>Settings</span>
        </button>
      </div>
    </div>
  );
}
