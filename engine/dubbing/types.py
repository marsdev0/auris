from enum import IntEnum


class Stage(IntEnum):
    DOWNLOAD = 0
    TRANSCRIBE = 1
    TRANSLATE = 2
    CHUNK = 3
    SYNTH = 4
    CONCAT = 5


class DubStatus(IntEnum):
    INIT = 0
    RUNNING = 1
    COMPLETED = 2
    FAILED = 3
