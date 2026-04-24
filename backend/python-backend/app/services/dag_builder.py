from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import HTTPException

from app.config import Settings
from app.models.dag import GraphDAG, GraphEdge, GraphNode

log = logging.getLogger(__name__)


async def build_proof_tree(mapping_id: str, settings: Settings, dynamodb) -> GraphDAG:
    mapping = await asyncio.to_thread(_get_item, dynamodb, settings.dynamodb_mappings_table, "id", mapping_id)
    if not mapping:
        raise HTTPException(status_code=404, detail=f"Mapping {mapping_id} not found")

    obligation_id = mapping.get("obligation_id", "")
    control_id = mapping.get("control_id", "")

    obligation, control, evidence_items = await asyncio.gather(
        asyncio.to_thread(_get_item, dynamodb, settings.dynamodb_obligations_table, "id", obligation_id),
        asyncio.to_thread(_get_item, dynamodb, settings.dynamodb_controls_table, "id", control_id),
        asyncio.to_thread(_scan_by_field, dynamodb, settings.dynamodb_evidence_table, "related_mapping_id", mapping_id),
    )

    nodes: list[GraphNode] = []
    edges: list[GraphEdge] = []

    # Root mapping node
    nodes.append(GraphNode(id=mapping_id, type="mapping", label=f"Mapping: {mapping_id}", data=_safe_dict(mapping)))

    # Obligation node + reg-chunk leaf
    if obligation:
        obl_id = obligation.get("id", obligation_id)
        nodes.append(GraphNode(id=obl_id, type="obligation", label=obligation.get("title", obl_id), data=_safe_dict(obligation)))
        edges.append(GraphEdge(id=f"{mapping_id}->{obl_id}", type="satisfies", label="satisfies"))

        source = obligation.get("source", {})
        if isinstance(source, dict):
            reg_id = f"regchunk-{obl_id}"
            reg_label = f"{source.get('regulation', '')} {source.get('article', '')}".strip() or "RegChunk"
            nodes.append(GraphNode(id=reg_id, type="regulation_chunk", label=reg_label))
            edges.append(GraphEdge(id=f"{obl_id}->{reg_id}", type="grounded_in", label="grounded_in"))

    # Control node + policy-chunk leaf
    if control:
        ctrl_id = control.get("id", control_id)
        nodes.append(GraphNode(id=ctrl_id, type="control", label=control.get("title", ctrl_id), data=_safe_dict(control)))
        edges.append(GraphEdge(id=f"{mapping_id}->{ctrl_id}", type="backed_by", label="backed_by"))

        pol_id = f"polchunk-{ctrl_id}"
        pol_label = str(control.get("source_doc_ref", "PolicyChunk"))
        nodes.append(GraphNode(id=pol_id, type="policy_chunk", label=pol_label))
        edges.append(GraphEdge(id=f"{ctrl_id}->{pol_id}", type="grounded_in", label="grounded_in"))

    # Evidence nodes
    for ev in evidence_items:
        ev_id = ev.get("id", str(id(ev)))
        nodes.append(GraphNode(id=ev_id, type="evidence", label=ev.get("title", ev_id), data=_safe_dict(ev)))
        edges.append(GraphEdge(id=f"{mapping_id}->{ev_id}", type="backed_by", label="backed_by"))

    return GraphDAG(nodes=nodes, edges=edges)


async def build_compliance_map(session_id: str, settings: Settings, dynamodb) -> GraphDAG:
    obligations, controls, mappings, gaps = await asyncio.gather(
        asyncio.to_thread(_scan_by_field, dynamodb, settings.dynamodb_obligations_table, "session_id", session_id),
        asyncio.to_thread(_scan_by_field, dynamodb, settings.dynamodb_controls_table, "session_id", session_id),
        asyncio.to_thread(_scan_by_field, dynamodb, settings.dynamodb_mappings_table, "session_id", session_id),
        asyncio.to_thread(_scan_by_field, dynamodb, settings.dynamodb_gaps_table, "session_id", session_id),
    )

    mapped_obligation_ids = {m.get("obligation_id") for m in mappings}

    nodes: list[GraphNode] = []
    edges: list[GraphEdge] = []

    for obl in obligations:
        obl_id = obl.get("id", "")
        has_gap = obl_id not in mapped_obligation_ids
        nodes.append(GraphNode(
            id=obl_id,
            type="obligation",
            label=obl.get("title", obl_id),
            data={"gap": has_gap, "status": "red" if has_gap else "green", **_safe_dict(obl)},
        ))

    for ctrl in controls:
        ctrl_id = ctrl.get("id", "")
        nodes.append(GraphNode(id=ctrl_id, type="control", label=ctrl.get("title", ctrl_id), data=_safe_dict(ctrl)))

    for m in mappings:
        obl_id = m.get("obligation_id", "")
        ctrl_id = m.get("control_id", "")
        edge_id = f"{obl_id}->{ctrl_id}"
        edges.append(GraphEdge(
            id=edge_id,
            type="mapping",
            label=m.get("gap_status", "satisfied"),
            data={"gap_status": m.get("gap_status", "")},
        ))

    return GraphDAG(nodes=nodes, edges=edges)


def _get_item(dynamodb, table_name: str, pk_name: str, pk_value: str) -> dict | None:
    table = dynamodb.Table(table_name)
    resp = table.get_item(Key={pk_name: pk_value})
    return resp.get("Item")


def _scan_by_field(dynamodb, table_name: str, field: str, value: str) -> list[dict]:
    from boto3.dynamodb.conditions import Attr
    table = dynamodb.Table(table_name)
    resp = table.scan(FilterExpression=Attr(field).eq(value))
    return resp.get("Items", [])


def _safe_dict(item: Any) -> dict:
    if not isinstance(item, dict):
        return {}
    return {k: str(v) for k, v in item.items() if isinstance(v, (str, int, float, bool))}
