import time

import httpx
from openai import AsyncOpenAI, BadRequestError

from engine.config import Settings
from loguru import logger

_TIMEOUT = httpx.Timeout(60.0, connect=5.0)

class LLMClient:

    def __init__(self):
        self._client: AsyncOpenAI | None = None

    def _ensure(self) -> AsyncOpenAI:
        if self._client is None:
            self._client = AsyncOpenAI(base_url=Settings.LLM_BASE_URL,
                                       api_key=Settings.LLM_API_KEY,
                                       timeout=_TIMEOUT)
        return self._client

    async def llm_chat(self, messages: list[dict]) -> str:
        client = self._ensure()
        try:
            start_time = time.monotonic()
            resp = await client.chat.completions.create(
                model=Settings.LLM_MODEL,
                messages=messages)
            latency_ms = (time.monotonic() - start_time) * 1000
            u = resp.usage
            logger.info(f"llm chat {Settings.LLM_MODEL} "
                        f"pt={u.prompt_tokens if u else -1} "
                        f"ct={u.completion_tokens if u else -1} {latency_ms:.0f}ms")
            return resp.choices[0].message.content or ""
        except Exception as e:
            raise e

_client: LLMClient | None = None


def get_llm_client() -> LLMClient:
    global _client
    if _client is None:
        _client = LLMClient()
    return _client