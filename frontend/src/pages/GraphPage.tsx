import * as d3 from 'd3';
import { useEffect, useRef, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { getGraph, type GraphNode as ApiGraphNode, type GraphLink as ApiGraphLink } from '../api/portal';

const CAT_COLOR: Record<string, string> = {
  terms: '#FF7819',
  aml: '#FF9F55',
  privacy: '#B08AFF',
  reports: '#5ECFA0',
  licensing: '#5ECFA0',
  pricing: '#FFD080',
  concept: '#445566',
};

interface GraphNode extends d3.SimulationNodeDatum {
  id: string;
  label: string;
  cat: string;
  doc: boolean;
  size: number;
  updated?: string;
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
  const initializedRef = useRef(false);

  useEffect(() => {
    if (nodes.length === 0 || initializedRef.current) return;
    initializedRef.current = true;
    const container = containerRef.current;
    const svgEl = svgRef.current;
    if (!container || !svgEl) return;

    const W = container.clientWidth;
    const H = container.clientHeight;

    nodes.forEach((n, i) => {
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

    const linkSel = g.append('g').selectAll<SVGLineElement, GraphLink>('line').data(links).join('line')
      .attr('stroke', 'rgba(255,255,255,0.1)')
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
          return (src === d.id || tgt === d.id) ? 'rgba(255,120,25,0.55)' : 'rgba(255,255,255,0.03)';
        });
        nodeG.select<SVGTextElement>('.node-label').attr('fill', (n: GraphNode) =>
          adj.has(n.id) ? 'rgba(255,255,255,0.92)' : 'rgba(255,255,255,0.15)'
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
        linkSel.attr('stroke', 'rgba(255,255,255,0.1)');
        nodeG.select<SVGTextElement>('.node-label').attr('fill', 'rgba(255,255,255,0.6)');
        setTooltip(null);
      })
      .on('click', (_e: MouseEvent, d: GraphNode) => {
        onNodeClick(d);
      });

    nodeG.append('circle').attr('class', 'halo')
      .attr('r', d => d.size + 10)
      .attr('fill', d => CAT_COLOR[d.cat] || '#446')
      .attr('opacity', d => d.doc ? 0.1 : 0.05);

    nodeG.append('circle').attr('class', 'core')
      .attr('r', d => d.size)
      .attr('fill', d => CAT_COLOR[d.cat] || '#446')
      .attr('filter', 'url(#glow2)')
      .attr('stroke', d => d.doc ? 'rgba(255,255,255,0.25)' : 'none')
      .attr('stroke-width', 1);

    nodeG.append('text').attr('class', 'node-label')
      .text(d => d.label)
      .attr('fill', 'rgba(255,255,255,0.6)')
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes, links]);

  useEffect(() => {
    if (!nodeGRef.current) return;
    nodeGRef.current.select<SVGCircleElement>('.core')
      .attr('stroke', (d: GraphNode) => {
        if (d.id === selectedId) return '#FF7819';
        return d.doc ? 'rgba(255,255,255,0.25)' : 'none';
      })
      .attr('stroke-width', (d: GraphNode) => d.id === selectedId ? 2.5 : 1);
  }, [selectedId]);

  return (
    <div ref={containerRef} className="absolute inset-0" style={{ background: '#080808' }}>
      <svg
        ref={svgRef}
        className="absolute inset-0 w-full h-full"
        style={{ cursor: 'grab' }}
      />

      {tooltip && (
        <div
          className="absolute z-[100] rounded-lg pointer-events-none text-xs"
          style={{
            left: tooltip.x,
            top: tooltip.y,
            background: 'rgba(13,13,13,0.95)',
            border: '1px solid rgba(255,255,255,0.08)',
            padding: '7px 12px',
            color: 'rgba(255,255,255,0.85)',
            maxWidth: 200,
            lineHeight: 1.45,
          }}
        >
          <div className="font-semibold mb-0.5">{tooltip.name}</div>
          <div style={{ color: 'rgba(255,255,255,0.4)', fontSize: 11 }}>{tooltip.meta}</div>
        </div>
      )}

      <div
        className="absolute top-5 right-5 rounded-xl z-20"
        style={{ background: 'rgba(13,13,13,0.88)', border: '1px solid rgba(255,255,255,0.06)', padding: '14px 16px', backdropFilter: 'blur(12px)' }}
      >
        <div className="text-[9px] font-bold uppercase tracking-[0.1em] mb-2.5" style={{ color: 'rgba(255,255,255,0.3)' }}>Document types</div>
        {[
          { color: '#FF7819', label: 'Terms & Conditions' },
          { color: '#FF9F55', label: 'AML / Sanctions' },
          { color: '#B08AFF', label: 'Privacy' },
          { color: '#5ECFA0', label: 'Licensing / Reports' },
          { color: '#FFD080', label: 'Pricing' },
          { color: '#445566', label: 'Concept' },
        ].map(row => (
          <div key={row.label} className="flex items-center gap-2 mb-1.5 last:mb-0">
            <div className="w-[7px] h-[7px] rounded-full flex-shrink-0" style={{ background: row.color }} />
            <span className="text-[11.5px]" style={{ color: 'rgba(255,255,255,0.5)' }}>{row.label}</span>
          </div>
        ))}
      </div>

      <div
        className="absolute bottom-5 left-1/2 -translate-x-1/2 text-[11px] pointer-events-none tracking-[0.04em]"
        style={{ color: 'rgba(255,255,255,0.2)' }}
      >
        Drag to pan · Scroll to zoom · Click a node to view details
      </div>
    </div>
  );
}

