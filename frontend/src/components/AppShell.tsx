import { useCallback, useEffect, useMemo, useState } from 'react';
import { Outlet, useLocation, useMatch, useNavigate } from 'react-router-dom';
import TopNav from './TopNav';
import ChatRail from './ChatRail';
import { ChatNavContext, type ChatNav } from '../lib/chatNav';

function useIsMobile(breakpoint = 768) {
  const [isMobile, setIsMobile] = useState(() =>
    typeof window === 'undefined' ? false : window.innerWidth < breakpoint,
  );
  useEffect(() => {
    const mq = window.matchMedia(`(max-width: ${breakpoint - 1}px)`);
    const onChange = () => setIsMobile(mq.matches);
    onChange();
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, [breakpoint]);
  return isMobile;
}

export default function AppShell() {
  const [sticky, setSticky] = useState(false);
  const [hover, setHover] = useState(false);
  const isMobile = useIsMobile();
  const expanded = !isMobile && (sticky || hover);

  const [activeChatId, setActiveChatId] = useState<string | null>(null);
  const [resetToken, setResetToken] = useState(0);
  const navigate = useNavigate();
  const location = useLocation();
  const sessionMatch = useMatch('/legacy/session/:sessionId');

  // Clear active chat when the user navigates away from the Ask page.
  useEffect(() => {
    if (location.pathname !== '/ask') setActiveChatId(null);
  }, [location.pathname]);

  const selectChat = useCallback(
    (chatId: string) => {
      setActiveChatId(chatId);
      if (location.pathname !== '/ask') navigate('/ask');
    },
    [navigate, location.pathname],
  );

  const newChat = useCallback(() => {
    setActiveChatId(null);
    setResetToken((v) => v + 1);
    const sessionId = sessionMatch?.params.sessionId;
    if (sessionId) {
      navigate(`/ask?sessionId=${encodeURIComponent(sessionId)}`);
    } else if (location.pathname !== '/ask') {
      navigate('/ask');
    }
  }, [navigate, location.pathname, sessionMatch]);

  const chatNav = useMemo<ChatNav>(
    () => ({ activeChatId, resetToken, selectChat, newChat }),
    [activeChatId, resetToken, selectChat, newChat],
  );

  return (
    <ChatNavContext.Provider value={chatNav}>
      <div className="min-h-screen bg-prism-bg text-white">
        <TopNav />
        {!isMobile && (
          <ChatRail
            expanded={expanded}
            onToggle={() => setSticky((v) => !v)}
            onHoverChange={setHover}
            activeChatId={activeChatId}
            onSelect={selectChat}
            onNewChat={newChat}
          />
        )}
        <main
          className="overflow-auto"
          style={{
            marginLeft: isMobile ? 0 : expanded ? '300px' : '48px',
            marginTop: '56px',
            minHeight: 'calc(100vh - 56px)',
            transition: 'margin-left 200ms ease',
          }}
        >
          <Outlet />
        </main>
      </div>
    </ChatNavContext.Provider>
  );
}
