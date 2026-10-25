# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""Engine config package: split per domain (refactored from a single Settings class).

- Env var names unchanged (zero .env changes); consumers go from Settings.ASR_X to ASR.X;
- load_dotenv must run before submodule imports (top of this file), otherwise getenv misses .env;
- New domain = new submodule + one export line here.
"""
from dotenv import load_dotenv

load_dotenv(override=True)

from engine.config.app import AppSettings as APP
from engine.config.asr import AsrSettings as ASR
from engine.config.dub import DubSettings as DUB
from engine.config.llm import LlmSettings as LLM
from engine.config.tts import TtsSettings as TTS

__all__ = ["APP", "ASR", "TTS", "LLM", "DUB"]