interface NodeDetailPanelProps {
  node: GraphNode;
  links: GraphLink[];
  onClose: () => void;
}

function NodeDetailPanel({ node, links, onClose }: NodeDetailPanelProps) {
  const connections = links
    .filter(l => (l.source as GraphNode).id === node.id || (l.target as GraphNode).id === node.id)
    .map(l => {
      const other = (l.source as GraphNode).id === node.id
        ? l.target as GraphNode
        : l.source as GraphNode;
      return other;
    });

  return (
    <div
      className="absolute right-0 top-0 bottom-0 z-30 flex flex-col"
      style={{
        width: 300,
        background: 'rgba(13,13,13,0.97)',
        borderLeft: '1px solid rgba(255,255,255,0.06)',
        backdropFilter: 'blur(16px)',
      }}
    >
      <div
        className="flex-shrink-0 flex items-center justify-between pt-4 pb-3 px-4"
        style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}
      >
        <div className="flex items-center gap-2">
          <div
            className="w-2 h-2 rounded-full flex-shrink-0"
            style={{ background: CAT_COLOR[node.cat] ?? '#556677' }}
          />
          <span className="font-mono text-[10px] font-bold tracking-[0.12em] uppercase" style={{ color: 'rgba(255,255,255,0.5)' }}>
            {node.cat}
          </span>
        </div>
        <button
          onClick={onClose}
          className="w-6 h-6 flex items-center justify-center rounded-full transition-all hover:brightness-125"
          style={{ background: 'rgba(255,255,255,0.06)', color: 'rgba(255,255,255,0.4)' }}
        >
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-4 flex flex-col gap-5">
        <div>
          <div className="text-base font-semibold text-white mb-1">{node.label}</div>
          {node.updated && (
            <div className="font-mono text-[10px] tracking-[0.06em]" style={{ color: 'rgba(255,255,255,0.3)' }}>
              Updated {node.updated}
            </div>
          )}
          <div
            className="mt-2 inline-flex items-center px-2.5 py-1 rounded-full text-[10px] font-mono tracking-[0.08em]"
            style={{
              background: node.doc ? 'rgba(255,120,25,0.1)' : 'rgba(255,255,255,0.05)',
              border: `1px solid ${node.doc ? 'rgba(255,120,25,0.3)' : 'rgba(255,255,255,0.1)'}`,
              color: node.doc ? '#FF9F55' : 'rgba(255,255,255,0.4)',
            }}
          >
            {node.doc ? 'Document' : 'Concept'}
          </div>
        </div>

        {connections.length > 0 && (
          <div>
            <div className="text-[9px] font-bold uppercase tracking-[0.1em] mb-3" style={{ color: 'rgba(255,255,255,0.3)' }}>
              Connections ({connections.length})
            </div>
            <div className="flex flex-col gap-1.5">
              {connections.map(conn => (
                <div
                  key={conn.id}
                  className="flex items-center gap-2.5 px-3 py-2 rounded-lg"
                  style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.05)' }}
                >
                  <div
                    className="w-1.5 h-1.5 rounded-full flex-shrink-0"
                    style={{ background: CAT_COLOR[conn.cat] ?? '#445566' }}
                  />
                  <span className="text-[12px]" style={{ color: 'rgba(255,255,255,0.7)' }}>{conn.label}</span>
                  <span
                    className="ml-auto text-[10px] font-mono"
                    style={{ color: 'rgba(255,255,255,0.25)' }}
                  >
                    {conn.cat}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export default function GraphPage() {
  const [nodes, setNodes] = useState<GraphNode[]>(MOCK_NODES);
  const [links, setLinks] = useState<GraphLink[]>(MOCK_LINKS);
  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);

  useEffect(() => {
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
  }, []);

  const handleNodeClick = useCallback((node: GraphNode) => {
    setSelectedNode(prev => prev?.id === node.id ? null : node);
  }, []);

  const handleClose = useCallback(() => {
    setSelectedNode(null);
  }, []);

  return (
    <div className="min-h-screen px-6 py-10 max-w-6xl mx-auto" style={{ color: '#E8E8E8' }}>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-white mb-1">Knowledge Graph</h1>
          <p className="font-mono text-[11px] uppercase tracking-wider" style={{ color: '#6B6B6B' }}>
            Compliance concepts and documents
          </p>
        </div>
        <Link
          to="/launches"
          className="px-4 py-2 rounded-xl text-[13px] font-medium transition-all"
          style={{
            background: 'rgba(255,120,25,0.14)',
            border: '1px solid rgba(255,120,25,0.35)',
            color: '#FF9F55',
          }}
        >
          ← Launches
        </Link>
      </div>

      <div
        className="rounded-xl overflow-hidden relative"
        style={{
          height: 660,
          background: '#0D0D0D',
          border: '1px solid rgba(255,255,255,0.06)',
        }}
      >
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

        <div
          className="absolute z-20 font-mono text-[10px] px-3 py-1.5 rounded-full tracking-[0.08em]"
          style={{
            top: 20,
            left: 20,
            background: 'rgba(13,13,13,0.92)',
            border: '1px solid rgba(255,255,255,0.08)',
            color: 'rgba(255,255,255,0.3)',
            backdropFilter: 'blur(12px)',
          }}
        >
          {`${nodes.length} NODES · ${links.length} LINKS`}
        </div>
      </div>
    </div>
  );
}
