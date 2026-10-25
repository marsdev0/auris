"""stage 顺序编排"""

import asyncio
import json
import shutil

import numpy as np
from loguru import logger

from engine.asr import gate
from engine.asr.audio import load_audio, pcm_to_wav
from engine.asr.long_audio.assembler import Assembler
from engine.asr.long_audio.scheduler import Scheduler
from engine.asr.long_audio.segmenter import Segmenter
from engine.asr.service import get_asr_service
from engine.config import ASR, DUB
from engine.dubbing import align, tasks
from engine.dubbing import checkpoint as ckp
from engine.dubbing import translate as tsl
from engine.dubbing import timeline as tl
from engine.dubbing.chunker import chunk
from engine.dubbing.concat import concat_mp3
from engine.dubbing.mux import mux
from engine.dubbing.tasks import DubStatus
from engine.dubbing.types import Seg, Stage, VStage
from engine.tts.service import get_tts_service

# _SR = ASR.SAMPLE_RATE
_TTS_RETRY = 3


def _fail(task_id: str, url: str, title: str | None, e: Exception, kind: str = "audio") -> None:
    tasks.set(task_id, DubStatus.FAILED, error=str(e))
    t = tasks.get_task(task_id)
    try:
        ckp.save_checkpoint(
            task_id,
            url=url,
            title=title,
            stage_index=t.stage_index if t else 0,
            status=DubStatus.FAILED,
            kind=kind,
        )
    except Exception:
        pass  # 落盘失败不掩盖原错误


async def _run(task_id: str, url: str, start_stage: Stage = Stage.DOWNLOAD) -> None:
    """任务执行体，任务stage异常 -> failed"""
    from engine.sources import fetch_source
    from engine.sources.base import SourceError

    prev = ckp.load_checkpoint(task_id)
    title = prev.get("title") if prev else None
    tdir = ckp.task_dir(task_id)
    tdir.mkdir(parents=True, exist_ok=True)
    tasks.set(task_id, DubStatus.RUNNING, stage_index=int(start_stage), title=title)

    try:
        # 1. download → src.bin(常驻任务目录,终态+TTL 才清)
        if start_stage <= Stage.DOWNLOAD:
            src = await fetch_source(url)
            title = src.title
            tasks.set(task_id, title=title)
            shutil.copyfile(src.audio_path, tdir / "src.bin")
            src.audio_path.unlink(missing_ok=True)  # 临时文件用完即删
            ckp.save_checkpoint(
                task_id, url=url, stage_index=int(Stage.TRANSCRIBE), title=title
            )

        # 2. transcribe → transcript.json(英文段)
        if start_stage <= Stage.TRANSCRIBE:
            tasks.set(task_id, stage_index=int(Stage.TRANSCRIBE))
            audio = (tdir / "src.bin").read_bytes()
            units = await _transcribe(audio, task_id)
            ckp.write_stage_data(task_id, Stage.TRANSLATE, units)
            ckp.save_checkpoint(
                task_id, url=url, stage_index=int(Stage.TRANSLATE), title=title
            )
        else:
            # 说明已经转写了
            units = ckp.read_stage_data(task_id, Stage.TRANSLATE)

        # 3. translate → translation.json(中文段;中文源内部直通)
        if start_stage <= Stage.TRANSLATE:
            tasks.set(task_id, stage_index=int(Stage.TRANSLATE))
            units = await tsl.translate(units)
            ckp.write_stage_data(task_id, Stage.CHUNK, units)
            ckp.save_checkpoint(
                task_id, url=url, stage_index=int(Stage.CHUNK), title=title
            )
        else:
            units = ckp.read_stage_data(task_id, Stage.CHUNK)

        # 4. chunk → units.json(朗读单元)
        if start_stage <= Stage.CHUNK:
            tasks.set(task_id, stage_index=int(Stage.CHUNK))
            units_out = chunk(units, DUB.CHUNK_MAX)
            if not units_out:
                raise ValueError("转写结果为空,无可合成内容")
            ckp.write_stage_data(task_id, Stage.SYNTH, units_out)
            ckp.save_checkpoint(
                task_id, url=url, stage_index=int(Stage.SYNTH), title=title
            )
        else:
            units_out = ckp.read_stage_data(task_id, Stage.SYNTH)

        # 5. synth → chunks/*.mp3(stage 级粒度:resume 整段重跑,覆盖写)
        if start_stage <= Stage.SYNTH:
            tasks.set(task_id, stage_index=int(Stage.SYNTH))
            out_dir = tdir / "chunks"
            out_dir.mkdir(parents=True, exist_ok=True)
            provider = get_tts_service().get()
            for i, text in enumerate(units_out):
                result = await _synth_with_retry(provider, text)
                (out_dir / f"{i:04d}.mp3").write_bytes(result.audio)
            ckp.save_checkpoint(
                task_id, url=url, stage_index=int(Stage.CONCAT), title=title
            )

        # 6. concat → narration.mp3(终态先落盘,清理放最后)
        tasks.set(task_id, stage_index=int(Stage.CONCAT))
        parts = sorted((tdir / "chunks").glob("*.mp3"))  # 从盘上取,不依赖前分支变量
        final = tdir / "narration.mp3"
        await concat_mp3(parts, final)
        ckp.save_checkpoint(
            task_id,
            url=url,
            stage_index=len(Stage),
            status=DubStatus.COMPLETED,
            title=title,
        )
        for p in parts:
            p.unlink(missing_ok=True)
        cd = tdir / "chunks"
        if cd.exists():
            cd.rmdir()
        tasks.set(
            task_id,
            DubStatus.COMPLETED,
            stage_index=len(Stage),
            audio_path=str(final),
            audio_bytes=final.stat().st_size,
        )
        logger.info(f"播报任务 {task_id} 完成: {final.stat().st_size}B")
    except SourceError as e:
        _fail(task_id, url, title, e)
    except Exception as e:
        _fail(task_id, url, title, e)
        logger.error(f"播报任务 {task_id} 失败: {e}")


