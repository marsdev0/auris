from engine.byok.factory import build_llm_client
from engine.byok.schemas import ValidateReq, ByokProbeError

async def probe_llm(req: ValidateReq) -> str:
    client = build_llm_client(_as_llm(req))
    try:
        models = await client.models.list()
        n = len(models.data)
        if not req.model:
            return f"models listed, {n} available"
        await client.chat.completions.create(
            model=req.model,
            messages=[{"role": "user", "content": "hi"}],
            max_tokens=1,
        )
        return f"models listed, {n} available; completion ok with {req.model}"
    except Exception as e:  # openai SDK 各类异常统一转译，message 即用户可见原因
        raise ByokProbeError(_humanize(e)) from e


def _as_llm(req: ValidateReq):
    from engine.byok.schemas import LlmConfig
    return LlmConfig(provider=req.provider, base_url=req.base_url,
                     api_key=req.api_key, model=req.model)

def _humanize(e: Exception) -> str:
    """SDK 异常 -> 用户可读原因。401/404/超时给明确话术，其余给类型+摘要"""
    import openai
    if isinstance(e, openai.AuthenticationError):
        return "鉴权失败：api_key 无效或已过期"
    if isinstance(e, openai.NotFoundError):
        return "404：base_url 路径或 model 不存在(注意 base_url 需含 /v1)"
    if isinstance(e, openai.APIConnectionError):
        return "无法连接：base_url 不可达"
    msg = str(e)
    return msg if len(msg) <= 200 else msg[:200] + "..."