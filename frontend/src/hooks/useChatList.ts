import { useCallback, useEffect, useState } from 'react';
import { listChats, type ChatSummary } from '../api/chat';

type Listener = () => void;

const refreshListeners = new Set<Listener>();

export function refreshChatList() {
  refreshListeners.forEach((l) => l());
}

interface State {
  chats: ChatSummary[];
  loading: boolean;
  error: string | null;
}

export function useChatList() {
  const [state, setState] = useState<State>({ chats: [], loading: true, error: null });

  const load = useCallback(() => {
    let cancelled = false;
    setState((s) => ({ ...s, loading: true, error: null }));
    listChats(200)
      .then((chats) => {
        if (cancelled) return;
        setState({ chats, loading: false, error: null });
      })
      .catch((err) => {
        if (cancelled) return;
        setState({
          chats: [],
          loading: false,
          error: err instanceof Error ? err.message : 'Failed to load chats',
        });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const cancel = load();
    const listener: Listener = () => load();
    refreshListeners.add(listener);
    return () => {
      cancel?.();
      refreshListeners.delete(listener);
    };
  }, [load]);

  return { ...state, refresh: load };
}
