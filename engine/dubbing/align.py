


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
    # 异步子进程，因为engine单循环上还跑着其他任务
    # -hide_banner 不打版权信息
    # -nostdin 禁交互(没有它 ffmpeg 会等键盘输入,后台进程假死——子进程调 ffmpeg 最经典的坑)
    # -y 覆盖已存在输出
    proc = await asyncio.create_subprocess_exec(
            ffmpeg, "-hide_banner", "-nostdin", "-y", *args,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    # 等子进程跑完并把全部输出一次性收走(communicate 是消费性读取,读完管道就空——
    # 所以 stderr 必须随返回值带出,loudnorm 要用它提测量 JSON)
    _, err = await proc.communicate()
    err_text = err.decode(errors="replace")
    if check and proc.returncode != 0:
        raise RuntimeError(f"ffmpeg 失败: {err_text[-300:]}")
    return proc, err_text



# ffmpeg -i src.mp4 -vn -c:a libmp3lame audio.mp3(ASR 友好格式;原音轨副本只服务于转写,
# 混流用的是独立合成的 dub.m4a,与此无关——曾用 aac/m4a,qwen3 端点不认,转写返回空,实测 2026-10-25)
async def extract_audio(video: Path, out_mp3: Path) -> None:
    """从视频抽音轨(转码 mp3,不玩 copy:此副本只给 ASR,128k 足够)。"""
    await _ffmpeg("-i", str(video), "-vn", "-c:a", "libmp3lame", "-b:a", "128k", str(out_mp3))

_DUR_RE = re.compile(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)")


async def probe_duration(p: Path) -> float:
    """实测时长(s)——估算器的实测兜底(§4.2):解析 `ffmpeg -i` stderr banner 的
    Duration 行。(曾用 pydub,但其 mediainfo 依赖 ffprobe 二进制——本机
    evermeet 静态版 ffmpeg 不带 ffprobe,实测 FileNotFoundError,故改为自解析;
    pydub 仅保留给 rebuild_track 的 wav 原生解析。)"""
    _, err = await _ffmpeg("-i", str(p), "-f", "null", "-", check=False)
    m = _DUR_RE.search(err)
    if not m:
        raise RuntimeError(f"无法解析时长: {p}")
    h, mnt, sec = int(m.group(1)), int(m.group(2)), float(m.group(3))
    return h * 3600 + mnt * 60 + sec

async def align_all(units: list[Seg], mp3s: list[Path], wav_dir: Path
                    ) -> tuple[list[list[float]], list[dict]]:
    """⑦对齐主流程(L4+L5,§4.3):chunk 级统一变速 → 新时间轴顺序累进 → 超顶尾部截断。

    返回:new_times(与 units 对齐的 [start,end])+ 速度统计(验收标准 6 的回填数据)。
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
            if k < len(group) - 1:              # 块内行间保留间隙(随变速收缩,附录 B.2-6)
                cur += gaps[i] / speed
        if cur > chunk_end + 0.05:              # L5:超顶从尾部截断(倒序,附录 B.2-5)
            for i in reversed(group):
                st, en = new_times[i]
                if en <= chunk_end:
                    break
                keep = max(chunk_end - st, 0.01)
                wav = wav_dir / f"{i:04d}.wav"
                tmp = wav.with_suffix(".trim.wav")  # 同上:输入输出不能同路径
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
    """变速(wav 16k/mono 输出)+ 时长校验:超预期 2% 截断 + 50ms fade(附录 B.2-4/5)。"""
    if abs(speed - 1.0) < 0.01:
        await _ffmpeg("-i", str(src), "-ar", str(ASR.SAMPLE_RATE), "-ac", "1", str(dst))
        return
    await _ffmpeg("-i", str(src), "-filter:a",
                  f"atempo={speed:.3f}", "-ar", str(ASR.SAMPLE_RATE), "-ac", "1", str(dst))
    in_dur = await probe_duration(src)
    out_dur = await probe_duration(dst)
    expect = in_dur / speed
    if out_dur > expect * 1.02:                 # 变速后仍超:截断到预期+淡出
        keep = max(expect, 0.05)
        tmp = dst.with_suffix(".trim.wav")      # 输入输出不能同路径(输出会截空输入)
        await _ffmpeg("-i", str(dst), "-t", f"{keep:.3f}",
                      "-af", f"afade=t=out:st={max(keep - 0.05, 0):.3f}:d=0.05",
                      "-ar", str(ASR.SAMPLE_RATE), "-ac", "1", str(tmp))
        os.replace(tmp, dst)


def rebuild_track(wavs: list[Path], new_times: list[list[float]], out_wav: Path) -> None:
    """⑧音轨重建(§4.4):静音段+变速后单元按新时间轴铺(pydub,16k/mono)。"""
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
    """响度归一两遍法(附录 B.2-7):测量 I/TP → 一次增益。目标 I=-20/TP=-1。"""
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
        pass                                     # 静音/超短:保持原电平
    await _ffmpeg("-i", str(in_wav), "-af", f"volume={gain:.2f}dB",
                  "-c:a", "aac", "-b:a", "128k", str(out_m4a))