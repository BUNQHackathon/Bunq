from fastapi import APIRouter, Depends

from app.config import get_settings
from app.deps import get_dynamodb, require_bearer
from app.models.dag import GraphDAG
from app.services.dag_builder import build_compliance_map, build_proof_tree

router = APIRouter()


@router.get("/proof-tree/{mapping_id}", response_model=GraphDAG, dependencies=[Depends(require_bearer)])
async def get_proof_tree(mapping_id: str) -> GraphDAG:
    settings = get_settings()
    dynamodb = get_dynamodb(settings)
    return await build_proof_tree(mapping_id, settings, dynamodb)


@router.get("/compliance-map/{session_id}", response_model=GraphDAG, dependencies=[Depends(require_bearer)])
async def get_compliance_map(session_id: str) -> GraphDAG:
    settings = get_settings()
    dynamodb = get_dynamodb(settings)
    return await build_compliance_map(session_id, settings, dynamodb)
