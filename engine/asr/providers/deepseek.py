# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import asyncio
from typing import AsyncIterator

import httpx

from engine.asr.provider import AsrCapability
from engine.asr.schemas import AsrResult, PartialResult
from loguru import logger

# 文档错误表中可重试的状态码(瞬态);400/413 等确定性失败不在此列
_RETRYABLE = {503, 504, 429}

class DeepSeekAsrProvider:
    name = "deepseek-asr"
    capabilities: set[AsrCapability] = set()  # HTTP 转写,无真流式
    max_segment_s = 110 # 官方要求最大120秒；管线据此自动切分段

    def __init__(self,
                 base_url: str,
                 api_key: str | None = None):
        root = base_url.rstrip("/")
        self._url = root + ("/tools/asr" if root.endswith("/v1") else "/v1/tools/asr")
        self._key = api_key or ""
        self._client = httpx.AsyncClient(timeout=httpx.Timeout(180.0, connect=5.0))

    async def transcribe(self, audio: bytes, lang: str | None = None) -> AsrResult:
        # 1秒音频 = 16kHz 采样率 * 16bit（2字节）* 单声道 = 每秒 32000 字节
        if len(audio) > self.max_segment_s * 32000:
            raise RuntimeError(f"deepseek asr exceeds limit: ~{len(audio) / 32000:.0f}s > "
                               f"{self.max_segment_s}s, pipeline should have segmented before calling")
        for attempt in (1, 2):
            try:
                resp = await self._client.post(
                    self._url,
                    headers={"Authorization": f"Bearer {self._key}"},
                    files={"file": ("audio.wav", audio, "audio/wav")}) # 文件、内容、MIME类型

                if resp.status_code in _RETRYABLE and attempt == 1:
                    last = RuntimeError(
                        f"deepseek asr {resp.status_code}(retryable): {resp.text[:200]}")
                    logger.warning(f"deepseek asr attempt 1 failed: {last}, retry once")
                    await asyncio.sleep(1)
                    continue

                resp.raise_for_status()
                data = resp.json()
                asr_result = AsrResult(
                    text=(data.get("text") or "").strip(),
                    segments=[])
                logger.info(f"DeepSeekAsrProvider transcribe: {asr_result}")
                return asr_result
            except httpx.HTTPStatusError as e:
                # 确定性失败(400/413/401...):不重试,直接抛
                raise RuntimeError(
                    f"deepseek asr {e.response.status_code}: {e.response.text[:200]}") from e
            except (httpx.TimeoutException, httpx.TransportError) as e:
                last = e
                if attempt == 1:
                    logger.warning(f"deepseek asr network error, retry once: {e}")
                    await asyncio.sleep(1)
                    continue
                raise RuntimeError(f"deepseek asr network error (still failing after retry): {e}") from e
        raise RuntimeError(f"deepseek asr retries exhausted: {last}")

    async def stream(self, chunks: AsyncIterator[bytes], lang: str | None = None) -> AsyncIterator[PartialResult]:
        raise NotImplementedError("deepseek-asr does not support streaming")