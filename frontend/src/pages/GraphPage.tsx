import * as d3 from 'd3';
import { useEffect, useRef, useState, useCallback } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import useJudgesGate from '../auth/useJudgesGate';
import { getGraph, type GraphNode as ApiGraphNode, type GraphLink as ApiGraphLink } from '../api/portal';
import { getComplianceMap } from '../api/jurisdictions';
import { getLaunch, jurisdictionFlag, jurisdictionLabel, runJurisdiction } from '../api/launch';

// ─── Compliance-map node type → resolved hex (from CSS token comments) ────────
// Using resolved hexes keeps D3 attr() calls clean; values mirror styles.css vars.
const COMPLIANCE_TYPE_COLOR: Record<string, string> = {
  obligation: '#ef6a2a',  // var(--tc)
  control:    '#5eb5a6',  // var(--lic) approx
  gap:        '#d94a4a',  // var(--danger) / var(--aml) warm-red approx
  evidence:   '#6a8fd8',  // var(--priv) approx
};

const CAT_COLOR: Record<string, string> = {
  terms:     '#ef6a2a',   // var(--tc)
  aml:       '#c84a3a',   // var(--aml) approx
  privacy:   '#6a8fd8',   // var(--priv) approx
  reports:   '#5eb5a6',   // var(--lic)
  licensing: '#5eb5a6',   // var(--lic)
  pricing:   '#d9b03d',   // var(--warning)
  concept:   '#6a6055',   // var(--concept) approx
};

interface GraphNode extends d3.SimulationNodeDatum {
  id: string;
  label: string;
  cat: string;
  doc: boolean;
  size: number;
  updated?: string;
  // compliance-map extras (live mode)
  severity?: 'low' | 'medium' | 'high' | 'critical';
  recommendedAction?: string;
  nodeType?: 'obligation' | 'control' | 'gap' | 'evidence';
}

interface GraphLink extends d3.SimulationLinkDatum<GraphNode> {
  source: GraphNode | string;
  target: GraphNode | string;
}

interface Tooltip {
  x: number;
  y: number;
  name: string;
  meta: string;
}

const MOCK_NODES: GraphNode[] = [
  { id: 'gdpr', label: 'GDPR', cat: 'privacy', doc: true, size: 13, updated: '2024-05-01' },
  { id: 'privacy-policy', label: 'Privacy Policy', cat: 'privacy', doc: true, size: 11, updated: '2024-11-12' },
  { id: 'kyc', label: 'KYC', cat: 'concept', doc: false, size: 9, updated: undefined },
  { id: 'aml-policy', label: 'AML Policy', cat: 'aml', doc: true, size: 12, updated: '2024-09-30' },
  { id: 'wwft', label: 'WWFT', cat: 'aml', doc: true, size: 11, updated: '2023-12-01' },
  { id: 'fatf', label: 'FATF Recommendations', cat: 'aml', doc: true, size: 10, updated: '2023-06-15' },
  { id: 'sanctions', label: 'Sanctions Screening', cat: 'concept', doc: false, size: 8, updated: undefined },
  { id: 'license-dnb', label: 'DNB License', cat: 'licensing', doc: true, size: 12, updated: '2022-03-10' },
  { id: 'license-ecb', label: 'ECB Authorization', cat: 'licensing', doc: true, size: 11, updated: '2021-07-22' },
  { id: 'psd2', label: 'PSD2', cat: 'concept', doc: false, size: 9, updated: undefined },
  { id: 'terms', label: 'Terms & Conditions', cat: 'terms', doc: true, size: 14, updated: '2025-01-15' },
  { id: 'fee-schedule', label: 'Fee Schedule', cat: 'pricing', doc: true, size: 10, updated: '2025-02-01' },
  { id: 'annual-report-2023', label: 'Annual Report 2023', cat: 'reports', doc: true, size: 11, updated: '2024-04-30' },
  { id: 'annual-report-2022', label: 'Annual Report 2022', cat: 'reports', doc: true, size: 10, updated: '2023-04-28' },
  { id: 'data-retention', label: 'Data Retention', cat: 'concept', doc: false, size: 7, updated: undefined },
  { id: 'cdd', label: 'CDD', cat: 'concept', doc: false, size: 8, updated: undefined },
  { id: 'pep', label: 'PEP Screening', cat: 'concept', doc: false, size: 7, updated: undefined },
  { id: 'cookie-policy', label: 'Cookie Policy', cat: 'privacy', doc: true, size: 9, updated: '2024-08-19' },
  { id: 'iban-terms', label: 'IBAN Assignment Terms', cat: 'terms', doc: true, size: 8, updated: '2024-03-05' },
  { id: 'risk-framework', label: 'Risk Framework', cat: 'aml', doc: true, size: 10, updated: '2024-01-20' },
];

