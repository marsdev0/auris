# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""ASR domain config (transcription/VAD/long-audio segmentation/streaming/scheduling gate). Env prefix ASR_*."""
import os


class AsrSettings:
    DEFAULT_PROVIDER = os.getenv("ASR_PROVIDER", "whisper")
    MODEL = os.getenv("ASR_MODEL", "medium")  # MVP starts with medium; tune to large-v3/small by RTF at acceptance
    DEVICE = os.getenv("ASR_DEVICE", "auto")  # effectively cpu on Mac
    COMPUTE = os.getenv("ASR_COMPUTE", "int8")
    LANG = os.getenv("ASR_LANG", "")   # empty = auto-detect source language; translation belongs to LLM (D4 split), do not set back to zh
    SAMPLE_RATE = 16000
    VAD_THRESHOLD = float(os.getenv("ASR_VAD_THRESHOLD", "0.5"))
    VAD_MIN_SILENCE_MS = int(os.getenv("ASR_VAD_MIN_SILENCE_MS", "300"))
    VAD_SPEECH_PAD_MS = int(os.getenv("ASR_VAD_SPEECH_PAD_MS", "30"))

    INITIAL_PROMPT: str = os.getenv("ASR_INITIAL_PROMPT", "以下是普通话的句子,使用简体中文。")

    QWEN3_BASE_URL = os.getenv("ASR_QWEN3_BASE_URL", "http://127.0.0.1:28000/v1")
    QWEN3_API_KEY = os.getenv("ASR_QWEN3_API_KEY", "123456")
    QWEN3_MODEL = os.getenv("ASR_QWEN3_MODEL", "Qwen3-ASR-1.7B-4bit")

    # ------ Long-audio segmentation ------
    SEG_TARGET_MIN_S = int(os.getenv("ASR_SEG_TARGET_MIN_S", "10"))  # min segment length
    SEG_TARGET_MAX_S = int(os.getenv("ASR_SEG_TARGET_MAX_S", "15"))  # max segment length (measured: non-preemptible unit granularity, see P8 §8.3; 60s segs → sync under load 3.6s, 15s → 1.18s)
    SEG_MIN_SILENCE_MS = int(os.getenv("ASR_SEG_MIN_SILENCE_MS", "500"))  # batch silence detection (streaming uses 300)
    SEG_OVERLAP_MS = int(os.getenv("ASR_SEG_OVERLAP_MS", "300"))  # tail padding, goes into pcm only, not reported boundaries
    SEG_SNAP_WINDOW_S = float(os.getenv("ASR_SEG_SNAP_WINDOW_S", "2.0"))  # hard-cut alignment search window

    # ------ Long-audio per-segment fault tolerance ------
    SEG_TIMEOUT_S = float(os.getenv("ASR_SEG_TIMEOUT_S", "120"))  # per-segment timeout (60s seg, whisper CPU RTF≈1, no false kill)
    SEG_RETRY = int(os.getenv("ASR_SEG_RETRY", "3"))  # segment-level retries

    # ------ Long-audio tasks ------
    # Below threshold: whole-audio transcription; above: segmented concurrency
    LONG_THRESHOLD_S = int(os.getenv("ASR_LONG_THRESHOLD_S", "600"))
    # Memory guard: decode peak ~6.9MB/min, 4h ≈ 920MB, reject beyond that
    LONG_MAX_DURATION_S = int(os.getenv("ASR_LONG_MAX_DURATION_S", "14400"))
    TASK_TTL_H = int(os.getenv("ASR_TASK_TTL_H", "24"))  # task table expiry (hours)

    # ------ Realtime streaming ASR ------
    # Note: getenv always returns str; must cast in place before putting into protocol JSON — the server is lenient
    # about string-typed params in run-task (task-started still returns), but disconnects with 1011
    # "parse parameters failed" once audio frames arrive (measured 2026-08-26); error surfacing at the audio stage is very hard to locate
    QAS_MODEL = os.getenv("ASR_QAS_MODEL", "qwen-audio-3.0-asr-flash-streaming")
    QAS_BASE_URL = os.getenv("ASR_QAS_BASE_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/inference")
    QAS_API_KEY = os.getenv("ASR_QAS_API_KEY", "")
    QAS_HEARTBEAT = os.getenv("ASR_QAS_HEARTBEAT", "true").lower() == "true"  # 60s silence keepalive; heartbeat frames filtered inside provider
    QAS_SILENCE_MS = int(os.getenv("ASR_QAS_SILENCE_MS", "400"))  # max_sentence_silence (interactive; default 1300 suits meetings)

    # ------ Scheduling gate (former ASR_LONG_CONCURRENCY deprecated; M semantics moved to GATE_TOTAL) ------
    GATE_TOTAL = int(os.getenv("ASR_GATE_TOTAL", "3"))  # M: total gate (carried over from 3; calibrated by P10 load test, prefer under)
    GATE_RESERVE = int(os.getenv("ASR_GATE_RESERVE", "1"))  # R: slots reserved for sync requests
    TASK_CONCURRENCY = int(os.getenv("ASR_TASK_CONCURRENCY", "2"))  # per-task segment concurrency
    GATE_QUEUE_MAX = int(os.getenv("ASR_GATE_QUEUE_MAX", "4096"))  # waiter cap (15s segments → ~470 waiters per task; 512 would falsely reject; only multi-task stacking should)
    GATE_AGE_S = int(os.getenv("ASR_GATE_AGE_S", "30"))  # aging step
    GATE_MODE = os.getenv("ASR_GATE_MODE", "semaphore")      # semaphore|priority
    SYNC_MAX_WAIT_S = float(os.getenv("ASR_SYNC_MAX_WAIT_S", "10"))  # sync wait cap
    RETRY_BACKOFF_BASE_S = float(os.getenv("ASR_RETRY_BACKOFF_BASE_S", "1"))  # retry backoff base