async def _transcribe(audio: bytes, task_id: str) -> list[str]:
    """音频字节 → 朗读段文本列表。双路径复用 asr 域(long_audio/tasks.py::_run 同构)。"""
    provider = get_asr_service().get(None)

    y = await asyncio.to_thread(load_audio, audio)
    duration = len(y) / ASR.SAMPLE_RATE
    if duration > ASR.LONG_MAX_DURATION_S:
        raise ValueError(
            f"音频 {duration / 3600:.1f}h 超过上限 {ASR.LONG_MAX_DURATION_S / 3600:.0f}h"
        )

    if duration < ASR.LONG_THRESHOLD_S:
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
        except Exception as e:  # edge-tts 瞬态失败(限流/断连),退避重试
            last = e
            logger.warning(f"tts 段失败 第{attempt}/{_TTS_RETRY}次: {e}")
            await asyncio.sleep(attempt)  # 1s/2s 线性退避,免费通道不需要指数级
    raise RuntimeError(f"TTS 重试耗尽: {last}")


async def _transcribe_seg(audio: bytes, task_id: str) -> list[Seg]:
    """_transcribe 的 Seg 版:音频字节 → 句级带时间戳段"""
    provider = get_asr_service().get(None)
    y = await asyncio.to_thread(load_audio, audio)
    duration = len(y) / ASR.SAMPLE_RATE
    if duration > ASR.LONG_MAX_DURATION_S:
        raise ValueError(f"音频 {duration / 3600:.1f}h 超过上限")
    if duration < ASR.LONG_THRESHOLD_S:
        pcm = (np.clip(y, -1.0, 1.0) * 32767).astype("<i2").tobytes()
        async with gate.short_lease(task_id):
            res = await provider.transcribe(pcm_to_wav(pcm))
        if res.segments:
            return [
                Seg(text=s.text.strip(), start=s.start, end=s.end)
                for s in res.segments
                if s.text.strip()
            ]
        if not res.text.strip():   # 空转写 fail-fast:不产出空 Seg 空跑到成品(实测教训 2026-10-25)
            raise RuntimeError("ASR 返回空文本(音频格式不支持或无语音)")
        return [
            Seg(text=res.text.strip(), start=0.0, end=round(duration, 3))
        ]  # 退化:单窗
    del y
    segs, _ = await asyncio.to_thread(Segmenter().segment, audio)
    results = await Scheduler().transcribe_all(segs, provider, task_id=task_id)
    out: list[Seg] = []
    for r in sorted(results, key=lambda r: r.start):
        # VAD 段起始偏移(16k 样本位→秒)
        off = r.start / ASR.SAMPLE_RATE
        if r.seg_detail:
            # 段内句级(相对段内秒)+偏移=全局
            for st, en, text in r.seg_detail:
                if text.strip():
                    out.append(
                        Seg(
                            text=text.strip(),
                            start=round(off + st, 3),
                            end=round(off + en, 3),
                        )
                    )
        elif r.text.strip():
            # 段级退化:整段一窗
            out.append(
                Seg(
                    text=r.text.strip(),
                    start=round(off, 3),
                    end=round(r.end / ASR.SAMPLE_RATE, 3),
                )
            )
    return out


