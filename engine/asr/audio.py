# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import io
import wave

import numpy as np
import librosa

from engine.config import Settings


def load_audio(data: bytes) -> np.ndarray:
    """任意格式字节 → 解码 + 重采样到 16k mono np.float32(供 faster-whisper)。"""
    y, _ = librosa.load(io.BytesIO(data), sr=Settings.ASR_SAMPLE_RATE, mono=True)
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
