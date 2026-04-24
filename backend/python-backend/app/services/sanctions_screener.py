from __future__ import annotations

import asyncio
import hashlib
import logging
import re
from datetime import datetime, timezone

import httpx
from rapidfuzz.distance import JaroWinkler

from app.config import Settings
from app.models.sanctions import Counterparty, SanctionHit, SanctionMatch

log = logging.getLogger(__name__)

_LEGAL_SUFFIXES = re.compile(
    r"\b(inc|llc|ltd|gmbh|s\.a\.|s\.a\.r\.l|plc|ag|co|corp|limited)\b",
    re.IGNORECASE,
)
_PUNCTUATION = re.compile(r"[^\w\s]")


def normalize_name(name: str) -> str:
    name = name.lower()
    name = _PUNCTUATION.sub(" ", name)
    name = _LEGAL_SUFFIXES.sub("", name)
    return " ".join(name.split()).strip()


def _hit_id(session_id: str, name: str) -> str:
    raw = session_id + normalize_name(name)
    return hashlib.sha1(raw.encode()).hexdigest()[:16]


async def screen(
    counterparty: Counterparty,
    session_id: str,
    settings: Settings,
    dynamodb,
    httpx_client: httpx.AsyncClient,
) -> SanctionHit:
    normalized = normalize_name(counterparty.name)
    hits: list[SanctionMatch] = []
    os_best_score: float = 0.0

    # 1. OpenSanctions API (if live API enabled and key present)
    if settings.sanctions_use_live_api and settings.opensanctions_api_key:
        os_hits, os_best_score = await _screen_opensanctions(
            counterparty, normalized, settings, httpx_client
        )
        hits.extend(os_hits)

    # 2. Local DynamoDB fuzzy lookup
    local_hits = await asyncio.to_thread(
        _screen_local, normalized, settings, dynamodb
    )
    hits.extend(local_hits)

    # 3. Determine match_status
    local_best = max((h.match_score for h in local_hits), default=0.0)
    best_score = max(os_best_score, local_best)

    if best_score >= 0.9:
        match_status = "flagged"
    elif best_score >= 0.7:
        match_status = "under_review"
    else:
        match_status = "clear"

    return SanctionHit(
        counterparty=counterparty,
        match_status=match_status,
        hits=hits,
    )


async def _screen_opensanctions(
    counterparty: Counterparty,
    normalized: str,
    settings: Settings,
    httpx_client: httpx.AsyncClient,
) -> tuple[list[SanctionMatch], float]:
    schema = "Person" if counterparty.type == "individual" else "Company"
    properties: dict = {"name": [counterparty.name]}
    if counterparty.country:
        properties["country"] = [counterparty.country]

    payload = {
        "queries": {
            "q1": {"schema": schema, "properties": properties}
        }
    }
    try:
        resp = await httpx_client.post(
            f"{settings.opensanctions_base_url}/match/default",
            json=payload,
            headers={"Authorization": f"ApiKey {settings.opensanctions_api_key}"},
            timeout=10.0,
        )
        resp.raise_for_status()
        data = resp.json()
    except Exception as exc:
        log.warning("OpenSanctions request failed: %s", exc)
        return [], 0.0

    hits: list[SanctionMatch] = []
    best_score: float = 0.0
    results = data.get("responses", {}).get("q1", {}).get("results", [])
    for r in results:
        score = float(r.get("score", 0))
        best_score = max(best_score, score)
        caption = r.get("caption", normalized)
        aliases = [a.get("value", "") if isinstance(a, dict) else str(a) for a in r.get("properties", {}).get("alias", [])]
        hits.append(
            SanctionMatch(
                list_source="OpenSanctions",
                entity_name=caption,
                aliases=aliases,
                match_score=score,
                list_version_timestamp=datetime.now(timezone.utc),
            )
        )
    return hits, best_score


def _screen_local(
    normalized: str,
    settings: Settings,
    dynamodb,
) -> list[SanctionMatch]:
    table = dynamodb.Table(settings.dynamodb_sanctions_entities_table)
    try:
        from boto3.dynamodb.conditions import Attr
        response = table.scan(
            FilterExpression=Attr("entity_name_normalized").contains(normalized[:6] if len(normalized) >= 6 else normalized),
            Limit=100,
        )
        items = response.get("Items", [])
    except Exception as exc:
        log.warning("Local sanctions DynamoDB scan failed: %s", exc)
        return []

    hits: list[SanctionMatch] = []
    for item in items:
        candidate = item.get("entity_name_normalized", "")
        similarity = JaroWinkler.similarity(normalized, candidate)
        if similarity >= settings.fuzzy_threshold:
            ts_raw = item.get("list_version_timestamp", "")
            try:
                ts = datetime.fromisoformat(ts_raw.replace("Z", "+00:00"))
            except Exception:
                ts = datetime.now(timezone.utc)
            hits.append(
                SanctionMatch(
                    list_source=item.get("list_source", "local"),
                    entity_name=item.get("entity_name", candidate),
                    aliases=item.get("aliases", []),
                    match_score=similarity,
                    list_version_timestamp=ts,
                )
            )
    return hits
