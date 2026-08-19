# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""编排层 VAD(语音活动检测)——流式切段(路线 A:伪流式)。

与 whisper provider 内置的 vad_filter 是两处不同职责(不是两套并存):
  - whisper 内置 VAD(onnx, 无 torch):provider 黑盒内,非流式转写前去静音、防幻觉;
  - 本模块 VadEngine(编排层):流式链路中检测"闭合语段",供 StreamAsrHandler 切段
    后整段送 provider.transcribe()——伪流式的分段时机由它决定。

"VAD 单一来源"纪律针对编排层:全进程只有一个 VadEngine(见 get_vad_engine),
规避旧项目"流式一套 VAD、长音频另一套"的坑。
"""
import numpy as np
from silero_vad import load_silero_vad, VADIterator, get_speech_timestamps

from engine.config import Settings


class VadEngine:
    """进程单例。持有 silero VAD 模型(几 MB,加载一次,所有连接共享)。

    模型本身无状态,可安全多连接并发;**有状态的是每连接的 VADIterator**
    (内部维护"当前是否在说话/静音计时/已输出的语音段"等),所以每个
    WS 连接调 new_iterator() 各拿一份,互不污染。

    silero 模型的 chunk 固定为 512 样本(16kHz 下 32ms),VADIterator
    会按该步长消费音频;喂入的每个 chunk 会得到一个事件:
      None        —— 状态无变化(仍在说话,或仍在静音)
      {"start": t} —— 检测到语音起点,t 为该 chunk 的时间戳(样本数)
      {"end": t}   —— 检测到语音终点,此时该语段已闭合,可切段送转写
    """

    def __init__(self):
        # silero-vad 官方权重(包内自带,首次用会从 torch hub 缓存读取)
        self._model = load_silero_vad()

    def new_iterator(self) -> VADIterator:
        """新建一个有状态迭代器(每个 WS 连接一份)。"""
        return VADIterator(
            self._model,
            # 语音概率阈值:模型对每个 512 样本 chunk 输出"是语音的概率",
            # ≥ threshold 判为语音。调高→切得更保守(漏字少、出字慢);
            # 调低→切得更灵敏(出字快、易把噪声当语音)。
            threshold=Settings.ASR_VAD_THRESHOLD,
            # 采样率。silero 支持 8000/16000,必须与喂入的 PCM 一致,
            # 且等于 ASR_SAMPLE_RATE(16000,单一来源)。
            sampling_rate=Settings.ASR_SAMPLE_RATE,
            # 判定"说完了"所需的最短静音时长(ms)。语音段内出现 ≥ 该值
            # 的连续静音才触发 {"end"} 事件闭合语段。这是伪流式尾部延迟
            # 的主要来源之一:用户停顿这么久,切段才会发生。
            # 调大→段更长更完整(语义边界好),但出字更慢。
            min_silence_duration_ms=Settings.ASR_VAD_MIN_SILENCE_MS,
            # 语音段前后各扩展的静音边距(ms)。silero 触发 start/end 的
            # 时刻比真实语音边界略靠内,前后各补一段静音,避免切掉
            # 词头/词尾的弱音(气音、轻声)。切出的段转写前不用再 pad。
            speech_pad_ms=Settings.ASR_VAD_SPEECH_PAD_MS,
        )

    def detect_regions(self, audio: np.ndarray) -> list[tuple[int, int]]:
        """批处理入口（长音频分段用）：整段音频 -> 语音区[start, end)样本位列表
        与流式路径 new_iterator() 共享同一个 silero 模型单例
        流式：VADIterator 逐窗推进、吐 start/end 事件，禁音判定参数为300ms
        批式：整段一次检测，直接拿全部语音区，静音判定参数为500ms
        """
        ts = get_speech_timestamps(
            audio=audio,
            model=self._model,
            threshold=Settings.ASR_VAD_THRESHOLD,
            min_speech_duration_ms=250,
            min_silence_duration_ms=Settings.ASR_SEG_MIN_SILENCE_MS,
            speech_pad_ms=Settings.ASR_VAD_SPEECH_PAD_MS,
            sampling_rate=Settings.ASR_SAMPLE_RATE,
        )
        return [(t["start"], t["end"]) for t in ts]

_engine: VadEngine | None = None


def get_vad_engine() -> VadEngine:
    """惰性单例:首次调用加载模型,之后复用同一个 VadEngine。"""
    global _engine
    if _engine is None:
        _engine = VadEngine()
    return _engine
