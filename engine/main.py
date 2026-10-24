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

from engine.config import APP
from engine.asr.router import router as asr_router
from engine.sources.router import router as sources_router
from engine.byok.router import router as byok_router
from engine.tts.router import router as tts_router
from engine.dubbing.router import router as dub_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{APP.NAME} v{APP.VERSION} 启动中...")
    logger.info(f"服务地址: http://{APP.HOST}:{APP.PORT}")

    from engine.asr.service import get_asr_service
    svc = get_asr_service()
    logger.info(f"已注册 ASR provider: {svc.registry.names()}")

    from engine.asr.vad import get_vad_engine
    get_vad_engine()  # 预热 VAD(分段与流式共用单例)

    from engine.llm.client import warn_if_unconfigured
    warn_if_unconfigured()  # LLM 空配置提醒:不拦启动,只告警

    from engine.asr.long_audio.tasks import cleanup_expired
    cleanup_task = asyncio.create_task(cleanup_expired())  # TTL 清理(启动一轮+每小时)

    from engine.dubbing.concat import ensure_ffmpeg
    ensure_ffmpeg()

    from engine.dubbing import tasks as dub_tasks
    dub_cleanup = asyncio.create_task(dub_tasks.cleanup_expired())
    await dub_tasks.resume_pending()

    yield
    cleanup_task.cancel()
    dub_cleanup.cancel()
    logger.info("服务正在关闭...")


app = FastAPI(
    lifespan=lifespan,
    title=APP.NAME,
    version=APP.VERSION,
    debug=APP.DEBUG,
    description="Auris AI engine",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=APP.ALLOW_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(asr_router)
app.include_router(sources_router)
app.include_router(byok_router)
app.include_router(tts_router)
app.include_router(dub_router)

@app.get("/")
async def root():
    return {"service": APP.NAME, "version": APP.VERSION, "status": "running"}


@app.get("/health")
async def health():
    return {"status": "healthy", "service": APP.NAME}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "engine.main:app",
        host=APP.HOST,
        port=APP.PORT,
        reload=APP.DEBUG,
        log_level="info",
    )
