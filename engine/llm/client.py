"""engine 平台 LLM 客户端

OpenAI 兼容出站(AsyncOpenAI),构建形态对齐 byok/factory.py::build_llm_client;
D1 后半(llm-gateway)落地后 base_url 换向网关,消费方代码不动(D1-mini 方案 §3)。

策略:
- 空配置不拦启动:首次调用才校验并抛 LlmError——中文源任务(跳过翻译)与其余域不受影响;
- 调用级重试 3 次指数退避(1s/2s/4s + jitter):瞬态失败(5xx/超时/断连/429 限流)自愈,
  耗尽抛 LlmError,处置权在调用域;
- 确定性错误不重试:4xx(除 429)直接抛——参数/凭证/模型名错误重试无意义;
  response_format 兼容问题除外,去掉参数重试一次;
- 计量日志:每次调用打 model/token 用量/延迟,为 P9 配额攒数据,不做计费。
"""
import asyncio
import random
import time

import httpx
from loguru import logger
from openai import AsyncOpenAI, APIStatusError

from engine.config import LLM

_TIMEOUT = httpx.Timeout(60.0, connect=5.0)  # 生产档 read 60s(批量翻译响应慢;byok probe 是 30s 档)
_RETRY = 3
_BACKOFF_S = 1.0


class LlmError(Exception):
    """LLM 调用失败(含未配置)。"""


class LlmClient:
    """Lazy 单例包装:首次调用才建 AsyncOpenAI,空配置在那一刻才报错。"""

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
        """一次对话调用,返回首选项文本。

        model 不传时用 LLM.MODEL(平台默认);外部传入(如 BYOK 用户模型)
        时以传入为准。temperature 默认 0.1(批量编号翻译对温度敏感);json_mode 请求
        JSON 输出,端点不支持 response_format 时自动去掉参数重试(兼容面优先)。
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
                # json_mode 兼容降级:400 且与 response_format 相关,去掉参数重试一次
                if use_json and isinstance(e, APIStatusError) \
                        and e.status_code == 400 and "response_format" in str(e):
                    use_json = False
                    continue
                # 确定性 4xx(除 429 限流):参数/凭证/模型名错误,重试无意义直接抛
                if isinstance(e, APIStatusError) and e.status_code is not None \
                        and 400 <= e.status_code < 500 and e.status_code != 429:
                    raise LlmError(f"LLM 请求被拒绝({e.status_code}): {e}") from e
                # 瞬态(5xx/超时/断连/429):退避重试
                last = e
                logger.warning(f"llm chat 第{attempt}/{_RETRY}次失败: {e}")
                if attempt < _RETRY:
                    await asyncio.sleep(_BACKOFF_S * 2 ** (attempt - 1) + random.uniform(0, 0.5))
        raise LlmError(f"LLM 调用重试耗尽: {last}")


_client: LlmClient | None = None


def get_llm_client() -> LlmClient:
    """进程单例(对称 tts/service.get_tts_service)。"""
    global _client
    if _client is None:
        _client = LlmClient()
    return _client


def warn_if_unconfigured() -> None:
    """启动提醒(main.py lifespan 调):空配置只告警,不拦启动。"""
    if not (LLM.BASE_URL and LLM.API_KEY):
        logger.warning("LLM 端点未配置(LLM_BASE_URL/LLM_API_KEY):涉及 LLM 的功能(如翻译)不可用")
    elif not LLM.MODEL:
        logger.warning("LLM_MODEL 未配置:未显式传 model 的调用(如翻译)会失败,显式传入可用")
