# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""P8 调度闸门单测(设计 §8 T1~T7)。用法:仓库根目录 `uv run python tests/test_gate.py`。

T1 用模块级 semaphore(默认 semaphore 模态);T2~T7 直接构造 PriorityGate 改参数,
不依赖环境变量。无 pytest 依赖,纯 asyncio。
"""
import asyncio
import time

from engine.asr import gate
from engine.asr.gate import GateRequest, PriorityGate, QueueFullError

_KINDS = ("sync", "short", "batch")


async def t1_reserve():
    """T1 预留不变式:batch 打满 M-R 时,sync 仍能在阈值内进入。"""
    async with gate.SEM_BATCH, gate.SEM_TOTAL:      # 模拟批量占满 M-R
        t0 = time.monotonic()
        async with gate.sync_lease():               # 预留位立即可用
            assert time.monotonic() - t0 < 2, "sync 未能在预留位内进入"


async def t2_no_deadlock():
    """T2 无死锁:混合 1000 次 acquire/release 压力,15s 全局兜底。"""
    g = PriorityGate(); g._capacity = 4
    done = 0

    async def worker(i: int):
        nonlocal done
        await asyncio.sleep((i % 7) * 0.001)
        lease = await g.acquire(GateRequest(i % 3, f"t{i % 5}" if i % 3 else None, _KINDS[i % 3]))
        done += 1
        await asyncio.sleep(0.001)
        lease.release()

    await asyncio.wait_for(asyncio.gather(*(worker(i) for i in range(1000))), timeout=15)
    assert done == 1000 and g._inflight == 0


async def t3_task_cap():
    """T3 per-task cap:单任务任意时刻在途 ≤ cap。"""
    g = PriorityGate(); g._capacity, g._task_cap = 4, 2
    tasks = [asyncio.create_task(g.acquire(GateRequest(2, "A", "batch"))) for _ in range(6)]
    await asyncio.sleep(0.05)
    assert g._inflight == 2 and g._inflight_by_task["A"] == 2, "cap 未生效"
    assert len(g._waiters) == 4
    tasks[0].result().release()
    await asyncio.sleep(0.01)
    assert g._inflight_by_task["A"] == 2            # 补位的还是 A,但恒 ≤ 2
    for t in tasks:                                  # 清场
        if t.done():
            t.result().release()
        else:
            t.cancel()
    await asyncio.sleep(0.01)


async def t4_queue_full():
    """T4 有界拒绝:等待者到 queue_max 抛 QueueFullError。"""
    g = PriorityGate(); g._capacity, g._task_cap, g._queue_max = 1, 99, 3
    hold = await g.acquire(GateRequest(2, "A", "batch"))       # 占住唯一位
    pend = [asyncio.create_task(g.acquire(GateRequest(2, "A", "batch"))) for _ in range(3)]
    await asyncio.sleep(0.01)
    assert len(g._waiters) == 3
    try:
        await g.acquire(GateRequest(2, "A", "batch"))
        raise AssertionError("应当抛 QueueFullError")
    except QueueFullError:
        pass
    hold.release()
    for p in pend:                                  # capacity=1:只有一个中签,其余 cancel
        if p.done():
            p.result().release()
        else:
            p.cancel()
    await asyncio.sleep(0.01)


async def t5_cancel_safety():
    """T5 取消安全:未中签不扣位、中签后还位;inflight 精确回零。"""
    g = PriorityGate(); g._capacity = 1
    # 5a 未中签取消:只从 waiters 摘除,不碰 inflight
    hold = await g.acquire(GateRequest(2, "A", "batch"))
    w_task = asyncio.create_task(g.acquire(GateRequest(0, None, "sync")))
    await asyncio.sleep(0.01)
    assert len(g._waiters) == 1
    w_task.cancel()
    try:
        await w_task
    except asyncio.CancelledError:
        pass
    assert not g._waiters and g._inflight == 1, "未中签取消不应碰 inflight"
    hold.release()
    await asyncio.sleep(0.01)
    assert g._inflight == 0
    # 5b 中签后、返回前取消:release 的同步 _drain 已 set_result,但被唤醒方
    #    还没跑——此时 cancel 落在 _must_cancel 窗口, acquire 的 except 分支
    #    应凭 granted 标记还位(§5.5 的核心场景)
    hold = await g.acquire(GateRequest(2, "A", "batch"))   # 占住唯一位
    t = asyncio.create_task(g.acquire(GateRequest(0, None, "sync")))
    await asyncio.sleep(0.01)                               # 确保它已挂起等待
    assert len(g._waiters) == 1 and g._inflight == 1
    hold.release()                                          # 同步 drain:中签+set_result,但 t 尚未调度
    t.cancel()                                              # 抢在 t 醒来之前落刀
    try:
        await t
    except asyncio.CancelledError:
        pass
    assert g._inflight == 0 and not g._waiters, "中签后取消应还位"


async def t6_priority():
    """T6 优先级序:空位竞争时 P0 恒先于 P2 被补位。"""
    g = PriorityGate(); g._capacity = 1; g._age_s = 10 ** 9       # 关 aging,隔离测优先级
    hold = await g.acquire(GateRequest(2, "A", "batch"))
    got: list[int] = []

    async def want(p: int):
        lease = await g.acquire(GateRequest(p, None, _KINDS[p]))
        got.append(p)
        lease.release()

    b = asyncio.create_task(want(2))
    await asyncio.sleep(0.01)
    s = asyncio.create_task(want(0))
    await asyncio.sleep(0.01)
    hold.release()
    await asyncio.sleep(0.05)
    assert got[0] == 0 and got[1] == 2, f"优先级序错误: {got}"


async def t7_aging():
    """T7 aging:aged P2 降到 eff=1,仍让位给后到的 P0(不越权);
    但不饿死——P0 过完后 P2 能进(实测教训:floor=0 会把 sync 埋死)。"""
    g = PriorityGate(); g._capacity = 1; g._age_s = 0.1
    hold = await g.acquire(GateRequest(2, "Z", "batch"))
    got: list[int] = []

    async def want(p: int):
        lease = await g.acquire(GateRequest(p, None, _KINDS[p]))
        got.append(p)
        lease.release()

    b = asyncio.create_task(want(2))
    await asyncio.sleep(0.25)                              # P2 已等 2.5×AGE → eff=1
    s = asyncio.create_task(want(0))
    await asyncio.sleep(0.01)                              # 确保 s 已入队,再放位
    hold.release()
    await asyncio.sleep(0.05)
    assert got[0] == 0 and got[1] == 2, f"aging 越权或饿死: {got}"


async def main():
    for name, fn in (("T1", t1_reserve), ("T2", t2_no_deadlock), ("T3", t3_task_cap),
                     ("T4", t4_queue_full), ("T5", t5_cancel_safety),
                     ("T6", t6_priority), ("T7", t7_aging)):
        await fn()
        print(f"{name} ✅")
    print("all passed")


if __name__ == "__main__":
    asyncio.run(main())
