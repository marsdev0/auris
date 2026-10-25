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
    """URL 预判任务形态(D5 决策 4):哔哩哔哩/视频后缀→video,其余→audio。
    预判错误的兜底:download 后 video_path 为 None 会在 copyfile 处报错入 FAILED,可见。"""
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
            shutil.rmtree(DUB.DIR / tid, ignore_errors=True)  # 整任务目录:快照+产物一起回收
        if expired:
            logger.info(f"清理过期播报任务 {len(expired)} 个")
        await asyncio.sleep(3600)


async def resume_pending() -> None:
    """启动扫描: kill -9 等中断的任务从断点续跑。单任务恢复失败只污染自身。
    按 checkpoint.kind 分流(D5):video 用 VStage+_run_video(8 stage),audio 用 Stage+_run(6 stage)。"""
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
