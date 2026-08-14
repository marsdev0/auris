# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import io
import numpy as np
import librosa

from engine.config import Settings


def load_audio(data: bytes) -> np.ndarray:
    """任意格式字节 → 解码 + 重采样到 16k mono np.float32(供 faster-whisper)。"""
    y, _ = librosa.load(io.BytesIO(data), sr=Settings.ASR_SAMPLE_RATE, mono=True)
    return y.astype(np.float32)