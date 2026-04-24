import httpx
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.deps import set_httpx_client
from app.routers import health, sanctions, evidence, proof_tree


@asynccontextmanager
async def lifespan(app: FastAPI):
    client = httpx.AsyncClient(timeout=30.0)
    set_httpx_client(client)
    yield
    await client.aclose()


app = FastAPI(title="LaunchLens Sidecar", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(Exception)
async def generic_handler(request: Request, exc: Exception):
    return JSONResponse(
        status_code=500,
        content={"error": "internal_error", "code": 500, "message": str(exc)},
    )


app.include_router(health.router)
app.include_router(sanctions.router)
app.include_router(evidence.router)
app.include_router(proof_tree.router)
