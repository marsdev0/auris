# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import io
import subprocess
import wave

import numpy as np
import librosa

from engine.config import ASR
from loguru import logger


def load_audio(data: bytes) -> np.ndarray:
    """
    Any-format audio bytes → decoded + resampled to 16k mono float32 (faster-whisper input).
    Two-stage decode: soundfile (librosa default, zero process overhead, covers wav/mp3/ogg/flac)
    → unknown containers (mp4/m4a/mkv video tracks) fall back to an ffmpeg subprocess emitting
    raw PCM, which is re-wrapped with a WAV header and fed back to librosa — fully transparent
    to callers.

    Return: 采样点数组 - 每个元素是万分之一秒级别的一个声音采样值;
    16kHZ、单声道、float32的一维数组
    """
    try:
        y, _ = librosa.load(io.BytesIO(data), sr=ASR.SAMPLE_RATE, mono=True)
        return y.astype(np.float32)
    except Exception as e:
        logger.warning(f"soundfile decode failed, falling back to ffmpeg: {e!r}")
        return _ffmpeg_decode(data)


def _ffmpeg_decode(data: bytes) -> np.ndarray:
    """ffmpeg fallback: any audio/video container → 16k mono float32. Requires ffmpeg on PATH."""
    proc = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error",
         "-i", "pipe:0",           # 从 stdin 读
         "-f", "wav", "-ac", "1", "-ar", str(ASR.SAMPLE_RATE),
         "pipe:1"],
        input=data, capture_output=True,
    )
    if proc.returncode != 0:
        err = proc.stderr.decode(errors="ignore")[:200]
        logger.error(f"audio decode failed (ffmpeg rc={proc.returncode}): {err}")
        raise ValueError(f"audio decode failed by ffmpeg: {err}")
    y, _ = librosa.load(io.BytesIO(proc.stdout), sr=ASR.SAMPLE_RATE, mono=True)
    return y.astype(np.float32)


def pcm_to_wav(pcm: bytes, sample_rate: int = ASR.SAMPLE_RATE) -> bytes:
    """
    Raw PCM (16bit LE mono) → WAV container bytes.
    Stream-cut segments carry no file header, while provider.transcribe() expects
    "any-format bytes" (librosa needs a container to decode); prepend a 44-byte WAV
    header before sending — provider protocol unchanged, streaming and non-streaming
    share the same decode path.
    """
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)          # mono
        w.setsampwidth(2)          # 16bit
        w.setframerate(sample_rate)
        w.writeframes(pcm)
    return buf.getvalue()
