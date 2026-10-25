# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""Dubbing domain config (narration/translation/video dubbing). Env prefix DUB_*."""
import os
from pathlib import Path


class DubSettings:
    TASK_TTL_H = int(os.getenv("DUB_TASK_TTL_H", "24"))
    CHUNK_MAX = int(os.getenv("DUB_CHUNK_MAX", "500"))  # same guardrail as tts router
    DIR = Path(__file__).resolve().parent.parent.parent / "data" / "dubbing"  # artifact root: repo/data/dubbing/{task_id}/

    # ------ Video alignment (D5, defaults from its plan appendix B.4) ------
    CPS = float(os.getenv("DUB_CPS", "4.5"))  # Chinese speech rate, chars/sec
    TOL = float(os.getenv("DUB_TOL", "1.5"))  # borrow cap from next unit's gap (s)
    MIN_SUB = float(os.getenv("DUB_MIN_SUB", "2.5"))  # short-window forced extension threshold (s)
    SPEED_ACCEPT = float(os.getenv("DUB_SPEED_ACCEPT", "1.2"))  # normal speed-up cap
    SPEED_MAX = float(os.getenv("DUB_SPEED_MAX", "1.4"))  # hard cap
