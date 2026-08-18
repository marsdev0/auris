# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
import io
from typing import AsyncIterator

from openai import AsyncOpenAI

from engine.asr.provider import AsrCapability
from engine.asr.schemas import AsrResult, PartialResult, AsrSegment, AsrWord
from engine.config import Settings

# OpenAI 兼容接口按文件名后缀识别音频格式，统一按 wav 上送
_UPLOAD_FILENAME = "audio.wav"

class Qwen3AsrProvider:
    name = "qwen3-asr"
    capabilities: set[AsrCapability] = set()  # HTTP转写，无真流式

    def __init__(self):
        self._client = AsyncOpenAI(
            base_url=Settings.ASR_QWEN3_BASE_URL,
            api_key=Settings.ASR_QWEN3_API_KEY
        )

    async def transcribe(self, audio: bytes, lang: str | None = None) -> AsrResult:
        buf = io.BytesIO(audio)
        buf.name = _UPLOAD_FILENAME
        resp = await self._client.audio.transcriptions.create(
            file=buf,
            model=Settings.ASR_QWEN3_MODEL,
            language=lang or Settings.ASR_LANG,
            response_format="verbose_json"
        )
        # verbose_json 才有 segments/language；服务端若只支持 json 则优雅降级为纯文本
        segs = [
            AsrSegment(
                start=s.start, end=s.end, text=s.text, words=[
                    AsrWord(start=w.start, end=w.end, word=w.word)
                    for w in (s.words or [])
                ]
            )
            for s in (getattr(resp, "segments", None) or [])
        ]
        return AsrResult(
            text=resp.text,
            segments=segs,
            language=getattr(resp, "language", None)
        )

    async def stream(self, chunks: AsyncIterator[bytes], lang: str | None = None) -> AsyncIterator[PartialResult]:
        raise NotImplementedError("qwen3-asr provider 不支持真流式")

# 模块级，供ProviderRegistry 目录扫描发现
PROVIDER = Qwen3AsrProvider
