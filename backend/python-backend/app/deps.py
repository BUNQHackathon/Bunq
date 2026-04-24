import boto3
import httpx
from fastapi import Header, HTTPException, status
from functools import lru_cache
from typing import Annotated

from app.config import Settings, get_settings


def get_dynamodb(settings: Settings = None):
    if settings is None:
        settings = get_settings()
    return boto3.resource("dynamodb", region_name=settings.aws_region)


_httpx_client: httpx.AsyncClient | None = None


def set_httpx_client(client: httpx.AsyncClient) -> None:
    global _httpx_client
    _httpx_client = client


def get_httpx_client() -> httpx.AsyncClient:
    if _httpx_client is None:
        raise RuntimeError("httpx client not initialised")
    return _httpx_client


def require_bearer(
    x_sidecar_token: Annotated[str | None, Header()] = None,
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    """Accept either X-Sidecar-Token (Java caller) or Authorization: Bearer <token> (generic)."""
    settings = get_settings()
    expected = settings.sidecar_token

    # Java sends X-Sidecar-Token
    if x_sidecar_token is not None:
        if x_sidecar_token == expected:
            return
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")

    # Generic Bearer
    if authorization is not None:
        if authorization == f"Bearer {expected}":
            return
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")

    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing token")
