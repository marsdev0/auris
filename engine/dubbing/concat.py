"""mp3拼接
"""
import asyncio
import shutil
from pathlib import Path

from loguru import logger


def ensure_ffmpeg() -> None:
    """启动时探测，缺失不crash进程，任务走到这步才失败"""
    if shutil.which("ffmpeg") is None:
        logger.warning("ffmpeg 未安装:播报任务的拼接 stage 将失败(brew install ffmpeg)")


async def concat_mp3(parts: list[Path], out: Path) -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise RuntimeError("ffmpeg 未安装,无法拼接音频")

    lst = out.parent / "list.txt"
    # 相对 cwd(=out.parent)的路径:分片在 chunks/ 子目录,p.name 会丢前缀导致
    # concat demuxer 打不开(实测 2026-10-21:Impossible to open '0000.mp3')
    lst.write_text("\n".join(f"file '{p.relative_to(out.parent)}'" for p in parts), encoding="utf-8")
    try:
        proc = await asyncio.create_subprocess_exec(ffmpeg,
                                                    "-f", "concat",
                                                    "-safe", "0",
                                                    "-i", lst.name,
                                                    "-c", "copy", out.name,
                                                    cwd=out.parent,
                                                    stdout=asyncio.subprocess.PIPE,
                                                    stderr=asyncio.subprocess.PIPE)
        _, err = await proc.communicate()
        if proc.returncode != 0:
            raise RuntimeError(f"ffmpeg concat 失败: {err.decode(errors='replace')[-300:]}")
    finally:
        lst.unlink(missing_ok=True)