const MOCK_LINKS: GraphLink[] = [
  { source: 'gdpr', target: 'privacy-policy' },
  { source: 'gdpr', target: 'data-retention' },
  { source: 'gdpr', target: 'cookie-policy' },
  { source: 'privacy-policy', target: 'data-retention' },
  { source: 'kyc', target: 'aml-policy' },
  { source: 'kyc', target: 'cdd' },
  { source: 'kyc', target: 'wwft' },
  { source: 'aml-policy', target: 'wwft' },
  { source: 'aml-policy', target: 'fatf' },
  { source: 'aml-policy', target: 'sanctions' },
  { source: 'aml-policy', target: 'risk-framework' },
  { source: 'wwft', target: 'cdd' },
  { source: 'wwft', target: 'pep' },
  { source: 'fatf', target: 'sanctions' },
  { source: 'fatf', target: 'pep' },
  { source: 'cdd', target: 'pep' },
  { source: 'license-dnb', target: 'psd2' },
  { source: 'license-ecb', target: 'psd2' },
  { source: 'license-dnb', target: 'license-ecb' },
  { source: 'terms', target: 'fee-schedule' },
  { source: 'terms', target: 'iban-terms' },
  { source: 'terms', target: 'psd2' },
  { source: 'annual-report-2023', target: 'annual-report-2022' },
  { source: 'annual-report-2023', target: 'risk-framework' },
  { source: 'risk-framework', target: 'kyc' },
  { source: 'risk-framework', target: 'sanctions' },
  { source: 'license-dnb', target: 'annual-report-2023' },
];

interface GraphCanvasProps {
  nodes: GraphNode[];
  links: GraphLink[];
  onNodeClick: (node: GraphNode) => void;
  selectedId: string | null;
}

