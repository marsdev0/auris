# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""长音频并发调度(形态④管线第二步)。

segmenter 的 list[Seg] → 有界并发逐段 provider.transcribe() → list[SegmentResult]。

四件套(老项目 parallel_asr.py 验收过的模式,平移到新协议):
  ① 模块级全局信号量 —— 整段路径(短音频)与分段路径共享同一个 M,
     多任务叠加时在跑推理恒 ≤ M(老项目验收#6:两层 semaphore 不叠加);
  ② wait_for 段级超时 —— 单段卡死不拖垮任务;
  ③ 段级重试 —— 瞬态失败自愈;
  ④ 段级隔离 —— 一段最终失败只污染自己的 SegmentResult,不冒泡。

已知限制(asyncio+线程,老项目验收偏差#1,继承不修):
  wait_for 超时只 cancel 协程,to_thread 里的 whisper 线程杀不掉会跑完
  占 worker/CPU;全局信号量把同时在跑的压在 ≤ M,堆积可控。
  qwen3-asr 是 HTTP provider,cancel 即断连接,无此问题。

on_done 回调:每段终态(无论成败)触发一次,供任务表推进度——
回调在信号量外调,慢回调不占推理槽位。
"""
import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from loguru import logger

from engine.asr.audio import pcm_to_wav
from engine.asr.long_audio.segmenter import Seg
from engine.asr.provider import AsrProvider
from engine.config import Settings

# 模块级单例:全进程一个信号量(跨 Scheduler 实例、跨任务共享),M 见配置注释
_global_sem = asyncio.Semaphore(Settings.ASR_LONG_CONCURRENCY)


@dataclass
class SegmentResult:
    """一段的终态。start/end 是报告边界样本位(与 Seg 一致,无 overlap)。"""
    index: int
    start: int
    end: int
    text: str          # 失败段为空串,由 assembler 决定占位策略
    ok: bool
    latency_ms: float  # 单段耗时(含重试),观测用


# 段终态回调:(seg_index, ok) -> None
OnSegDone = Callable[[int, bool], Awaitable[None]]


class Scheduler:
    """无状态(信号量在模块级),可随意复用/并发调用。"""

    async def transcribe_all(
        self,
        segs: list[Seg],
        provider: AsrProvider,
        on_done: OnSegDone | None = None,
    ) -> list[SegmentResult]:
        """并发转写所有分段。返回顺序与 segs 一致(gather 保序)。"""
        return await asyncio.gather(
            *(self._transcribe_one(seg, provider, on_done) for seg in segs)
        )

    async def _transcribe_one(
        self,
        seg: Seg,
        provider: AsrProvider,
        on_done: OnSegDone | None,
    ) -> SegmentResult:
        # 全局信号量:持有期间恰是一次推理(重试间也持有,防失败热旋打满引擎)
        async with _global_sem:
            t0 = asyncio.get_running_loop().time()
            last_err: Exception | None = None
            for attempt in range(1, Settings.ASR_SEG_RETRY + 1):
                try:
                    # 裸 PCM 包 WAV 头(provider.transcribe 契约是"任意格式字节",
                    # 与流式路径 stream_handler._transcribe 同一条路)
                    res = await asyncio.wait_for(
                        provider.transcribe(pcm_to_wav(seg.pcm)),
                        timeout=Settings.ASR_SEG_TIMEOUT_S,
                    )
                    latency = (asyncio.get_running_loop().time() - t0) * 1000
                    await self._notify(on_done, seg.index, True)
                    return SegmentResult(
                        index=seg.index, start=seg.start, end=seg.end,
                        text=res.text.strip(), ok=True, latency_ms=latency,
                    )
                except Exception as e:  # 含 TimeoutError;段级隔离,不冒泡
                    last_err = e
                    logger.warning(
                        f"seg#{seg.index} 第{attempt}/{Settings.ASR_SEG_RETRY}次失败: {e}")

            latency = (asyncio.get_running_loop().time() - t0) * 1000
            logger.error(f"seg#{seg.index} 重试耗尽,标记失败: {last_err}")
            await self._notify(on_done, seg.index, False)
            return SegmentResult(
                index=seg.index, start=seg.start, end=seg.end,
                text="", ok=False, latency_ms=latency,
            )

    @staticmethod
    async def _notify(on_done: OnSegDone | None, index: int, ok: bool) -> None:
        """信号量外回调;回调自身异常不影响调度(记日志吞掉)。"""
        if on_done is None:
            return
        try:
            await on_done(index, ok)
        except Exception as e:
            logger.warning(f"on_done 回调异常(忽略): {e}")
