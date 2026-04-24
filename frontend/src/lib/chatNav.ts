import { createContext, useContext } from 'react';

export interface ChatNav {
  activeChatId: string | null;
  // Monotonically increments on every "new chat" action so AskPage can reset.
  resetToken: number;
  selectChat: (chatId: string) => void;
  newChat: () => void;
}

export const ChatNavContext = createContext<ChatNav | null>(null);

export function useChatNav(): ChatNav {
  const ctx = useContext(ChatNavContext);
  if (!ctx) throw new Error('useChatNav must be used inside <ChatNavContext.Provider>');
  return ctx;
}
