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
    """视频管线"""
    DOWNLOAD = 0  # 下载源视频
    DEMUX = 1  # 分离音视频轨，从 src.mp4 抽出音频轨，audio.m4a
    TRANSCRIBE = 2  # 转写，音轨进ASR，输出带句级时间戳的英文段
    TRANSLATE = 3 # 翻译，批量翻译，每段带窗口约束
    RESPLIT = 4  # 目标语重切+排窗，超窗长译文按句再切，子窗按字数占比分配
    SYNTH = 5 # 合成，TTS原速逐单元合成中文语音
    ALIGN = 6 # 时长对齐
    MUX = 7 # 混流，中文音轨与原视频轨合成

class Seg(BaseModel):
    """带时间窗的段"""
    text: str
    start: float
    end: float

class DubStatus(IntEnum):
    INIT = 0
    RUNNING = 1
    COMPLETED = 2
    FAILED = 3
