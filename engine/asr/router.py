# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import asyncio
import json
from typing import AsyncIterator

from fastapi import APIRouter, UploadFile, HTTPException, WebSocket, WebSocketDisconnect, Form
from loguru import logger

from engine.asr.provider import AsrCapability
from engine.asr.service import get_asr_service
from engine.asr.stream_handler import StreamHandler
from engine.asr.long_audio import tasks as long_tasks

"""
ASR 三条入口,按场景选:
- POST /transcribe   短音频:同步阻塞,当场返回全文
- WS   /stream       实时麦克风流式:边说边出
- POST /tasks        长音频:异步任务,202 秒回 task_id,轮询取结果
"""
router = APIRouter(prefix="/v1/asr", tags=["asr"])

@router.post("/transcribe")
async def transcribe(audio: UploadFile, provider: str | None = Form(None)):
    """短音频同步转写:请求阻塞到转写完成,直接返回全文(text/segments/language)。
    与 /tasks 的区别:本接口全程挂着连接等结果,适合秒级~分钟级音频;
    长音频请走 POST /tasks(异步,秒回 task_id,轮询获取)。"""
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
async def asr_stream(ws: WebSocket):
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

    first_control = await ws.receive()
    ctrl = json.loads(first_control["text"])
    config = ctrl.get("config", {})
    provider = get_asr_service().get(config.get("provider"))
    if AsrCapability.STREAMING not in provider.capabilities:
        # 伪流式
        handler = StreamHandler(config.get("provider"))
        try:
            while True:
                msg = await ws.receive()
                if msg.get("text"):
                    c = json.loads(msg["text"])
                    if c["type"] == "stop":
                        final = await handler.flush()
                        if final:
                            await ws.send_text(final.model_dump_json())
                        break
                elif msg.get("bytes"):
                    for r in await handler.on_audio(msg["bytes"]):
                        await ws.send_text(r.model_dump_json())
        except WebSocketDisconnect:
            pass
        return

    # 真流式
    audio_q: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=64)
    async def _pipe() -> AsyncIterator[bytes]:
        while True:
            item = await audio_q.get()
            if item is None:
                return
            yield item

    async def _reader():
        try:
            while True:
                msg = await ws.receive()
                if msg.get("text"):
                    if json.loads(msg["text"]).get("type") == "stop":
                        await audio_q.put(None)
                        return
                elif msg.get("bytes"):
                    await audio_q.put(msg["bytes"])
        except WebSocketDisconnect:
            logger.info("真流式客户端断连,投哨兵收尾云会话")
            pass
        finally:
            # 手动设置哨兵，_pipe就会结束，不至于一直卡在audio_q.get()
            await audio_q.put(None)

    reader = asyncio.create_task(_reader())
    try:
        async for r in provider.stream(_pipe()):
            await ws.send_text(r.model_dump_json())
    except WebSocketDisconnect:
        # 客户端已断，错误帧无处可发，直接走到finally
        logger.info("真流式收尾: 客户端断连(发送路径), 静默关闭")
        pass
    except Exception as e:
        # 原异常先落日志——except 里再抛的新异常会顶掉它,先记下来才不会丢病因
        logger.warning(f"WS /asr/stream 真流式异常: {e}")
        try:
            await ws.send_text(json.dumps({
                "type": "error",
                "code": "ASR_FAILED",
                "message": str(e)
            }))
        except Exception as send_err:
            # 客户端恰好也断了，错误帧发不出去——不算新错误,只 log,别让二次异常顶掉原异常
            logger.info(f"错误帧发送失败(客户端已断): {send_err}")
    finally:
        reader.cancel()

@router.post("/tasks", status_code=202)
async def create_task(audio: UploadFile, provider: str | None = Form(None)):
    """提交长音频转写任务,秒回 task_id,后台跑完整管线。
    与 /transcribe 的区别:异步——立即 202,结果轮询 GET /tasks/{id} 获取。
    大文件上传/限流由 gateway/ai-service 负责。"""
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
    """轮询任务状态;completed 时 result 随行一次拿全(未完成时不含 result,轮询保持轻量)。"""
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
