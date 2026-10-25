# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""TTS domain config. Env prefix TTS_*."""
import os


class TtsSettings:
    DEFAULT_PROVIDER = os.getenv("TTS_PROVIDER", "edge_tts")
    DEFAULT_VOICE = os.getenv("TTS_VOICE", "zh-CN-XiaoxiaoNeural")  # aligned with frontend edgeVoices default
