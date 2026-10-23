import re

from fastapi import APIRouter, HTTPException, Request, Response
from pydantic import BaseModel

from engine.dubbing import tasks
from engine.dubbing.tasks import DubStatus

router = APIRouter(prefix="/v1/dubbing", tags=["dubbing"])

_RANGE = re.compile(r"^bytes=(\d*)-(\d*)$")


class SubmitReq(BaseModel):
    url: str


@router.post("/task", status_code=202)
async def submit(req: SubmitReq):
    if not req.url.strip():
        return {"code": 1, "message": "url 不能为空", "data": None}
    task_id = await tasks.submit(req.url)
    return {"code": 0, "message": "success", "data": {"task_id": task_id}}


@router.get("/task/{task_id}")
async def get_task(task_id: str):
    t = tasks.get_task(task_id)
    if t is None:
        raise HTTPException(404, "task not found")
    return {"code": 0, "message": "success", "data": t.to_public()}


@router.get("/task/{task_id}/asset")
async def asset(task_id: str, request: Request):
    from pathlib import Path
    t = tasks.get_task(task_id)
    if t is None or t.status != DubStatus.COMPLETED or not t.audio_path:
        raise HTTPException(404, "asset not found")
    p = Path(t.audio_path)
    if not p.exists():
        raise HTTPException(404, "asset not found")

    size = p.stat().st_size
    headers = {"accept-ranges": "bytes"}
    rng = request.headers.get("range")
    if rng and (m := _RANGE.match(rng.strip())):
        start_s, end_s = m.group(1), m.group(2)
        if start_s:  # "a-b" / "a-"
            start, end = int(start_s), int(end_s) if end_s else size - 1
        else:  # "-n":最后 n 字节
            start, end = max(size - int(end_s), 0), size - 1
        end = min(end, size - 1)
        if start > end or start >= size:
            return Response(status_code=416, headers={"content-range": f"bytes */{size}"})
        with open(p, "rb") as f:
            f.seek(start)
            data = f.read(end - start + 1)
        headers["content-range"] = f"bytes {start}-{end}/{size}"
        headers["content-length"] = str(len(data))
        return Response(data, status_code=206, media_type="audio/mpeg", headers=headers)

    with open(p, "rb") as f:
        data = f.read()
    headers["content-length"] = str(len(data))
    return Response(data, media_type="audio/mpeg", headers=headers)
