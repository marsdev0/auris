"""stage 顺序编排

"""
import asyncio
from pathlib import Path

import numpy as np
from loguru import logger

from engine.asr import gate
from engine.asr.audio import load_audio, pcm_to_wav
from engine.asr.long_audio.assembler import Assembler
from engine.asr.long_audio.scheduler import Scheduler
from engine.asr.long_audio.segmenter import Segmenter
from engine.asr.service import get_asr_service
from engine.config import Settings
from engine.dubbing import tasks
from engine.dubbing.chunker import chunk
from engine.dubbing.concat import concat_mp3
from engine.dubbing.tasks import DubStatus
from engine.tts.service import get_tts_service

_SR = Settings.ASR_SAMPLE_RATE
_TTS_RETRY = 3

async def _run(task_id: str, url: str) -> None:
    """任务执行体，任务stage异常 -> failed"""
    from engine.sources import fetch_source
    from engine.sources.base import SourceError
    try:
        # stage 1: 下载音频
        tasks._set(task_id, DubStatus.RUNNING, stage_index=0)
        src = await fetch_source(url)
        tasks._set(task_id, title=src.title)
        audio = src.audio_path.read_bytes()
        # 临时文件用完即删
        src.audio_path.unlink(missing_ok=True)

        # stage 2: 转写
        tasks._set(task_id, stage_index=1)
        units = await _transcribe(audio, task_id)

        # stage 3: chunk段文本 <= limit 朗读单元
        tasks._set(task_id, stage_index=2)
        chunks_out = chunk(units, Settings.DUB_CHUNK_MAX)
        if not chunks_out:
            raise ValueError("转写结果为空,无可合成内容")

        # stage 4: synth 串行逐段合成
        tasks._set(task_id, stage_index=3)
        out_dir = Settings.DUB_DIR / task_id / "chunks"
        out_dir.mkdir(parents=True, exist_ok=True)
        parts: list[Path] = []
        provider = get_tts_service().get()
        for i, text in enumerate(chunks_out):
            result = await _synth_with_retry(provider, text)
            p = out_dir / f"{i:04d}.mp3"
            p.write_bytes(result.audio)
            parts.append(p)

        # stage 5: concat
        tasks._set(task_id, stage_index=4)
        final = out_dir.parent / "narration.mp3"
        await concat_mp3(parts, final)
        for p in parts:
            p.unlink(missing_ok=True)
        out_dir.rmdir()
        tasks._set(task_id, DubStatus.COMPLETED, stage_index=5,
                   audio_path=str(final), audio_bytes=final.stat().st_size)
        logger.info(f"播报任务 {task_id} 完成: {len(parts)} 段, {final.stat().st_size}B")
    except SourceError as e:
        tasks._set(task_id, DubStatus.FAILED, error=str(e))
    except Exception as e:
        tasks._set(task_id, DubStatus.FAILED, error=str(e))
        logger.error(f"播报任务 {task_id} 失败: {e}")

async def _transcribe(audio: bytes, task_id: str) -> list[str]:
    """音频字节 → 朗读段文本列表。双路径复用 asr 域(long_audio/tasks.py::_run 同构)。"""
    provider = get_asr_service().get(None)

    y = await asyncio.to_thread(load_audio, audio)
    duration = len(y) / _SR
    if duration > Settings.ASR_LONG_MAX_DURATION_S:
        raise ValueError(
            f"音频 {duration/3600:.1f}h 超过上限 {Settings.ASR_LONG_MAX_DURATION_S/3600:.0f}h")

    if duration < Settings.ASR_LONG_THRESHOLD_S:
        # 短路整段:拿全文单元素(无停顿边界,chunker 兜底)
        pcm = (np.clip(y, -1.0, 1.0) * 32767).astype("<i2").tobytes()
        async with gate.short_lease(task_id):
            res = await provider.transcribe(pcm_to_wav(pcm))
        return [res.text.strip()]
    del y

    segs, _ = await asyncio.to_thread(Segmenter().segment, audio)
    results = await Scheduler().transcribe_all(segs, provider, task_id=task_id)
    assembled = Assembler.assemble(results)
    # 失败段([转写失败] 占位)剔除——不朗读占位符;全失败则自然进 chunk 后的空判
    return [s.text.strip() for s in assembled.segments if s.ok and s.text.strip()]


async def _synth_with_retry(provider, text: str):
    last: Exception | None = None
    for attempt in range(1, _TTS_RETRY + 1):
        try:
            return await provider.synthesize(text)
        except Exception as e:   # edge-tts 瞬态失败(限流/断连),退避重试
            last = e
            logger.warning(f"tts 段失败 第{attempt}/{_TTS_RETRY}次: {e}")
            await asyncio.sleep(attempt)   # 1s/2s 线性退避,免费通道不需要指数级
    raise RuntimeError(f"TTS 重试耗尽: {last}")