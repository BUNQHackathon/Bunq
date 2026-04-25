import pytest


@pytest.mark.asyncio
async def test_health_returns_up(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "UP"}


@pytest.mark.asyncio
async def test_health_requires_no_auth(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
