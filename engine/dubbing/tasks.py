import asyncio
import shutil
import time
from uuid import uuid4
from pydantic import BaseModel

from engine.dubbing.types import DubStatus, Stage
from loguru import logger


class DubbingTask(BaseModel):
    task_id: str
    status: DubStatus = DubStatus.INIT
    stage_total: int = len(Stage)
    stage_index: int = 0
    url: str | None = None
    title: str | None = None
    audio_path: str | None = None
    audio_bytes: int | None = None
    created_at: float
    updated_at: float
    error: str | None = None

    def to_public(self) -> dict:
        return self.model_dump(exclude={"audio_path", "url"})


_tasks: dict[str, DubbingTask] = {}


def get_task(task_id: str) -> DubbingTask | None:
    return _tasks.get(task_id)


def set(task_id: str, status: DubStatus | None = None, stage_index: int | None = None, **kw) -> None:
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
    while True:
        now = time.time()
        from engine.config import Settings
        ttl = Settings.DUB_TASK_TTL_H * 3600
        expired = [tid for tid, t in _tasks.items() if now - t.updated_at > ttl]
        for tid in expired:
            _tasks.pop(tid)
            shutil.rmtree(Settings.DUB_DIR / tid, ignore_errors=True)  # 整任务目录:快照+产物一起回收
        if expired:
            logger.info(f"清理过期播报任务 {len(expired)} 个")
        await asyncio.sleep(3600)


async def resume_pending() -> None:
    """启动扫描: kill -9 等中断的任务从断点续跑。单任务恢复失败只污染自身。"""
    from engine.dubbing import checkpoint as ckp
    from engine.dubbing.pipeline import _run
    for task_id in ckp.scan_pending():
        state = ckp.load_checkpoint(task_id)
        if not state or not state.get("url"):
            continue
        try:
            start = Stage(state["stage_index"])
        except ValueError:
            logger.warning(f"state stage 越界(跳过): {task_id}")
            continue
        now = time.time()
        _tasks[task_id] = DubbingTask(
            task_id=task_id, status=DubStatus.RUNNING,
            stage_index=int(start), url=state["url"],
            title=state.get("title"), created_at=now, updated_at=now)
        asyncio.create_task(_run(task_id, state["url"], start_stage=start))
        logger.info(f"断点续跑 {task_id}: 从 stage {start.name} 起")