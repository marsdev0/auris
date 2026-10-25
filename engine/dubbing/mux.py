
from engine.dubbing.align import _ffmpeg


async def mux(video: str, audio_m4a: str, out_mp4: str) -> None:
    """Copy first; on container incompatibility (e.g. vp9+webm into mp4 edge case) fall back to re-encoding h264."""
    try:
        await _ffmpeg("-i", str(video), "-i", str(audio_m4a),
                      "-map", "0:v:0", "-map", "1:a:0",
                      "-c:v", "copy", "-c:a", "copy",
                      "-shortest", "-movflags", "+faststart", str(out_mp4))
    except RuntimeError:
        from loguru import logger
        logger.warning("视频流 copy 失败(容器不兼容),降级重编码 h264")
        await _ffmpeg("-i", str(video), "-i", str(audio_m4a),
                      "-map", "0:v:0", "-map", "1:a:0",
                      "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                      "-c:a", "copy", "-shortest",
                      "-movflags", "+faststart", str(out_mp4))