function GraphCanvas({ nodes, links, onNodeClick, selectedId }: GraphCanvasProps) {
  const svgRef = useRef<SVGSVGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [tooltip, setTooltip] = useState<Tooltip | null>(null);
  const nodeGRef = useRef<d3.Selection<SVGGElement, GraphNode, SVGGElement, unknown> | null>(null);

  useEffect(() => {
    if (nodes.length === 0) return;
    const container = containerRef.current;
    const svgEl = svgRef.current;
    if (!container || !svgEl) return;

    const W = container.clientWidth;
    const H = container.clientHeight;

    nodes.forEach((n, i) => {
      if (n.x !== undefined && n.y !== undefined) return;
      const angle = (i / nodes.length) * 2 * Math.PI;
      const r = 200 + Math.random() * 80;
      n.x = W / 2 + Math.cos(angle) * r;
      n.y = H / 2 + Math.sin(angle) * r;
    });

    const svg = d3.select(svgEl);
    svg.selectAll('*').remove();

    const defs = svg.append('defs');
    defs.append('filter').attr('id', 'glow').attr('x', '-80%').attr('y', '-80%').attr('width', '260%').attr('height', '260%')
      .html(`<feGaussianBlur in="SourceGraphic" stdDeviation="5" result="b"/>
             <feMerge><feMergeNode in="b"/><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>`);
    defs.append('filter').attr('id', 'glow2').attr('x', '-80%').attr('y', '-80%').attr('width', '260%').attr('height', '260%')
      .html(`<feGaussianBlur in="SourceGraphic" stdDeviation="2.5" result="b"/>
             <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>`);

    const g = svg.append('g');

    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.2, 4])
      .on('zoom', e => { g.attr('transform', e.transform); });
    svg.call(zoom);

    const sim = d3.forceSimulation<GraphNode, GraphLink>(nodes)
      .force('link', d3.forceLink<GraphNode, GraphLink>(links).id(d => d.id)
        .distance(d => {
          const src = d.source as GraphNode;
          const tgt = d.target as GraphNode;
          return (src.cat === 'concept' || tgt.cat === 'concept') ? 75 : 130;
        })
        .strength(0.35))
      .force('charge', d3.forceManyBody<GraphNode>().strength(-280))
      .force('center', d3.forceCenter(W / 2, H / 2))
      .force('collision', d3.forceCollide<GraphNode>(d => d.size + 22));

    sim.alpha(1).alphaDecay(0.0228).velocityDecay(0.4).restart();

    // Link stroke uses a soft warm-white matching the canvas dark bg
    const linkSel = g.append('g').selectAll<SVGLineElement, GraphLink>('line').data(links).join('line')
      .attr('stroke', 'var(--line-soft, rgba(246,241,234,0.06))')
      .attr('stroke-width', 0.75);

    const nodeG = g.append('g').selectAll<SVGGElement, GraphNode>('g').data(nodes).join('g')
      .style('cursor', 'pointer')
      .call(d3.drag<SVGGElement, GraphNode>()
        .on('start', (e, d) => { if (!e.active) sim.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; })
        .on('drag', (e, d) => { d.fx = e.x; d.fy = e.y; })
        .on('end', (e, d) => { if (!e.active) sim.alphaTarget(0); d.fx = null; d.fy = null; })
      )
      .on('mouseenter', function (e: MouseEvent, d: GraphNode) {
        d3.select(this).select<SVGCircleElement>('.core').attr('filter', 'url(#glow)').attr('r', d.size * 1.35);
        d3.select(this).select<SVGCircleElement>('.halo').attr('opacity', 0.22);

        const adj = new Set([d.id]);
        links.forEach(l => {
          const src = (l.source as GraphNode).id;
          const tgt = (l.target as GraphNode).id;
          if (src === d.id) adj.add(tgt);
          if (tgt === d.id) adj.add(src);
        });
        linkSel.attr('stroke', (l: GraphLink) => {
          const src = (l.source as GraphNode).id;
          const tgt = (l.target as GraphNode).id;
          return (src === d.id || tgt === d.id) ? 'rgba(239,106,42,0.55)' : 'rgba(246,241,234,0.03)';
        });
        nodeG.select<SVGTextElement>('.node-label').attr('fill', (n: GraphNode) =>
          adj.has(n.id) ? 'var(--ink-0, #f6f1ea)' : 'var(--ink-3, #5a544c)'
        );

        const rect = container.getBoundingClientRect();
        setTooltip({
          x: e.clientX - rect.left + 14,
          y: e.clientY - rect.top - 10,
          name: d.label,
          meta: d.doc ? `Updated ${d.updated} · Click to view` : 'Concept reference',
        });
      })
      .on('mouseleave', function (_e: MouseEvent, d: GraphNode) {
        d3.select(this).select<SVGCircleElement>('.core').attr('filter', 'url(#glow2)').attr('r', d.size);
        d3.select(this).select<SVGCircleElement>('.halo').attr('opacity', d.doc ? 0.1 : 0.05);
        linkSel.attr('stroke', 'var(--line-soft, rgba(246,241,234,0.06))');
        nodeG.select<SVGTextElement>('.node-label').attr('fill', 'var(--ink-2, #8a8278)');
        setTooltip(null);
      })
      .on('click', (_e: MouseEvent, d: GraphNode) => {
        onNodeClick(d);
      });

    nodeG.append('circle').attr('class', 'halo')
      .attr('r', d => d.size + 10)
      .attr('fill', d => CAT_COLOR[d.cat] || '#445566')
      .attr('opacity', d => d.doc ? 0.1 : 0.05);

    nodeG.append('circle').attr('class', 'core')
      .attr('r', d => d.size)
      .attr('fill', d => CAT_COLOR[d.cat] || '#445566')
      .attr('filter', 'url(#glow2)')
      .attr('stroke', d => d.doc ? 'rgba(246,241,234,0.2)' : 'none')
      .attr('stroke-width', 1);

    nodeG.append('text').attr('class', 'node-label')
      .text(d => d.label)
      .attr('fill', 'var(--ink-2, #8a8278)')
      .attr('font-size', d => d.doc ? 11.5 : 10)
      .attr('font-weight', d => d.doc ? 500 : 400)
      .attr('dx', d => d.size + 7)
      .attr('dy', '0.35em')
      .style('pointer-events', 'none')
      .style('user-select', 'none');

    nodeGRef.current = nodeG;

    sim.on('tick', () => {
      linkSel
        .attr('x1', d => (d.source as GraphNode).x ?? 0)
        .attr('y1', d => (d.source as GraphNode).y ?? 0)
        .attr('x2', d => (d.target as GraphNode).x ?? 0)
        .attr('y2', d => (d.target as GraphNode).y ?? 0);
      nodeG.attr('transform', d => `translate(${d.x ?? 0},${d.y ?? 0})`);
    });

    const handleMouseMove = (e: MouseEvent) => {
      setTooltip(prev => {
        if (!prev) return null;
        const rect = container.getBoundingClientRect();
        return { ...prev, x: e.clientX - rect.left + 14, y: e.clientY - rect.top - 10 };
      });
    };
    container.addEventListener('mousemove', handleMouseMove);

    return () => {
      sim.stop();
      container.removeEventListener('mousemove', handleMouseMove);
    };
  }, [nodes, links]);

  useEffect(() => {
    if (!nodeGRef.current) return;
    nodeGRef.current.select<SVGCircleElement>('.core')
      .attr('stroke', (d: GraphNode) => {
        if (d.id === selectedId) return 'var(--orange, #ef6a2a)';
        return d.doc ? 'rgba(246,241,234,0.2)' : 'none';
      })
      .attr('stroke-width', (d: GraphNode) => d.id === selectedId ? 2.5 : 1);
  }, [selectedId]);

  return (
    <div ref={containerRef} style={{ position: 'absolute', inset: 0 }}>
      {/* Dot-grid background matching handoff */}
      <div className="graph__canvas-bg" />

      <svg
        ref={svgRef}
        className="graph__svg"
        style={{ cursor: 'grab' }}
      />

      {tooltip && (
        <div
          className="absolute z-[100] rounded-lg pointer-events-none text-xs"
          style={{
            left: tooltip.x,
            top: tooltip.y,
            background: 'var(--bg-1)',
            border: '1px solid var(--line-1)',
            padding: '7px 12px',
            color: 'var(--ink-0)',
            maxWidth: 200,
            lineHeight: 1.45,
          }}
        >
          <div className="font-semibold mb-0.5">{tooltip.name}</div>
          <div style={{ color: 'var(--ink-2)', fontSize: 11 }}>{tooltip.meta}</div>
        </div>
      )}
    </div>
  );
}

