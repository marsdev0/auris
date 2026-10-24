"""
checkpoint.json 结构:
{
  "v": 1,
  "task_id": "9f3c2a7b1e5d40f6a8c2",
  "url": "https://example.com/episode-42.mp3",
  "title": "Episode 42: Why Kubernetes Won",
  "stage_index": 2,
  "status": "running/completed/failed)"
}

data/dubbing/{task_id}/
    checkpoint.json   # 断点快照(结构见下)——与模块同名
    src.bin           # 1.下载的音频(D3 是"临时目录+用完即删",本站改落任务目录,终态+TTL 才清)
    transcript.json   # 2.转写产物·英文段(=3.translate 的输入)
    translation.json  # 3.翻译产物·中文段(=4.chunk 的输入)
    units.json        # 4.朗读单元(=5.synth 的输入)
    chunks/*.mp3      # 5.逐段合成
    narration.mp3     # 6.成品
"""
import json
import os
from pathlib import Path

from loguru import logger

from engine.config import DUB
from engine.dubbing.types import DubStatus, Stage

_V = 1

# 阶段输入产物:消费者阶段 → 文件名(原 stage{N}.json 的内容命名修订)
_DATA_FILE = {
    Stage.TRANSLATE: "transcript.json",   # translate 的输入(=transcribe 产物,英文段)
    Stage.CHUNK: "translation.json",      # chunk 的输入(=translate 产物,中文段)
    Stage.SYNTH: "units.json",            # synth 的输入(=chunk 产物,朗读单元)
}


def task_dir(task_id: str) -> Path:
    return DUB.DIR / task_id


def save_checkpoint(task_id: str, *, url: str, stage_index: int,
                    status: DubStatus = DubStatus.RUNNING,
                    title: str | None = None) -> None:
    """原子写:写 checkpoint.json.tmp → os.replace。写一半 crash 盘上仍是旧完整版。

    status 落盘 name.lower()("running" 等,人可读);转换封装在此,调用方传枚举。
    """
    payload = {"v": _V, "task_id": task_id, "url": url, "title": title,
               "stage_index": int(stage_index), "status": status.name.lower()}
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
    """阶段输入产物落盘(先于 save_checkpoint 调用):按消费者阶段映射文件名。"""
    (task_dir(task_id) / _DATA_FILE[stage_index]).write_text(
        json.dumps(units, ensure_ascii=False))


def read_stage_data(task_id: str, stage_index: Stage) -> list[str] | None:
    p = task_dir(task_id) / _DATA_FILE[stage_index]
    if not p.exists():
        return None
    return json.loads(p.read_text())


def scan_pending() -> list[str]:
    """启动扫描: status==running 的任务——kill -9 时 checkpoint 停留 running。"""
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
