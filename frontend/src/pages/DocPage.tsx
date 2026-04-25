import { useState, useRef, useEffect, useMemo, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { Document, Page, pdfjs } from 'react-pdf';
import 'react-pdf/dist/Page/AnnotationLayer.css';
import 'react-pdf/dist/Page/TextLayer.css';
import pdfWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import {
  getDocument,
  listDocuments,
  type DocumentContent,
  type DocumentSummary,
} from '../api/portal';
import {
  IconBack,
  IconDownload,
  IconChevron,
} from '../components/icons';

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorker;

// ─── Helpers ──────────────────────────────────────────────────────────────────

const CATEGORY_COLOR: Record<string, string> = {
  'Terms & Conditions': 'text-prism-terms',
  Privacy: 'text-prism-privacy',
  AML: 'text-prism-aml',
  Licensing: 'text-prism-licensing',
  Reports: 'text-prism-reports',
  Pricing: 'text-prism-orange',
  Sanctions: 'text-prism-sanctions',
};

const CATEGORY_DOT: Record<string, string> = {
  'Terms & Conditions': 'bg-prism-terms',
  Privacy: 'bg-prism-privacy',
  AML: 'bg-prism-aml',
  Licensing: 'bg-prism-licensing',
  Reports: 'bg-prism-reports',
  Pricing: 'bg-prism-orange',
  Sanctions: 'bg-prism-sanctions',
};

function categoryColorClass(cat: string): string {
  return CATEGORY_COLOR[cat] ?? 'text-prism-orange';
}

function categoryDotClass(cat: string): string {
  return CATEGORY_DOT[cat] ?? 'bg-prism-orange';
}

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
  } catch {
    return '—';
  }
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function renderBody(body: string): string {
  const escaped = escapeHtml(body);
  return escaped.replace(/\n/g, '<br>');
}

// ─── Sidebar tree ─────────────────────────────────────────────────────────────

interface CategoryGroupProps {
  category: string;
  docs: DocumentSummary[];
  currentDocId: string;
}

