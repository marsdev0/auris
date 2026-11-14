# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""TTS domain config. Env prefix TTS_*."""
import os


class TtsSettings:
    DEFAULT_PROVIDER = os.getenv("TTS_PROVIDER", "")
    DEFAULT_VOICE = os.getenv("TTS_VOICE", "")  # aligned with frontend edgeVoices default
