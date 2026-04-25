import os
import boto3
import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport
from moto import mock_aws

# Must set SIDECAR_TOKEN before importing app
os.environ.setdefault("SIDECAR_TOKEN", "test-token")
os.environ.setdefault("AWS_DEFAULT_REGION", "eu-central-1")
os.environ.setdefault("AWS_ACCESS_KEY_ID", "test")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "test")


@pytest.fixture(scope="session", autouse=True)
def aws_credentials():
    os.environ["AWS_DEFAULT_REGION"] = "eu-central-1"
    os.environ["AWS_ACCESS_KEY_ID"] = "test"
    os.environ["AWS_SECRET_ACCESS_KEY"] = "test"


@pytest.fixture
def settings():
    from app.config import get_settings
    get_settings.cache_clear()
    s = get_settings()
    return s


@pytest.fixture
def auth_headers():
    return {"X-Sidecar-Token": "test-token"}


@pytest_asyncio.fixture
async def client():
    from app.config import get_settings
    from app.deps import set_httpx_client
    import httpx

    get_settings.cache_clear()

    # Provide a real httpx client so lifespan works
    http_client = httpx.AsyncClient()
    set_httpx_client(http_client)

    from app.main import app
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c
    await http_client.aclose()
