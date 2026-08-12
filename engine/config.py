"""Engine 配置（骨架）。

业务配置（LLMProvider / EndPoint / ASR / TTS providers 等）后续按需加。
"""
import os

from dotenv import load_dotenv

load_dotenv(override=True)


class Settings:
    # 服务
    APP_NAME: str = os.getenv("APP_NAME", "Auris AI Engine")
    APP_VERSION: str = "0.1.0"
    HOST: str = os.getenv("HOST", "127.0.0.1")
    PORT: int = int(os.getenv("PORT", "18000"))
    DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"

    # CORS
    ALLOW_ORIGINS: list[str] = ["*"]
