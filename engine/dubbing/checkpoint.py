"""
checkpoint.json structure:
{
  "v": 1,
  "task_id": "9f3c2a7b1e5d40f6a8c2",
  "url": "https://example.com/episode-42.mp3",
  "title": "Episode 42: Why Kubernetes Won",
  "stage_index": 2,
  "status": "running/completed/failed)"
}

data/dubbing/{task_id}/
    checkpoint.json   # resume snapshot (structure below) — named after the module
    src.bin           # 1. downloaded audio (D3 used "temp dir + delete after use"; now persisted in task dir, cleaned at terminal state + TTL)
    transcript.json   # 2. transcription artifact, English segments (= input of 3.translate)
    translation.json  # 3. translation artifact, Chinese segments (= input of 4.chunk)
    units.json        # 4. narration units (= input of 5.synth)
    chunks/*.mp3      # 5. per-segment synthesis
    narration.mp3     # 6. final artifact
"""
import json
import os
from pathlib import Path

from loguru import logger

from engine.config import DUB
from engine.dubbing.types import DubStatus, Stage

_V = 2   # v2 (D5): segment structure str→Seg break + kind field; v1 tasks not migrated (rerun is cheaper than migration)

# Stage input artifacts: consumer stage → filename (renamed from stage{N}.json to content naming)
_DATA_FILE = {
    Stage.TRANSLATE: "transcript.json",   # translate's input (= transcribe artifact, English segments)
    Stage.CHUNK: "translation.json",      # chunk's input (= translate artifact, Chinese segments)
    Stage.SYNTH: "units.json",            # synth's input (= chunk artifact, narration units)
}


def task_dir(task_id: str) -> Path:
    return DUB.DIR / task_id


def save_checkpoint(task_id: str, *, url: str, stage_index: int,
                    status: DubStatus = DubStatus.RUNNING,
                    title: str | None = None, kind: str | None = None) -> None:
    """Atomic write: checkpoint.json.tmp → os.replace. A crash mid-write leaves the previous complete version.

    status is persisted as name.lower() ("running" etc., human-readable); conversion lives here, callers pass the enum.
    """
    payload = {"v": _V, "task_id": task_id, "url": url, "title": title,
               "stage_index": stage_index, "status": status.name.lower(), "kind": kind}
    d = task_dir(task_id)
    d.mkdir(parents=True, exist_ok=True)
    tmp = d / "checkpoint.json.tmp"
    tmp.write_text(json.dumps(payload, ensure_ascii=False))
    os.replace(tmp, d / "checkpoint.json")


def load_checkpoint(task_id: str) -> dict | None:
    p = task_dir(task_id) / "checkpoint.json"
    if not p.exists():
        return None
    try:
        s = json.loads(p.read_text())
    except json.JSONDecodeError:
        logger.warning(f"checkpoint.json 损坏(跳过): {task_id}")
        return None
    if s.get("v") != _V:
        logger.warning(f"checkpoint 版本不兼容 v={s.get('v')}(跳过): {task_id}")
        return None
    return s


def write_stage_data(task_id: str, stage_index: Stage, units: list[str]) -> None:
    """Persist stage input artifacts (before save_checkpoint): filename mapped by consumer stage."""
    (task_dir(task_id) / _DATA_FILE[stage_index]).write_text(
        json.dumps(units, ensure_ascii=False))


def read_stage_data(task_id: str, stage_index: Stage) -> list[str] | None:
    p = task_dir(task_id) / _DATA_FILE[stage_index]
    if not p.exists():
        return None
    return json.loads(p.read_text())


def write_segs(task_id: str, name: str, segs: list) -> None:
    (task_dir(task_id) / name).write_text(json.dumps(
        [s.model_dump() for s in segs], ensure_ascii=False))

def read_segs(task_id: str, name: str) -> list:
    from engine.dubbing.types import Seg
    return [Seg(**d) for d in json.loads((task_dir(task_id) / name).read_text())]


def scan_pending() -> list[str]:
    """Startup scan: tasks with status==running — checkpoint stays running on kill -9."""
    out: list[str] = []
    if not DUB.DIR.exists():
        return out
    for d in DUB.DIR.iterdir():
        p = d / "checkpoint.json"
        if not p.is_file():
            continue
        try:
            s = json.loads(p.read_text())
        except (json.JSONDecodeError, OSError):
            continue
        if s.get("v") == _V and s.get("status") == DubStatus.RUNNING.name.lower():
            out.append(d.name)
    return out
