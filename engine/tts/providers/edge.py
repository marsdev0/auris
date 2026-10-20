import re

from engine.asr.audio import pcm_to_wav
from engine.config import Settings
from engine.tts.provider import SynthResult
from loguru import logger

_CHARS_TO_REMOVE = ("&", "®", "™", "©")

# 100ms@16kHz·16bit·单声道 = 1600 样本 × 2B
# 3200字节的全零 PCM
_SILENCE_WAV = pcm_to_wav(b"\x00" * 3200)

class EdgeTtsProvider:
    name = "edge_tts"

    async def synthesize(self, text: str, voice: str | None = None) -> SynthResult:
        # 1. 清洗，删除首尾空白，删除TTS敏感字符，这些字符容易被引擎念出来或者干扰标记解析
        cleaned = text.strip()
        for ch in _CHARS_TO_REMOVE:
            cleaned = cleaned.replace(ch, "")
        # 2. 把所有非空白的字符，即全部标点删除，删除后的长度<=1，默认为静音
        # 单个字符合成容易有bug，这里也直接认为是静音
        if len(re.sub(r"[^\w\s]", "", cleaned).strip()) <= 1:
            return SynthResult(audio=_SILENCE_WAV, content_type="audio/wav")

        # 延迟导入:依赖缺失时仅 tts 域受影响,engine 其余路由不受牵连
        import edge_tts
        buf = bytearray()
        communicate = edge_tts.Communicate(cleaned, voice or Settings.TTS_DEFAULT_VOICE)
        async for chunk in communicate.stream():
            logger.info(f"synthesize {chunk}")
            if chunk["type"] == "audio":
                buf.extend(chunk["data"])
        return SynthResult(audio=bytes(buf), content_type="audio/mpeg")


PROVIDER = EdgeTtsProvider
