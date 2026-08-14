# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
from engine.asr.provider import ProviderRegistry, AsrProvider
from engine.config import Settings


class AsrService:
    """进程单例，启动时扫描 + 预热默认provider，模型只加载一次"""

    def __init__(self):
        self.registry = ProviderRegistry()
        self._scan_and_register()
        _ = self.get()

    def _scan_and_register(self):
        import importlib, pkgutil
        from engine.asr import providers as pkg
        for m in pkgutil.iter_modules(pkg.__path__):
            mod = importlib.import_module(f"{pkg.__name__}.{m.name}")
            if hasattr(mod, "PROVIDER"):
                self.registry.register(mod.PROVIDER())

    def get(self, provider : str | None = None) -> AsrProvider:
        return self.registry.get(provider, Settings.ASR_DEFAULT_PROVIDER)

_service: AsrService | None = None

def get_asr_service() -> AsrService:
    global _service
    if _service is None:
        _service = AsrService()
    return _service