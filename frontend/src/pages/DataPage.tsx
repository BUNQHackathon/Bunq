import { useState, useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import useJudgesGate from '../auth/useJudgesGate';
import { type DocumentSummary } from '../api/portal';
import { listLibraryDocuments, type LibraryDocument, presignDocument, putToPresignedUrl, finalizeDocument, computeSha256Base64, type DocumentKind } from '../api/session';
import { getAllDocJurisdictions, setDocJurisdiction } from '../data/docJurisdiction';
import { JURISDICTION_CATALOG, jurisdictionFlag, jurisdictionLabel } from '../api/launch';
import {
  IconFilter,
  IconChevron,
  IconArrowRight,
  IconPlus,
  IconExternal,
} from '../components/icons';

// ─── union type + helpers ────────────────────────────────────────────────────

type UnifiedDoc = DocumentSummary | LibraryDocument;

function isLibrary(d: UnifiedDoc): d is LibraryDocument {
  return 'filename' in d;
}

function docId(d: UnifiedDoc): string {
  return d.id;
}

function docTitle(d: UnifiedDoc): string {
  return isLibrary(d) ? (d.displayName ?? d.filename) : d.title;
}

function docCategory(d: UnifiedDoc): string {
  return isLibrary(d) ? d.kind : d.category;
}

function docUpdated(d: UnifiedDoc): string {
  return isLibrary(d) ? d.lastUsedAt : d.updated;
}

function docSize(d: UnifiedDoc): number {
  return isLibrary(d) ? d.sizeBytes : d.size;
}

function docType(d: UnifiedDoc): string {
  if (!isLibrary(d)) return d.type ?? '';
  const ct = d.contentType ?? '';
  if (ct.includes('pdf')) return 'pdf';
  if (ct.includes('csv')) return 'csv';
  if (ct.includes('markdown')) return 'md';
  return '';
}

// ─── misc helpers ────────────────────────────────────────────────────────────

const CATEGORY_DOT: Record<string, string> = {
  'Terms & Conditions': 'bg-prism-terms',
  AML: 'bg-prism-aml',
  Privacy: 'bg-prism-privacy',
  Licensing: 'bg-prism-licensing',
  Reports: 'bg-prism-reports',
  Pricing: 'bg-prism-orange-soft',
};

function formatBytes(b: number): string {
  if (!b || b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(0)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(iso: string): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
  } catch {
    return '—';
  }
}

// ─── tree ────────────────────────────────────────────────────────────────────

interface FolderNode {
  id: string;
  name: string;
  emoji?: string;
  docIds: string[];
  level: 'jurisdiction' | 'category';
  children: FolderNode[];
}


const UNASSIGNED_CODE = '__none__';

function buildLibraryTree(docs: LibraryDocument[], assignments: Record<string, string>): FolderNode[] {
  const byJur = new Map<string, LibraryDocument[]>();
  for (const d of docs) {
    const fromServer = (d.jurisdictions ?? []).filter((c) => typeof c === 'string' && c.length > 0);
    const codes = fromServer.length > 0
      ? fromServer
      : (assignments[d.id] ? [assignments[d.id]] : [UNASSIGNED_CODE]);
    for (const code of codes) {
      if (!byJur.has(code)) byJur.set(code, []);
      byJur.get(code)!.push(d);
    }
  }

  const knownOrdered = JURISDICTION_CATALOG.map((j) => j.code).filter((c) => byJur.has(c));
  const knownSet = new Set(knownOrdered);
  const otherCodes = [...byJur.keys()]
    .filter((c) => c !== UNASSIGNED_CODE && !knownSet.has(c))
    .sort();
  const orderedCodes = [
    ...knownOrdered,
    ...otherCodes,
    ...(byJur.has(UNASSIGNED_CODE) ? [UNASSIGNED_CODE] : []),
  ];

  return orderedCodes.map((code) => {
    const items = byJur.get(code)!;
    const byKind = new Map<string, LibraryDocument[]>();
    for (const d of items) {
      const kind = d.kind || 'other';
      if (!byKind.has(kind)) byKind.set(kind, []);
      byKind.get(kind)!.push(d);
    }
    const children: FolderNode[] = [...byKind.entries()].map(([kind, kItems]) => ({
      id: `jur-${code}-kind-${kind}`,
      name: kind.charAt(0).toUpperCase() + kind.slice(1),
      emoji: '',
      docIds: kItems.map((d) => d.id),
      level: 'category' as const,
      children: [],
    }));
    return {
      id: `jur-${code}`,
      name: code === UNASSIGNED_CODE ? 'Unassigned' : jurisdictionLabel(code),
      emoji: code === UNASSIGNED_CODE ? '🏳️' : jurisdictionFlag(code),
      docIds: items.map((d) => d.id),
      level: 'jurisdiction' as const,
      children,
    };
  });
}

function buildTree(docs: UnifiedDoc[], assignments: Record<string, string>): FolderNode[] {
  return buildLibraryTree(docs.filter(isLibrary), assignments);
}

function collectDocIds(node: FolderNode): string[] {
  const own = node.docIds ?? [];
  const child = (node.children ?? []).flatMap(collectDocIds);
  return [...own, ...child];
}

function findNodeById(nodes: FolderNode[], id: string): FolderNode | null {
  for (const n of nodes) {
    if (n.id === id) return n;
    const hit = findNodeById(n.children ?? [], id);
    if (hit) return hit;
  }
  return null;
}

function countDocsForNode(node: FolderNode): number {
  return collectDocIds(node).filter(
    (id, idx, arr) => arr.indexOf(id) === idx,
  ).length;
}

// ─── TreeNode component ───────────────────────────────────────────────────────

interface TreeNodeProps {
  node: FolderNode;
  level: number;
  selectedId: string | null;
  expandedIds: Set<string>;
  onSelect: (id: string) => void;
  onToggle: (id: string) => void;
}

function TreeNode({ node, level, selectedId, expandedIds, onSelect, onToggle }: TreeNodeProps) {
  const hasChildren = (node.children?.length ?? 0) > 0;
  const isExpanded = expandedIds.has(node.id);
  const isSelected = selectedId === node.id;
  const docCount = countDocsForNode(node);

  return (
    <div>
      <div
        className={`flex items-center gap-2 px-4 py-1.5 cursor-pointer hover:bg-white/[0.03] transition-colors ${isSelected ? 'bg-white/[0.04]' : ''
          }`}
        style={{ paddingLeft: `${16 + level * 12}px` }}
        onClick={() => {
          onSelect(node.id);
          if (hasChildren) onToggle(node.id);
        }}
      >
        {hasChildren ? (
          <IconChevron
            size={10}
            className={`text-white/40 flex-shrink-0 transition-transform duration-150 ${isExpanded ? 'rotate-0' : '-rotate-90'
              }`}
          />
        ) : (
          <span className="w-[10px] h-[10px] flex-shrink-0" />
        )}

        {node.emoji && (
          <span className="text-[13px] leading-none flex-shrink-0">{node.emoji}</span>
        )}

        <span
          className={`flex-1 min-w-0 truncate ${level === 0
            ? 'text-[12px] text-white/80'
            : 'text-[11.5px] text-white/70'
            } ${isSelected ? 'text-white' : ''}`}
        >
          {node.name}
        </span>

        {docCount > 0 && (
          <span className="font-mono text-[10px] text-white/40 flex-shrink-0">{docCount}</span>
        )}
      </div>

      {hasChildren && isExpanded && (
        <div>
          {node.children!.map((child) => (
            <TreeNode
              key={child.id}
              node={child}
              level={level + 1}
              selectedId={selectedId}
              expandedIds={expandedIds}
              onSelect={onSelect}
              onToggle={onToggle}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ─── DocCard component ────────────────────────────────────────────────────────

interface DocCardProps {
  doc: UnifiedDoc;
}

function DocCard({ doc }: DocCardProps) {
  const navigate = useNavigate();
  const category = docCategory(doc);
  const dotColor = CATEGORY_DOT[category] ?? 'bg-white/20';
  const type = docType(doc);
  const title = docTitle(doc);
  const updated = docUpdated(doc);
  const size = docSize(doc);

  const cardBody = (
    <>
      <div className="flex items-center gap-2 mb-3">
        <span className={`w-2 h-2 rounded-full flex-shrink-0 ${dotColor}`} />
        {type && (
          <span className="font-mono text-[9px] uppercase text-white/60 px-2 py-0.5 rounded-full bg-white/[0.06]">
            {type.toUpperCase()}
          </span>
        )}
        {isLibrary(doc) && doc.obligationsExtracted && (
          <span className="font-mono text-[9px] uppercase px-2 py-0.5 rounded-full bg-teal-500/20 text-teal-300">
            obligations
          </span>
        )}
        {isLibrary(doc) && doc.controlsExtracted && (
          <span className="font-mono text-[9px] uppercase px-2 py-0.5 rounded-full bg-teal-500/20 text-teal-300">
            controls
          </span>
        )}
      </div>

      <div className="text-[14.5px] font-semibold text-white leading-tight line-clamp-2 mb-2">
        {title}
      </div>

      <div className="flex items-center gap-2 font-mono text-[10px] text-white/40 mt-2">
        <span>Updated {formatDate(updated)}</span>
        <span>·</span>
        {isLibrary(doc) && doc.pageCount != null ? (
          <span>{doc.pageCount} pages</span>
        ) : (
          <span>{formatBytes(size)}</span>
        )}
      </div>

      <div className="flex items-center justify-between mt-3 pt-3 border-t border-white/[0.04]">
        <IconArrowRight size={12} className="text-white/40" />
        <IconExternal size={10} className="text-white/30" />
      </div>
    </>
  );

  if (isLibrary(doc)) {
    return (
      <div
        className="rounded-xl border p-4 select-none cursor-pointer transition-all"
        style={{ background: 'var(--bg-1)', borderColor: 'var(--line-1)' }}
        onClick={() => navigate(`/library/${doc.id}`)}
      >
        {cardBody}
      </div>
    );
  }

  return (
    <div
      onClick={() => navigate(`/doc/${doc.id}`)}
      className="rounded-xl border p-4 cursor-pointer transition-all"
      style={{ background: 'var(--bg-1)', borderColor: 'var(--line-1)' }}
    >
      {cardBody}
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

function inferKindAndType(filename: string): { kind: DocumentKind; contentType: string } {
  const ext = filename.slice(filename.lastIndexOf('.')).toLowerCase();
  if (ext === '.pdf') return { kind: 'regulation', contentType: 'application/pdf' };
  if (ext === '.md') return { kind: 'policy', contentType: 'text/markdown' };
  if (ext === '.csv') return { kind: 'evidence', contentType: 'text/csv' };
  if (ext === '.txt') return { kind: 'other', contentType: 'text/plain' };
  if (ext === '.mp3') return { kind: 'audio', contentType: 'audio/mpeg' };
  if (ext === '.wav') return { kind: 'audio', contentType: 'audio/wav' };
  if (ext === '.m4a') return { kind: 'audio', contentType: 'audio/mp4' };
  return { kind: 'other', contentType: 'application/octet-stream' };
}

export default function DataPage() {
  const { requireJudge, modal } = useJudgesGate();

  const source: 'library' = 'library';
  const [docs, setDocs] = useState<UnifiedDoc[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [kindFilter, setKindFilter] = useState<string | undefined>(undefined);
const [typeFilter, setTypeFilter] = useState<string | null>(null);
  const [sortKey, setSortKey] = useState<'recent' | 'oldest' | 'name' | 'size'>('recent');
  const [typeMenuOpen, setTypeMenuOpen] = useState(false);
  const [sortMenuOpen, setSortMenuOpen] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploadState, setUploadState] = useState<'idle' | 'hashing' | 'uploading' | 'finalizing' | 'done' | 'error'>('idle');
  const [refreshToken, setRefreshToken] = useState(0);
  const [uploadFileName, setUploadFileName] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [jurisdictionMap, setJurisdictionMap] = useState<Record<string, string>>(() => getAllDocJurisdictions());
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [pendingFile, setPendingFile] = useState<File | null>(null);
  const [pendingJurisdiction, setPendingJurisdiction] = useState<string>(UNASSIGNED_CODE);

  useEffect(() => {
    if (uploadState === 'done') {
      const t = setTimeout(() => setUploadState('idle'), 2500);
      return () => clearTimeout(t);
    }
  }, [uploadState]);

  async function handleUpload(file: File, jurisdictionCode?: string) {
    setUploadFileName(file.name);
    setUploadError(null);
    setUploadState('hashing');
    try {
      const sha256 = await computeSha256Base64(file);
      setUploadState('uploading');
      const { kind, contentType } = inferKindAndType(file.name);
      const { incomingKey, uploadUrl } = await presignDocument({ filename: file.name, contentType, sha256 });
      await putToPresignedUrl(uploadUrl, file, contentType, sha256);
      setUploadState('finalizing');
      const finalized = await finalizeDocument({ incomingKey, filename: file.name, contentType, kind });
      if (jurisdictionCode && jurisdictionCode !== UNASSIGNED_CODE) {
        setDocJurisdiction(finalized.document.id, jurisdictionCode);
        setJurisdictionMap((prev) => ({ ...prev, [finalized.document.id]: jurisdictionCode }));
      }
      setUploadState('done');
      setRefreshToken((n) => n + 1);
    } catch (err) {
      setUploadState('error');
      setUploadError(err instanceof Error ? err.message : 'Upload failed');
    }
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setDocs([]);
    setSelectedFolderId(null);

    const loader: Promise<UnifiedDoc[]> = listLibraryDocuments(kindFilter, 200).then((r) => r.documents);

    loader
      .then((data) => { if (!cancelled) { setDocs(data); setError(null); } })
      .catch((err) => { if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load'); })
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source, kindFilter, refreshToken]);

const tree = useMemo(() => buildTree(docs, jurisdictionMap), [docs, jurisdictionMap]);

  const [selectedFolderId, setSelectedFolderId] = useState<string | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set<string>());

  useEffect(() => {
    if (tree.length > 0) {
      setExpandedIds(new Set([tree[0]?.id, tree[1]?.id].filter(Boolean) as string[]));
    }
  }, [tree]);

  const selectedNode = useMemo(
    () => (selectedFolderId ? findNodeById(tree, selectedFolderId) : null),
    [selectedFolderId, tree],
  );

  const filteredDocs = useMemo<UnifiedDoc[]>(() => {
    if (!selectedNode) return docs;

    if (selectedNode.docIds && selectedNode.docIds.length > 0) {
      const idSet = new Set(selectedNode.docIds);
      return docs.filter((d) => idSet.has(d.id));
    }

    if (selectedNode.children && selectedNode.children.length > 0) {
      const allIds = new Set(collectDocIds(selectedNode));
      return docs.filter((d) => allIds.has(d.id));
    }

    const nameLower = selectedNode.name.toLowerCase();
    return docs.filter((d) => docCategory(d).toLowerCase() === nameLower);
  }, [selectedNode, docs]);

  const availableTypes = useMemo(() => {
    const set = new Set<string>();
    for (const d of docs) {
      const t = docType(d);
      if (t) set.add(t);
    }
    return Array.from(set).sort();
  }, [docs]);

  const displayDocs = useMemo(() => {
    let arr = filteredDocs;
    if (typeFilter) arr = arr.filter((d) => docType(d) === typeFilter);
    const sorted = [...arr];
    if (sortKey === 'recent') sorted.sort((a, b) => (docUpdated(b) || '').localeCompare(docUpdated(a) || ''));
    else if (sortKey === 'oldest') sorted.sort((a, b) => (docUpdated(a) || '').localeCompare(docUpdated(b) || ''));
    else if (sortKey === 'name') sorted.sort((a, b) => docTitle(a).localeCompare(docTitle(b)));
    else if (sortKey === 'size') sorted.sort((a, b) => docSize(b) - docSize(a));
    return sorted;
  }, [filteredDocs, typeFilter, sortKey]);

  const breadcrumbName = selectedNode ? selectedNode.name : 'All kinds';
  const breadcrumbCount = displayDocs.length;
  const breadcrumbPrefix = 'Library';

  useEffect(() => {
    if (!typeMenuOpen && !sortMenuOpen) return;
    const handler = () => { setTypeMenuOpen(false); setSortMenuOpen(false); };
    window.addEventListener('mousedown', handler);
    return () => window.removeEventListener('mousedown', handler);
  }, [typeMenuOpen, sortMenuOpen]);

  function handleToggle(id: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  const uploadLabel =
    uploadState === 'hashing' ? 'Hashing…' :
      uploadState === 'uploading' ? 'Uploading…' :
        uploadState === 'finalizing' ? 'Finalizing…' :
          'Upload';
  const uploadBusy = uploadState === 'hashing' || uploadState === 'uploading' || uploadState === 'finalizing';

  const GRID_LINE_COLOR = 'rgba(214, 214, 214, 0.13)';
  const gridOverlayStyle = {
    position: 'absolute' as const,
    inset: 0,
    pointerEvents: 'none' as const,
    zIndex: -1,
    backgroundImage:
      `linear-gradient(${GRID_LINE_COLOR} 1px, transparent 1px),` +
      `linear-gradient(90deg, ${GRID_LINE_COLOR} 1px, transparent 1px)`,
    backgroundSize: '44px 44px',
    WebkitMaskImage: 'radial-gradient(ellipse at center, black 40%, transparent 95%)',
    maskImage: 'radial-gradient(ellipse at center, black 40%, transparent 95%)',
  };

  return (
    <div
      className="flex flex-col h-[calc(100vh-56px)] relative"
      style={{ isolation: 'isolate', background: 'var(--bg-0)' }}
    >
      {modal}
      <div aria-hidden style={gridOverlayStyle} />
      <input
        type="file"
        ref={fileInputRef}
        className="hidden"
        accept=".pdf,.md,.csv,.txt,.mp3,.wav,.m4a"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) setPendingFile(file);
          e.target.value = '';
        }}
      />
      {/* Breadcrumb bar */}
      <div className="shrink-0 h-12 px-3 sm:px-6 flex items-center justify-between border-b border-white/[0.05]" style={{ background: 'var(--bg-0)' }}>
        <div className="flex items-center gap-4">
          <span className="font-mono text-[12px] text-white/60">
            {breadcrumbPrefix} / {breadcrumbName} · {breadcrumbCount} files
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <button
            disabled={uploadBusy}
            onClick={requireJudge(() => { setPendingFile(null); setPendingJurisdiction(UNASSIGNED_CODE); setUploadModalOpen(true); })}
            className={`whitespace-nowrap flex items-center gap-1.5 rounded-full px-3 py-1.5 font-mono text-[12px] font-semibold transition-colors border ${uploadBusy ? 'bg-[rgba(239,106,42,0.04)] text-[#ef6a2a]/40 border-[rgba(239,106,42,0.15)] cursor-not-allowed' : 'bg-[rgba(239,106,42,0.10)] text-[#ef6a2a] border-[rgba(239,106,42,0.3)] hover:bg-[rgba(239,106,42,0.16)] hover:border-[rgba(239,106,42,0.45)]'}`}
          >
            <IconPlus size={11} />
            {uploadLabel}
          </button>
          <div className="relative" onMouseDown={(e) => e.stopPropagation()}>
            <button
              onClick={() => { setTypeMenuOpen((o) => !o); setSortMenuOpen(false); }}
              className={`whitespace-nowrap flex items-center gap-1.5 rounded-full border px-3 py-1.5 font-mono text-[12px] transition-colors ${typeFilter ? 'border-[#FF7819]/60 text-white bg-[#FF7819]/10' : 'border-white/[0.12] text-white/70 hover:bg-white/[0.04]'}`}
            >
              <IconFilter size={11} />
              {typeFilter ? `Type: ${typeFilter.toUpperCase()}` : 'Type'}
            </button>
            {typeMenuOpen && (
              <div className="absolute right-0 mt-1 z-20 min-w-[140px] rounded-lg border border-white/[0.1] shadow-lg overflow-hidden" style={{ background: 'var(--bg-1)' }}>
                <button
                  onClick={() => { setTypeFilter(null); setTypeMenuOpen(false); }}
                  className={`w-full text-left px-3 py-1.5 font-mono text-[11px] hover:bg-white/[0.05] ${typeFilter === null ? 'text-white' : 'text-white/70'}`}
                >
                  All types
                </button>
                {availableTypes.map((t) => (
                  <button
                    key={t}
                    onClick={() => { setTypeFilter(t); setTypeMenuOpen(false); }}
                    className={`w-full text-left px-3 py-1.5 font-mono text-[11px] hover:bg-white/[0.05] ${typeFilter === t ? 'text-white' : 'text-white/70'}`}
                  >
                    {t.toUpperCase()}
                  </button>
                ))}
              </div>
            )}
          </div>
          <div className="relative" onMouseDown={(e) => e.stopPropagation()}>
            <button
              onClick={() => { setSortMenuOpen((o) => !o); setTypeMenuOpen(false); }}
              className="whitespace-nowrap flex items-center gap-1.5 rounded-full border border-white/[0.12] px-3 py-1.5 font-mono text-[12px] text-white/70 hover:bg-white/[0.04] transition-colors"
            >
              Sort: {sortKey === 'recent' ? 'Recent' : sortKey === 'oldest' ? 'Oldest' : sortKey === 'name' ? 'Name' : 'Size'}
              <IconChevron size={11} />
            </button>
            {sortMenuOpen && (
              <div className="absolute right-0 mt-1 z-20 min-w-[140px] rounded-lg border border-white/[0.1] shadow-lg overflow-hidden" style={{ background: 'var(--bg-1)' }}>
                {([['recent', 'Recent'], ['oldest', 'Oldest'], ['name', 'Name (A→Z)'], ['size', 'Size']] as const).map(([k, label]) => (
                  <button
                    key={k}
                    onClick={() => { setSortKey(k); setSortMenuOpen(false); }}
                    className={`w-full text-left px-3 py-1.5 font-mono text-[11px] hover:bg-white/[0.05] ${sortKey === k ? 'text-white' : 'text-white/70'}`}
                  >
                    {label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
      {uploadError && (
        <div className="shrink-0 px-6 py-1 flex items-center gap-2">
          <span className="font-mono text-[11px] text-[#E05050]">{uploadError}</span>
          <button onClick={() => setUploadError(null)} className="font-mono text-[11px] text-[#E05050] hover:text-white/70">×</button>
        </div>
      )}
      {uploadState === 'done' && uploadFileName && (
        <div className="shrink-0 px-6 py-1">
          <span className="font-mono text-[11px] text-teal-300">Uploaded: {uploadFileName}</span>
        </div>
      )}

      {/* Main row */}
      <div className="flex-1 overflow-hidden flex">
        {/* Left tree */}
        <div className="hidden md:block shrink-0 w-[260px] border-r border-white/[0.05] overflow-y-auto flex flex-col" style={{ background: 'var(--bg-1)' }}>
          <div className="flex items-center justify-between px-4 py-3 border-b border-white/[0.05]">
            <span className="font-mono uppercase text-[11px] text-white/30 tracking-widest">
              Library
            </span>
          </div>

          <div className="py-1">
            {loading && tree.length === 0 ? (
              <div className="px-4 py-3 font-mono text-[11px] text-white/30">Loading...</div>
            ) : (
              tree.map((node) => (
                <TreeNode
                  key={node.id}
                  node={node}
                  level={0}
                  selectedId={selectedFolderId}
                  expandedIds={expandedIds}
                  onSelect={(id) =>
                    setSelectedFolderId((prev) => (prev === id ? null : id))
                  }
                  onToggle={handleToggle}
                />
              ))
            )}
          </div>
        </div>

        {/* Center */}
        <div className="flex-1 overflow-y-auto p-3 sm:p-6">
          <div className="font-mono uppercase text-[11px] text-white/30 tracking-widest mb-3">
            Documents
          </div>

          <div className="flex flex-wrap items-center gap-1.5 mb-4">
            {([undefined, 'regulation', 'policy', 'brief', 'evidence', 'audio', 'other'] as Array<string | undefined>).map((k) => (
              <button
                key={k ?? 'all'}
                onClick={() => setKindFilter(k)}
                className={`font-mono text-[10px] px-2.5 py-1 rounded-full transition-colors ${kindFilter === k
                  ? 'bg-[rgba(239,106,42,0.10)] text-[#ef6a2a] border border-[rgba(239,106,42,0.3)] font-semibold'
                  : 'bg-white/[0.05] text-white/50 hover:text-white/80 hover:bg-white/[0.08] border border-transparent'
                  }`}
              >
                {k === undefined ? 'All' : k.charAt(0).toUpperCase() + k.slice(1)}
              </button>
            ))}
          </div>

          {loading && docs.length === 0 ? (
            <div className="flex items-center justify-center py-16 font-mono text-[13px] text-white/40">
              Loading documents...
            </div>
          ) : error ? (
            <div
              className="rounded-lg p-3 font-mono text-[13px] text-[#E05050]"
              style={{
                background: 'rgba(224,80,80,0.08)',
                border: '1px solid rgba(224,80,80,0.2)',
              }}
            >
              {error}
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              {displayDocs.map((doc) => (
                <DocCard key={docId(doc)} doc={doc} />
              ))}
            </div>
          )}

        </div>
      </div>

      {uploadModalOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
          onClick={() => setUploadModalOpen(false)}
        >
          <div
            className="w-[calc(100vw-32px)] max-w-[420px] rounded-xl border border-white/[0.1] p-5 shadow-2xl"
            style={{ background: 'var(--bg-1)', borderColor: 'var(--line-1)' }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="font-mono uppercase text-[11px] text-white/40 tracking-widest mb-4">
              Upload document
            </div>

            <div className="mb-4">
              <div className="font-mono text-[11px] text-white/50 mb-2">File</div>
              {pendingFile ? (
                <div className="flex items-center justify-between gap-2 rounded-lg border border-white/[0.08] bg-white/[0.03] px-3 py-2">
                  <span className="font-mono text-[12px] text-white/80 truncate">{pendingFile.name}</span>
                  <button
                    onClick={() => setPendingFile(null)}
                    className="font-mono text-[11px] text-white/40 hover:text-white/70"
                  >
                    Change
                  </button>
                </div>
              ) : (
                <button
                  onClick={() => fileInputRef.current?.click()}
                  className="w-full rounded-lg border border-dashed border-white/[0.15] bg-white/[0.02] px-3 py-4 font-mono text-[12px] text-white/60 hover:bg-white/[0.04] hover:text-white/80 transition-colors"
                >
                  Choose file
                </button>
              )}
            </div>

            <div className="mb-5">
              <div className="font-mono text-[11px] text-white/50 mb-2">Jurisdiction</div>
              <select
                value={pendingJurisdiction}
                onChange={(e) => setPendingJurisdiction(e.target.value)}
                className="w-full rounded-lg border border-white/[0.1] bg-white/[0.03] px-3 py-2 font-mono text-[12px] text-white/80 outline-none focus:border-[#FF7819]/60"
              >
                <option value={UNASSIGNED_CODE}>Unassigned</option>
                {JURISDICTION_CATALOG.map((j) => (
                  <option key={j.code} value={j.code}>{j.flag} {j.name}</option>
                ))}
              </select>
            </div>

            <div className="flex items-center justify-end gap-2">
              <button
                onClick={() => setUploadModalOpen(false)}
                className="rounded-full border border-white/[0.12] px-3 py-1.5 font-mono text-[12px] text-white/70 hover:bg-white/[0.04]"
              >
                Cancel
              </button>
              <button
                disabled={!pendingFile || uploadBusy}
                onClick={() => {
                  if (!pendingFile) return;
                  const file = pendingFile;
                  const jur = pendingJurisdiction;
                  setUploadModalOpen(false);
                  handleUpload(file, jur);
                }}
                className={`rounded-full px-3 py-1.5 font-mono text-[12px] font-semibold transition-colors border ${(!pendingFile || uploadBusy) ? 'bg-[rgba(239,106,42,0.04)] text-[#ef6a2a]/40 border-[rgba(239,106,42,0.15)] cursor-not-allowed' : 'bg-[rgba(239,106,42,0.10)] text-[#ef6a2a] border-[rgba(239,106,42,0.3)] hover:bg-[rgba(239,106,42,0.16)] hover:border-[rgba(239,106,42,0.45)]'}`}
              >
                Upload
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
