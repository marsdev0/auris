import time

from fastapi import APIRouter

from engine.byok.probes import probe_llm
from engine.byok.schemas import ValidateReq, ByokConfigError, ByokProbeError, ValidateResp
from engine.net.safety import assert_public_url, UrlSafetyError
from loguru import logger

router = APIRouter(prefix="/byok", tags=["byok"])

_PROBES = {"llm": probe_llm}

@router.post("/validate")
async def validate(req: ValidateReq) -> dict:
    if req.base_url:
        try:
            assert_public_url(req.base_url)
        except UrlSafetyError as e:
            return {"code": 3, "message": f"SSRF 拦截: {e}", "data": None}
    started = time.monotonic()
    try:
        detail = await _PROBES[req.domain](req)
    except ByokConfigError as e:
        return {"code": 2, "message": str(e), "data": None}
    except ByokProbeError as e:
        return {"code": 1, "message": str(e), "data": None}
    except Exception as e:  # 未预期异常：不把内部栈给前端，但留痕
        logger.exception(f"byok validate 未预期异常 domain={req.domain}")
        return {"code": 1, "message": f"校验异常: {type(e).__name__}", "data": None}
    resp = ValidateResp(latency_ms=int((time.monotonic() - started) * 1000),
                        detail=detail)
    return {"code": 0, "message": "success", "data": resp.model_dump()}

