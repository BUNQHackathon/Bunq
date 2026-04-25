import pytest


SECURED_ENDPOINTS = [
    ("POST", "/sanctions/screen", {"session_id": "s1", "counterparties": []}),
    ("POST", "/evidence/hash", None),
    ("GET", "/proof-tree/map-001", None),
    ("GET", "/compliance-map/sess-001", None),
]


@pytest.mark.asyncio
async def test_no_token_returns_401(client):
    resp = await client.post(
        "/sanctions/screen",
        json={"session_id": "s1", "counterparties": []},
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_wrong_token_returns_401(client):
    resp = await client.post(
        "/sanctions/screen",
        json={"session_id": "s1", "counterparties": []},
        headers={"X-Sidecar-Token": "wrong-token"},
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_wrong_bearer_returns_401(client):
    resp = await client.post(
        "/sanctions/screen",
        json={"session_id": "s1", "counterparties": []},
        headers={"Authorization": "Bearer wrong-token"},
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_correct_x_sidecar_token_passes(client, auth_headers):
    resp = await client.post(
        "/sanctions/screen",
        json={"session_id": "s1", "counterparties": []},
        headers=auth_headers,
    )
    # May return 500 due to DynamoDB not being mocked here, but NOT 401
    assert resp.status_code != 401


@pytest.mark.asyncio
async def test_correct_bearer_passes(client):
    resp = await client.post(
        "/sanctions/screen",
        json={"session_id": "s1", "counterparties": []},
        headers={"Authorization": "Bearer test-token"},
    )
    assert resp.status_code != 401
