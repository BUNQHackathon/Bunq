import asyncio
import logging

from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.deps import get_dynamodb, get_httpx_client, require_bearer
from app.models.sanctions import ScreenRequest, ScreenResponse, SanctionHit
from app.services.sanctions_screener import screen

log = logging.getLogger(__name__)
router = APIRouter()

_MAX_PARALLEL = 20


@router.post("/sanctions/screen", response_model=ScreenResponse, dependencies=[Depends(require_bearer)])
async def screen_sanctions(body: ScreenRequest) -> ScreenResponse:
    settings: Settings = get_settings()
    dynamodb = get_dynamodb(settings)
    httpx_client = get_httpx_client()

    if body.brief_text:
        log.warning("brief_text supplied but NER stub is not implemented — ignoring brief_text")

    sem = asyncio.Semaphore(_MAX_PARALLEL)

    async def _screen_one(cp):
        async with sem:
            return await screen(cp, body.session_id, settings, dynamodb, httpx_client)

    results: list[SanctionHit] = await asyncio.gather(*[_screen_one(cp) for cp in body.counterparties])
    return ScreenResponse(results=results)
