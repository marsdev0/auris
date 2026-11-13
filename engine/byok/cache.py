import asyncio
import time

_TTL_S = 600


class UserProviderCache:
    def __init__(self, ttl_s: int = _TTL_S):
        self._ttl = ttl_s
        self._store: dict[tuple[int, str, str], tuple[float, object]] = {}
        self._lock = asyncio.Lock()

    async def get_or_build(self, user_id: int, domain: str, config_hash: str,
                               build):
        key = (user_id, domain, config_hash)
        now = time.monotonic()
        async with self._lock:
            hit = self._store.get(key)
            if hit and now - hit[0] < self._ttl:
                return hit[1]
            provider = build()
            self._store[key] = (now, provider)
            self._sweep(now)
            return provider

    def _sweep(self, now: float) -> None:
        for k in [k for k, (ts, _) in self._store.items() if now - ts >= self._ttl]:
            del self._store[k]


_user_cache = UserProviderCache()

def get_user_provider_cache() -> UserProviderCache:
    return _user_cache