function CategoryGroup({ category, docs, currentDocId }: CategoryGroupProps) {
  const [open, setOpen] = useState<boolean>(true);
  const dotClass = categoryDotClass(category);

  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-1.5 rounded-md px-2 py-[5px] text-left text-xs leading-[1.4] text-white/50 hover:text-white/80 transition-colors"
      >
        <IconChevron
          size={11}
          className={['flex-shrink-0 transition-transform duration-150', open ? '' : '-rotate-90'].join(' ')}
        />
        <span
          className={['h-[5px] w-[5px] flex-shrink-0 rounded-full', dotClass].join(' ')}
        />
        <span className="truncate font-mono text-[10px] uppercase tracking-wide">{category}</span>
      </button>

      {open && (
        <div>
          {docs.map((d) => {
            const isActive = d.id === currentDocId;
            return (
              <Link
                key={d.id}
                to={`/doc/${d.id}`}
                className={[
                  'flex items-center gap-2 rounded-md px-2 py-[4px] text-[11px] leading-[1.4] transition-colors',
                  isActive
                    ? 'bg-white/[0.06] text-white'
                    : 'text-white/40 hover:text-white/70',
                ].join(' ')}
                style={{ paddingLeft: '20px' }}
              >
                <span
                  className={['h-[5px] w-[5px] flex-shrink-0 rounded-full', dotClass].join(' ')}
                />
                <span className="truncate">{d.title}</span>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ─── References SVG mock ──────────────────────────────────────────────────────

function ReferencesMinimap() {
  const nodeColors = ['#FF7819', '#B08AFF', '#5ECFA0', '#FF9F55', '#6EB7E8', '#E05050', '#A8D66C'];
  const cx = 130;
  const cy = 80;
  const r = 55;
  const satellites = [0, 52, 104, 156, 208, 260].map((angle, i) => ({
    x: cx + r * Math.cos((angle * Math.PI) / 180),
    y: cy + r * Math.sin((angle * Math.PI) / 180),
    color: nodeColors[i + 1] ?? '#888',
  }));

  return (
    <svg width="260" height="160" viewBox="0 0 260 160" fill="none" xmlns="http://www.w3.org/2000/svg">
      {satellites.map((s, i) => (
        <line key={i} x1={cx} y1={cy} x2={s.x} y2={s.y} stroke="rgba(255,255,255,0.08)" strokeWidth="1" />
      ))}
      {satellites.map((s, i) => (
        <circle key={i} cx={s.x} cy={s.y} r="4" fill={s.color} opacity="0.7" />
      ))}
      <circle cx={cx} cy={cy} r="6" fill="#FF7819" />
      <circle cx={cx} cy={cy} r="10" fill="#FF7819" opacity="0.15" />
    </svg>
  );
}

// ─── Chat message types ───────────────────────────────────────────────────────

interface ChatMessage {
  role: 'bot' | 'user';
  text: string;
}


// ─── DocPage ──────────────────────────────────────────────────────────────────

export default function DocPage() {
  const { docId } = useParams<{ docId: string }>();
  const navigate = useNavigate();

  const [doc, setDoc] = useState<DocumentContent | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const [allDocs, setAllDocs] = useState<DocumentSummary[]>([]);

  const [chatInput, setChatInput] = useState<string>('');
  const [chatMessages] = useState<ChatMessage[]>([]);
  const [rightCollapsed, setRightCollapsed] = useState<boolean>(false);

  const [numPages, setNumPages] = useState<number | null>(null);
  const [pageNumber, setPageNumber] = useState<number>(1);
  const [scale, setScale] = useState<number>(1.0);

  const articleRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!docId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    setDoc(null);
    getDocument(docId)
      .then((data) => { if (!cancelled) setDoc(data); })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : 'Failed to load document');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [docId]);

  useEffect(() => {
    setPageNumber(1);
    setNumPages(null);
  }, [docId]);

  useEffect(() => {
    listDocuments().then(setAllDocs).catch(() => { });
  }, []);

  const byCategory = useMemo(() => {
    const m = new Map<string, DocumentSummary[]>();
    for (const d of allDocs) {
      if (!m.has(d.category)) m.set(d.category, []);
      m.get(d.category)!.push(d);
    }
    return m;
  }, [allDocs]);

  const handleCiteClick = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      const target = e.target as HTMLElement;
      const cite = target.closest('.doc-cite') as HTMLElement | null;
      if (!cite) return;
      const refId = cite.dataset['ref'];
      if (!refId) return;
      navigate(`/doc/${refId}`);
    },
    [navigate],
  );

  if (loading) {
    return (
      <>
        <style>{`
          .doc-cite {
            color: #FF7819;
            text-decoration: underline;
            text-decoration-color: rgba(255,120,25,0.4);
            text-underline-offset: 2px;
            cursor: pointer;
            font-weight: 500;
          }
          .doc-cite:hover { color: #d96010; }
        `}</style>
        <div className="flex h-[calc(100vh-56px)] w-full overflow-hidden bg-[#0D0D0D]">
          {/* Left sidebar skeleton */}
          <div
            className="flex w-[260px] flex-shrink-0 flex-col overflow-hidden"
            style={{ borderRight: '1px solid rgba(255,255,255,0.05)' }}
          />
          {/* Article loading area */}
          <div className="flex flex-1 items-center justify-center bg-prism-cream-2">
            <span className="font-mono text-[12px] text-[#1C1C1C]/40">Loading document…</span>
          </div>
          {/* Right panel placeholder */}
          <div
            className="w-[300px] flex-shrink-0"
            style={{ borderLeft: '1px solid rgba(255,255,255,0.05)' }}
          />
        </div>
      </>
    );
  }

  if (error !== null) {
    return (
      <div className="flex h-[calc(100vh-56px)] w-full items-center justify-center bg-[#0D0D0D]">
        <div className="text-center">
          <p className="mb-4 text-white/50">{error}</p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="rounded-pill bg-white/[0.06] px-4 py-2 text-xs text-white/70 hover:text-white transition-colors"
          >
            ← Back
          </button>
        </div>
      </div>
    );
  }

  if (doc === null) {
    return null;
  }

  const dotClass = categoryDotClass(doc.category);
  const colorClass = categoryColorClass(doc.category);
  const formattedDate = formatDate(doc.updated);

  return (
    <>
      <style>{`
        .doc-cite {
          color: #FF7819;
          text-decoration: underline;
          text-decoration-color: rgba(255,120,25,0.4);
          text-underline-offset: 2px;
          cursor: pointer;
          font-weight: 500;
        }
        .doc-cite:hover { color: #d96010; }
      `}</style>

      <div className="flex h-[calc(100vh-56px)] w-full flex-col overflow-hidden bg-[#0D0D0D]">

        {/* ── Top bar ─────────────────────────────────────────────────────── */}
        <div
          className="flex h-[52px] flex-shrink-0 items-center gap-3 px-4"
          style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}
        >
          {/* Back */}
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="flex items-center gap-1.5 rounded-pill bg-white/[0.05] px-3 py-1.5 text-[11px] text-white/70 hover:bg-white/[0.08] hover:text-white transition-colors"
          >
            <IconBack size={12} />
            Back to folders
          </button>

          {/* Breadcrumb */}
          <div className="font-mono text-[11px] uppercase tracking-wide text-white/60">
            <span
              className={[
                'h-[6px] w-[6px] rounded-full inline-block mr-1.5 align-middle',
                dotClass,
              ].join(' ')}
            />
            {doc.jurisdiction} / {doc.category}
          </div>

          <div className="flex-1" />

          {/* Action chips */}
          <div className="flex items-center gap-2">
            <a
              href={doc.downloadUrl}
              target="_blank"
              rel="noreferrer"
              className="flex items-center gap-1.5 rounded-pill bg-white/[0.05] px-3 py-1.5 text-[11px] text-white/60 hover:bg-white/[0.08] hover:text-white transition-colors"
            >
              <IconDownload size={12} />
              Download PDF
            </a>
          </div>
        </div>

        {/* ── 3-column layout ──────────────────────────────────────────────── */}
        <div className="flex flex-1 overflow-hidden">

          {/* ── Left sidebar ──────────────────────────────────────────────── */}
          <div
            className="flex w-[260px] flex-shrink-0 flex-col overflow-hidden"
            style={{ borderRight: '1px solid rgba(255,255,255,0.05)' }}
          >
            {/* Workspace header */}
            <div
              className="flex flex-shrink-0 items-center justify-between px-4 py-3"
              style={{ borderBottom: '1px solid rgba(255,255,255,0.04)' }}
            >
              <span className="font-mono text-[11px] uppercase tracking-wide text-white/30">
                Workspace
              </span>
            </div>

            {/* Tree */}
            <div className="flex-1 overflow-y-auto px-1.5 py-2">
              {Array.from(byCategory.entries()).map(([cat, docs]) => (
                <CategoryGroup
                  key={cat}
                  category={cat}
                  docs={docs}
                  currentDocId={docId ?? ''}
                />
              ))}
            </div>

            {/* Doc metadata */}
            <div
              className="flex-shrink-0 space-y-3 px-4 py-4"
              style={{ borderTop: '1px solid rgba(255,255,255,0.05)' }}
            >
              <div>
                <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">Updated</p>
                <p className="mt-0.5 text-[12px] text-white/70">{formattedDate}</p>
              </div>
              <div>
                <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">Jurisdiction</p>
                <p className="mt-0.5 text-[12px] text-white/70">{doc.jurisdiction}</p>
              </div>
              <div>
                <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">Owner</p>
                <p className="mt-0.5 text-[12px] text-white/70">Compliance · bunq B.V.</p>
              </div>
              <div>
                <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">Status</p>
                <p className="mt-0.5 flex items-center gap-1.5 text-[12px] text-white/70">
                  <span className="h-[6px] w-[6px] rounded-full bg-prism-teal" />
                  In force
                </p>
              </div>
            </div>
          </div>

          {/* ── Center article ────────────────────────────────────────────── */}
          <div
            ref={articleRef}
            className="flex-1 overflow-y-auto bg-prism-cream-2"
            onClick={handleCiteClick}
          >
            <div className="mx-auto max-w-[680px] px-8 py-[52px]">

              {/* Eyebrow */}
              <div className="flex items-center gap-2">
                <span
                  className={[
                    'h-[7px] w-[7px] rounded-full flex-shrink-0',
                    dotClass,
                  ].join(' ')}
                />
                <span className={['font-mono text-[11px] uppercase tracking-wider', colorClass].join(' ')}>
                  {doc.category}
                </span>
                <span className="font-mono text-[11px] text-[#999]">
                  · Framework · {doc.jurisdiction}
                </span>
              </div>

              {/* Title */}
              <h1 className="mt-3 font-serif text-[28px] font-normal leading-[1.3] tracking-tight text-[#1C1C1C]">
                {doc.title}
              </h1>

              {/* Subtitle */}
              <p className="mt-2 text-[12px] text-[#999]">
                bunq B.V. · Amsterdam · Updated {formattedDate}
              </p>

              {/* Divider */}
              <div className="mb-8 mt-6 border-b border-[#E8E5E0]" />

              {/* PDF Viewer */}
              {doc.downloadUrl && (
                <div className="mb-8">
                  <div className="mb-3 flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2 text-[12px] text-[#666]">
                      <button
                        type="button"
                        disabled={pageNumber <= 1}
                        onClick={() => setPageNumber((p) => Math.max(1, p - 1))}
                        className="rounded-md border border-[#E8E5E0] px-2 py-1 disabled:opacity-40 hover:bg-[#F0EDE7]"
                      >‹</button>
                      <span className="font-mono">{pageNumber} / {numPages ?? '…'}</span>
                      <button
                        type="button"
                        disabled={!numPages || pageNumber >= numPages}
                        onClick={() => setPageNumber((p) => Math.min(numPages ?? p, p + 1))}
                        className="rounded-md border border-[#E8E5E0] px-2 py-1 disabled:opacity-40 hover:bg-[#F0EDE7]"
                      >›</button>
                    </div>
                    <div className="flex items-center gap-2 text-[12px] text-[#666]">
                      <button
                        type="button"
                        onClick={() => setScale((s) => Math.max(0.5, +(s - 0.1).toFixed(2)))}
                        className="rounded-md border border-[#E8E5E0] px-2 py-1 hover:bg-[#F0EDE7]"
                      >−</button>
                      <span className="font-mono w-[44px] text-center">{Math.round(scale * 100)}%</span>
                      <button
                        type="button"
                        onClick={() => setScale((s) => Math.min(2.5, +(s + 0.1).toFixed(2)))}
                        className="rounded-md border border-[#E8E5E0] px-2 py-1 hover:bg-[#F0EDE7]"
                      >+</button>
                      <button
                        type="button"
                        onClick={() => setScale(1)}
                        className="rounded-md border border-[#E8E5E0] px-2 py-1 hover:bg-[#F0EDE7]"
                      >Fit</button>
                    </div>
                  </div>
                  <div className="flex justify-center rounded-lg border border-[#E8E5E0] bg-white p-3 overflow-auto">
                    <Document
                      file={doc.downloadUrl}
                      onLoadSuccess={({ numPages: n }) => setNumPages(n)}
                      loading={<div className="py-12 text-[12px] text-[#999]">Loading PDF…</div>}
                      error={<div className="py-12 text-[12px] text-red-500">Failed to load PDF</div>}
                    >
                      <Page pageNumber={pageNumber} scale={scale} renderAnnotationLayer={false} renderTextLayer={false} />
                    </Document>
                  </div>
                </div>
              )}

              {/* Sections */}
              {doc.sections.map((section, i) => (
                <div key={i}>
                  <h2 className="mb-3 mt-7 text-[15px] font-semibold text-[#1C1C1C]">
                    {section.title}
                  </h2>
                  <p
                    className="mb-3.5 text-[14.5px] leading-[1.75] text-[#333]"
                    style={{ whiteSpace: 'pre-wrap' }}
                    dangerouslySetInnerHTML={{ __html: renderBody(section.body) }}
                  />
                </div>
              ))}
            </div>
          </div>

          {/* ── Right panel ───────────────────────────────────────────────── */}
          {!rightCollapsed && (
            <div
              className="flex w-[300px] flex-shrink-0 flex-col overflow-hidden"
              style={{ borderLeft: '1px solid rgba(255,255,255,0.05)' }}
            >

              {/* References section */}
              <div
                className="flex-shrink-0 px-4 pt-4 pb-2"
                style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}
              >
                <div className="mb-3 flex items-center justify-between">
                  <span className="font-mono text-[11px] uppercase tracking-wide text-white/30">
                    References
                  </span>
                  <span className="font-mono text-[11px] text-white/30">
                    linked
                  </span>
                </div>

                {/* Minimap container */}
                <div
                  className="rounded-xl overflow-hidden"
                  style={{
                    background: 'rgba(8,8,8,0.96)',
                    border: '1px solid rgba(255,120,25,0.18)',
                  }}
                >
                  <div className="flex items-center justify-center px-1 pt-2">
                    <ReferencesMinimap />
                  </div>
                  <div
                    className="px-3 py-2"
                    style={{ borderTop: '1px solid rgba(255,255,255,0.05)' }}
                  >
                    <button
                      type="button"
                      onClick={() => navigate('/graph')}
                      className="flex w-full items-center justify-center gap-1.5 rounded-pill bg-white/[0.05] py-1.5 text-[11px] text-white/50 hover:bg-white/[0.08] hover:text-white transition-colors"
                    >
                      Open in graph
                    </button>
                  </div>
                </div>
              </div>

              {/* Assistant section */}
              <div className="flex flex-1 flex-col overflow-hidden">
                <div
                  className="flex flex-shrink-0 items-center justify-between px-4 py-3"
                  style={{ borderBottom: '1px solid rgba(255,255,255,0.04)' }}
                >
                  <span className="font-mono text-[11px] uppercase tracking-wide text-white/30">
                    Assistant · This doc
                  </span>
                  <button
                    type="button"
                    onClick={() => setRightCollapsed(true)}
                    className="rounded-md p-1 text-white/30 hover:text-white/60 transition-colors"
                    aria-label="Collapse panel"
                  >
                    <svg width="12" height="12" viewBox="0 0 14 14" fill="none">
                      <path d="M2 5l3 3-3 3M12 5l-3 3 3 3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </button>
                </div>

                {/* Messages */}
                <div className="flex flex-1 flex-col overflow-hidden">
                  {chatMessages.length === 0 ? (
                    <div className="flex flex-1 items-center justify-center">
                      <span className="font-mono text-[12px] text-white/30">No messages yet</span>
                    </div>
                  ) : (
                    <div className="flex-1 space-y-3 overflow-y-auto px-3 py-4">
                      {chatMessages.map((msg, i) => (
                        msg.role === 'user' ? (
                          <div key={i} className="flex justify-end">
                            <div
                              className="max-w-[80%] rounded-2xl px-3 py-2.5 text-[12px] leading-[1.6] text-white"
                              style={{ background: '#FF7819' }}
                            >
                              {msg.text}
                            </div>
                          </div>
                        ) : (
                          <div key={i} className="flex justify-start">
                            <div
                              className="max-w-[85%] rounded-2xl px-3 py-2.5 text-[12px] leading-[1.6] text-white/80"
                              style={{
                                background: '#171717',
                                border: '1px solid rgba(255,255,255,0.06)',
                              }}
                            >
                              {msg.text}
                            </div>
                          </div>
                        )
                      ))}
                    </div>
                  )}
                </div>

                {/* Input */}
                <div
                  className="flex-shrink-0 p-3"
                  style={{ borderTop: '1px solid rgba(255,255,255,0.05)' }}
                >
                  <div
                    className="flex items-end gap-2 rounded-xl px-3 py-2"
                    style={{
                      background: '#141414',
                      border: '1px solid rgba(255,255,255,0.07)',
                    }}
                  >
                    <textarea
                      value={chatInput}
                      onChange={(e) => setChatInput(e.target.value)}
                      placeholder="Ask about this document…"
                      rows={2}
                      className="flex-1 resize-none bg-transparent text-[12px] text-white/80 placeholder-white/25 focus:outline-none"
                    />
                    <button
                      type="button"
                      className="mb-0.5 flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full transition-colors"
                      style={{ background: chatInput.trim() ? '#FF7819' : 'rgba(255,255,255,0.08)' }}
                      aria-label="Send"
                    >
                      <svg width="12" height="12" viewBox="0 0 14 14" fill="none">
                        <path d="M1 7h12M8 3.5L12 7l-4 3.5" stroke="white" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Collapsed right panel re-open tab */}
          {rightCollapsed && (
            <button
              type="button"
              onClick={() => setRightCollapsed(false)}
              className="flex w-8 flex-shrink-0 items-center justify-center text-white/20 hover:text-white/60 transition-colors"
              style={{ borderLeft: '1px solid rgba(255,255,255,0.05)' }}
              aria-label="Expand panel"
            >
              <svg width="12" height="12" viewBox="0 0 14 14" fill="none">
                <path d="M9 2l-3 3 3 3M5 2l3 3-3 3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
          )}

        </div>
      </div>
    </>
  );
}
