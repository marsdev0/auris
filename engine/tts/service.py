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
        return self.registry.get(provider, TTS.DEFAULT_PROVIDER)

_service: TtsService | None = None

def get_tts_service() -> TtsService:
    global _service
    if _service is None:
        _service = TtsService()
    return _service