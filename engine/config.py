# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""Engine 配置（骨架）。

业务配置（LLMProvider / EndPoint / ASR / TTS providers 等）后续按需加。
"""
import os

from dotenv import load_dotenv

load_dotenv(override=True)


class Settings:
    # 服务
    APP_NAME: str = os.getenv("APP_NAME", "Auris AI Engine")
    APP_VERSION: str = "0.1.0"
    HOST: str = os.getenv("HOST", "127.0.0.1")
    PORT: int = int(os.getenv("PORT", "18000"))
    DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"

    # CORS
    ALLOW_ORIGINS: list[str] = ["*"]

    ASR_DEFAULT_PROVIDER = os.getenv("ASR_PROVIDER", "whisper")
    ASR_MODEL = os.getenv("ASR_MODEL", "medium")  # MVP 先 medium;验收按 RTF 调 large-v3/small
    ASR_DEVICE = os.getenv("ASR_DEVICE", "auto")  # Mac 实际 cpu
    ASR_COMPUTE = os.getenv("ASR_COMPUTE", "int8")
    ASR_LANG = os.getenv("ASR_LANG", "zh")
    ASR_SAMPLE_RATE = 16000
    ASR_VAD_THRESHOLD = float(os.getenv("ASR_VAD_THRESHOLD", "0.5"))
    ASR_VAD_MIN_SILENCE_MS = int(os.getenv("ASR_VAD_MIN_SILENCE_MS", "300"))
    ASR_VAD_SPEECH_PAD_MS = int(os.getenv("ASR_VAD_SPEECH_PAD_MS", "30"))

    ASR_INITIAL_PROMPT: str = os.getenv("ASR_INITIAL_PROMPT", "以下是普通话的句子,使用简体中文。")

    ASR_QWEN3_BASE_URL = os.getenv("ASR_QWEN3_BASE_URL", "http://127.0.0.1:28000/v1")
    ASR_QWEN3_API_KEY = os.getenv("ASR_QWEN3_API_KEY", "123456")
    ASR_QWEN3_MODEL = os.getenv("ASR_QWEN3_MODEL", "Qwen3-ASR-1.7B-4bit")

    # ------ 长音频分段 ------
    ASR_SEG_TARGET_MIN_S = int(os.getenv("ASR_SEG_TARGET_MIN_S", "10"))  # 段最短
    ASR_SEG_TARGET_MAX_S = int(os.getenv("ASR_SEG_TARGET_MAX_S", "60"))  # 段最长
    ASR_SEG_MIN_SILENCE_MS = int(os.getenv("ASR_SEG_MIN_SILENCE_MS", "500"))  # 批处理判静音(流式 300)
    ASR_SEG_OVERLAP_MS = int(os.getenv("ASR_SEG_OVERLAP_MS", "300"))  # 尾延,只进 pcm 不进报告边界
    ASR_SEG_SNAP_WINDOW_S = float(os.getenv("ASR_SEG_SNAP_WINDOW_S", "2.0"))  # 硬切对齐搜索窗

    # ------ 长音频并发调度 ------
    # 全局推理并发上限 M(整段路径与分段路径共享)。老项目实测脚本结论:待复跑
    # phase2b_concurrency_test 定,默认 2 是保守值,不拍脑袋调大
    ASR_LONG_CONCURRENCY = int(os.getenv("ASR_LONG_CONCURRENCY", "2"))
    ASR_SEG_TIMEOUT_S = float(os.getenv("ASR_SEG_TIMEOUT_S", "120"))  # 单段超时(60s段 whisper CPU RTF≈1 不误杀)
    ASR_SEG_RETRY = int(os.getenv("ASR_SEG_RETRY", "3"))  # 段级重试

    # ------ 长音频任务 ------
    # 低于阈值整段转写, 高于走分段并发
    ASR_LONG_THRESHOLD_S = int(os.getenv("ASR_LONG_THRESHOLD_S", "600"))
    # 内存护栏:解码峰值 ~6.9MB/分钟,4h ≈ 920MB,超过直接拒绝
    ASR_LONG_MAX_DURATION_S = int(os.getenv("ASR_LONG_MAX_DURATION_S", "14400"))
    ASR_TASK_TTL_H = int(os.getenv("ASR_TASK_TTL_H", "24"))  # 任务表过期(小时)

    # ------ 实时语音识别 ------
    # 注:getenv 出来恒为 str,必须就地转型再进协议 JSON——服务端对字符串类型参数
    # 的 run-task 校验宽松(task-started 照回),但收到音频帧后会以 1011
    # "parse parameters failed" 断连(实测 2026-08-26),错误出现在音频阶段极难定位
    ASR_QAS_MODEL = os.getenv("ASR_QAS_MODEL", "qwen-audio-3.0-asr-flash-streaming")
    ASR_QAS_BASE_URL = os.getenv("ASR_QAS_BASE_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/inference")
    ASR_QAS_API_KEY = os.getenv("ASR_QAS_API_KEY", "")
    ASR_QAS_HEARTBEAT = os.getenv("ASR_QAS_HEARTBEAT", "true").lower() == "true"  # 60s 静音保活;心跳帧 provider 内过滤
    ASR_QAS_SILENCE_MS = int(os.getenv("ASR_QAS_SILENCE_MS", "400"))  # max_sentence_silence(交互场景;默认 1300 偏会议)