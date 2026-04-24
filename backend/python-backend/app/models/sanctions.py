from __future__ import annotations

from datetime import datetime
from typing import Literal
from pydantic import BaseModel, Field


class Counterparty(BaseModel):
    name: str
    country: str | None = None
    type: Literal["individual", "company", "organization", "government", "unknown"] | None = None


class SanctionMatch(BaseModel):
    list_source: str
    entity_name: str
    aliases: list[str] = []
    match_score: float
    list_version_timestamp: datetime


class SanctionHit(BaseModel):
    counterparty: Counterparty
    match_status: Literal["clear", "flagged", "under_review"]
    hits: list[SanctionMatch]
    entity_metadata: dict[str, str] = {}


class ScreenRequest(BaseModel):
    session_id: str
    counterparties: list[Counterparty]
    brief_text: str | None = None


class ScreenResponse(BaseModel):
    results: list[SanctionHit]
