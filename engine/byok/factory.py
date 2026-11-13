import httpx
from openai import AsyncOpenAI

from engine.asr.providers.qwen3 import Qwen3AsrProvider
from engine.byok.schemas import ByokConfigError, LlmConfig, TtsConfig, AsrConfig
from engine.net import safety
from engine.tts.provider import SynthResult
from engine.tts.providers.edge import EdgeTtsProvider

_PROBE_TIMEOUT = httpx.Timeout(30.0, connect=5.0)  # validate 探测:与 ai 侧 read 35s 配套
_RUNTIME_TIMEOUT = httpx.Timeout(60.0, connect=5.0) # 任务运行时:与 LlmClient 生产超时同档

def _gate(url: str | None) -> None:
    if url:
        safety.assert_public_url(url)


def build_llm_client(cfg: LlmConfig, *, probe: bool = True) -> AsyncOpenAI:
    # base_url 缺失时 _gate 会静默放行,AsyncOpenAI 将落到 api.openai.com 默认端点,
    # 用户 key 直接发给官方——必须在此显式拒绝
    if not cfg.base_url:
        raise ByokConfigError("llm 域必须填写 base_url")
    _gate(cfg.base_url)
    return AsyncOpenAI(base_url=cfg.base_url,
                       api_key=cfg.api_key or "EMPTY",
                       timeout=_PROBE_TIMEOUT if probe else _RUNTIME_TIMEOUT,
                       max_retries=0 if probe else 2)


class _VoiceBoundTts:
    def __init__(self, voice: str | None):
        self.name = "edge_tts"
        self._voice = voice
        self._inner = EdgeTtsProvider()


    async def synthesize(self, text: str, voice: str | None = None) -> SynthResult:
        return await self._inner.synthesize(text, voice=voice or self._voice)


class OpenAITtsProvider:
    name = "openai_tts"

    def __init__(self, cfg: TtsConfig):
        if not cfg.base_url:
            raise ByokConfigError("openai_tts 必须填写 base_url")
        _gate(cfg.base_url)
        self._client = AsyncOpenAI(base_url=cfg.base_url,
                                   api_key=cfg.api_key or "EMPTY",
                                   timeout=_RUNTIME_TIMEOUT)
        self._model = cfg.model or "tts-1"
        self._voice = cfg.voice

    async def synthesize(self, text: str, voice: str | None = None) -> SynthResult:
        resp = await self._client.audio.speech.create(
            model=self._model,
            voice=voice or self._voice or "alloy",
            input=text,
            response_format="mp3")
        return SynthResult(audio=resp.content, content_type="audio/mpeg")


def build_tts_provider(cfg: TtsConfig):
    if cfg.provider == "edge_tts":
        return _VoiceBoundTts(cfg.voice)
    if cfg.provider in ("openai_tts", "openai_compat"):
        return OpenAITtsProvider(cfg)
    raise ByokConfigError(f"tts provider 暂不支持: {cfg.provider}")

def build_asr_provider(cfg: AsrConfig) -> Qwen3AsrProvider:
    if not cfg.base_url:
        raise ByokConfigError("asr 域必须填写 base_url")
    _gate(cfg.base_url)
    return Qwen3AsrProvider(base_url=cfg.base_url, api_key=cfg.api_key,
                            model=cfg.model)