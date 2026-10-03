"""
全局并发闸门
只管"M个位先给谁"，不管业务
"""
import asyncio
from contextlib import asynccontextmanager

from engine.config import Settings

assert Settings.ASR_GATE_TOTAL > Settings.ASR_GATE_RESERVE > 0, "M 必须 > R, 批量零并发，静默死锁"

SEM_TOTAL = asyncio.Semaphore(Settings.ASR_GATE_TOTAL)
SEM_BATCH = asyncio.Semaphore(Settings.ASR_GATE_TOTAL - Settings.ASR_GATE_RESERVE)

@asynccontextmanager
async def batch_lease():
    async with SEM_BATCH, SEM_TOTAL:
        yield

@asynccontextmanager
async def sync_lease():
    async with SEM_TOTAL:
        yield