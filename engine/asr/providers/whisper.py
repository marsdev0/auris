# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
from faster_whisper import WhisperModel

from engine.asr.audio import load_audio
from engine.asr.provider import AsrCapability
from engine.asr.schemas import AsrResult, AsrSegment, AsrWord
from engine.config import Settings


class FasterWhisperProvider:
    name = "whisper"
    capabilities: set[AsrCapability] = set()

    def __init__(self) -> None:
        self._model = WhisperModel(
            Settings.ASR_MODEL,
            device=Settings.ASR_DEVICE,
            compute_type=Settings.ASR_COMPUTE
        )

    async def transcribe(self, audio: bytes, lang: str | None = None) -> AsrResult:
        # 返回音频数组
        y = load_audio(audio)
        segments, info = self._model.transcribe(
            y,
            language=lang or Settings.ASR_LANG,
            vad_filter=True,
            beam_size=5,
            word_timestamps=False,
            initial_prompt=Settings.ASR_INITIAL_PROMPT
        )
        segs = [
            AsrSegment(start=s.start, end=s.end, text=s.text,
                       words=[
                           AsrWord(start=w.start, end=w.end, word=w.word, probability=getattr(w, "probability", None))
                           for w in (s.words or [])
                       ])
            for s in segments
        ]
        return AsrResult(
            text="".join(s.text for s in segs),
            segments=segs,
            language=info.language
        )


# 模块级，供ProviderRegistry 目录扫描发现
PROVIDER = FasterWhisperProvider
