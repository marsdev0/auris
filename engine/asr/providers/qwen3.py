# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import io
from typing import AsyncIterator

from openai import AsyncOpenAI

from engine.asr.provider import AsrCapability
from engine.asr.schemas import AsrResult, PartialResult, AsrSegment, AsrWord
from engine.config import ASR
from loguru import logger

# OpenAI 兼容接口按文件名后缀识别音频格式，统一按 wav 上送
_UPLOAD_FILENAME = "audio.wav"


class Qwen3AsrProvider:
    name = "qwen3-asr"
    capabilities: set[AsrCapability] = set()  # HTTP transcription, no true streaming
    max_segment_s: int | None = None  # 类默认:平台 omlx 本地无限制 → 600 兜底

    def __init__(self, base_url: str | None = None, api_key: str | None = None,
                 model: str | None = None, max_segment_s: int | None = None):
        # SDK 要求 key 非空;平台默认未配置(出站默认值已全空)时用占位符构造成功,
        # 真实调用时才会 401 快速失败——「不配=不可用」而非「启动即崩/静默打官方」
        self._client = AsyncOpenAI(
            base_url=base_url or ASR.QWEN3_BASE_URL,
            api_key=api_key or ASR.QWEN3_API_KEY or "EMPTY"
        )
        self._model = model or ASR.QWEN3_MODEL
        if max_segment_s is not None:
            self.max_segment_s = max_segment_s

    async def transcribe(self, audio: bytes, lang: str | None = None) -> AsrResult:
        buf = io.BytesIO(audio)
        buf.name = _UPLOAD_FILENAME
        resp = await self._client.audio.transcriptions.create(
            file=buf,
            model=self._model,
            language=lang or ASR.LANG or None,  # 空=auto 检测(ASR 出源语,翻译归 LLM)
            response_format="verbose_json"
        )
        # verbose_json 才有 segments/language；服务端若只支持 json 则优雅降级为纯文本
        segs = [
            AsrSegment(
                start=s.start, end=s.end, text=s.text, words=[
                    AsrWord(start=w.start, end=w.end, word=w.word)
                    for w in (getattr(s, "words", None) or [])
                ]
            )
            for s in (getattr(resp, "segments", None) or [])
        ]
        asr_result = AsrResult(
            text=resp.text,
            segments=segs,
            language=getattr(resp, "language", None)
        )
        logger.info(f"Qwen3AsrProvider transcribe: {asr_result}")
        return asr_result

    async def stream(self, chunks: AsyncIterator[bytes], lang: str | None = None) -> AsyncIterator[PartialResult]:
        raise NotImplementedError("qwen3-asr provider does not support streaming")


# 模块级，供ProviderRegistry 目录扫描发现
PROVIDER = Qwen3AsrProvider
