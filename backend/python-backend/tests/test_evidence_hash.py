import hashlib
import io
import pytest


@pytest.mark.asyncio
async def test_hash_known_bytes(client, auth_headers):
    data = b"hello"
    expected_sha256 = hashlib.sha256(data).hexdigest()
    assert expected_sha256 == "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    resp = await client.post(
        "/evidence/hash",
        headers=auth_headers,
        files={"file": ("test.bin", io.BytesIO(data), "application/octet-stream")},
        data={"content_type": "application/octet-stream", "related_mapping_id": "map-001"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["sha256"] == expected_sha256
    assert body["size"] == 5
    assert body["contentType"] == "application/octet-stream"


@pytest.mark.asyncio
async def test_hash_empty_bytes(client, auth_headers):
    data = b""
    expected = hashlib.sha256(data).hexdigest()
    resp = await client.post(
        "/evidence/hash",
        headers=auth_headers,
        files={"file": ("empty.bin", io.BytesIO(data), "text/plain")},
        data={"content_type": "text/plain", "related_mapping_id": "map-002"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["sha256"] == expected
    assert body["size"] == 0
