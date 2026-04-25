import os
import boto3
import pytest
import pytest_asyncio
from moto import mock_aws
from httpx import AsyncClient, ASGITransport


TABLE_NAME = "launchlens-sanctions-entities"


def _create_table(dynamodb_resource):
    dynamodb_resource.create_table(
        TableName=TABLE_NAME,
        KeySchema=[{"AttributeName": "id", "KeyType": "HASH"}],
        AttributeDefinitions=[{"AttributeName": "id", "AttributeType": "S"}],
        BillingMode="PAY_PER_REQUEST",
    )
    table = dynamodb_resource.Table(TABLE_NAME)
    table.put_item(Item={
        "id": "OFAC_SDN#001",
        "entity_name": "Zeta",
        "entity_name_normalized": "zeta",
        "list_source": "OFAC_SDN",
        "aliases": ["Zeta GmbH"],
        "country": "RU",
        "type": "company",
        "list_entry_id": "sdn-001",
        "list_version_timestamp": "2026-04-22T08:00:00Z",
    })
    return table


@pytest.mark.asyncio
async def test_flagged_on_local_match():
    """Screen 'Zeta GmbH' — should be flagged via local DynamoDB match (cache-only mode)."""
    with mock_aws():
        import importlib
        from app.config import get_settings
        get_settings.cache_clear()

        dyn = boto3.resource("dynamodb", region_name="eu-central-1")
        _create_table(dyn)

        import httpx
        from app.deps import set_httpx_client
        mock_client = httpx.AsyncClient()
        set_httpx_client(mock_client)

        from app.main import app
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
            resp = await c.post(
                "/sanctions/screen",
                headers={"X-Sidecar-Token": "test-token"},
                json={
                    "session_id": "sess-001",
                    "counterparties": [{"name": "Zeta GmbH"}],
                },
            )
        await mock_client.aclose()

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert len(body["results"]) == 1
    result = body["results"][0]
    assert result["match_status"] == "flagged"
    assert len(result["hits"]) >= 1
    assert result["hits"][0]["list_source"] == "OFAC_SDN"


@pytest.mark.asyncio
async def test_clear_on_no_match():
    """Screen 'RandomClean Corp' — should be clear."""
    with mock_aws():
        from app.config import get_settings
        get_settings.cache_clear()

        dyn = boto3.resource("dynamodb", region_name="eu-central-1")
        _create_table(dyn)

        import httpx
        from app.deps import set_httpx_client
        mock_client = httpx.AsyncClient()
        set_httpx_client(mock_client)

        from app.main import app
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
            resp = await c.post(
                "/sanctions/screen",
                headers={"X-Sidecar-Token": "test-token"},
                json={
                    "session_id": "sess-002",
                    "counterparties": [{"name": "RandomClean Corp"}],
                },
            )
        await mock_client.aclose()

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert len(body["results"]) == 1
    assert body["results"][0]["match_status"] == "clear"
