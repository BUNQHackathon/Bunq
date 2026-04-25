import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Document, Page, pdfjs } from 'react-pdf';
import 'react-pdf/dist/Page/AnnotationLayer.css';
import 'react-pdf/dist/Page/TextLayer.css';
import pdfWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import { getLibraryDocument, type LibraryDocument } from '../api/session';
import { IconBack, IconDownload } from '../components/icons';

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorker;

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  } catch {
    return '—';
  }
}

function formatBytes(b: number): string {
  if (!b || b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(0)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

export default function LibraryDocPage() {
  const { docId } = useParams<{ docId: string }>();
  const navigate = useNavigate();

  const [doc, setDoc] = useState<LibraryDocument | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [numPages, setNumPages] = useState<number | null>(null);
  const [pageNumber, setPageNumber] = useState<number>(1);
  const [scale, setScale] = useState<number>(1.0);

  useEffect(() => {
    if (!docId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    setDoc(null);
    getLibraryDocument(docId)
      .then((d) => { if (!cancelled) setDoc(d); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [docId]);

  useEffect(() => {
    setPageNumber(1);
    setNumPages(null);
  }, [docId]);

  if (loading) {
    return (
      <div className="flex h-[calc(100vh-56px)] w-full items-center justify-center bg-[#0D0D0D]">
        <span className="font-mono text-[12px] text-white/40">Loading document…</span>
      </div>
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

  if (doc === null) return null;

  const title = doc.displayName || doc.filename;
  const isPdf = (doc.contentType || '').includes('pdf');

  return (
    <div className="flex h-[calc(100vh-56px)] w-full flex-col overflow-hidden bg-[#0D0D0D]">
      <div
        className="flex h-[52px] flex-shrink-0 items-center gap-3 px-4"
        style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}
      >
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="flex items-center gap-1.5 rounded-pill bg-white/[0.05] px-3 py-1.5 text-[11px] text-white/70 hover:bg-white/[0.08] hover:text-white transition-colors"
        >
          <IconBack size={12} />
          Back to library
        </button>

        <div className="font-mono text-[11px] uppercase tracking-wide text-white/60">
          Library / {doc.kind}
        </div>

        <div className="flex-1" />

        {doc.downloadUrl && (
          <a
            href={doc.downloadUrl}
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-1.5 rounded-pill bg-white/[0.05] px-3 py-1.5 text-[11px] text-white/60 hover:bg-white/[0.08] hover:text-white transition-colors"
          >
            <IconDownload size={12} />
            Download
          </a>
        )}
      </div>

      <div className="flex flex-1 overflow-hidden">
        <div className="flex w-[260px] flex-shrink-0 flex-col px-4 py-4 gap-3" style={{ borderRight: '1px solid rgba(255,255,255,0.05)' }}>
          <div>
            <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">Filename</p>
            <p className="mt-1 text-[12px] text-white/80 break-words">{doc.filename}</p>
          </div>
          <div>
            <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">Kind</p>
            <p className="mt-1 text-[12px] text-white/80 capitalize">{doc.kind}</p>
          </div>
          <div>
            <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">Size</p>
            <p className="mt-1 text-[12px] text-white/80">{formatBytes(doc.sizeBytes)}</p>
          </div>
          {doc.pageCount != null && (
            <div>
              <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">Pages</p>
              <p className="mt-1 text-[12px] text-white/80">{doc.pageCount}</p>
            </div>
          )}
          <div>
            <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">First seen</p>
            <p className="mt-1 text-[12px] text-white/80">{formatDate(doc.firstSeenAt)}</p>
          </div>
          <div>
            <p className="font-mono text-[10px] uppercase tracking-wide text-white/30">Last used</p>
            <p className="mt-1 text-[12px] text-white/80">{formatDate(doc.lastUsedAt)}</p>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto bg-[#0D0D0D]">
          <div className="mx-auto max-w-[920px] px-8 py-[40px]">
            <h1 className="font-serif text-[26px] font-normal leading-[1.3] tracking-tight text-white/90">
              {title}
            </h1>
            <p className="mt-2 text-[12px] text-white/40">
              {doc.kind} · {formatBytes(doc.sizeBytes)} · Updated {formatDate(doc.lastUsedAt)}
            </p>
            <div className="mb-6 mt-6 border-b border-white/[0.05]" />

            {isPdf && doc.downloadUrl ? (
              <div className="mb-8">
                <div className="mb-3 flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2 text-[12px] text-white/60">
                    <button type="button" disabled={pageNumber <= 1} onClick={() => setPageNumber((p) => Math.max(1, p - 1))} className="rounded-md border border-white/[0.08] px-2 py-1 disabled:opacity-40 hover:bg-white/[0.08] text-white/70">‹</button>
                    <span className="font-mono">{pageNumber} / {numPages ?? '…'}</span>
                    <button type="button" disabled={!numPages || pageNumber >= numPages} onClick={() => setPageNumber((p) => Math.min(numPages ?? p, p + 1))} className="rounded-md border border-white/[0.08] px-2 py-1 disabled:opacity-40 hover:bg-white/[0.08] text-white/70">›</button>
                  </div>
                  <div className="flex items-center gap-2 text-[12px] text-white/60">
                    <button type="button" onClick={() => setScale((s) => Math.max(0.5, +(s - 0.1).toFixed(2)))} className="rounded-md border border-white/[0.08] px-2 py-1 hover:bg-white/[0.08] text-white/70">−</button>
                    <span className="font-mono w-[44px] text-center">{Math.round(scale * 100)}%</span>
                    <button type="button" onClick={() => setScale((s) => Math.min(2.5, +(s + 0.1).toFixed(2)))} className="rounded-md border border-white/[0.08] px-2 py-1 hover:bg-white/[0.08] text-white/70">+</button>
                    <button type="button" onClick={() => setScale(1)} className="rounded-md border border-white/[0.08] px-2 py-1 hover:bg-white/[0.08] text-white/70">Fit</button>
                  </div>
                </div>
                <div className="flex justify-center rounded-lg border border-white/[0.05] bg-white/[0.02] p-3 overflow-auto">
                  <Document
                    file={doc.downloadUrl}
                    onLoadSuccess={({ numPages: n }) => setNumPages(n)}
                    loading={<div className="py-12 text-[12px] text-white/40">Loading PDF…</div>}
                    error={<div className="py-12 text-[12px] text-red-500">Failed to load PDF</div>}
                  >
                    <Page pageNumber={pageNumber} scale={scale} renderAnnotationLayer={false} renderTextLayer={false} />
                  </Document>
                </div>
              </div>
            ) : doc.extractedText ? (
              <pre className="whitespace-pre-wrap font-sans text-[14px] leading-[1.7] text-white/80">{doc.extractedText}</pre>
            ) : (
              <div className="py-12 text-center font-mono text-[12px] text-white/40">
                No preview available. {doc.downloadUrl && (<a href={doc.downloadUrl} target="_blank" rel="noreferrer" className="text-[#FF7819] underline">Download file</a>)}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
