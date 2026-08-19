# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""长音频分段器

任意格式音频字节 → VAD 语音区 → 归一化(合并过短/硬拆过长)
→ 硬切点静音对齐 → 切片(尾延 overlap) → list[Seg]。

两类边界,两套处理:
  VAD 静音边界 —— 段间必有 ≥ ASR_SEG_MIN_SILENCE_MS 的静音,天然不腰斩词,
                  这是"VAD 归一化分段"优于"固定切块"的根;
  硬切边界     —— 连续说话超 target_max 逼出来的(说话人不喘气)。
                  _snap 把切点挪到 ±snap 窗内能量最小的 100ms 处(大概率是
                  换气/停顿),是硬切段的主要保护;尾延对它天然失效(见 _slice)。

报告边界(start/end)与转写负载(pcm)分离:
  pcm 尾部多带 ASR_SEG_OVERLAP_MS 静音兜底,但 start/end 保持干净——
  业务层拿时间戳做字幕对齐时不会被 overlap 污染。
"""
from dataclasses import dataclass

import numpy as np

from engine.asr.audio import load_audio
from engine.asr.vad import get_vad_engine
from engine.config import Settings


@dataclass
class Seg:
    index: int
    start: int
    end: int
    pcm: bytes


_SR = Settings.ASR_SAMPLE_RATE  # 16000,单一来源(1ms = 16 样本)
_FRAME_MS = 100  # _snap 能量帧宽:找"最静的 100ms 窗"
_GUARD_S = 1.0  # _snap 每侧至少保留时长:防切点挪出段外把段切碎


class Segmenter:

    def __init__(self, target_min: float = 10.0, target_max: float = 60.0):
        self._target_min = target_min
        self._target_max = target_max

    def segment(self, audio: bytes) -> tuple[list[Seg], float]:
        """任意格式音频字节 → (分段列表, 总时长秒)。全内存,零临时文件。"""
        # 任意格式转成 16k float32
        y = load_audio(audio)
        duration = len(y) / _SR

        raw = get_vad_engine().detect_regions(y)
        if not raw:
            return [], duration

        # 合并<10s、硬拆>60s
        norm = self._normalize(raw)
        # 硬切点静音对齐
        norm = self._snap(norm, y)
        return self._slice(norm, y), duration

    def _normalize(self, raw: list[tuple[int, int]]) -> list[tuple[int, int]]:
        """VAD 语音区 → 10~60s 目标区间。
        合并:当前区 < target_min 且并入前区后总跨度 ≤ target_max → 并入。
            跨度从前区起点量到当前区终点(含中间静音),因为静音也随段
            一起送转写,不量跨度会把段撑爆。
        拆分:单区 > target_max(说话人不喘气)→ 按 target_max 硬切。
            硬切切在语音中间,是腰斩词的唯一来源——切点交给 _snap 对齐。
        """
        tmin = int(Settings.ASR_SEG_TARGET_MIN_S * _SR)
        tmax = int(Settings.ASR_SEG_TARGET_MAX_S * _SR)

        # 1. 合并 - 当前段太短时合并到上一段
        merged: list[tuple[int, int]] = []
        for start, end in raw:
            if merged and end - start < tmin and end - merged[-1][0] <= tmax:
                # 只改重点，吞掉中间静音
                merged[-1] = (merged[-1][0], end)
                continue
            merged.append((start, end))

        # 2. 拆分
        out: list[tuple[int, int]] = []
        for start, end in merged:
            if (end - start) <= tmax:
                out.append((start, end))
                continue
            cur = start
            while end - cur > tmax:
                out.append((cur, cur + tmax))
                cur += tmax
            if end - cur > 0:
                # 尾块太短(< tmin)时并入最后一个整块,而不是留个 2s 碎段。
                # out[-1][0] >= start 保证并入的确实是同一个长语音区切出来的块
                # (out 里可能还有更早语音区的段,不能吞错)
                if end - cur < tmin and out and out[-1][0] >= start:
                    out[-1] = (out[-1][0], end)
                else:
                    out.append((cur, end))
        return out

    def _snap(self, segs: list[tuple[int, int]], y: np.ndarray) -> list[tuple[int, int]]:
        """硬切边界 → 附近最静处。

        只处理硬切边界:相邻段间隙 < ASR_SEG_MIN_SILENCE_MS 说明不是 VAD 静音
        边界(硬切块间隙恒为 0)——静音边界本来就切在静音里,不用动。

        在切点 ± ASR_SEG_SNAP_WINDOW_S 内按 100ms 帧算能量,取最小帧的
        中心作新切点——大概率落在换气/词间隙。边界是相邻两段共享的,
        起点/终点要一起挪;原地顺序更新,后一个边界的搜索范围自然基于
        已挪好的前段。
        """
        snap = int(Settings.ASR_SEG_SNAP_WINDOW_S * _SR)
        frame = _FRAME_MS * _SR // 1000  # 100ms = 1600 样本
        guard = int(_GUARD_S * _SR)  # 每侧至少留 1s,防把段切没
        # 间隙小于"VAD 判静音的最短时长"即视为硬切边界(正常 VAD 边界 ≥ 500ms)
        min_gap = Settings.ASR_SEG_MIN_SILENCE_MS * _SR // 1000

        out = list(segs)
        for i in range(len(out) - 1):
            a, b = out[i]  # 段 i: (start, end)
            c, d = out[i + 1]  # 段 i+1: (start, end)
            if c - b >= min_gap:
                continue  # VAD 静音边界,天然安全

            # 搜索窗:切点 ±snap,且不许越出两段各自的最小保留区
            lo = max(a + guard, b - snap)
            hi = min(d - guard, b + snap)
            if hi - lo < 2 * frame:
                continue  # 搜索空间不足(段太短),保持原切点

            # 非重叠 100ms 帧逐帧算均方能量,取最小帧中心
            # (均方/均方根对 argmin 等价,省一次 sqrt)
            region = y[lo:hi]
            n_frames = len(region) // frame
            # 第 0 帧当选基准,之后严格更小才替换;并列(能量无区分度,
            # 如纯数字静音文件)时取最早的最小帧——确定性,不依赖初值魔法
            best = lo + frame // 2
            best_e = float(np.mean(region[:frame] ** 2))
            for k in range(1, n_frames):
                chunk = region[k * frame:(k + 1) * frame]
                e = float(np.mean(chunk * chunk))
                if e < best_e:
                    best_e = e
                    best = lo + k * frame + frame // 2  # 帧中心作新切点

            out[i] = (a, best)  # 边界共享:两段一起挪
            out[i + 1] = (best, d)
        return out

    def _slice(self, segs: list[tuple[int, int]], y: np.ndarray) -> list[Seg]:
        """归一化边界 → Seg 列表(overlap 只进 pcm,不进报告边界)。

        尾延的边界:payload_end = min(end + overlap, 下一段 start, 音频末尾)。
          VAD 静音边界:段间静音 ≥ 500ms > overlap 300ms,尾延整段落在
            这段静音里——上段的转写吃到一点尾部静音上下文,不会吃进
            下段的词;
          硬切边界:间隙为 0,尾延自然为 0——硬切段的保护来自 _snap,
            不来自尾延(这正是 snap 必须存在的原因,别删);
          最后一段:next_start 取音频末尾,尾延可吃结尾静音。
        """
        overlap = Settings.ASR_SEG_OVERLAP_MS * _SR // 1000
        total = len(y)

        out: list[Seg] = []
        for i, (start, end) in enumerate(segs):
            next_start = segs[i + 1][0] if i + 1 < len(segs) else total
            payload_end = min(end + overlap, next_start, total)
            out.append(Seg(
                index=i,
                start=start,  # 报告边界:干净,无 overlap
                end=end,
                pcm=self._to_pcm(y[start:payload_end]),  # 负载:含尾延
            ))
        return out

    @staticmethod
    def _to_pcm(y: np.ndarray) -> bytes:
        """float32 [-1,1] → int16 LE bytes(与流式路径 WS 帧同格式)。

        缩放用 32767 而非 32768:乘 32768 时 +1.0 → 32768.0 超出 int16 上限,
        astype 溢出翻转成 -32768(正最大值变成负最大,爆音+识别劣化)——
        clip 防的是越界输入,防不了 scale 本身出界。
        """
        return (np.clip(y, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()
