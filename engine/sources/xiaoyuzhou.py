"""小宇宙单集:页面是 SSR HTML,bs4 直接抠出音频 CDN 地址与标题"""
import re

import httpx
from bs4 import BeautifulSoup

from engine.sources.base import SourceResult, _UA, SourceError, download_to_temp

# 匹配 https://www.xiaoyuzhoufm.com/episode/{id}
EPISODE_PATTERN = re.compile(
    r"xiaoyuzhoufm\.com/episode/([a-f0-9]+)"
)

def match(url: str) -> bool:
    return bool(EPISODE_PATTERN.search(url))

def fetch(url: str) -> SourceResult:
    """同步实现:由 fetch_source 统一 to_thread 调度(见 __init__.py)。"""
    try:
        resp = httpx.get(url, headers={"User-Agent": _UA}, follow_redirects=True)
        resp.raise_for_status()
    except httpx.HTTPError as e:
        raise SourceError(f"小宇宙页面获取失败: {e.__class__.__name__}")
    soup = BeautifulSoup(resp.text, "html.parser")
    audio_tag = soup.find("audio", src=True)
    og_audio = soup.find("meta", property="og:audio", content=True)
    audio_url = (audio_tag["src"] if audio_tag else None) or (og_audio["content"] if og_audio else None)
    og_title = soup.find("meta", property="og:title", content=True)
    title = (og_title["content"] if og_title else None) or \
            (soup.title.string.split(" - ")[0] if soup.title and soup.title.string else "小宇宙单集")
    if not audio_url:
        raise SourceError("页面中未找到音频地址")
    path = download_to_temp(audio_url)
    return SourceResult(audio_path=path, title=title.strip()[:200], site="xiaoyuzhou")