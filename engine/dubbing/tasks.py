import asyncio
import time
from enum import IntEnum
from uuid import uuid4

from pydantic import BaseModel


class DubStatus(IntEnum):
    INIT = 0
    RUNNING = 1
    COMPLETED = 2
    FAILED = 3


class DubbingTask(BaseModel):
    task_id: str
    status: DubStatus = DubStatus.INIT
    stage_total: int = 5
    stage_index: int = 0
    title: str | None = None
    audio_path: str | None = None
    audio_bytes: int | None = None
    created_at: float
    updated_at: float
    error: str | None = None

    def to_public(self) -> dict:
        return self.model_dump(exclude={"audio_path"})


_tasks: dict[str, DubbingTask] = {}


def get_task(task_id: str) -> DubbingTask | None:
    return _tasks.get(task_id)


def _set(task_id: str, status: DubStatus | None = None, stage_index: int | None = None, **kw) -> None:
    t = _tasks.get(task_id)
    if t is None:
        return
    if status is not None:
        t.status = status
    if stage_index is not None:
        t.stage_index = stage_index
    for k, v in kw.items():
        setattr(t, k, v)
    t.updated_at = time.time()


async def submit(url: str) -> str:
    from engine.dubbing.pipeline import _run
    now = time.time()
    task_id = uuid4().hex
    _tasks[task_id] = DubbingTask(task_id=task_id, created_at=now, updated_at=now)
    asyncio.create_task(_run(task_id, url))
    return task_id


async def cleanup_expired() -> None:
    from pathlib import Path
    while True:
        now = time.time()
        from engine.config import Settings
        ttl = Settings.DUB_TASK_TTL_H * 3600
        expired = [tid for tid, t in _tasks.items() if now - t.updated_at > ttl]
        for tid in expired:
            t = _tasks.pop(tid)
            if t.audio_path:
                p = Path(t.audio_path)
                p.unlink(missing_ok=True)
                p.parent.rmdir()
        if expired:
            from loguru import logger
            logger.info(f"清理过期播报任务 {len(expired)} 个")
        await asyncio.sleep(3600)
