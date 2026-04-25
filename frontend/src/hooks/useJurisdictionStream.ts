import { useEffect, useRef, useState } from 'react';
import { jurisdictionSseUrl } from '../api/launch';

export interface JurisdictionStreamEvent {
  type: 'connected' | 'stage.started' | 'stage.complete' | 'stage.failed' | 'done' | 'ping' | 'error';
  stage?: string;
  ordinal?: number;
  totalStages?: number;
  durationMs?: number;
  itemsProduced?: number;
  errorCode?: string;
  message?: string;
  reportUrl?: string;
  summary?: unknown;
  timestamp?: string;
  sessionId?: string;
}

export type StreamStatus = 'idle' | 'connecting' | 'open' | 'closed' | 'error';

const MAX_EVENTS = 100;

export function useJurisdictionStream(
  launchId: string,
  code: string,
  opts?: { enabled?: boolean; onDone?: () => void },
): {
  events: JurisdictionStreamEvent[];
  currentStage: string | null;
  status: StreamStatus;
  lastEvent: JurisdictionStreamEvent | null;
} {
  const [events, setEvents] = useState<JurisdictionStreamEvent[]>([]);
  const [currentStage, setCurrentStage] = useState<string | null>(null);
  const [status, setStatus] = useState<StreamStatus>('idle');
  const [lastEvent, setLastEvent] = useState<JurisdictionStreamEvent | null>(null);

  const onDoneRef = useRef(opts?.onDone);
  useEffect(() => { onDoneRef.current = opts?.onDone; });

  const retriedRef = useRef(false);
  const esRef = useRef<EventSource | null>(null);

  const enabled = opts?.enabled !== false;

  useEffect(() => {
    if (!enabled || !launchId || !code) return;

    retriedRef.current = false;

    function open() {
      const url = jurisdictionSseUrl(launchId, code);
      const es = new EventSource(url);
      esRef.current = es;
      setStatus('connecting');

      function pushEvent(raw: MessageEvent, type: JurisdictionStreamEvent['type']) {
        let payload: Partial<JurisdictionStreamEvent> = {};
        try { payload = JSON.parse(raw.data); } catch { /* non-JSON data */ }
        const evt: JurisdictionStreamEvent = { ...payload, type };
        setLastEvent(evt);
        setEvents((prev) => {
          const next = [...prev, evt];
          return next.length > MAX_EVENTS ? next.slice(next.length - MAX_EVENTS) : next;
        });
        return evt;
      }

      es.addEventListener('open', () => setStatus('open'));

      es.addEventListener('connected', (e: Event) => {
        pushEvent(e as MessageEvent, 'connected');
        setStatus('open');
      });

      es.addEventListener('stage.started', (e: Event) => {
        const evt = pushEvent(e as MessageEvent, 'stage.started');
        if (evt.stage) setCurrentStage(evt.stage);
      });

      es.addEventListener('stage.complete', (e: Event) => {
        pushEvent(e as MessageEvent, 'stage.complete');
      });

      es.addEventListener('stage.failed', (e: Event) => {
        pushEvent(e as MessageEvent, 'stage.failed');
      });

      es.addEventListener('done', (e: Event) => {
        pushEvent(e as MessageEvent, 'done');
        setCurrentStage(null);
        setStatus('closed');
        es.close();
        onDoneRef.current?.();
      });

      es.addEventListener('ping', (e: Event) => {
        pushEvent(e as MessageEvent, 'ping');
      });

      es.addEventListener('error', (e: Event) => {
        pushEvent(e as MessageEvent, 'error');
      });

      es.onerror = () => {
        es.close();
        if (!retriedRef.current) {
          retriedRef.current = true;
          setStatus('error');
          setTimeout(open, 5000);
        } else {
          setStatus('error');
          // Give up; polling fallback takes over
        }
      };
    }

    open();

    return () => {
      esRef.current?.close();
      esRef.current = null;
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [launchId, code, enabled]);

  return { events, currentStage, status, lastEvent };
}