interface NodeDetailPanelProps {
  node: GraphNode;
  links: GraphLink[];
  onClose: () => void;
}

// Severity → CSS var token mapping (resolved hexes as fallbacks)
const SEVERITY_TOKEN: Record<string, { color: string; bg: string; border: string }> = {
  low:      { color: 'var(--success, #5eb86a)',  bg: 'rgba(94,184,106,0.08)',  border: 'rgba(94,184,106,0.25)'  },
  medium:   { color: 'var(--warning, #d9b03d)',  bg: 'rgba(217,176,61,0.08)',  border: 'rgba(217,176,61,0.25)'  },
  high:     { color: 'var(--orange, #ef6a2a)',   bg: 'rgba(239,106,42,0.08)',  border: 'rgba(239,106,42,0.25)'  },
  critical: { color: 'var(--danger, #d94a4a)',   bg: 'rgba(217,74,74,0.08)',   border: 'rgba(217,74,74,0.25)'   },
};

function NodeDetailPanel({ node, links, onClose }: NodeDetailPanelProps) {
  const connections = links
    .filter(l => (l.source as GraphNode).id === node.id || (l.target as GraphNode).id === node.id)
    .map(l => {
      const other = (l.source as GraphNode).id === node.id
        ? l.target as GraphNode
        : l.source as GraphNode;
      return other;
    });

  const isGap = node.nodeType === 'gap';
  const sevTokens = node.severity ? SEVERITY_TOKEN[node.severity] : null;
  const dotColor = CAT_COLOR[node.cat] ?? COMPLIANCE_TYPE_COLOR[node.nodeType ?? ''] ?? 'var(--ink-3)';

  return (
    <div className="graph__focus glow-behind" style={{ bottom: 'auto', top: 62, left: 'auto', right: 22, width: 320, maxHeight: 'calc(100% - 90px)', overflowY: 'auto' }}>
      {/* Header */}
      <div className="graph__focus-head">
        <span
          className="srcrow__dot"
          style={{ background: dotColor, width: 8, height: 8, borderRadius: '50%', display: 'inline-block' }}
        />
        <span className="mono-label mono-label--ink" style={{ fontSize: 10, letterSpacing: '0.12em' }}>
          {node.nodeType ?? node.cat}
        </span>
        {isGap && node.severity && (
          <span
            className="chip chip--sm"
            style={{ marginLeft: 'auto', color: sevTokens?.color, borderColor: sevTokens?.border, background: sevTokens?.bg, fontSize: 10, padding: '2px 7px' }}
          >
            {node.severity}
          </span>
        )}
        <button
          onClick={onClose}
          className="btn btn--icon btn--ghost"
          style={{ width: 24, height: 24, marginLeft: isGap && node.severity ? 6 : 'auto', color: 'var(--ink-2)', border: '1px solid var(--line-1)' }}
          aria-label="Close"
        >
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>

      {/* Title */}
      <div className="graph__focus-title" style={{ fontSize: isGap ? 18 : 20 }}>
        {node.label}
      </div>

      {/* Gap mono label */}
      {isGap && (
        <div className="mono-label" style={{ marginBottom: 8, color: 'var(--danger, #d94a4a)' }}>
          GAP{node.severity ? ` · SEVERITY ${node.severity.toUpperCase()}` : ''}
        </div>
      )}

      {/* Recommended action */}
      {isGap && node.recommendedAction && (
        <div className="graph__focus-body">
          {node.recommendedAction}
        </div>
      )}

      {/* Stats row */}
      <div className="graph__focus-stats" style={{ gridTemplateColumns: node.updated ? 'repeat(3, 1fr)' : 'repeat(2, 1fr)' }}>
        {node.updated && (
          <div>
            <span className="mono-label">UPDATED</span>
            <span>{node.updated}</span>
          </div>
        )}
        <div>
          <span className="mono-label">TYPE</span>
          <span style={{ textTransform: 'capitalize' }}>{node.doc ? 'Document' : 'Concept'}</span>
        </div>
        <div>
          <span className="mono-label">LINKS</span>
          <span>{connections.length}</span>
        </div>
      </div>

      {/* Resolve action for gap nodes */}
      {isGap && (
        <div className="graph__focus-actions" style={{ marginTop: 4, marginBottom: 14 }}>
          <button className="btn btn--orange-hollow btn--sm">Mark resolved</button>
          <button className="btn btn--sm btn--ghost">Dismiss</button>
        </div>
      )}

      {/* Connections */}
      {connections.length > 0 && (
        <div>
          <div className="mono-label" style={{ marginBottom: 10 }}>CONNECTIONS ({connections.length})</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            {connections.map(conn => (
              <div
                key={conn.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  padding: '7px 10px',
                  background: 'var(--bg-2)',
                  border: '1px solid var(--line-0)',
                  borderRadius: 'var(--r-sm)',
                }}
              >
                <span
                  style={{
                    width: 6,
                    height: 6,
                    borderRadius: '50%',
                    background: CAT_COLOR[conn.cat] ?? COMPLIANCE_TYPE_COLOR[conn.nodeType ?? ''] ?? 'var(--ink-3)',
                    flexShrink: 0,
                    display: 'inline-block',
                  }}
                />
                <span style={{ fontSize: 12, color: 'var(--ink-1)', flex: 1 }}>{conn.label}</span>
                <span className="mono-label" style={{ fontSize: 10 }}>{conn.nodeType ?? conn.cat}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Override CAT_COLOR lookup when in live mode by injecting an ephemeral key. */
function buildCatKey(type: string): string {
  return `__compliance_${type}`;
}

// ─── Main page ────────────────────────────────────────────────────────────────

export default function GraphPage() {
  const { code, id } = useParams<{ code?: string; id?: string }>();
  const isLive = Boolean(code && id);
  const navigate = useNavigate();
  const { requireJudge, modal } = useJudgesGate();

  const [nodes, setNodes] = useState<GraphNode[]>(isLive ? [] : MOCK_NODES);
  const [links, setLinks] = useState<GraphLink[]>(isLive ? [] : MOCK_LINKS);
  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);
  const [loading, setLoading] = useState(isLive);
  const [error, setError] = useState<string | null>(null);
  const [launchName, setLaunchName] = useState<string>(id ?? '');
  const [rerunning, setRerunning] = useState(false);
  const [rerunMsg, setRerunMsg] = useState<string | null>(null);

  // Inject compliance-type colors into CAT_COLOR at runtime (once)
  useEffect(() => {
    if (isLive) {
      CAT_COLOR['__compliance_obligation'] = COMPLIANCE_TYPE_COLOR.obligation;
      CAT_COLOR['__compliance_control']    = COMPLIANCE_TYPE_COLOR.control;
      CAT_COLOR['__compliance_gap']        = COMPLIANCE_TYPE_COLOR.gap;
      CAT_COLOR['__compliance_evidence']   = COMPLIANCE_TYPE_COLOR.evidence;
    }
  }, [isLive]);

  const fetchComplianceMap = useCallback(() => {
    setLoading(true);
    setError(null);

    getComplianceMap(id!, code!)
      .then(payload => {
        const mappedNodes: GraphNode[] = payload.nodes.map(n => ({
          id: n.id,
          label: n.label,
          cat: buildCatKey(n.type),
          doc: n.type === 'evidence' || n.type === 'control',
          size: n.type === 'gap' ? 11 : n.type === 'obligation' ? 13 : 10,
          nodeType: n.type,
          severity: n.severity,
          recommendedAction: n.recommendedAction,
        }));
        const nodeIds = new Set(mappedNodes.map(n => n.id));
        const mappedLinks: GraphLink[] = payload.edges
          .map(e => {
            let source = e.source;
            let target = e.target;
            if ((!source || !target) && e.id) {
              const idx = e.id.indexOf('->');
              if (idx > 0) {
                source = source ?? e.id.slice(0, idx);
                target = target ?? e.id.slice(idx + 2);
              }
            }
            return source && target ? { source, target } : null;
          })
          .filter((l): l is { source: string; target: string } => l !== null && nodeIds.has(l.source) && nodeIds.has(l.target));
        setNodes(mappedNodes);
        setLinks(mappedLinks);
        setLoading(false);
      })
      .catch(err => {
        setError(err instanceof Error ? err.message : 'Failed to load compliance graph');
        setLoading(false);
      });
  }, [id, code]);

  // Live mode: fetch compliance map + launch name in parallel
  useEffect(() => {
    if (!isLive) {
      // Legacy path: fetch from portal API (existing behavior)
      getGraph()
        .then((data) => {
          const apiNodes = (data.nodes as ApiGraphNode[]).map((n): GraphNode => ({
            id: n.id,
            label: n.label,
            cat: n.cat,
            doc: n.doc,
            size: n.size,
            updated: n.updated || undefined,
          }));
          const apiLinks = (data.links as ApiGraphLink[]).map((l): GraphLink => ({
            source: l.source,
            target: l.target,
          }));
          setNodes(apiNodes);
          setLinks(apiLinks);
        })
        .catch(() => {
          console.warn('GraphPage: API unavailable, using mock data');
        });
      return;
    }

    // Fetch launch name non-blocking (fallback to id)
    getLaunch(id!)
      .then(detail => setLaunchName(detail.launch.name))
      .catch(() => { /* keep id as fallback */ });

    fetchComplianceMap();
  }, [code, id, isLive, fetchComplianceMap]);

  const handleNodeClick = useCallback((node: GraphNode) => {
    setSelectedNode(prev => prev?.id === node.id ? null : node);
  }, []);

  const handleClose = useCallback(() => {
    setSelectedNode(null);
  }, []);

  // The graph layout is full-bleed inside the AppShell frame__view.
  // .graph CSS grid: folders-col | canvas | rail-col.
  // We skip the folders and rail columns here — just canvas full-width.
  // Use a single-column grid (1fr) override so canvas fills the view.
  return (
    <div style={{ height: '100%', display: 'grid', gridTemplateColumns: '1fr', background: 'var(--bg-0)' }}>
      {modal}
      <div className="graph__canvas" style={{ position: 'relative', overflow: 'hidden' }}>

        {/* Toolbar — absolute, top of canvas */}
        <div className="graph__toolbar">
          <div className="graph__toolbar-group">
            {isLive ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <Link
                  to={`/jurisdictions/${code}`}
                  className="chip chip--sm"
                  style={{ color: 'var(--ink-2)', textDecoration: 'none' }}
                >
                  ←
                </Link>
                <span className="mono-label" style={{ color: 'var(--ink-2)' }}>
                  {jurisdictionFlag(code!)} {jurisdictionLabel(code!)} / {launchName} / Compliance Graph
                </span>
              </div>
            ) : (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Link to="/launches" className="chip chip--sm" style={{ textDecoration: 'none', color: 'var(--ink-1)' }}>
                  ← Launches
                </Link>
                <span className="mono-label">Knowledge Graph</span>
              </div>
            )}
          </div>

          <div className="graph__toolbar-group">
            <span className="mono-label">{`${nodes.length} NODES · ${links.length} LINKS`}</span>
          </div>
        </div>

        {/* Error banner */}
        {error && (
          <div
            style={{
              position: 'absolute',
              top: 64,
              left: 22,
              right: 22,
              zIndex: 10,
              padding: '8px 14px',
              borderRadius: 'var(--r-sm)',
              background: 'rgba(217,74,74,0.08)',
              border: '1px solid rgba(217,74,74,0.25)',
              color: 'var(--danger, #d94a4a)',
              fontFamily: 'var(--mono)',
              fontSize: 11,
            }}
          >
            {error}
          </div>
        )}

        {/* Legend — positioned top-right inside canvas */}
        <div className="graph__legend">
          <div className="mono-label mono-label--ink" style={{ marginBottom: 10 }}>DOCUMENT TYPES</div>
          {[
            { cat: 'terms',     color: 'var(--tc)',      label: 'Terms & Contracts'   },
            { cat: 'aml',       color: 'var(--aml)',     label: 'AML / Sanctions'     },
            { cat: 'privacy',   color: 'var(--priv)',    label: 'Privacy'             },
            { cat: 'licensing', color: 'var(--lic)',     label: 'Licensing / Reports' },
            { cat: 'concept',   color: 'var(--concept)', label: 'Concept'             },
          ].map(row => (
            <div key={row.cat} className="legrow">
              <span className="legrow__dot" style={{ background: row.color }} />
              <span className="legrow__l">{row.label}</span>
            </div>
          ))}
        </div>

        {/* Loading state */}
        {loading ? (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <span className="mono-label">Loading compliance graph…</span>
          </div>
        ) : isLive && nodes.length === 0 && !error ? (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 12,
            }}
          >
            <span className="mono-label" style={{ fontSize: 13 }}>Compliance map not yet generated</span>
            <span className="mono-label mono-label--ink" style={{ fontSize: 11 }}>
              This jurisdiction hasn't been analysed yet, or the analysis is still running.
            </span>
            <div style={{ display: 'flex', alignItems: 'center' }}>
              <button className="btn btn--sm" style={{ marginTop: 8 }} onClick={() => navigate(-1)}>
                ← Back
              </button>
              <button
                className="btn btn--orange-hollow btn--sm"
                style={{ marginTop: 8, marginLeft: 8 }}
                disabled={rerunning}
                onClick={requireJudge(async () => {
                  setRerunning(true);
                  setRerunMsg(null);
                  try {
                    await runJurisdiction(id!, code!);
                    setRerunMsg('Analysis started. This may take a minute — refreshing soon.');
                    setTimeout(() => fetchComplianceMap(), 5000);
                  } catch (err) {
                    setRerunMsg('Failed to start: ' + (err as Error).message);
                  } finally {
                    setRerunning(false);
                  }
                })}
              >
                {rerunning ? 'Starting…' : 'Rerun analysis'}
              </button>
            </div>
            {rerunMsg && (
              <span className="mono-label" style={{ fontSize: 11, marginTop: 6 }}>{rerunMsg}</span>
            )}
          </div>
        ) : (
          <>
            <GraphCanvas
              nodes={nodes}
              links={links}
              onNodeClick={handleNodeClick}
              selectedId={selectedNode?.id ?? null}
            />

            {selectedNode && (
              <NodeDetailPanel
                node={selectedNode}
                links={links}
                onClose={handleClose}
              />
            )}
          </>
        )}

        {/* Hint bar — bottom-center */}
        {!loading && (
          <div className="graph__hint">
            <span className="mono-label">DRAG TO PAN</span>
            <span className="graph__hint-dot" />
            <span className="mono-label">SCROLL TO ZOOM</span>
            <span className="graph__hint-dot" />
            <span className="mono-label">CLICK NODE TO OPEN</span>
          </div>
        )}
      </div>
    </div>
  );
}
