import hashlib
import logging

from fastapi import APIRouter, Depends, Form, UploadFile, File
from fastapi.responses import JSONResponse

from app.deps import require_bearer

log = logging.getLogger(__name__)
router = APIRouter()


@router.post("/evidence/hash", dependencies=[Depends(require_bearer)])
async def hash_evidence(
    file: UploadFile = File(...),
    content_type: str = Form(...),
    related_mapping_id: str = Form(...),
) -> JSONResponse:
    body = await file.read()
    sha256 = hashlib.sha256(body).hexdigest()
    log.debug("Hashed evidence for mapping %s: sha256=%s size=%d", related_mapping_id, sha256, len(body))
    # Return contentType (camelCase) to match Java EvidenceHashResult field name
    return JSONResponse(content={"sha256": sha256, "size": len(body), "contentType": content_type})
