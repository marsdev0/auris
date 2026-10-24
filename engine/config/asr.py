# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""ASR 域配置(转写/VAD/长音频分段/流式/调度闸门)。env 前缀 ASR_*。"""
import os


class AsrSettings:
    DEFAULT_PROVIDER = os.getenv("ASR_PROVIDER", "whisper")
    MODEL = os.getenv("ASR_MODEL", "medium")  # MVP 先 medium;验收按 RTF 调 large-v3/small
    DEVICE = os.getenv("ASR_DEVICE", "auto")  # Mac 实际 cpu
    COMPUTE = os.getenv("ASR_COMPUTE", "int8")
    LANG = os.getenv("ASR_LANG", "")   # 空=自动检测源语言;翻译归 LLM(D4 分工),勿改回 zh
    SAMPLE_RATE = 16000
    VAD_THRESHOLD = float(os.getenv("ASR_VAD_THRESHOLD", "0.5"))
    VAD_MIN_SILENCE_MS = int(os.getenv("ASR_VAD_MIN_SILENCE_MS", "300"))
    VAD_SPEECH_PAD_MS = int(os.getenv("ASR_VAD_SPEECH_PAD_MS", "30"))

    INITIAL_PROMPT: str = os.getenv("ASR_INITIAL_PROMPT", "以下是普通话的句子,使用简体中文。")

    QWEN3_BASE_URL = os.getenv("ASR_QWEN3_BASE_URL", "http://127.0.0.1:28000/v1")
    QWEN3_API_KEY = os.getenv("ASR_QWEN3_API_KEY", "123456")
    QWEN3_MODEL = os.getenv("ASR_QWEN3_MODEL", "Qwen3-ASR-1.7B-4bit")

    # ------ 长音频分段 ------
    SEG_TARGET_MIN_S = int(os.getenv("ASR_SEG_TARGET_MIN_S", "10"))  # 段最短
    SEG_TARGET_MAX_S = int(os.getenv("ASR_SEG_TARGET_MAX_S", "15"))  # 段最长(实测:不可抢占单元粒度,见 P8 §8.3;60s 时 sync under load 3.6s,15s 时 1.18s)
    SEG_MIN_SILENCE_MS = int(os.getenv("ASR_SEG_MIN_SILENCE_MS", "500"))  # 批处理判静音(流式 300)
    SEG_OVERLAP_MS = int(os.getenv("ASR_SEG_OVERLAP_MS", "300"))  # 尾延,只进 pcm 不进报告边界
    SEG_SNAP_WINDOW_S = float(os.getenv("ASR_SEG_SNAP_WINDOW_S", "2.0"))  # 硬切对齐搜索窗

    # ------ 长音频段级容错 ------
    SEG_TIMEOUT_S = float(os.getenv("ASR_SEG_TIMEOUT_S", "120"))  # 单段超时(60s段 whisper CPU RTF≈1 不误杀)
    SEG_RETRY = int(os.getenv("ASR_SEG_RETRY", "3"))  # 段级重试

    # ------ 长音频任务 ------
    # 低于阈值整段转写, 高于走分段并发
    LONG_THRESHOLD_S = int(os.getenv("ASR_LONG_THRESHOLD_S", "600"))
    # 内存护栏:解码峰值 ~6.9MB/分钟,4h ≈ 920MB,超过直接拒绝
    LONG_MAX_DURATION_S = int(os.getenv("ASR_LONG_MAX_DURATION_S", "14400"))
    TASK_TTL_H = int(os.getenv("ASR_TASK_TTL_H", "24"))  # 任务表过期(小时)

    # ------ 实时语音识别 ------
    # 注:getenv 出来恒为 str,必须就地转型再进协议 JSON——服务端对字符串类型参数
    # 的 run-task 校验宽松(task-started 照回),但收到音频帧后会以 1011
    # "parse parameters failed" 断连(实测 2026-08-26),错误出现在音频阶段极难定位
    QAS_MODEL = os.getenv("ASR_QAS_MODEL", "qwen-audio-3.0-asr-flash-streaming")
    QAS_BASE_URL = os.getenv("ASR_QAS_BASE_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/inference")
    QAS_API_KEY = os.getenv("ASR_QAS_API_KEY", "")
    QAS_HEARTBEAT = os.getenv("ASR_QAS_HEARTBEAT", "true").lower() == "true"  # 60s 静音保活;心跳帧 provider 内过滤
    QAS_SILENCE_MS = int(os.getenv("ASR_QAS_SILENCE_MS", "400"))  # max_sentence_silence(交互场景;默认 1300 偏会议)

    # ------ 调度闸门(原 ASR_LONG_CONCURRENCY 已废弃,M 语义归 GATE_TOTAL) ------
    GATE_TOTAL = int(os.getenv("ASR_GATE_TOTAL", "3"))  # M：总闸门(原值 3 平移;P10 实测校准,宁欠勿超)
    GATE_RESERVE = int(os.getenv("ASR_GATE_RESERVE", "1"))  # R：给同步请求预留的位数
    TASK_CONCURRENCY = int(os.getenv("ASR_TASK_CONCURRENCY", "2"))  # 单任务段级
    GATE_QUEUE_MAX = int(os.getenv("ASR_GATE_QUEUE_MAX", "4096"))  # 等待者上限(15s 段粒度下单任务 ~470 waiter,512 会误拒;多任务叠加才该拒)
    GATE_AGE_S = int(os.getenv("ASR_GATE_AGE_S", "30"))  # aging步长
    GATE_MODE = os.getenv("ASR_GATE_MODE", "semaphore")      # semaphore|priority
    SYNC_MAX_WAIT_S = float(os.getenv("ASR_SYNC_MAX_WAIT_S", "10"))  # sync 等待上限
    RETRY_BACKOFF_BASE_S = float(os.getenv("ASR_RETRY_BACKOFF_BASE_S", "1"))  # 重试退避基数
