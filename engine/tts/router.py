from fastapi import APIRouter, Response
from pydantic import BaseModel
from fastapi.responses import JSONResponse
from loguru import logger

from engine.tts.service import get_tts_service

router = APIRouter(prefix="/v1/tts", tags=["tts"])

_MAX_TEXT = 500


class SynthesizeReq(BaseModel):
    text: str
    voice: str | None = None


@router.post("/synthesize")
async def synthesize(req: SynthesizeReq):
    text = req.text.strip()
    if not text or len(text) > _MAX_TEXT:
        return JSONResponse(status_code=400, content={
            "code": 1, "message": f"text 需为 1~{_MAX_TEXT} 字", "data": None})
    service = get_tts_service()
    provider = service.get()  # provider 选择参数等 BYOK/D4 需要时再加
    try:
        result = await provider.synthesize(text, req.voice)
    except Exception:
        logger.exception(f"tts synth fail provider={provider.name}")
        return JSONResponse(status_code=502, content={
            "code": 1, "message": f"合成失败: {provider.name}", "data": None})
    logger.info(f"tts synth ok provider={provider.name} {len(result.audio)}B")
    return Response(content=result.audio, media_type=result.content_type)
