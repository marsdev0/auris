"""
sources 统一入口:SSRF 预检 → 站点分发 → 抓取。
抓取是同步阻塞 IO(httpx 同步 client / yt-dlp 无异步 API),统一丢线程池执行——
不能冻住事件循环(engine 单循环上还跑着 WS 流式和其他任务,见 P4 §2.1)。
"""
import asyncio

from . import direct, xiaoyuzhou, bilibili
from .base import SourceResult, assert_public_url, SourceError

# 站点解析器; direct 是兜底，最后判定
_PARSERS = (xiaoyuzhou, bilibili)


async def fetch_source(url: str) -> SourceResult:
    """异步外壳:整个分发+抓取过程(含 DNS 解析)在线程池跑,await 即得结果。"""
    return await asyncio.to_thread(_fetch_sync, url)


def _fetch_sync(url: str) -> SourceResult:
    """同步实现:assert_public_url 的 getaddrinfo 也是阻塞调用,一并留在本线程。"""
    assert_public_url(url)
    for p in _PARSERS:
        if p.match(url):
            return p.fetch(url)
    if direct.match(url):
        return direct.fetch(url)
    raise SourceError("暂不支持的站点")
