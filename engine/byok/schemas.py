from typing import Literal

from pydantic import BaseModel, Field

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
    # repr=False:任何日志/异常栈零明文(api_key 同理,见 feature 分支纪律)
    api_key: str | None = Field(default=None, repr=False)
    model: str | None = None
    config_hash: str = Field(default="", repr=False)  # 缓存键成分,恒为非空摘要


class TtsConfig(BaseModel):
    provider: str
    base_url: str | None = None
    api_key: str | None = Field(default=None, repr=False)
    model: str | None = None
    voice: str | None = None
    config_hash: str = Field(default="", repr=False)


class AsrConfig(BaseModel):
    provider: str
    base_url: str | None = None
    api_key: str | None = Field(default=None, repr=False)
    model: str | None = None
    config_hash: str = Field(default="", repr=False)



class ByokContext(BaseModel):
    user_id: int
    llm: LlmConfig | None = None
    tts: TtsConfig | None = None
    asr: AsrConfig | None = None