async def _run_video(task_id: str, url: str, start_stage: VStage = VStage.DOWNLOAD) -> None:
    from engine.sources import fetch_source

    prev = ckp.load_checkpoint(task_id)
    title = prev.get("title") if prev else None
    tdir = ckp.task_dir(task_id)
    tdir.mkdir(parents=True, exist_ok=True)
    tasks.set(task_id, DubStatus.RUNNING, stage_index=int(start_stage), title=title)

    try:
        # 1. 下载视频，src.mp4
        if start_stage <= VStage.DOWNLOAD:
            src = await fetch_source(url)
            title = src.title
            tasks.set(task_id, title=title)
            shutil.copyfile(src.video_path, tdir / "src.mp4")  # pyright: ignore[reportArgumentType]
            src.video_path.unlink(missing_ok=True)  # pyright: ignore[reportOptionalMemberAccess]
            ckp.save_checkpoint(
                task_id,
                url=url,
                stage_index=int(VStage.DEMUX),
                title=title,
                kind="video",
            )

        # 2. 分离音频 audio.mp3(ASR 友好格式;m4a 实测 qwen3 不认,返回空转写)
        if start_stage <= VStage.DEMUX:
            tasks.set(task_id, stage_index=int(VStage.DEMUX))
            await align.extract_audio(tdir / "src.mp4", tdir / "audio.mp3")
            ckp.save_checkpoint(
                task_id,
                url=url,
                stage_index=int(VStage.TRANSCRIBE),
                title=title,
                kind="video",
            )

        # 3. 转写
        if start_stage <= VStage.TRANSCRIBE:
            tasks.set(task_id, stage_index=int(VStage.TRANSCRIBE))
            segs = await _transcribe_seg((tdir / "audio.mp3").read_bytes(), task_id)
            ckp.write_segs(task_id, "transcript.json", segs)
            ckp.save_checkpoint(
                task_id,
                url=url,
                stage_index=int(VStage.TRANSLATE),
                title=title,
                kind="video",
            )
        else:
            segs = ckp.read_segs(task_id, "transcript.json")

        # 4. 翻译
        if start_stage <= VStage.TRANSLATE:
            tasks.set(task_id, stage_index=int(VStage.TRANSLATE))
            segs = await tsl.translate_segs(segs)
            ckp.write_segs(task_id, "translation.json", segs)
            ckp.save_checkpoint(task_id, url=url, stage_index=int(VStage.RESPLIT),
                                title=title, kind="video")
        else:
            segs = ckp.read_segs(task_id, "translation.json")

        if start_stage <= VStage.RESPLIT:
            tasks.set(task_id, stage_index=int(VStage.RESPLIT))
            units = tl.resplit(segs)
            if not units:
                raise ValueError("翻译结果为空,无可合成内容")
            ckp.write_segs(task_id, "units.json", units)
            ckp.save_checkpoint(task_id, url=url, stage_index=int(VStage.SYNTH), title=title, kind="video")
        else:
            units = ckp.read_segs(task_id, "units.json")

        if start_stage <= VStage.SYNTH:
            tasks.set(task_id, stage_index=int(VStage.SYNTH))
            out_dir = tdir / "chunks"
            out_dir.mkdir(parents=True, exist_ok=True)
            provider = get_tts_service().get()
            for i, u in enumerate(units):
                result = await _synth_with_retry(provider, tl.clean_for_tts(u.text))
                (out_dir / f"{i:04d}.mp3").write_bytes(result.audio)
            ckp.save_checkpoint(task_id, url=url, stage_index=int(VStage.ALIGN), title=title, kind="video")

        mp3s = [tdir / "chunks" / f"{i:04d}.mp3" for i in range(len(units))]
        if start_stage <= VStage.ALIGN:
            tasks.set(task_id, stage_index=int(VStage.ALIGN))
            new_times, speed_log = await align.align_all(units, mp3s, tdir / "aligned")
            (tdir / "align_report.json").write_text(json.dumps(speed_log, ensure_ascii=False))
            tl.write_srt(new_times, [u.text for u in units], tdir / "dub.srt")
            align.rebuild_track([tdir / "aligned" / f"{i:04d}.wav" for i in range(len(units))],
                          new_times, tdir / "dub_track.wav")
            ckp.save_checkpoint(task_id, url=url, stage_index=int(VStage.MUX),
                                 title=title, kind="video")

        tasks.set(task_id, stage_index=int(VStage.MUX))
        out = tdir / "output.mp4"
        await align.loudnorm(tdir / "dub_track.wav", tdir / "dub.m4a")
        await mux(tdir / "src.mp4", tdir / "dub.m4a", out)
        ckp.save_checkpoint(task_id, url=url, stage_index=len(VStage), status=DubStatus.COMPLETED,
                            title=title, kind="video")
        for p in (tdir / "aligned").glob("*.wav"):
            p.unlink(missing_ok=True)
        for p in mp3s:
            p.unlink(missing_ok=True)
        tasks.set(task_id, DubStatus.COMPLETED, stage_index=len(VStage),
                  video_path=str(out), audio_bytes=out.stat().st_size)
        logger.info(f"配音任务 {task_id} 完成: {out.stat().st_size}B")
    except Exception as e:
        _fail(task_id, url, title, e, kind="video")
        logger.error(f"配音任务 {task_id} 失败: {e}")
