from engine.asr import audio
from engine.asr.schemas import AsrResult
from engine.byok.factory import build_llm_client, build_tts_provider, build_asr_provider
from engine.byok.schemas import ValidateReq, ByokProbeError
from engine.config import ASR

# 1s 静音:16kHz * 16bit * 单声道 = 32000B 零值 PCM
_SILENCE_WAV = audio.pcm_to_wav(b"\x00" * (ASR.SAMPLE_RATE * 2))

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


async def probe_tts(req: ValidateReq) -> str:
    provider = build_tts_provider(_as_tts(req))  # ByokConfigError 原样上抛(配置不成立)
    try:
        result = await provider.synthesize("测")
        if not result.audio:
            raise ByokProbeError("合成结果为空")
        return f"synthesized {len(result.audio)} bytes sample"
    except ByokProbeError:
        raise
    except Exception as e:
        raise ByokProbeError(_humanize(e)) from e


async def probe_asr(req: ValidateReq) -> str:
    provider = build_asr_provider(_as_asr(req))
    try:
        result = await provider.transcribe(_SILENCE_WAV)
        # 静音 -> 空文本是正常态；能构造出 AsrResult 即响应结构正确
        _ = AsrResult(text=result.text, segments=result.segments)
        return f"transcription ok, {len(result.segments)} segments on silence"
    except Exception as e:
        raise ByokProbeError(_humanize(e)) from e


def _as_llm(req: ValidateReq):
    from engine.byok.schemas import LlmConfig
    return LlmConfig(provider=req.provider, base_url=req.base_url,
                     api_key=req.api_key, model=req.model)

def _as_tts(req: ValidateReq):
    from engine.byok.schemas import TtsConfig
    return TtsConfig(provider=req.provider, base_url=req.base_url,
                     api_key=req.api_key, model=req.model, voice=req.voice)


def _as_asr(req: ValidateReq):
    from engine.byok.schemas import AsrConfig
    return AsrConfig(provider=req.provider, base_url=req.base_url,
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