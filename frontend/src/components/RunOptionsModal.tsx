import { useEffect, useState } from 'react';
import { listLibraryDocuments, type LibraryDocument } from '../api/session';

interface RunOpts {
  documentIds?: string[];
  applyRelevanceFilter?: boolean;
}

interface Props {
  launchId: string;
  code: string;
  brief?: string;
  onClose: () => void;
  onConfirm: (opts: RunOpts) => void;
}

export default function RunOptionsModal({ launchId: _launchId, code, brief, onClose, onConfirm }: Props) {
  const [docs, setDocs] = useState<LibraryDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const hasBrief = Boolean(brief && brief.trim().length > 0);
  const [applyFilter, setApplyFilter] = useState(true);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  useEffect(() => {
    setLoading(true);
    listLibraryDocuments(undefined, 100)
      .then((r) => setDocs(r.documents))
      .catch(() => setDocs([]))
      .finally(() => setLoading(false));
  }, []);

  function toggleDoc(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function handleConfirm() {
    const opts: RunOpts = {};
    if (selectedIds.size > 0) opts.documentIds = [...selectedIds];
    if (hasBrief) opts.applyRelevanceFilter = applyFilter;
    onConfirm(opts);
  }

  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.6)',
        backdropFilter: 'blur(4px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: 'var(--bg-1)',
          border: '1px solid var(--line-1)',
          borderRadius: 14,
          padding: 28,
          maxWidth: 480,
          width: '100%',
          fontFamily: 'var(--ui)',
          maxHeight: '80vh',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--ink-0)', marginBottom: 4 }}>
          Run jurisdiction {code}
        </div>
        <p style={{ fontSize: 13, color: 'var(--ink-2)', margin: '0 0 16px' }}>
          Select documents to include, or leave empty to auto-select by jurisdiction.
        </p>

        {/* Document list */}
        <div
          style={{
            flex: 1,
            overflowY: 'auto',
            border: '1px solid var(--line-1)',
            borderRadius: 8,
            marginBottom: 16,
            minHeight: 80,
          }}
        >
          {loading && (
            <div style={{ padding: '16px 14px', fontSize: 12, color: 'var(--ink-3)' }}>
              Loading documents…
            </div>
          )}
          {!loading && docs.length === 0 && (
            <div style={{ padding: '16px 14px', fontSize: 12, color: 'var(--ink-3)' }}>
              No documents in library.
            </div>
          )}
          {!loading && docs.map((doc) => {
            const checked = selectedIds.has(doc.id);
            const label = doc.displayName || doc.filename;
            const juris = doc.jurisdictions?.join(', ') ?? '';
            return (
              <label
                key={doc.id}
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: 10,
                  padding: '9px 14px',
                  cursor: 'pointer',
                  borderBottom: '1px solid var(--line-1)',
                  background: checked ? 'rgba(239,106,42,0.06)' : 'transparent',
                  transition: 'background .1s ease',
                }}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggleDoc(doc.id)}
                  style={{ marginTop: 2, accentColor: 'var(--orange)', flexShrink: 0 }}
                />
                <div style={{ minWidth: 0 }}>
                  <span style={{ fontSize: 13, color: 'var(--ink-0)', display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {label}
                  </span>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginTop: 2 }}>
                    <span
                      className="chip chip--sm chip--tag"
                      style={{ fontSize: 10 }}
                    >
                      {doc.kind}
                    </span>
                    {juris && (
                      <span style={{ fontSize: 11, color: 'var(--ink-3)', fontFamily: 'var(--mono)' }}>
                        {juris}
                      </span>
                    )}
                  </span>
                </div>
              </label>
            );
          })}
        </div>

        {/* Relevance filter toggle — only if brief is set */}
        {hasBrief && (
          <div style={{ marginBottom: 20 }}>
            <button
              className={`chip${applyFilter ? ' chip--orange' : ''}`}
              onClick={() => setApplyFilter((v) => !v)}
              type="button"
              style={{ fontSize: 12 }}
            >
              {applyFilter ? '✓ ' : ''}Apply relevance filter
            </button>
          </div>
        )}

        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button className="btn btn--sm" onClick={onClose} type="button">
            Cancel
          </button>
          <button className="btn btn--orange btn--sm" onClick={handleConfirm} type="button">
            Run
          </button>
        </div>
      </div>
    </div>
  );
}
