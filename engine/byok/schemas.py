from typing import Literal

from pydantic import BaseModel

Domain = Literal["llm", "tts", "asr"]


class ValidateReq(BaseModel):
    domain: Domain
    provider: str
    base_url: str | None = None
    api_key: str | None = None
    model: str | None = None
    voice: str | None = None

class ValidateResp(BaseModel):
    latency_ms: int
    detail: str


class ByokConfigError(Exception):
    """配置本身不成立(缺 base_url / provider 不支持)，区别于"调了但失败"""

class ByokProbeError(Exception):
    """实测失败：连通性/鉴权/模型不存在等，message 即给用户看的原因"""

class LlmConfig(BaseModel):
    provider: str
    base_url: str | None = None
    api_key: str | None = None
    model: str | None = None