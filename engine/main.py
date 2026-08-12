"""Engine 入口（骨架）。

业务 router（asr / tts / agent / speech / ...）后续写好后，
在这里 `from engine.xxx.router import router` 再 `app.include_router(...)` 挂上来。
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

from engine.config import Settings


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{Settings.APP_NAME} v{Settings.APP_VERSION} 启动中...")
    logger.info(f"服务地址: http://{Settings.HOST}:{Settings.PORT}")
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
