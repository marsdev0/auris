# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""长音频拼装(形态④管线第三步)。

scheduler 的 list[SegmentResult] → LongTaskResult(全文 + 段级明细)。

不做重叠文本对齐去重(设计文档 §3.3 的决策,三层防护替代):
  ① VAD 归一化后段边界处必有 ≥500ms 静音,300ms 尾延整段落在静音里;
  ② 硬切点被 _snap 挪到低能量处(且硬切边界尾延为 0);
  ③ 真出现的个别重复词是可接受噪音,不是设计缺陷。

失败段留 [转写失败] 占位,不静默——让用户看见缺漏(老项目验证过的决策)。
"""
from pydantic import BaseModel

from engine.asr.long_audio.scheduler import SegmentResult

FAILED_PLACEHOLDER = "[转写失败]"


class SegmentOut(BaseModel):
    """段级明细的对外投影(与调度层 SegmentResult 解耦:单位换算成 ms、只留对外字段)。"""
    index: int
    start_ms: int
    end_ms: int
    text: str
    ok: bool
    latency_ms: float


class LongTaskResult(BaseModel):
    """completed 任务的产物。"""
    text: str
    segments: list[SegmentOut]
    failed: int   # 失败段数(占位符数)


class Assembler:

    @staticmethod
    def assemble(results: list[SegmentResult]) -> LongTaskResult:
        """按报告边界排序后拼全文 + 保留段级明细。

        scheduler 返回本就有序(gather 保序),排序是防御——结果可能来自
        重试后的重组或未来的断点续跑,别赌上游。
        """
        ordered = sorted(results, key=lambda r: r.start)
        return LongTaskResult(
            text="\n".join(
                r.text.strip() or FAILED_PLACEHOLDER if r.ok else FAILED_PLACEHOLDER
                for r in ordered
            ),
            segments=[
                SegmentOut(
                    index=r.index,
                    start_ms=r.start // 16,  # 样本位→ms(16k 下 1ms=16样本)
                    end_ms=r.end // 16,
                    text=r.text,
                    ok=r.ok,
                    latency_ms=r.latency_ms,
                )
                for r in ordered
            ],
            failed=sum(1 for r in ordered if not r.ok),
        )
