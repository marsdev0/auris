# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
from engine.config import TTS
from engine.tts.provider import ProviderRegistry, TtsProvider


class TtsService:
    def __init__(self):
        self.registry = ProviderRegistry()
        self._scan_and_register()

    def _scan_and_register(self):
        import importlib, pkgutil
        from engine.tts import providers as pkg
        for m in pkgutil.iter_modules(pkg.__path__):
            mod = importlib.import_module(f"{pkg.__name__}.{m.name}")
            if hasattr(mod, "PROVIDER"):
                self.registry.register(mod.PROVIDER())

    def get(self, provider: str | None = None) -> TtsProvider:
        # 出站默认值已全空:未配置且无显式 provider 时给出明确报错,
        # 而非裸 KeyError(仅 BYOK tts 缺省回落等边缘场景会走到)
        if not (provider or TTS.DEFAULT_PROVIDER):
            raise RuntimeError("平台默认 TTS 未配置(TTS_PROVIDER),请配置 BYOK tts 或设置环境变量")
        return self.registry.get(provider, TTS.DEFAULT_PROVIDER)

_service: TtsService | None = None

def get_tts_service() -> TtsService:
    global _service
    if _service is None:
        _service = TtsService()
    return _service