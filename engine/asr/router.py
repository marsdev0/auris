# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import json

from fastapi import APIRouter, UploadFile, HTTPException, WebSocket, WebSocketDisconnect
from loguru import logger

from engine.asr.service import get_asr_service
from engine.asr.stream_handler import StreamHandler

router = APIRouter(prefix="/v1/asr", tags=["asr"])


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


@router.websocket("/stream")
async def stream(ws: WebSocket):
    """
    client -> server:
    { "type": "start", "config": { "lang": "zh", "provider": "whisper" } }   // 控制帧(文本)
    // binary PCM 16bit 16kHz mono,每帧 ~20-60ms                              // 音频帧(二进制,不封装 JSON)
    { "type": "stop" }

    server -> client:
    { "type": "asr_result", "is_final": true, "text": "...", "beg_ms": 1200, "end_ms": 3500 }
    { "type": "error", "code": "ASR_FAILED", "message": "..." }
    """
    await ws.accept()
    handler: StreamHandler | None = None
    try:
        while True:
            # 混合帧:必须用 receive() 拿原始 dict
            # 按 message["text"](JSON 控制帧)/ message["bytes"](音频帧)区分
            # 不要用 receive_text / receive_bytes —— 混合帧会卡死
            msg = await ws.receive()
            if msg.get("text"):
                ctrl = json.loads(msg["text"])
                if ctrl["type"] == "start":
                    handler = StreamHandler(ctrl.get("config", {}).get("provider"))
                elif ctrl["type"] == "stop" and handler:
                    final = await handler.flush()
                    if final:
                        await ws.send_text(final.model_dump_json())
                    break
            elif msg.get("bytes") and handler:
                # 二进制音频帧(PCM 16k)——必须与 text 分支平级,缩进进控制帧链里永远走不到
                for r in await handler.on_audio(msg["bytes"]):
                    await ws.send_text(r.model_dump_json())
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning(f"WS /asr/stream 异常: {e}")
