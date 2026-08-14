# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""Engine 配置（骨架）。

业务配置（LLMProvider / EndPoint / ASR / TTS providers 等）后续按需加。
"""
import os

from dotenv import load_dotenv

load_dotenv(override=True)


class Settings:
    # 服务
    APP_NAME: str = os.getenv("APP_NAME", "Auris AI Engine")
    APP_VERSION: str = "0.1.0"
    HOST: str = os.getenv("HOST", "127.0.0.1")
    PORT: int = int(os.getenv("PORT", "18000"))
    DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"

    # CORS
    ALLOW_ORIGINS: list[str] = ["*"]

    ASR_DEFAULT_PROVIDER = os.getenv("ASR_PROVIDER", "whisper")
    ASR_MODEL = os.getenv("ASR_MODEL", "medium")  # MVP 先 medium;验收按 RTF 调 large-v3/small
    ASR_DEVICE = os.getenv("ASR_DEVICE", "auto")  # Mac 实际 cpu
    ASR_COMPUTE = os.getenv("ASR_COMPUTE", "int8")
    ASR_LANG = os.getenv("ASR_LANG", "zh")
    ASR_SAMPLE_RATE = 16000  # 单一来源
    ASR_VAD_THRESHOLD = float(os.getenv("ASR_VAD_THRESHOLD", "0.5"))
    ASR_VAD_MIN_SILENCE_MS = int(os.getenv("ASR_VAD_MIN_SILENCE_MS", "300"))
    ASR_VAD_SPEECH_PAD_MS = int(os.getenv("ASR_VAD_SPEECH_PAD_MS", "30"))

    ASR_INITIAL_PROMPT: str = os.getenv("ASR_INITIAL_PROMPT", "以下是普通话的句子,使用简体中文。")
