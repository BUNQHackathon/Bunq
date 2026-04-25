import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { listDocuments, type DocumentSummary } from '../api/portal';
import { listLibraryDocuments, createSession, attachDocument, type LibraryDocument } from '../api/session';
import { chats, type Chat } from '../data/portal';
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
  return isLibrary(d) ? d.filename : d.title;
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

function buildKbTree(docs: DocumentSummary[]): FolderNode[] {
  const byJuris = new Map<string, DocumentSummary[]>();
  for (const d of docs) {
    const j = d.jurisdiction || 'Other';
    if (!byJuris.has(j)) byJuris.set(j, []);
    byJuris.get(j)!.push(d);
  }
  const jurisEmoji: Record<string, string> = {
    EU: '🇪🇺', NL: '🇳🇱', DE: '🇩🇪', FR: '🇫🇷', UK: '🇬🇧', Other: '🌍',
  };
  return [...byJuris.entries()].map(([juris, jurDocs]) => {
    const byCat = new Map<string, DocumentSummary[]>();
    for (const d of jurDocs) {
      if (!byCat.has(d.category)) byCat.set(d.category, []);
      byCat.get(d.category)!.push(d);
    }
    return {
      id: `jur-${juris}`,
      name: juris === 'EU' ? 'EU-wide' : juris,
      emoji: jurisEmoji[juris] ?? '🌍',
      docIds: jurDocs.map((d) => d.id),
      level: 'jurisdiction' as const,
      children: [...byCat.entries()].map(([cat, catDocs]) => ({
        id: `cat-${juris}-${cat}`,
        name: cat,
        docIds: catDocs.map((d) => d.id),
        level: 'category' as const,
        children: [],
      })),
    };
  });
}

function buildLibraryTree(docs: LibraryDocument[]): FolderNode[] {
  const byKind = new Map<string, LibraryDocument[]>();
  for (const d of docs) {
    const kind = d.kind || 'other';
    if (!byKind.has(kind)) byKind.set(kind, []);
    byKind.get(kind)!.push(d);
  }
  return [...byKind.entries()].map(([kind, items]) => ({
    id: `kind-${kind}`,
    name: kind.charAt(0).toUpperCase() + kind.slice(1),
    emoji: '',
    docIds: items.map((d) => d.id),
    level: 'jurisdiction' as const,
    children: [],
  }));
}

function buildTree(docs: UnifiedDoc[], source: 'kb' | 'library'): FolderNode[] {
  if (source === 'library') {
    return buildLibraryTree(docs.filter(isLibrary));
  }
  return buildKbTree(docs.filter((d): d is DocumentSummary => !isLibrary(d)));
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
  onUseInAnalysis?: (doc: LibraryDocument) => Promise<void>;
  usingId?: string | null;
  useError?: string | null;
}

function DocCard({ doc, onUseInAnalysis, usingId, useError = null }: DocCardProps) {
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
    const isInFlight = usingId === doc.id;
    return (
      <div
        className="rounded-xl border border-white/[0.06] hover:border-white/[0.14] p-4 bg-prism-panel select-none cursor-pointer transition-all"
        onClick={() => navigate(`/library/${doc.id}`)}
      >
        {cardBody}
        <div className="mt-3 pt-2 border-t border-white/[0.04] flex flex-col gap-1">
          <button
            disabled={isInFlight}
            onClick={(e) => { e.stopPropagation(); onUseInAnalysis?.(doc); }}
            className={`w-full font-mono text-[11px] px-3 py-1.5 rounded-full transition-colors ${isInFlight
              ? 'bg-[#FF7819]/40 text-white/50 cursor-not-allowed'
              : 'bg-[#FF7819] text-white hover:bg-[#e86a10]'
              }`}
          >
            {isInFlight ? 'Starting…' : 'Use in new analysis'}
          </button>
          {useError && (
            <span className="font-mono text-[10px] text-[#E05050] leading-snug">
              {useError}
            </span>
          )}
        </div>
      </div>
    );
  }

  return (
    <div
      onClick={() => navigate(`/doc/${doc.id}`)}
      className="rounded-xl border border-white/[0.06] hover:border-white/[0.14] p-4 cursor-pointer transition-all bg-prism-panel"
    >
      {cardBody}
    </div>
  );
}

// ─── ChatRow component ────────────────────────────────────────────────────────

interface ChatRowProps {
  chat: Chat;
}

