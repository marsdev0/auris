"""音频直链: URL本身就是音频 或 响应 Content-Type 是音频"""
from pathlib import PurePosixPath
from urllib.parse import urlparse

import httpx

from engine.sources.base import SourceResult, download_to_temp

_AUDIO_EXT = {".mp3", ".m4a", ".wav", ".flac", ".ogg", ".aac", ".wma", ".opus"}
_AUDIO_MIME_PREFIX = "audio/"

def match(url: str) -> bool:
    ext = PurePosixPath(urlparse(url).path).suffix.lower()
    if ext in _AUDIO_EXT:
        return True
    try:
        head = httpx.head(url, follow_redirects=True, timeout=10)
        return head.headers.get("Content-Type", "").startswith(_AUDIO_MIME_PREFIX)
    except httpx.HTTPError:
        return False

def fetch(url: str) -> SourceResult:
    """同步实现:由 fetch_source 统一 to_thread 调度(见 __init__.py)。"""
    path = download_to_temp(url)
    name = PurePosixPath(urlparse(url).path).stem or urlparse(url).hostname or "未知音频"
    return SourceResult(audio_path=path, title=name[:200], site="direct")