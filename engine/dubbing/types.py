from enum import IntEnum

from pydantic import BaseModel


class Stage(IntEnum):
    DOWNLOAD = 0
    TRANSCRIBE = 1
    TRANSLATE = 2
    CHUNK = 3
    SYNTH = 4
    CONCAT = 5


class VStage(IntEnum):
    """Video pipeline"""
    DOWNLOAD = 0  # download source video
    DEMUX = 1  # demux audio/video tracks, extract audio track from src.mp4
    TRANSCRIBE = 2  # transcribe, audio into ASR, English segments with sentence-level timestamps out
    TRANSLATE = 3  # translate, batch translation with per-segment window constraint
    RESPLIT = 4  # target-language re-split + window re-assignment, over-window translations split by sentence, sub-windows by char proportion
    SYNTH = 5  # synthesize, TTS at native speed per unit into Chinese speech
    ALIGN = 6  # duration alignment
    MUX = 7  # mux, Chinese audio track merged with original video track

class Seg(BaseModel):
    """Segment with a time window"""
    text: str
    start: float
    end: float

class DubStatus(IntEnum):
    INIT = 0
    RUNNING = 1
    COMPLETED = 2
    FAILED = 3
