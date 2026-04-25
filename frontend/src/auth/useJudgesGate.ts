import { useState, type ReactElement } from 'react';
import { createElement } from 'react';
import { useAuth } from './useAuth';
import JudgesOnlyModal from '../components/JudgesOnlyModal';

interface GateOptions {
  title?: string;
  message?: string;
}

interface JudgesGate {
  requireJudge: <T extends (...args: Parameters<T>) => ReturnType<T>>(fn: T, opts?: GateOptions) => (...args: Parameters<T>) => void;
  showGate: (opts?: GateOptions) => void;
  modal: ReactElement;
}

export default function useJudgesGate(): JudgesGate {
  const { isAuthenticated } = useAuth();
  const [open, setOpen] = useState(false);
  const [opts, setOpts] = useState<GateOptions>({});

  function showGate(o?: GateOptions): void {
    setOpts(o ?? {});
    setOpen(true);
  }

  function requireJudge<T extends (...args: Parameters<T>) => ReturnType<T>>(fn: T, o?: GateOptions) {
    return (...args: Parameters<T>): void => {
      if (!isAuthenticated) {
        showGate(o);
        return;
      }
      fn(...args);
    };
  }

  const modal = createElement(JudgesOnlyModal, {
    open,
    onClose: () => setOpen(false),
    title: opts.title,
    message: opts.message,
  });

  return { requireJudge, showGate, modal };
}
