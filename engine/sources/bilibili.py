"""
bilibili视频, yt-dlp 选音频下载（DASH 音视频分离, 声音是独立流）
"""
import shutil
import tempfile
from pathlib import Path

from engine.sources.base import SourceResult, SourceError, TMP_DIR

_DOMAINS = ("bilibili.com", "b23.tv")    # b23.tv 短链:yt-dlp 自行跟随,但需先过 SSRF 闸

def match(url: str) -> bool:
    return any(d in url for d in _DOMAINS)

def fetch(url: str) -> SourceResult:
    """同步实现:由 fetch_source 统一 to_thread 调度(见 __init__.py)。"""
    try:
        from yt_dlp import YoutubeDL
    except ImportError:
        raise SourceError("engine 未安装 yt-dlp")

    out = Path(tempfile.mkdtemp(prefix="auris-bili-", dir=TMP_DIR))
    opts = {
        "format": "bestaudio/best",  # 只拉音频流
        "outtmpl": f"{out}/%(id)s.%(ext)s",
        "quiet": True,
        "noprogress": True,
        "cookiefile": None,
    }
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            file = next(out.iterdir())
            title = info.get("title") or "B站视频"
            return SourceResult(audio_path=file, title=title[:200], site="bilibili")
    except Exception as e:
        shutil.rmtree(out, ignore_errors=True)   # 失败清场,半截下载不留
        raise SourceError(f"bilibili下载失败: {str(e)[:120]}")
