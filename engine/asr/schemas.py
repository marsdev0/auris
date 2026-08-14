# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
from pydantic import BaseModel

# 单个字
class AsrWord(BaseModel):
    start: float
    end: float
    word: str
    probability: float | None = None

# 段落
class AsrSegment(BaseModel):
    start: float
    end: float
    text: str
    words: list[AsrWord] | None = None


class AsrResult(BaseModel):
    text: str
    segments: list[AsrSegment]
    language: str | None = None


class PartialResult(BaseModel):
    is_final: bool
    text: str
    beg_ms: int | None = None
    end_ms: int | None = None


# WS start帧的config
class StartConfig(BaseModel):
    lang: str | None = None
    provider: str | None = None
