"""MP3 concatenation
"""
import asyncio
import shutil
from pathlib import Path

from loguru import logger


def ensure_ffmpeg() -> None:
    """Probe at startup; missing binary does not crash the process — the task fails only when it reaches this step"""
    if shutil.which("ffmpeg") is None:
        logger.warning("ffmpeg 未安装:播报任务的拼接 stage 将失败(brew install ffmpeg)")


async def concat_mp3(parts: list[Path], out: Path) -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise RuntimeError("ffmpeg 未安装,无法拼接音频")

    lst = out.parent / "list.txt"
    # Paths relative to cwd (= out.parent): parts live in the chunks/ subdir; p.name would drop the prefix and the
    # concat demuxer would fail to open (measured 2026-10-21: Impossible to open '0000.mp3')
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
