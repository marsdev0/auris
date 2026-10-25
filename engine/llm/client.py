"""Engine platform LLM client.

OpenAI-compatible egress (AsyncOpenAI), construction aligned with byok/factory.py::build_llm_client;
After D1 part 2 (llm-gateway) lands, base_url flips to the gateway, consumer code untouched (D1-mini plan §3).

Policies:
- Missing config does not block startup: validated on first call, raising LlmError — Chinese-source tasks (skip translation) and other domains unaffected;
- Call-level retry, 3x exponential backoff (1s/2s/4s + jitter): transient failures (5xx/timeout/disconnect/429) self-heal;
  exhaustion raises LlmError, disposition belongs to the calling domain;
- Deterministic errors are not retried: 4xx (except 429) raises immediately — bad params/credentials/model names will not heal;
  response_format incompatibility is the exception: drop the param and retry once;
- Metering log: model/token usage/latency per call, data for P9 quotas, no billing.
"""
import asyncio
import random
import time

import httpx
from loguru import logger
from openai import AsyncOpenAI, APIStatusError

from engine.config import LLM

_TIMEOUT = httpx.Timeout(60.0, connect=5.0)  # production read 60s (batch translation responses are slow; byok probe is the 30s tier)
_RETRY = 3
_BACKOFF_S = 1.0


class LlmError(Exception):
    """LLM call failure (including unconfigured)."""


class LlmClient:
    """Lazy singleton wrapper: AsyncOpenAI built on first call; missing config errors at that moment."""

    def __init__(self):
        self._client: AsyncOpenAI | None = None

    def _ensure(self) -> AsyncOpenAI:
        if self._client is None:
            if not (LLM.BASE_URL and LLM.API_KEY):
                raise LlmError("未配置 LLM 端点:请设置 LLM_BASE_URL / LLM_API_KEY")
            self._client = AsyncOpenAI(base_url=LLM.BASE_URL,
                                       api_key=LLM.API_KEY,
                                       timeout=_TIMEOUT)
        return self._client

    async def llm_chat(self, messages: list[dict], *, model: str | None = None,
                       temperature: float = 0.1, json_mode: bool = False) -> str:
        """One chat call, returns the first choice text.

        Without model, uses LLM.MODEL (platform default); an external model (e.g. BYOK user model)
        takes precedence. temperature defaults to 0.1 (numbered batch translation is temperature-sensitive); json_mode
        requests JSON output; endpoints without response_format support get the param dropped and one retry (compat first).
        """
        client = self._ensure()
        use_model = model or LLM.MODEL
        if not use_model:
            raise LlmError("未指定 model 且未配置 LLM_MODEL(默认模型)")
        use_json = json_mode
        last: Exception | None = None
        for attempt in range(1, _RETRY + 1):
            try:
                kwargs = {"response_format": {"type": "json_object"}} if use_json else {}
                t0 = time.monotonic()
                resp = await client.chat.completions.create(
                    model=use_model, messages=messages,
                    temperature=temperature, **kwargs)
                latency_ms = (time.monotonic() - t0) * 1000
                u = resp.usage
                logger.info(f"llm chat {use_model} "
                            f"pt={u.prompt_tokens if u else -1} "
                            f"ct={u.completion_tokens if u else -1} {latency_ms:.0f}ms")
                return resp.choices[0].message.content or ""
            except Exception as e:
                # json_mode compat fallback: 400 related to response_format → drop the param and retry once
                if use_json and isinstance(e, APIStatusError) \
                        and e.status_code == 400 and "response_format" in str(e):
                    use_json = False
                    continue
                # Deterministic 4xx (except 429 rate limit): params/credentials/model errors, retrying is pointless — raise
                if isinstance(e, APIStatusError) and e.status_code is not None \
                        and 400 <= e.status_code < 500 and e.status_code != 429:
                    raise LlmError(f"LLM 请求被拒绝({e.status_code}): {e}") from e
                # Transient (5xx/timeout/disconnect/429): backoff retry
                last = e
                logger.warning(f"llm chat 第{attempt}/{_RETRY}次失败: {e}")
                if attempt < _RETRY:
                    await asyncio.sleep(_BACKOFF_S * 2 ** (attempt - 1) + random.uniform(0, 0.5))
        raise LlmError(f"LLM 调用重试耗尽: {last}")


_client: LlmClient | None = None


def get_llm_client() -> LlmClient:
    """Process singleton (symmetric with tts/service.get_tts_service)."""
    global _client
    if _client is None:
        _client = LlmClient()
    return _client


def warn_if_unconfigured() -> None:
    """Startup reminder (called from main.py lifespan): missing config warns only, never blocks startup."""
    if not (LLM.BASE_URL and LLM.API_KEY):
        logger.warning("LLM 端点未配置(LLM_BASE_URL/LLM_API_KEY):涉及 LLM 的功能(如翻译)不可用")
    elif not LLM.MODEL:
        logger.warning("LLM_MODEL 未配置:未显式传 model 的调用(如翻译)会失败,显式传入可用")
