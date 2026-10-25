


import asyncio
import json
import os
import re
from pathlib import Path
import shutil

from pydub import AudioSegment

from engine.dubbing.types import Seg
from engine.dubbing import timeline as tl
from loguru import logger
from engine.config import DUB, ASR


async def _ffmpeg(*args: str, check: bool = True) -> tuple["asyncio.subprocess.Process", str]:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise RuntimeError("ffmpeg 未安装")
    # Async subprocess: the engine event loop serves other tasks too
    # -hide_banner: suppress copyright banner
    # -nostdin: disable interaction (without it ffmpeg waits on stdin and background processes hang — the classic pitfall)
    # -y: overwrite existing output
    proc = await asyncio.create_subprocess_exec(
            ffmpeg, "-hide_banner", "-nostdin", "-y", *args,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    # Wait for exit and drain all output at once (communicate is a consuming read; the pipe is empty afterwards —
    # so stderr must be returned to callers; loudnorm parses measurement JSON from it)
    _, err = await proc.communicate()
    err_text = err.decode(errors="replace")
    if check and proc.returncode != 0:
        raise RuntimeError(f"ffmpeg 失败: {err_text[-300:]}")
    return proc, err_text



# ffmpeg -i src.mp4 -vn -c:a libmp3lame audio.mp3 (ASR-friendly format; this audio copy only feeds transcription,
# muxing uses the independently synthesized dub.m4a — aac/m4a was tried before, qwen3 endpoint rejected it and returned empty, measured 2026-10-25)
async def extract_audio(video: Path, out_mp3: Path) -> None:
    """Extract audio track from video (transcode to mp3, no copy trick: this copy is for ASR only, 128k is enough)."""
    await _ffmpeg("-i", str(video), "-vn", "-c:a", "libmp3lame", "-b:a", "128k", str(out_mp3))

_DUR_RE = re.compile(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)")


async def probe_duration(p: Path) -> float:
    """Measured duration (s) — the estimator's empirical backstop (§4.2): parse the Duration line from
    the `ffmpeg -i` stderr banner. (pydub was used before, but its mediainfo depends on the ffprobe binary —
    the local evermeet static ffmpeg build has no ffprobe, hit FileNotFoundError in testing, so we parse ourselves;
    pydub is kept only for rebuild_track's native wav parsing.)"""
    _, err = await _ffmpeg("-i", str(p), "-f", "null", "-", check=False)
    m = _DUR_RE.search(err)
    if not m:
        raise RuntimeError(f"无法解析时长: {p}")
    h, mnt, sec = int(m.group(1)), int(m.group(2)), float(m.group(3))
    return h * 3600 + mnt * 60 + sec

async def align_all(units: list[Seg], mp3s: list[Path], wav_dir: Path
                    ) -> tuple[list[list[float]], list[dict]]:
    """⑦ Alignment main flow (L4+L5, §4.3): chunk-level unified time-stretch → sequential new-timeline advance → over-cap tail truncation.

    Returns: new_times ([start,end] aligned with units) + speed stats (acceptance criterion 6 data).
    """
    wav_dir.mkdir(parents=True, exist_ok=True)
    gaps = tl.gaps_of(units)
    tols = [tl.tol_of(u, g) for u, g in zip(units, gaps)]
    chunks = tl.plan_chunks(units)
    new_times: list[list[float] | None] = [None] * len(units)
    speed_log: list[dict] = []

    for group in chunks:
        chunk_end = units[group[-1]].end + (tols[group[-1]] - (units[group[-1]].end - units[group[-1]].start))
        reals = [await probe_duration(p) for p in (mp3s[i] for i in group)]
        budget = sum(tols[i] for i in group)
        speed = min(max(sum(reals) / max(budget - 0.1, 0.1), 1.0), DUB.SPEED_MAX)
        cur = units[group[0]].start
        for k, i in enumerate(group):
            wav = wav_dir / f"{i:04d}.wav"
            await atempo(mp3s[i], wav, speed)
            ad = len(AudioSegment.from_wav(wav)) / 1000.0
            new_times[i] = [round(cur, 3), round(cur + ad, 3)]
            cur += ad
            if k < len(group) - 1:              # keep intra-chunk gaps (shrunk by the speed factor, appendix B.2-6)
                cur += gaps[i] / speed
        if cur > chunk_end + 0.05:              # L5: over cap → truncate from tail (reversed order, appendix B.2-5)
            for i in reversed(group):
                st, en = new_times[i]
                if en <= chunk_end:
                    break
                keep = max(chunk_end - st, 0.01)
                wav = wav_dir / f"{i:04d}.wav"
                tmp = wav.with_suffix(".trim.wav")  # ditto: input and output must not share a path
                await _ffmpeg("-i", str(wav), "-t", f"{keep:.3f}",
                              "-af", f"afade=t=out:st={max(keep - 0.05, 0):.3f}:d=0.05",
                              "-ar", str(ASR.SAMPLE_RATE), "-ac", "1", str(tmp))
                os.replace(tmp, wav)
                new_times[i] = [st, round(st + keep, 3)]
            logger.warning(f"chunk {group} 变速 {speed:.2f}x 后仍超顶,尾部截断")
        speed_log.append({"chunk": group, "speed": round(speed, 3),
                          "over": round(cur - chunk_end, 3)})
    return new_times, speed_log


async def atempo(src: Path, dst: Path, speed: float) -> None:
    """Time-stretch (16k/mono wav output) + duration check: >2% over expectation → truncate + 50ms fade (appendix B.2-4/5)."""
    if abs(speed - 1.0) < 0.01:
        await _ffmpeg("-i", str(src), "-ar", str(ASR.SAMPLE_RATE), "-ac", "1", str(dst))
        return
    await _ffmpeg("-i", str(src), "-filter:a",
                  f"atempo={speed:.3f}", "-ar", str(ASR.SAMPLE_RATE), "-ac", "1", str(dst))
    in_dur = await probe_duration(src)
    out_dur = await probe_duration(dst)
    expect = in_dur / speed
    if out_dur > expect * 1.02:                 # still over after stretch: truncate to expected + fade out
        keep = max(expect, 0.05)
        tmp = dst.with_suffix(".trim.wav")      # input and output must not share a path (output truncates the input)
        await _ffmpeg("-i", str(dst), "-t", f"{keep:.3f}",
                      "-af", f"afade=t=out:st={max(keep - 0.05, 0):.3f}:d=0.05",
                      "-ar", str(ASR.SAMPLE_RATE), "-ac", "1", str(tmp))
        os.replace(tmp, dst)


def rebuild_track(wavs: list[Path], new_times: list[list[float]], out_wav: Path) -> None:
    """⑧ Track rebuild (§4.4): silence + time-stretched units laid on the new timeline (pydub, 16k/mono)."""
    track = AudioSegment.silent(duration=0, frame_rate=ASR.SAMPLE_RATE)
    prev_end = 0.0
    for wav, (st, en) in zip(wavs, new_times):
        gap_ms = int(max(st - prev_end, 0.0) * 1000)
        if gap_ms > 0:
            track += AudioSegment.silent(duration=gap_ms, frame_rate=ASR.SAMPLE_RATE)
        seg = AudioSegment.from_wav(wav).set_frame_rate(ASR.SAMPLE_RATE).set_channels(1)
        track += seg
        prev_end = en
    track.export(out_wav, format="wav")


async def loudnorm(in_wav: Path, out_m4a: Path) -> None:
    """Two-pass loudness normalization (appendix B.2-7): measure I/TP → single gain. Targets I=-20/TP=-1."""
    _, err = await _ffmpeg("-i", str(in_wav),
                            "-af", "loudnorm=I=-20:TP=-1:LRA=11:print_format=json",
                            "-f", "null", "-", check=False)
    import math
    gain = 0.0
    try:
        stats, _ = json.JSONDecoder().raw_decode(err[err.rfind("{"):])
        i_, tp = float(stats["input_i"]), float(stats["input_tp"])
        if math.isfinite(i_) and math.isfinite(tp):
            gain = min(-20.0 - i_, -1.0 - tp)
    except Exception:
        pass                                     # silent/too-short: keep original level
    await _ffmpeg("-i", str(in_wav), "-af", f"volume={gain:.2f}dB",
                  "-c:a", "aac", "-b:a", "128k", str(out_m4a))
