# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""全局并发闸门(P8 实现设计 §3/§5)。

只管「M 个位先给谁」,不管业务(优先级由入口形态自判,§2)。
两种模式(ASR_GATE_MODE,判定收敛在本模块,三个收编点无感知,§5.7):
  semaphore — ① 双信号量预留槽:batch 先过 SEM_BATCH(M-R) 再过 SEM_TOTAL(M),
             sync 只过总闸 → R 个位永不被批量占满
  priority  — ③ PriorityGate:有界优先级队列 + aging + per-task cap;
             ② 的 task_sem 语义由 TASK_CAP 吸收(②已裁撤,见设计 §4 注)

对外三个入口(收编点不感知模式):
  sync_lease()             POST /transcribe 同步          priority=0
  short_lease(task_id)     异步任务·整段短路(<阈值)     priority=1
  segment_lease(task_id)   长任务分段路径                 priority=2
"""
import asyncio
import time
from collections import deque
from contextlib import asynccontextmanager
from dataclasses import dataclass

from loguru import logger

from engine.config import ASR

assert ASR.GATE_TOTAL > ASR.GATE_RESERVE > 0, \
    "启动校验(§3.3):ASR_GATE_TOTAL > ASR_GATE_RESERVE > 0(M=R 意味批量零并发,静默死锁)"


class QueueFullError(Exception):
    """队列已满或 sync 等待超时(engine 层最后防线;业务准入在 P9)。"""


# ==================== 模式一:双信号量(Step ①,§3) ====================

SEM_TOTAL = asyncio.Semaphore(ASR.GATE_TOTAL)          # M
SEM_BATCH = asyncio.Semaphore(ASR.GATE_TOTAL - ASR.GATE_RESERVE)


# ==================== 模式二:PriorityGate(Step ③,§5) ====================

@dataclass
class GateRequest:
    priority: int          # 0/1/2(§2 入口自判)
    task_id: str | None    # per-task cap 记账键;sync 为 None
    kind: str              # "sync"/"short"/"batch",仅观测标签


class _Waiter:
    """排队中的一个请求。§5.5:granted 标记区分「中签后取消」与「未中签取消」。"""

    __slots__ = ("req", "t_enqueue", "seq", "future", "granted", "t_grant", "skip_count")

    def __init__(self, req: GateRequest, t_enqueue: float, seq: int):
        self.req = req
        self.t_enqueue = t_enqueue        # 单调时基,aging 用
        self.seq = seq                    # 到达序,同优先级 FIFO 决胜
        self.future = asyncio.get_running_loop().create_future()
        self.granted = False              # _drain 置位;cancel 分支据此决定是否还位
        self.t_grant: float | None = None
        self.skip_count = 0               # 被 cap 跳过次数(观测)


class _Lease:
    """中签凭证。release 幂等(正常退出/中签后取消两路径共用)。"""

    def __init__(self, gate: "PriorityGate", w: _Waiter):
        self._gate, self._w = gate, w
        self._released = False

    def release(self) -> None:
        if not self._released:
            self._released = True
            self._gate._release(self._w)


def _pct(xs: list[float], p: float) -> float:
    if not xs:
        return 0.0
    s = sorted(xs)
    return s[min(int(len(s) * p), len(s) - 1)]


class PriorityGate:
    """有界优先级队列闸门。核心不变式(§5.3):inflight < M ⟺ waiters 空(或全员被 cap 卡住)。"""

    def __init__(self) -> None:
        self._capacity = ASR.GATE_TOTAL
        self._queue_max = ASR.GATE_QUEUE_MAX
        self._age_s = ASR.GATE_AGE_S
        self._task_cap = ASR.TASK_CONCURRENCY
        self._waiters: list[_Waiter] = []
        self._inflight = 0
        self._inflight_by_task: dict[str, int] = {}
        self._seq = 0
        self._wait_ms: dict[str, deque] = {k: deque(maxlen=1024) for k in ("sync", "short", "batch")}
        self._hold_ms: dict[str, deque] = {k: deque(maxlen=1024) for k in ("sync", "short", "batch")}

    # ---- 排序键:aging 现算(§5.2 弃堆用列表的理由) ----
    def _sort_key(self, w: _Waiter) -> tuple[int, int]:
        waited = time.monotonic() - w.t_enqueue
        age = int(waited // self._age_s) if self._age_s > 0 else 0
        # aging 下限定 1(实测教训:降到 0 会与 sync 平权,批量洪峰下 sync 被
        # 几百个 aged waiter 按到达序埋死 → 10s 超时 429;batch 永不越过 sync,
        # 靠 sync 需求的间隙推进,而 sync 需求本身有界——与 semaphore 模式的
        # R 预留同哲学)
        if w.req.priority <= 0:
            eff = 0
        else:
            eff = w.req.priority - min(age, w.req.priority - 1)  # 最低降到 1,不越权
        return (eff, w.seq)

    def _drain(self) -> None:
        """还位/新到后补位。同步代码段,事件循环内不交错(§5.5 论证的前提)。"""
        while self._inflight < self._capacity and self._waiters:
            skipped: list[_Waiter] = []
            picked = False
            while self._waiters:
                w = min(self._waiters, key=self._sort_key)
                if w.req.task_id and self._inflight_by_task.get(w.req.task_id, 0) >= self._task_cap:
                    self._waiters.remove(w); skipped.append(w); w.skip_count += 1
                    continue
                self._waiters.remove(w)
                if w.future.cancelled():        # §5.5 双保险:cancel 交接窗口内的死等待者
                    continue
                self._inflight += 1
                if w.req.task_id:               # sync(None)不记键,与 cap 检查对称
                    self._inflight_by_task[w.req.task_id] = self._inflight_by_task.get(w.req.task_id, 0) + 1
                w.granted = True                # 中签标记(cancel 分支据此还位)
                w.t_grant = time.monotonic()
                w.future.set_result(None)       # 唤醒等待者
                picked = True
                break
            self._waiters.extend(skipped)       # 被 cap 跳过的放回
            if not picked:
                break                           # 全被 cap 卡住,空位留给 sync

    async def acquire(self, req: GateRequest) -> _Lease:
        if len(self._waiters) >= self._queue_max:
            raise QueueFullError(f"gate 队列已满({self._queue_max})")
        w = _Waiter(req, time.monotonic(), self._seq)
        self._seq += 1
        self._waiters.append(w)
        self._drain()                            # 空闲时立刻中签
        try:
            await w.future
        except asyncio.CancelledError:
            if w.granted:                        # 中签后取消:还位(§5.5)
                self._release(w)
            elif w in self._waiters:             # 未中签:仅摘除,不碰 inflight
                self._waiters.remove(w)
            raise
        self._wait_ms[req.kind].append((w.t_grant - w.t_enqueue) * 1000)
        return _Lease(self, w)

    def _release(self, w: _Waiter) -> None:
        self._inflight -= 1
        if w.req.task_id:
            n = self._inflight_by_task.get(w.req.task_id, 1) - 1
            if n > 0:
                self._inflight_by_task[w.req.task_id] = n
            else:
                self._inflight_by_task.pop(w.req.task_id, None)
        self._hold_ms[w.req.kind].append((time.monotonic() - w.t_grant) * 1000)
        self._drain()

    def stats(self) -> dict:
        def dist(d: deque) -> dict:
            xs = list(d)
            return {"count": len(xs), "p50_ms": round(_pct(xs, .5), 1), "p99_ms": round(_pct(xs, .99), 1)}
        return {
            "mode": "priority",
            "capacity": self._capacity,
            "inflight": self._inflight,
            "queue_depth": {k: sum(1 for w in self._waiters if w.req.kind == k)
                            for k in ("sync", "short", "batch")},
            "inflight_by_task": dict(self._inflight_by_task),
            "wait_ms": {k: dist(v) for k, v in self._wait_ms.items()},
            "hold_ms": {k: dist(v) for k, v in self._hold_ms.items()},  # 下游单容量侧写,喂 P10 定 M
        }


# ==================== 模式分发(§5.7,收编点无感知) ====================

_GATE: PriorityGate | None = PriorityGate() if ASR.GATE_MODE == "priority" else None
logger.info(f"gate 模式: {ASR.GATE_MODE}"
            f"(M={ASR.GATE_TOTAL}"
            + (f", queue_max={ASR.GATE_QUEUE_MAX})" if _GATE
               else f", R={ASR.GATE_RESERVE})"))


@asynccontextmanager
async def sync_lease():
    """同步路径:priority 0。有界等待(§5.4):超时抛 QueueFullError → router 映射 429。"""
    if _GATE is None:
        try:
            await asyncio.wait_for(SEM_TOTAL.acquire(), timeout=ASR.SYNC_MAX_WAIT_S)
        except asyncio.TimeoutError:
            raise QueueFullError(f"sync 等待超时(>{ASR.SYNC_MAX_WAIT_S}s)")
        try:
            yield
        finally:
            SEM_TOTAL.release()
    else:
        try:
            lease = await asyncio.wait_for(
                _GATE.acquire(GateRequest(0, None, "sync")),
                timeout=ASR.SYNC_MAX_WAIT_S)
        except asyncio.TimeoutError:
            # 超时 cancel 走 acquire 的取消安全分支(§5.5):中签则还位,未中签则摘除,不泄漏
            raise QueueFullError(f"sync 等待超时(>{ASR.SYNC_MAX_WAIT_S}s)")
        try:
            yield
        finally:
            lease.release()


@asynccontextmanager
async def short_lease(task_id: str | None = None):
    """异步任务·整段短路:priority 1。semaphore 模式与 batch 共用批量闸。"""
    if _GATE is None:
        async with SEM_BATCH, SEM_TOTAL:
            yield
    else:
        lease = await _GATE.acquire(GateRequest(1, task_id, "short"))
        try:
            yield
        finally:
            lease.release()


@asynccontextmanager
async def segment_lease(task_id: str | None = None):
    """长任务分段:priority 2。semaphore 模式同①:先 batch 后 total。"""
    if _GATE is None:
        async with SEM_BATCH, SEM_TOTAL:
            yield
    else:
        lease = await _GATE.acquire(GateRequest(2, task_id, "batch"))
        try:
            yield
        finally:
            lease.release()


def stats() -> dict:
    """观测入口(§5.6,router 挂 GET /v1/asr/gate/stats)。"""
    if _GATE is not None:
        return _GATE.stats()
    return {
        "mode": "semaphore",
        "capacity": ASR.GATE_TOTAL,
        "inflight": ASR.GATE_TOTAL - SEM_TOTAL._value,
        "batch_inflight": (ASR.GATE_TOTAL - ASR.GATE_RESERVE) - SEM_BATCH._value,
        "note": "semaphore 模式无队列/分位数观测,切 priority 后有 wait_ms/hold_ms",
    }
