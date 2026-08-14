from enum import Enum, auto
from typing import Protocol, AsyncIterator

from engine.asr.schemas import AsrResult, PartialResult


class AsrCapability(Enum):
    STREAMING = auto()  # 支持真流式


class AsrProvider(Protocol):
    name: str
    capabilities: set[AsrCapability]

    async def transcribe(self, audio: bytes, lang: str | None = None) -> AsrResult:
        """非流式：整段音频 -> 最终文本。所有 provider 必须实现。"""
        ...

    async def stream(self, chunks: AsyncIterator[bytes], lang: str | None = None) -> AsyncIterator[PartialResult]:
        """真流式：音频流 -> partial/final。仅 STREAMING 能力 provider 实现。"""
        raise NotImplementedError


class ProviderRegistry:
    """目录扫描注册 + 预留 entry_points。"""

    def __init__(self):
        self._providers: dict[str, AsrProvider] = {}

    def register(self, provider: AsrProvider) -> None:
        self._providers[provider.name] = provider

    def get(self, name: str | None, default: str) -> AsrProvider:
        return self._providers[name or default]

    def names(self) -> list[str]:
        return list(self._providers)
