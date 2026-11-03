"""
s3产物上传
"""
import asyncio
from pathlib import Path

import boto3
from huggingface_hub import auth_switch

from engine.config import STORAGE, DUB
from botocore.client import Config as BotoConfig
from loguru import logger

_bucket_ready = False

def _client():
    """每次上传新建 client
    每个任务仅1-2次调用，连接复用的收益很小，以此换取免全局态和线程安全
    """
    return boto3.client(
        "s3",
        endpoint_url=STORAGE.ENDPOINT,
        aws_access_key_id=STORAGE.ACCESS_KEY,
        aws_secret_access_key=STORAGE.SECRET_KEY,
        region_name=STORAGE.REGION,
        config=BotoConfig(signature_version="s3v4", s3={"addressing_style": "path"})
    )

def _ensure_bucket(s3):
    """幂等建桶：head 404 则 create"""
    global _bucket_ready
    if _bucket_ready:
        return
    try:
        s3.head_bucket(Bucket=STORAGE.BUCKET)
    except Exception:
        s3.create_bucket(Bucket=STORAGE.BUCKET)
        logger.info(f"对象桶已创建: {STORAGE.BUCKET}")
    _bucket_ready = True

def _upload_artifacts(task_id: str, kind: str) -> list[str]:
    """同步实现（由 upload_artifacts 经 to_thread 调用），返回上传的 key 列表
    产物：
    video → output.mp4 + dub.srt
    audio → narration.mp3
    """
    tdir: Path =  DUB.DIR / task_id
    names = ["output.mp4", "dub.srt"] if kind == "video" else ["narration.mp3"]
    s3 = _client()
    _ensure_bucket(s3)
    keys = []
    for name in names:
        p = tdir / name
        if not p.is_file():
            raise FileNotFoundError(f"上传前置产物缺失: {p}")
        key = f"dubbing/{task_id}/{name}"
        # 分片阈值由 boto3 自动决策
        s3.upload_file(str(p), STORAGE.BUCKET, key)
        keys.append(key)
        logger.info(f"产物已上传: {key} ({p.stat().st_size}B)")
    return keys

async def upload_artifacts(task_id: str, kind: str) -> list[str]:
    return await asyncio.to_thread(_upload_artifacts, task_id, kind)