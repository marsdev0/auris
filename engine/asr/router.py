# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import json

from fastapi import APIRouter, UploadFile, HTTPException, WebSocket, WebSocketDisconnect, Form
from loguru import logger

from engine.asr.service import get_asr_service
from engine.asr.stream_handler import StreamHandler
from engine.asr.long_audio import tasks as long_tasks

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


@router.post("/tasks", status_code=202)
async def create_task(audio: UploadFile, provider: str | None = Form(None)):
    """提交长音频转写任务,秒回 task_id。大文件上传/限流由 gateway/ai-service 负责。"""
    data = await audio.read()
    if not data:
        raise HTTPException(400, "音频内容为空")
    # 时长护栏在 _run 的 decoding 阶段做(要先解码才知道时长);
    # 这里只挡"明显离谱"的——body 大小粗筛,避免解码 900MB 才拒绝
    if len(data) > 500 * 1024 * 1024:  # 500MB ≈ 6h PCM 上限的富余量
        raise HTTPException(413, f"文件过大: {len(data) // 1024 // 1024}MB")
    try:
        task_id = await long_tasks.submit(data, provider)
    except KeyError:
        raise HTTPException(404, f"provider 不存在: {provider}")
    return {"task_id": task_id}


@router.get("/tasks/{task_id}")
async def get_task_status(task_id: str):
    """轮询任务状态。completed 前 result 不随行(轻量),结果走 /result。"""
    task = long_tasks.get_task(task_id)
    if task is None:
        raise HTTPException(404, "任务不存在或已过期")
    return task.to_public()


@router.get("/tasks/{task_id}/result")
async def get_task_result(task_id: str):
    """取转写结果:全文 + 段级明细(时间戳/状态/耗时)。仅 completed 可用。"""
    task = long_tasks.get_task(task_id)
    if task is None:
        raise HTTPException(404, "任务不存在或已过期")
    if task.status != "completed":
        raise HTTPException(409, f"任务未完成,当前状态: {task.status}")
    if task.result is None:  # 理论不可达(completed 必有 result),防御
        raise HTTPException(500, "任务完成但结果缺失")
    return task.result.model_dump()
