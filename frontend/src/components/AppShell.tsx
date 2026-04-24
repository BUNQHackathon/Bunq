import { useCallback, useEffect, useMemo, useState } from 'react';
import { Outlet, useLocation, useMatch, useNavigate } from 'react-router-dom';
import TopNav from './TopNav';
import ChatRail from './ChatRail';
import { ChatNavContext, type ChatNav } from '../lib/chatNav';

const frameStyles = `
.frame {
  width: 100%;
  height: 100vh;
  display: grid;
  grid-template-rows: auto 1fr;
  background: var(--bg-0);
  overflow: hidden;
  color: var(--ink-0);
  font-family: var(--ui);
}
.frame__body {
  display: grid;
  grid-template-columns: auto 1fr;
  min-height: 0;
  height: 100%;
}
.frame__view {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  height: 100%;
}
`;

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
      <style>{frameStyles}</style>
      <div className="frame">
        <TopNav />
        <div className="frame__body">
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
          <div className="frame__view">
            <Outlet />
          </div>
        </div>
      </div>
    </ChatNavContext.Provider>
  );
}
