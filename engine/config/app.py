# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""服务级配置(进程/HTTP/CORS)。"""
import os


class AppSettings:
    NAME: str = os.getenv("APP_NAME", "Auris AI Engine")
    VERSION: str = "0.1.0"
    HOST: str = os.getenv("HOST", "127.0.0.1")
    PORT: int = int(os.getenv("PORT", "18000"))
    DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"

    # CORS
    ALLOW_ORIGINS: list[str] = ["*"]
