# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""LLM 域配置(D1-mini)。env 前缀 LLM_*。"""
import os


class LlmSettings:
    BASE_URL = os.getenv("LLM_BASE_URL", "")   # OpenAI 兼容端点(dashscope compatible-mode 等)
    API_KEY = os.getenv("LLM_API_KEY", "")
    MODEL = os.getenv("LLM_MODEL", "")         # 如 qwen-plus / gpt-4o-mini
