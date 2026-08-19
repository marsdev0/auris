# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import io
import subprocess
import wave

import numpy as np
import librosa

from engine.config import Settings


def load_audio(data: bytes) -> np.ndarray:
    """任意格式字节 → 解码 + 重采样到 16k mono np.float32(供 faster-whisper)。

    两级解码:soundfile(librosa 默认,零进程开销,覆盖 wav/mp3/ogg/flac)
    → 不认识的容器(mp4/m4a/mkv 等视频)走 ffmpeg 子进程兜底,输出裸 PCM
    再自包 WAV 头喂回 librosa——对调用方完全透明。"""
    try:
        y, _ = librosa.load(io.BytesIO(data), sr=Settings.ASR_SAMPLE_RATE, mono=True)
        return y.astype(np.float32)
    except Exception:
        return _ffmpeg_decode(data)


def _ffmpeg_decode(data: bytes) -> np.ndarray:
    """ffmpeg 兜底:任意音视频容器 → 16k mono float32。系统需安装 ffmpeg。"""
    proc = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error",
         "-i", "pipe:0",           # 从 stdin 读
         "-f", "wav", "-ac", "1", "-ar", str(Settings.ASR_SAMPLE_RATE),
         "pipe:1"],
        input=data, capture_output=True,
    )
    if proc.returncode != 0:
        raise ValueError(f"音频解码失败(ffmpeg): {proc.stderr.decode(errors='ignore')[:200]}")
    y, _ = librosa.load(io.BytesIO(proc.stdout), sr=Settings.ASR_SAMPLE_RATE, mono=True)
    return y.astype(np.float32)


def pcm_to_wav(pcm: bytes, sample_rate: int = Settings.ASR_SAMPLE_RATE) -> bytes:
    """裸 PCM(16bit LE mono)→ WAV 容器字节。

    流式切出的段没有文件头,而 provider.transcribe() 的契约是"任意格式字节"
    (librosa 需要容器才能解码),送转写前包一层 44 字节的 WAV 头——
    provider 协议保持不变,流式/非流式走同一条解码路径。"""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)          # mono
        w.setsampwidth(2)          # 16bit
        w.setframerate(sample_rate)
        w.writeframes(pcm)
    return buf.getvalue()
