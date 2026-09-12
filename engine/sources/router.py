# router.py:调试直连口,Java 不依赖
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from . import fetch_source
from .base import SourceError

router = APIRouter(prefix="/v1/sources", tags=["sources"])

class ParseReq(BaseModel):
    url: str

@router.post("/parse")
async def parse(req: ParseReq):
    try:
        r = await fetch_source(req.url)
    except SourceError as e:
        raise HTTPException(400, str(e))
    return {"code": 0, "message": "success",
            "data": {"title": r.title, "site": r.site, "path": str(r.audio_path)}}