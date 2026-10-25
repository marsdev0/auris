# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""LLM domain config (D1-mini). Env prefix LLM_*."""
import os


class LlmSettings:
    BASE_URL = os.getenv("LLM_BASE_URL", "")   # OpenAI-compatible endpoint (dashscope compatible-mode etc.)
    API_KEY = os.getenv("LLM_API_KEY", "")
    MODEL = os.getenv("LLM_MODEL", "")         # e.g. qwen-plus / gpt-4o-mini
