import httpx
from openai import AsyncOpenAI

from engine.byok.schemas import ByokConfigError, LlmConfig

_PROBE_TIMEOUT = httpx.Timeout(30.0, connect=5.0)  # 与 ai 侧 read 35s 配套

def build_llm_client(cfg: LlmConfig) -> AsyncOpenAI:
    if not cfg.base_url:
        raise ByokConfigError("llm 域必须填写 base_url")
    return AsyncOpenAI(base_url=cfg.base_url, api_key=cfg.api_key or "EMPTY",
                       timeout=_PROBE_TIMEOUT)