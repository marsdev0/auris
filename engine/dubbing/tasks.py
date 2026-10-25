import asyncio
import shutil
import time
from uuid import uuid4
from pydantic import BaseModel

from engine.dubbing.types import DubStatus, Stage, VStage
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
    video_path: str | None = None
    kind: str = "audio"  # audio or video
    created_at: float
    updated_at: float
    error: str | None = None

    def to_public(self) -> dict:
        return self.model_dump(exclude={"audio_path", "video_path", "url"})


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


def _guess_kind(url: str) -> str:
    """URL-based task kind guess (D5 decision 4): bilibili/video extension → video, otherwise audio.
    Wrong guess backstop: after download a None video_path errors at copyfile into FAILED, visible."""
    from engine.sources import bilibili, direct
    if bilibili.match(url) or direct.is_video_url(url):
        return "video"
    return "audio"


async def submit(url: str) -> str:
    from engine.dubbing.pipeline import _run, _run_video
    now = time.time()
    task_id = uuid4().hex
    kind = _guess_kind(url)
    _tasks[task_id] = DubbingTask(
        task_id=task_id, created_at=now, updated_at=now,
        kind=kind, stage_total=len(VStage) if kind == "video" else len(Stage))
    runner = _run_video if kind == "video" else _run
    asyncio.create_task(runner(task_id, url))
    return task_id


async def cleanup_expired() -> None:
    while True:
        now = time.time()
        ttl = DUB.TASK_TTL_H * 3600
        expired = [tid for tid, t in _tasks.items() if now - t.updated_at > ttl]
        for tid in expired:
            _tasks.pop(tid)
            shutil.rmtree(DUB.DIR / tid, ignore_errors=True)  # whole task dir: snapshot + artifacts reclaimed together
        if expired:
            logger.info(f"清理过期播报任务 {len(expired)} 个")
        await asyncio.sleep(3600)


async def resume_pending() -> None:
    """Startup scan: resume tasks interrupted by kill -9 etc. A single task's recovery failure pollutes only itself.
    Dispatch by checkpoint.kind (D5): video → VStage+_run_video (8 stages), audio → Stage+_run (6 stages)."""
    from engine.dubbing import checkpoint as ckp
    from engine.dubbing.pipeline import _run, _run_video
    from engine.dubbing.types import VStage
    for task_id in ckp.scan_pending():
        state = ckp.load_checkpoint(task_id)
        if not state or not state.get("url"):
            continue
        kind = state.get("kind") or "audio"
        enum_cls = VStage if kind == "video" else Stage
        runner = _run_video if kind == "video" else _run
        try:
            start = enum_cls(state["stage_index"])
        except ValueError:
            logger.warning(f"state stage 越界(跳过): {task_id}")
            continue
        now = time.time()
        _tasks[task_id] = DubbingTask(
            task_id=task_id, status=DubStatus.RUNNING,
            stage_index=int(start), stage_total=len(enum_cls), kind=kind,
            url=state["url"], title=state.get("title"),
            created_at=now, updated_at=now)
        asyncio.create_task(runner(task_id, state["url"], start_stage=start))
        logger.info(f"断点续跑 {task_id}: {kind} 从 stage {start.name} 起")
