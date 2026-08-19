# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""长音频任务表 + 编排(形态④管线第四步)。

submit() 秒回 task_id,后台协程跑完整管线:
  短(< 阈值)→ 整段 provider.transcribe()   (复用①非流式,走全局信号量)
  长(≥ 阈值)→ Segmenter → Scheduler → Assembler

状态机:pending → decoding → segmenting → transcribing → completed / failed
进度:transcribing 按段完成比例推进(细粒度);其余阶段标阶段切换(粗粒度)。

内存态,MVP 取舍:进程重启任务即丢,持久化/断点续跑是触发式 L3 不预做
(触发条件见设计文档 §8)。TTL 清理防泄漏。
"""
import asyncio
import time
from typing import Literal
from uuid import uuid4

import numpy as np
from loguru import logger
from pydantic import BaseModel

from engine.asr.audio import load_audio, pcm_to_wav
from engine.asr.long_audio.assembler import Assembler, LongTaskResult
from engine.asr.long_audio.scheduler import Scheduler, _global_sem
from engine.asr.long_audio.segmenter import Segmenter
from engine.asr.service import get_asr_service
from engine.config import Settings

_SR = Settings.ASR_SAMPLE_RATE


class LongTask(BaseModel):
    task_id: str
    status: Literal["pending", "decoding", "segmenting", "transcribing",
                    "completed", "failed"] = "pending"
    progress: float = 0.0                 # 0~100
    total_segments: int = 0
    done_segments: int = 0
    failed_segments: int = 0
    error: str | None = None
    result: LongTaskResult | None = None
    created_at: float
    updated_at: float

    def to_public(self) -> dict:
        """状态查询视图:completed 前不携带 result(轮询轻量,结果单独取)。"""
        d = self.model_dump(exclude={"result"})
        if self.status == "completed":
            d["result"] = self.result.model_dump() if self.result else None
        return d


# ---- 内存任务表(模块级单例;TTL 清理见 cleanup_expired) ----
_tasks: dict[str, LongTask] = {}
_TTL_S = Settings.ASR_TASK_TTL_H * 3600


def get_task(task_id: str) -> LongTask | None:
    return _tasks.get(task_id)


def _set(task_id: str, status: str | None = None, progress: float | None = None, **kw) -> None:
    t = _tasks.get(task_id)
    if t is None:
        return
    if status is not None:
        t.status = status
    if progress is not None:
        t.progress = progress
    for k, v in kw.items():
        setattr(t, k, v)
    t.updated_at = time.time()


async def submit(audio: bytes, provider_name: str | None = None) -> str:
    """建任务 + 后台跑,立即返回 task_id。音频字节全程驻内存(上限见 _run 护栏)。"""
    now = time.time()
    task_id = uuid4().hex
    _tasks[task_id] = LongTask(task_id=task_id, created_at=now, updated_at=now)
    asyncio.create_task(_run(task_id, audio, provider_name))
    return task_id


async def _run(task_id: str, audio: bytes, provider_name: str | None) -> None:
    """任务执行体。任何阶段异常 → failed(错误信息进任务,不抛出——后台协程无人接)。"""
    try:
        provider = get_asr_service().get(provider_name)

        # ---- decoding:解码一次拿时长(分段器内部还会再解码一次;
        #      接受这个重复——先量时长做护栏,过不了护栏就不值得省这次解码) ----
        _set(task_id, "decoding", 5)
        y = await asyncio.to_thread(load_audio, audio)
        duration = len(y) / _SR
        if duration > Settings.ASR_LONG_MAX_DURATION_S:
            raise ValueError(
                f"音频 {duration/3600:.1f}h 超过上限 {Settings.ASR_LONG_MAX_DURATION_S/3600:.0f}h")
        # 短路整段路径直接复用这份 y(转 PCM 送转写);长路径分段器
        # 自己再解码一次(它要完整 float32 做能量对齐),这份就地放掉
        if duration >= Settings.ASR_LONG_THRESHOLD_S:
            del y

        if duration < Settings.ASR_LONG_THRESHOLD_S:
            # ---- 短音频:整段(复用①非流式,但共享全局信号量——
            #      不让大文件插队挤占长任务之外的实时流量) ----
            # 注意:不能把原始字节直接送 provider——mp4/m4a 等容器
            # provider 不认(omlx 500)。统一走解码→PCM→WAV,provider
            # 拿到的恒是 16k mono wav,与长路径分段负载同构。
            _set(task_id, "transcribing", 30)
            pcm = (np.clip(y, -1.0, 1.0) * 32767).astype("<i2").tobytes()
            async with _global_sem:
                res = await provider.transcribe(pcm_to_wav(pcm))
            result = LongTaskResult(
                text=res.text.strip(),
                segments=[],
                failed=0,
            )
        else:
            # ---- 长音频:分段 → 并发 → 拼装 ----
            _set(task_id, "segmenting", 10)
            segs, _ = await asyncio.to_thread(Segmenter().segment, audio)
            _set(task_id, "transcribing", 15,
                 total_segments=len(segs), done_segments=0, failed_segments=0)

            async def on_done(index: int, ok: bool) -> None:
                t = _tasks.get(task_id)
                if t is None:
                    return
                t.done_segments += 1
                if not ok:
                    t.failed_segments += 1
                # 段进度映射到 15~99,给 completed 留 100
                t.progress = 15 + (t.done_segments / t.total_segments) * 84

            results = await Scheduler().transcribe_all(segs, provider, on_done)
            result = Assembler.assemble(results)
            _set(task_id, failed_segments=result.failed)

        _set(task_id, "completed", 100, result=result)
        logger.info(f"长音频任务 {task_id} 完成: {result.failed} 段失败")
    except Exception as e:
        _set(task_id, "failed", error=str(e))
        logger.error(f"长音频任务 {task_id} 失败: {e}")


async def cleanup_expired() -> None:
    """TTL 清理(挂 main.py lifespan:启动跑一次 + 每小时一轮)。"""
    while True:
        now = time.time()
        expired = [tid for tid, t in _tasks.items() if now - t.updated_at > _TTL_S]
        for tid in expired:
            del _tasks[tid]
        if expired:
            logger.info(f"清理过期长音频任务 {len(expired)} 个")
        await asyncio.sleep(3600)
