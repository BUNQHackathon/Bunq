import boto3
import pytest
from moto import mock_aws
from httpx import AsyncClient, ASGITransport


def _seed_dynamo(dyn):
    # Create tables
    for table_name, pk in [
        ("launchlens-mappings", "id"),
        ("launchlens-obligations", "id"),
        ("launchlens-controls", "id"),
        ("launchlens-evidence", "id"),
    ]:
        dyn.create_table(
            TableName=table_name,
            KeySchema=[{"AttributeName": pk, "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": pk, "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )

    dyn.Table("launchlens-mappings").put_item(Item={
        "id": "MAP-001",
        "session_id": "sess-001",
        "obligation_id": "OBL-001",
        "control_id": "CTRL-001",
        "gap_status": "satisfied",
    })
    dyn.Table("launchlens-obligations").put_item(Item={
        "id": "OBL-001",
        "session_id": "sess-001",
        "title": "GDPR Article 32",
        "source": {"regulation": "GDPR", "article": "Art 32"},
    })
    dyn.Table("launchlens-controls").put_item(Item={
        "id": "CTRL-001",
        "session_id": "sess-001",
        "title": "Encryption Policy",
        "source_doc_ref": "bunq T&C §5.3",
    })
    dyn.Table("launchlens-evidence").put_item(Item={
        "id": "EV-001",
        "session_id": "sess-001",
        "related_mapping_id": "MAP-001",
        "title": "Encryption Certificate",
    })


@pytest.mark.asyncio
async def test_proof_tree_structure():
    with mock_aws():
        from app.config import get_settings
        get_settings.cache_clear()

        dyn = boto3.resource("dynamodb", region_name="eu-central-1")
        _seed_dynamo(dyn)

        import httpx
        from app.deps import set_httpx_client
        mock_client = httpx.AsyncClient()
        set_httpx_client(mock_client)

        from app.main import app
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
            resp = await c.get(
                "/proof-tree/MAP-001",
                headers={"X-Sidecar-Token": "test-token"},
            )
        await mock_client.aclose()

    assert resp.status_code == 200, resp.text
    body = resp.json()
    nodes = body["nodes"]
    edges = body["edges"]

    assert len(nodes) >= 4  # mapping, obligation, control, evidence (+ reg/pol chunks)
    assert len(edges) >= 3  # mapping→obl, mapping→ctrl, mapping→ev

    node_types = {n["type"] for n in nodes}
    assert "mapping" in node_types
    assert "obligation" in node_types
    assert "control" in node_types
    assert "evidence" in node_types


@pytest.mark.asyncio
async def test_proof_tree_404_on_missing():
    with mock_aws():
        from app.config import get_settings
        get_settings.cache_clear()

        dyn = boto3.resource("dynamodb", region_name="eu-central-1")
        # Create table but don't seed
        dyn.create_table(
            TableName="launchlens-mappings",
            KeySchema=[{"AttributeName": "id", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "id", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )

        import httpx
        from app.deps import set_httpx_client
        mock_client = httpx.AsyncClient()
        set_httpx_client(mock_client)

        from app.main import app
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
            resp = await c.get(
                "/proof-tree/NONEXISTENT",
                headers={"X-Sidecar-Token": "test-token"},
            )
        await mock_client.aclose()

    assert resp.status_code == 404
