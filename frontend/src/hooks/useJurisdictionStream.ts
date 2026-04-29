import { useEffect, useRef, useState } from 'react';
import { jurisdictionSseUrl } from '../api/launch';
import type { Obligation, Control, Mapping, Gap, SanctionHit } from '../api/session';

// ── Minimal inline DTO types for SSE events not covered by session.ts ──────────

export interface ObligationDto {
  id?: string;
  subject?: string;
  action?: string;
  severity?: string;
  [k: string]: unknown;
}

export interface ControlDto {
  id?: string;
  description?: string;
  controlType?: string;
  [k: string]: unknown;
}

export interface MappingDto {
  id?: string;
  obligationId?: string;
  controlId?: string;
  mappingConfidence?: number;
  semanticReason?: string;
  gapStatus?: string;
  reviewerNotes?: string;
  [k: string]: unknown;
}

export interface GapDto {
  id?: string;
  obligationId?: string;
  gapType?: string;
  severity?: string;
  narrative?: string;
  [k: string]: unknown;
}

export interface SanctionsHitDto {
  id?: string;
  counterparty?: { name?: string };
  matchStatus?: string;
  [k: string]: unknown;
}

// Re-export the rich types from session.ts for callers who want them
export type { Obligation, Control, Mapping, Gap, SanctionHit };

// ── Discriminated union for all audit events ──────────────────────────────────

export type AuditEvent =
  | { kind: 'obligation.extracted'; ts: number; obligation: ObligationDto }
  | { kind: 'obligation.rejected';  ts: number; reason: string; subject: string; snippet_preview: string }
  | { kind: 'control.extracted';    ts: number; control: ControlDto }
  | { kind: 'control.rejected';     ts: number; reason: string; subject: string; snippet_preview: string }
  | { kind: 'mapping.computed';     ts: number; mapping: MappingDto }
  | { kind: 'mapping.progress';     ts: number; processed: number; total: number }
  | { kind: 'gap.identified';       ts: number; gap: GapDto }
  | { kind: 'sanctions.hit';        ts: number; hit: SanctionsHitDto }
  | { kind: 'sanctions.degraded';   ts: number; reason: string }
  | { kind: 'ground_check.verified'; ts: number; mapping: MappingDto }
  | { kind: 'ground_check.dropped';  ts: number; mapping: MappingDto; reason: string }
  | { kind: 'narrative.completed';  ts: number; summary: string }
  | { kind: 'cost.update';          ts: number; stage: string; model: string; total_input: number; total_output: number; cache_hit_ratio: number; total_usd: number };

// ── Existing event type ────────────────────────────────────────────────────────

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

export type StreamStatus = 'connecting' | 'open' | 'retrying' | 'dead' | 'closed';

const MAX_EVENTS = 100;
const MAX_AUDIT_EVENTS = 200;

export function useJurisdictionStream(
  launchId: string,
  code: string,
  opts?: { enabled?: boolean; onDone?: () => void },
): {
  events: JurisdictionStreamEvent[];
  currentStage: string | null;
  status: StreamStatus;
  lastEvent: JurisdictionStreamEvent | null;
  auditEvents: AuditEvent[];
} {
  const [events, setEvents] = useState<JurisdictionStreamEvent[]>([]);
  const [currentStage, setCurrentStage] = useState<string | null>(null);
  const [status, setStatus] = useState<StreamStatus>('connecting');
  const [lastEvent, setLastEvent] = useState<JurisdictionStreamEvent | null>(null);
  const [auditEvents, setAuditEvents] = useState<AuditEvent[]>([]);

  const onDoneRef = useRef(opts?.onDone);
  useEffect(() => { onDoneRef.current = opts?.onDone; });

  const retriedRef = useRef(false);
  const esRef = useRef<EventSource | null>(null);

  const enabled = opts?.enabled !== false;

  useEffect(() => {
    if (!enabled || !launchId || !code) return;

    retriedRef.current = false;

    function pushAudit(evt: AuditEvent) {
      setAuditEvents((prev) => {
        const next = [evt, ...prev];
        return next.length > MAX_AUDIT_EVENTS ? next.slice(0, MAX_AUDIT_EVENTS) : next;
      });
    }

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
        setStatus('open');
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

      // ── New audit event listeners ───────────────────────────────────────────

      es.addEventListener('obligation.extracted', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'obligation.extracted', ts: Date.now(), obligation: d.obligation ?? d });
        } catch { /* ignore */ }
      });

      es.addEventListener('obligation.rejected', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'obligation.rejected', ts: Date.now(), reason: d.reason ?? '', subject: d.subject ?? '', snippet_preview: d.snippet_preview ?? '' });
        } catch { /* ignore */ }
      });

      es.addEventListener('control.extracted', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'control.extracted', ts: Date.now(), control: d.control ?? d });
        } catch { /* ignore */ }
      });

      es.addEventListener('control.rejected', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'control.rejected', ts: Date.now(), reason: d.reason ?? '', subject: d.subject ?? '', snippet_preview: d.snippet_preview ?? '' });
        } catch { /* ignore */ }
      });

      es.addEventListener('mapping.computed', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'mapping.computed', ts: Date.now(), mapping: d.mapping ?? d });
        } catch { /* ignore */ }
      });

      es.addEventListener('mapping.progress', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'mapping.progress', ts: Date.now(), processed: d.processed ?? 0, total: d.total ?? 0 });
        } catch { /* ignore */ }
      });

      es.addEventListener('gap.identified', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'gap.identified', ts: Date.now(), gap: d.gap ?? d });
        } catch { /* ignore */ }
      });

      es.addEventListener('sanctions.hit', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'sanctions.hit', ts: Date.now(), hit: d.hit ?? d });
        } catch { /* ignore */ }
      });

      es.addEventListener('sanctions.degraded', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'sanctions.degraded', ts: Date.now(), reason: d.reason ?? '' });
        } catch { /* ignore */ }
      });

      es.addEventListener('ground_check.verified', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'ground_check.verified', ts: Date.now(), mapping: d.mapping ?? d });
        } catch { /* ignore */ }
      });

      es.addEventListener('ground_check.dropped', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'ground_check.dropped', ts: Date.now(), mapping: d.mapping ?? d, reason: d.reason ?? '' });
        } catch { /* ignore */ }
      });

      es.addEventListener('narrative.completed', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({ kind: 'narrative.completed', ts: Date.now(), summary: d.summary ?? '' });
        } catch { /* ignore */ }
      });

      es.addEventListener('cost.update', (e: Event) => {
        try {
          const d = JSON.parse((e as MessageEvent).data);
          pushAudit({
            kind: 'cost.update',
            ts: Date.now(),
            stage: d.stage ?? '',
            model: d.model ?? '',
            total_input: d.total_input ?? 0,
            total_output: d.total_output ?? 0,
            cache_hit_ratio: d.cache_hit_ratio ?? 0,
            total_usd: d.total_usd ?? 0,
          });
        } catch { /* ignore */ }
      });

      es.onerror = () => {
        es.close();
        if (!retriedRef.current) {
          retriedRef.current = true;
          setStatus('retrying');
          setTimeout(open, 5000);
        } else {
          setStatus('dead');
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

  return { events, currentStage, status, lastEvent, auditEvents };
}
