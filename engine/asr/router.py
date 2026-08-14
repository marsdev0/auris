# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
from fastapi import APIRouter, UploadFile, HTTPException

from engine.asr.service import get_asr_service

router = APIRouter(tags=["asr"])


@router.post("/transcribe")
async def transcribe(audio: UploadFile, provider: str | None = None):
    data = await audio.read()
    if not data:
        raise HTTPException(400, "音频内容为空")
    try:
        res = await get_asr_service().get(provider).transcribe(data)
    except KeyError:
        raise HTTPException(404, f"provider 不存在: {provider}")
    except Exception as e:
        raise HTTPException(400, f"音频解码或转写失败: {e}")
    return res.model_dump()
