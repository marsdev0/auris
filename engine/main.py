# Copyright (c) 2026 marsdev0
# Licensed under the MIT License. See the LICENSE file for details.
"""Engine 入口（骨架）。

业务 router（asr / tts / agent / speech / ...）后续写好后，
在这里 `from engine.xxx.router import router` 再 `app.include_router(...)` 挂上来。
"""
from contextlib import asynccontextmanager

import asyncio
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

from engine.config import Settings
from engine.asr.router import router as asr_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{Settings.APP_NAME} v{Settings.APP_VERSION} 启动中...")
    logger.info(f"服务地址: http://{Settings.HOST}:{Settings.PORT}")
    from engine.asr.service import get_asr_service
    svc = get_asr_service()
    logger.info(f"已注册 ASR provider: {svc.registry.names()}")
    from engine.asr.vad import get_vad_engine
    get_vad_engine()                      # 预热 VAD(分段与流式共用单例)
    yield
    logger.info("服务正在关闭...")


app = FastAPI(
    lifespan=lifespan,
    title=Settings.APP_NAME,
    version=Settings.APP_VERSION,
    debug=Settings.DEBUG,
    description="Auris AI engine",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=Settings.ALLOW_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(asr_router, tags=["asr"])

@app.get("/")
async def root():
    return {"service": Settings.APP_NAME, "version": Settings.APP_VERSION, "status": "running"}


@app.get("/health")
async def health():
    return {"status": "healthy", "service": Settings.APP_NAME}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "engine.main:app",
        host=Settings.HOST,
        port=Settings.PORT,
        reload=Settings.DEBUG,
        log_level="info",
    )
