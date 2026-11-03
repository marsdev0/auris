# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""S3 artifact upload (P12-D8): the pipeline end uploads finished artifacts to object storage.

- Depends only on the S3 protocol (boto3 + endpoint override + path-style), storage impl is swappable;
- Key convention (decision 2): dubbing/{task_id}/{output.mp4|narration.mp3|dub.srt}, no DB column needed;
- Bucket is lazily ensured on first upload (idempotent head-then-create);
- boto3 is synchronous: calls go through asyncio.to_thread (the engine is asyncio-based;
  only 1-2 uploads per task at terminal state, not worth an aioboto3 dependency);
- Failure semantics (decision 5): missing files / network errors propagate -> pipeline _fail()
  marks the task FAILED, no degraded dual path.
"""
import asyncio
from pathlib import Path

import boto3
from botocore.client import Config as BotoConfig
from loguru import logger

from engine.config import DUB, STORAGE

_bucket_ready = False


def _client():
    """Create a fresh client per upload: only 1-2 calls per task, connection reuse gains
    nothing — traded for no global state and thread safety."""
    return boto3.client(
        "s3",
        endpoint_url=STORAGE.ENDPOINT,
        aws_access_key_id=STORAGE.ACCESS_KEY,
        aws_secret_access_key=STORAGE.SECRET_KEY,
        region_name=STORAGE.REGION,
        config=BotoConfig(signature_version="s3v4", s3={"addressing_style": "path"}),
    )


def _ensure_bucket(s3):
    """Idempotent bucket ensure: head 404 -> create."""
    global _bucket_ready
    if _bucket_ready:
        return
    try:
        s3.head_bucket(Bucket=STORAGE.BUCKET)
    except Exception:
        s3.create_bucket(Bucket=STORAGE.BUCKET)
        logger.info(f"bucket created: {STORAGE.BUCKET}")
    _bucket_ready = True


def _upload_artifacts(task_id: str, kind: str) -> list[str]:
    """Synchronous impl (called via to_thread by upload_artifacts). Returns uploaded keys.

    Artifact list stays strictly aligned with the asset route and the Java signing side:
      video -> output.mp4 + dub.srt; audio -> narration.mp3.
    """
    tdir: Path = DUB.DIR / task_id
    names = ["output.mp4", "dub.srt"] if kind == "video" else ["narration.mp3"]
    s3 = _client()
    _ensure_bucket(s3)
    keys = []
    for name in names:
        p = tdir / name
        if not p.is_file():
            raise FileNotFoundError(f"artifact missing before upload: {p}")
        key = f"dubbing/{task_id}/{name}"
        # multipart threshold decided by boto3 automatically
        s3.upload_file(str(p), STORAGE.BUCKET, key)
        keys.append(key)
        logger.info(f"artifact uploaded: {key} ({p.stat().st_size}B)")
    return keys


async def upload_artifacts(task_id: str, kind: str) -> list[str]:
    """Async entry: called by the pipeline before save_checkpoint(COMPLETED) —
    upload is part of the completion semantics."""
    return await asyncio.to_thread(_upload_artifacts, task_id, kind)
