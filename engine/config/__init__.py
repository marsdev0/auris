# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""engine 配置包:按域拆分(原单一 Settings 巨类重构)。

- 环境变量名不变(.env 零改动);仅消费侧从 Settings.ASR_X 变为 ASR.X;
- load_dotenv 必须先于子模块 import(本文件顶部),否则 getenv 读不到 .env;
- 新增域 = 新增子模块 + 此处汇出一行。
"""
from dotenv import load_dotenv

load_dotenv(override=True)

from engine.config.app import AppSettings as APP
from engine.config.asr import AsrSettings as ASR
from engine.config.dub import DubSettings as DUB
from engine.config.llm import LlmSettings as LLM
from engine.config.tts import TtsSettings as TTS

__all__ = ["APP", "ASR", "TTS", "LLM", "DUB"]
