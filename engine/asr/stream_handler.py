# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""流式 ASR handler(路线 A:伪流式)。

PCM 字节流 → VAD 检测闭合语段 → 整段送 provider.transcribe() → PartialResult(is_final=True)。
能力驱动降级:STREAMING → provider.stream() 真 partial(路线 B,待实现);否则走本路径。

时间轴(绝对样本位;16k 下 1ms=16 样本,1 样本=2 字节):
  _base_sample  _buf[0] 的绝对样本位——_drop_before 修剪时前移
  _fed_sample   已喂入 VAD 的绝对样本位(按 512 样本/窗推进,与 VADIterator 内部同步)
  _seg_start    当前未闭合语段起点;None = 不在语音中
ms 只在产出 PartialResult 的边界换算——silero 的事件时间戳本来就是样本位。

三游标一图(_buf 是活的,修剪只挪基准,游标不动):
  0 ────── _base_sample ──── _fed_sample ──── _buf 尾
             ◀── 已丢弃 ──▶ ◀── 攒着未喂(等凑满 512) ─▶ ◀── 已喂 ──▶
"""
from typing import Iterator, NamedTuple

import numpy as np

from engine.asr.audio import pcm_to_wav
from engine.asr.provider import AsrCapability
from engine.asr.schemas import PartialResult
from engine.asr.service import get_asr_service
from engine.asr.vad import get_vad_engine
from engine.config import Settings

_VAD_WINDOW = 512  # silero 16k 固定窗口 = 512 样本(32ms),模型写死,喂其他长度直接报错
_MAX_SEG_SAMPLES = 15_000 * 16  # 强切段上限(15s):用户不停顿就等不到 end 事件
# 静音期护栏:不在语音中时,已喂的静音最多回留这么多样本。
# silero 的 start 时间戳最多回看 speech_pad + 一个窗口(默认 480+512=992 样本),
# 保留 pad + 两个窗口,保证任何 start 时间戳都不会因提前丢弃而被截。
_SILENCE_GUARD = Settings.ASR_VAD_SPEECH_PAD_MS * 16 + _VAD_WINDOW * 2


class _Segment(NamedTuple):
    """一个闭合语段:裸 PCM + 起止时间(流内绝对毫秒)。"""
    pcm: bytes
    beg_ms: int
    end_ms: int


class StreamHandler:
    """每个 WS 连接一个实例。有状态(buffer / VAD 迭代器 / 时间轴),严禁跨连接复用。"""

    def __init__(self, provider_name: str | None) -> None:
        self._provider = get_asr_service().get(provider_name)
        self._vad = get_vad_engine().new_iterator()   # 每连接独立的有状态迭代器
        self._buf = bytearray()                       # 未吐出的 PCM(16bit LE mono @16k)
        self._base_sample = 0
        self._fed_sample = 0
        self._seg_start: int | None = None

    # ---------- 对外入口 ----------

    async def on_audio(self, pcm: bytes) -> list[PartialResult]:
        """每帧二进制音频调一次;返回本次闭合出的 0..n 个 final(逐条下发)。"""
        self._buf.extend(pcm)
        results: list[PartialResult] = []

        # ===== 能力驱动降级:STREAMING 是 MVP 唯一的决策点 =====
        if AsrCapability.STREAMING in self._provider.capabilities:
            # TODO 路线 B:provider.stream() 真 partial(未来 Paraformer-streaming)
            raise NotImplementedError("STREAMING provider 的真流式路径待实现")

        # 路线 A(whisper):VAD 找闭合段 → 每段整体转
        for window in self._iter_16k_chunks():
            event = self._vad(window)                 # None / {start} / {end}

            if event and "start" in event:
                # 起点已含 speech_pad 前扩;它之前的静音丢掉,防 buffer 无界
                self._seg_start = max(event["start"], self._base_sample)
                self._drop_before(self._seg_start)

            elif event and "end" in event:
                seg = self._pop_closed_segment(event["end"])
                if seg:
                    results.append(await self._transcribe(seg))

            # 强切段:说了太久不停顿,VAD 等不到静音,说满上限强切
            if (self._seg_start is not None
                    and self._fed_sample - self._seg_start >= _MAX_SEG_SAMPLES):
                seg = self._pop_closed_segment(self._fed_sample)
                self._seg_start = self._fed_sample    # 语音大概率仍在,新段从此起
                if seg:
                    results.append(await self._transcribe(seg))

            # 静音期内存护栏:不在语音中时丢弃更早的已喂静音,防长连接纯静音下无界增长
            if self._seg_start is None:
                self._drop_before(self._fed_sample - _SILENCE_GUARD)

        return results

    async def flush(self) -> PartialResult | None:
        """stop 时:未闭合的段不再等静音判定,直接整段转掉。

        _seg_start 为 None 说明 VAD 从未见过语音(或段已闭合)——剩下的只有
        静音和不足一个窗口(<32ms)的尾巴,丢弃(送 whisper 纯浪费且易幻觉)。"""
        if self._seg_start is None:
            return None
        end = self._base_sample + len(self._buf) // 2
        seg = self._pop_closed_segment(end)
        return await self._transcribe(seg) if seg else None

    # ---------- 内部:_buf → 窗口流 / 闭合段 → 结果 ----------

    # silero VAD 的模型结构固定：16kHz 下每次调用只吃 512 样本（32ms）
    def _iter_16k_chunks(self) -> Iterator[np.ndarray]:
        """
        _buf → silero 窗口流:每次吐一个 512 样本(32ms)的归一化 float32 窗。
        VADIterator 一次只吃一个固定窗口,内部 current_sample 随窗口累加——
        必须严格按 512 喂,事件时间戳(绝对样本位)才能与 _fed_sample 对齐;
        不足一个窗口的尾巴留在 _buf,等下一帧音频补齐。

        注意:窗口取自 _buf 的切片副本而非直接视图——np.frombuffer 直读活
        bytearray 会持有 buffer 导出,生成器挂起期间 _drop_before 的 del 会
        触发 BufferError(bytearray 持有导出时禁止 resize)。

        16bit = 2byte
        """
        while max(self._fed_sample - self._base_sample, 0) * 2 + _VAD_WINDOW * 2 <= len(self._buf):
            # base 可能短暂超过 fed(flush 丢弃了全部数据)——钳 0 自愈,不再错切负偏移
            off = max(self._fed_sample - self._base_sample, 0) * 2
            raw = np.frombuffer(self._buf[off:off + _VAD_WINDOW * 2], dtype="<i2")
            yield raw.astype(np.float32) / 32768.0  # int16 → [-1,1](silero 要 float)
            self._fed_sample += _VAD_WINDOW

    def _pop_closed_segment(self, end_sample: int) -> _Segment | None:
        """按 [seg_start, end_sample) 切出闭合段,并修剪 _buf(段取出,之前的字节全可丢)。

        碎片(<50ms,几乎只剩 pad)与孤儿 end(无 seg_start)不值得送 whisper,按静音丢弃。"""
        if self._seg_start is None:      # 孤儿 end:仅 handler 在语音中途创建才可能出现
            # 钳到 fed:真实 silero 的时间戳不会超前,但防御外部 VAD/未来改动——
            # 一旦 base 被推过 fed,_iter 的 off 变负、while 条件恒真,窗口错切尾部数据
            self._drop_before(min(end_sample, self._fed_sample))
            return None
        start = max(self._seg_start, self._base_sample)
        end = min(end_sample, self._base_sample + len(self._buf) // 2)
        self._seg_start = None
        if end - start < 50 * 16:
            self._drop_before(end)
            return None
        seg = bytes(self._buf[(start - self._base_sample) * 2:(end - self._base_sample) * 2])
        self._drop_before(end)
        return _Segment(seg, start // 16, end // 16)  # ms 换算只发生在产出边界

    async def _transcribe(self, seg: _Segment) -> PartialResult:
        """整段送 provider:裸 PCM 包 WAV 头(transcribe 契约是"任意格式字节")→ final。"""
        # NOTE: whisper 推理是 CPU 同步阻塞;多连接并发时应换 asyncio.to_thread
        result = await self._provider.transcribe(pcm_to_wav(seg.pcm))
        return PartialResult(is_final=True, text=result.text.strip(),
                             beg_ms=seg.beg_ms, end_ms=seg.end_ms)

    def _drop_before(self, sample: int) -> None:
        """丢弃绝对样本位 sample 之前的字节(已消费的静音 / 已吐出的段)。"""
        n = (sample - self._base_sample) * 2
        if n > 0:
            del self._buf[:n]
            self._base_sample = sample
