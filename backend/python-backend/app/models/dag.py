from __future__ import annotations

from typing import Any
from pydantic import BaseModel


class GraphNode(BaseModel):
    id: str
    type: str
    label: str
    data: dict[str, Any] = {}


class GraphEdge(BaseModel):
    id: str
    type: str
    label: str | None = None
    data: dict[str, Any] = {}


class GraphDAG(BaseModel):
    nodes: list[GraphNode]
    edges: list[GraphEdge]
