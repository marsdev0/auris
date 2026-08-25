"""
DashScope qwen-audio-3.0-asr-flash-streaming 真流式 provider(raw WebSocket,不走 SDK)。

协议生命周期:connect → run-task → task-started → binary 音频帧×N
→ finish-task → [余量 result-generated] → task-finished → 客户端主动断开。
"""
import asyncio
import json
import time
import uuid
from typing import AsyncIterator

import websockets

from engine.asr.provider import AsrCapability
from loguru import logger

from engine.asr.schemas import AsrResult, PartialResult
from engine.config import Settings

_START_TIMEOUT_S = 10.0  # 等 task-started 的上限,超时按协议错误处理

# 任务启动成功，客户端可开始发送音频数据
_SERVER_TASK_STARTED = "task-started"
# 识别结果，包含中间结果（sentence_end=false）和最终结果（sentence_end=true）。其中，新句子的首个中间结果包含 sentence_begin=true
_SERVER_RESULT_GENERATED = "result-generated"
# 任务正常结束，可关闭连接或复用连接
_SERVER_TASK_FINISHED = "task-finished"
# 任务失败，连接会被关闭，无法复用
_SERVER_TASK_FAILED = "task-failed"

# 启动语音识别任务，设置模型、音频格式、采样率等参数
# 发送时机：建立 WebSocket 连接后立即发送
# 响应事件：服务端返回 task-started 事件后才能发送音频
_CLIENT_RUN_TASK = "run-task"

# 在任务执行过程中更新对话上下文信息，用于辅助识别
# 发送时机：任务运行中，需要更新对话上下文时发送
_CLIENT_CONTINUE_TASK = "continue-task"

# 通知服务端音频发送完毕，请求结束任务
# 发送时机：所有音频数据发送完毕后
# 响应事件：服务端返回 task-finished 事件
_CLIENT_FINISH_TASK = "finish-task"


class QwenAudioStreamingProvider:
    name = "qwen-audio-streaming"
    capabilities = {AsrCapability.STREAMING}

    def __init__(self, settings):
        self._s = settings
        if not self._s.ASR_QWEN3_RT_API_KEY:
            logger.warning("ASR_QWEN3_RT_API_KEY 未配置, qwen3-realtime 将无法建链")

    async def transcribe(self, audio: bytes, lang: str | None = None) -> AsrResult:
        pass

    async def stream(self, chunks: AsyncIterator[bytes], lang: str | None = None) -> AsyncIterator[PartialResult]:
        """
        真流式：chunk(PCM 16k/16bit/mono 字节流) -> partial/final 帧

        每次调用新建一条 WS 连接 + task_id(MVP 不做连接复用,§1.6)
        """
        task_id = uuid.uuid4().hex[:32]
        t0 = time.monotonic()
        headers = {"Authorization": f"Bearer {Settings.ASR_QAS_API_KEY}"}
        async with websockets.connect(Settings.ASR_QAS_BASE_URL,
                                      additional_headers=headers) as ws:
            logger.info("ws#3 connected %.0fms", (time.monotonic() - t0) * 1000)
            await self._run_task(ws, task_id, lang)  # run-task,等 task-started
            await self._await_task_started(ws)  # 等"就绪"事件,带超时

            sender = asyncio.create_task(self._send_audio(ws, chunks, task_id))
            try:
                async for msg in ws:  # receiver 即主循环
                    if self._handle_state_event(msg):
                        return
                    r = self._map_event(msg)
                    if r is not None:
                        yield r
            finally:
                sender.cancel()

    # 内部方法
    async def _run_task(self, ws, task_id, lang):
        """发 run-task(带全部断句/保活参数)。task-started 由主循环分流,这里只发。"""
        params: dict = {
            "format": "pcm",
            "sample_rate": Settings.ASR_SAMPLE_RATE,
            "heartbeat": Settings.ASR_QAS_HEARTBEAT,
            "max_sentence_silence": Settings.ASR_QAS_SILENCE_MS,
        }
        if lang:
            params["language_hints"] = [lang]

        await ws.send(json.dumps({
            "header": {
                "action": "run-task",
                "task_id": str(task_id),
                "streaming": "duplex"
            },
            "payload": {
                "task_group": "audio",
                "task": "asr",
                "function": "recognition",
                "model": Settings.ASR_QAS_MODEL,
                "parameters": params,
                "input": {},
            },
        }))

    async def _await_task_started(self, ws):
        """
        显式等 task-started:与官方 demo 的 task_started 标志+开线程同构,
        asyncio 里的"等"就是 await 一次 recv。等待窗口内只可能来:
        task-started(就绪)/ task-failed(报错抛出)/ 其他(容错跳过)。
        超时保护:wait_for 包住 recv——服务端不回(网络挂/鉴权慢)时按协议错误抛,
        绝不永久挂死;超时后整条连接就废弃,recv 被 cancel 也不心疼丢消息。"""
        while True:
            try:
                msg = asyncio.wait_for(ws.recv(), timeout=_START_TIMEOUT_S)
            except TimeoutError:
                raise RuntimeError(f"run-task 后 {_START_TIMEOUT_S}s 未收到 task-started")
            evt = json.loads(msg)
            event = evt.get("header", {}).get("event")
            if event == _SERVER_TASK_STARTED:
                return
            if event == _SERVER_TASK_FAILED:
                raise RuntimeError(
                    f"任务失败: [{evt["header"].get('error_code')}] {evt["header"].get('error_message')}")
            logger.info(f"未知事件: {event}")

    async def _send_audio(self, ws, chunks, task_id):
        """裸二进制直传;chunks 耗尽 → finish-task。只在 task-started 之后被创建"""
        async for chunk in chunks:
            await ws.send(chunk)
        await ws.send({
            "header": {
                'action': "finish-task",
                'task_id': task_id,
                'streaming': "duplex"
            },
            "payload": {
                'input': {}
            }
        })

    def _handle_state_event(self, msg: str) -> bool:
        """终态事件(task-finished/task-failed)→ True(结束生成器),False=继续"""
        h = json.loads(msg).get("header", {})
        event = h.get("event")
        if event == _SERVER_TASK_FINISHED:
            return True
        if event == _SERVER_TASK_FAILED:
            raise RuntimeError(f"[{h.get('error_code')}] {h.get('error_message')}")
        return False

    def _map_event(self, msg: str) -> PartialResult | None:
        """result-generated → PartialResult(§1.3 映射表)。None = 不下发。"""
        evt = json.loads(msg)
        sentence = evt.get("payload", {}).get("output", {}).get("sentence", {})
        if not sentence:
            return None
        if sentence.get("heartbeat"):  # 心跳帧(sentence_id=0)
            return None
        return PartialResult(
            is_final=sentence.get("sentence_end", False),
            text=sentence.get("text", ""),
            beg_ms=sentence.get("begin_time"),  # 句级时间戳白拿(§1.3)
            end_ms=sentence.get("end_time"),
        )