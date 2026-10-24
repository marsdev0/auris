# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""Dubbing 域配置(播报/翻译/视频配音)。env 前缀 DUB_*。"""
import os
from pathlib import Path


class DubSettings:
    TASK_TTL_H = int(os.getenv("DUB_TASK_TTL_H", "24"))
    CHUNK_MAX = int(os.getenv("DUB_CHUNK_MAX", "500"))  # 与 tts router 护栏同口径
    DIR = Path(__file__).resolve().parent.parent.parent / "data" / "dubbing"  # 产物根:repo/data/dubbing/{task_id}/

    # ------ 视频对齐(D5,默认值出处其方案附录 B.4)------
    CPS = float(os.getenv("DUB_CPS", "4.5"))  # 中文语速 字/秒
    TOL = float(os.getenv("DUB_TOL", "1.5"))  # 向下一单元间隙的借用上限(s)
    MIN_SUB = float(os.getenv("DUB_MIN_SUB", "2.5"))  # 短窗强制扩展阈值(s)
    SPEED_ACCEPT = float(os.getenv("DUB_SPEED_ACCEPT", "1.2"))  # 常态变速上限
    SPEED_MAX = float(os.getenv("DUB_SPEED_MAX", "1.4"))  # 硬顶
