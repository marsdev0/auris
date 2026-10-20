"""TTS provider 插件协议"""
from typing import Protocol

from pydantic import BaseModel


class SynthResult(BaseModel):
    """一次合成的产物, content_type 区分正常 mp3 与静音兜底 wav"""
    audio: bytes
    content_type: str


class TtsProvider(Protocol):
    name: str

    async def synthesize(self, text: str, voice: str | None = None) -> SynthResult:
        """文本 -> 音频, voice 为 None 时由实现方取平台默认音色"""
        ...


class ProviderRegistry:
    def __init__(self):
        self._providers: dict[str, TtsProvider] = {}

    def register(self, provider: TtsProvider) -> None:
        self._providers[provider.name] = provider

    def get(self, name: str | None, default: str) -> TtsProvider:
        return self._providers[name or default]

    def names(self) -> list[str]:
        return list(self._providers)