function ChatRow({ chat }: ChatRowProps) {
  return (
    <div
      className="px-4 py-3 border-b border-white/[0.04] hover:bg-white/[0.02] cursor-pointer flex flex-col gap-1"
      onClick={() => { }}
    >
      <div className="flex items-baseline justify-between gap-4">
        <span className="text-[13.5px] text-white/85 font-medium truncate">{chat.title}</span>
        <span className="font-mono text-[10px] text-white/40 flex-shrink-0">{chat.timestamp}</span>
      </div>
      <p className="text-[12px] text-white/55 leading-snug line-clamp-2">{chat.snippet}</p>
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function DataPage() {
  const navigate = useNavigate();

  const [source, setSource] = useState<'kb' | 'library'>('kb');
  const [docs, setDocs] = useState<UnifiedDoc[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [kindFilter, setKindFilter] = useState<string | undefined>(undefined);
  const [usingDocId, setUsingDocId] = useState<string | null>(null);
  const [docErrors, setDocErrors] = useState<Map<string, string>>(new Map<string, string>());

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setDocs([]);
    setSelectedFolderId(null);

    const loader: Promise<UnifiedDoc[]> =
      source === 'kb'
        ? listDocuments()
        : listLibraryDocuments(kindFilter, 200).then((r) => r.documents);

    loader
      .then((data) => { if (!cancelled) { setDocs(data); setError(null); } })
      .catch((err) => { if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load'); })
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source, kindFilter]);

  async function handleUseInAnalysis(doc: LibraryDocument): Promise<void> {
    setUsingDocId(doc.id);
    setDocErrors((prev) => {
      const next = new Map(prev);
      next.delete(doc.id);
      return next;
    });
    try {
      const session = await createSession({});
      await attachDocument(session.id, doc.id);
      navigate(`/session/${session.id}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to start session';
      setDocErrors((prev) => new Map(prev).set(doc.id, msg));
    } finally {
      setUsingDocId(null);
    }
  }

  const tree = useMemo(() => buildTree(docs, source), [docs, source]);

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

  const breadcrumbName = selectedNode ? selectedNode.name : (source === 'kb' ? 'All documents' : 'All kinds');
  const breadcrumbCount = selectedNode ? filteredDocs.length : docs.length;
  const breadcrumbPrefix = source === 'kb' ? 'Workspace' : 'Library';

  function handleToggle(id: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  return (
    <div className="flex flex-col h-[calc(100vh-56px)]">
      {/* Breadcrumb bar */}
      <div className="shrink-0 h-12 px-6 flex items-center justify-between border-b border-white/[0.05]">
        <div className="flex items-center gap-4">
          <span className="font-mono text-[12px] text-white/60">
            {breadcrumbPrefix} / {breadcrumbName} · {breadcrumbCount} files
          </span>
        </div>

        <div className="flex items-center gap-2">
          <button className="flex items-center gap-1.5 rounded-full border border-white/[0.12] px-3 py-1.5 font-mono text-[12px] text-white/70 hover:bg-white/[0.04] transition-colors">
            <IconFilter size={11} />
            Type
          </button>
          <button className="flex items-center gap-1.5 rounded-full border border-white/[0.12] px-3 py-1.5 font-mono text-[12px] text-white/70 hover:bg-white/[0.04] transition-colors">
            Sort: Recent
            <IconChevron size={11} />
          </button>

        </div>
      </div>

      {/* Main row */}
      <div className="flex-1 overflow-hidden flex">
        {/* Left tree */}
        <div className="shrink-0 w-[260px] bg-prism-panel border-r border-white/[0.05] overflow-y-auto flex flex-col">
          <div className="flex items-center justify-between px-4 py-3 border-b border-white/[0.05]">
            <span className="font-mono uppercase text-[11px] text-white/30 tracking-widest">
              {source === 'kb' ? 'Workspace' : 'Library'}
            </span>
            <button className="flex items-center justify-center w-5 h-5 rounded text-white/40 hover:text-white/70 hover:bg-white/[0.05] transition-colors">
              <IconPlus size={11} />
            </button>
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
        <div className="flex-1 overflow-y-auto p-6">
          <div className="font-mono uppercase text-[11px] text-white/30 tracking-widest mb-3">
            Documents
          </div>

          {source === 'library' && (
            <div className="flex flex-wrap items-center gap-1.5 mb-4">
              {([undefined, 'regulation', 'policy', 'brief', 'evidence', 'audio', 'other'] as Array<string | undefined>).map((k) => (
                <button
                  key={k ?? 'all'}
                  onClick={() => setKindFilter(k)}
                  className={`font-mono text-[10px] px-2.5 py-1 rounded-full transition-colors ${kindFilter === k
                    ? 'bg-[#FF7819] text-white'
                    : 'bg-white/[0.05] text-white/50 hover:text-white/80 hover:bg-white/[0.08]'
                    }`}
                >
                  {k === undefined ? 'All' : k.charAt(0).toUpperCase() + k.slice(1)}
                </button>
              ))}
            </div>
          )}

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
              {filteredDocs.map((doc) => (
                <DocCard
                  key={docId(doc)}
                  doc={doc}
                  onUseInAnalysis={isLibrary(doc) ? handleUseInAnalysis : undefined}
                  usingId={usingDocId}
                  useError={isLibrary(doc) ? (docErrors.get(doc.id) ?? null) : null}
                />
              ))}
            </div>
          )}

          {/* Recent chats */}
          <div className="mt-10 pt-6 border-t border-white/[0.06]">
            <div className="font-mono uppercase text-[11px] text-white/30 tracking-widest mb-4">
              Recent chats citing this folder
            </div>
            <div className="rounded-xl border border-white/[0.06] overflow-hidden">
              {chats.map((chat) => (
                <ChatRow key={chat.id} chat={chat} />
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
