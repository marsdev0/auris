# auris

**简体中文** | [English](README.md)

**auris** 是一个开源的语音转文字工作台：把音频变成可检索、可分享的文本，并进一步把**外语视频变成中文配音版**。从文件上传、直链、B 站视频、小宇宙播客等多来源输入，经可插拔的 ASR 引擎转写，到 LLM 批量翻译、TTS 合成、音画时长对齐，到站内通知触达，提供端到端的完整链路。

## 核心特性

- 🎙️ **可插拔 ASR 引擎** — 内置 Whisper、Qwen3-ASR、流式音频三个 provider，VAD 智能分段，句级时间戳，长短音频通吃
- 🌐 **视频中文配音（P12）** — 贴一个 B 站英文视频链接，自动完成下载→转写→LLM 批量翻译→TTS 合成→**音画时长对齐**（三层递进：字数约束 / 分格重切 / chunk 级变速）→混流，产出中文配音 mp4 + srt 字幕；**stage 级检查点，kill -9 重启断点续跑**
- 🔗 **多来源接入** — 文件上传同步直返；URL 异步任务，支持 B 站视频、小宇宙播客、音视频直链自动抓取
- ⚡ **流式识别** — WebSocket 实时转写，边说边出
- 🧠 **LLM 引擎侧客户端** — 批量翻译（聚批 + 编号对齐 + 容错降级链）、调用级重试与确定性错误甄别、json_mode 兼容降级
- 🧱 **微服务后端** — Spring Boot 3：gateway（路由）/ user（JWT 鉴权）/ ai（转写与配音编排、幂等、asset 流式代理）/ push（通知投递）
- 📨 **事件驱动通知** — Kafka 两级 topic 架构，站内信 / 邮件 / 飞书多渠道投递，两级幂等 + DB 驱动重试 + DLQ 死信队列
- 🖥️ **现代 Web 前端** — Vue 3 + TypeScript + Tailwind CSS，登录、转写、配音任务台（双形态进度/播放/字幕下载）、历史、通知一站直达

## 架构一览

| 模块 | 技术栈 | 职责 |
|---|---|---|
| `engine/` | Python · FastAPI | AI 引擎：ASR 转写（同步/异步/流式）、VAD 分段、长音频分段管线、多来源抓取、LLM 客户端、TTS（edge-tts）、dubbing 配音管线（六/八 stage、checkpoint 断点续跑、时长对齐、ffmpeg 混流） |
| `server/` | Java · Spring Boot 3 | 业务微服务：gateway 路由（Nacos 动态配置）、user 鉴权、ai 转写/配音编排（任务表、幂等、两层轮询、asset 代理）、push 通知投递、common 公共库 |
| `frontend/` | Vue 3 · TypeScript · Tailwind CSS | Web 端：转写工作台、配音任务台（音频/视频双形态）、实时识别、历史记录、站内通知、模型配置（BYOK） |
| 基础设施 | MySQL · Redis · Kafka · Nacos | 持久化（任务状态权威源）、缓存/限流、事件流、服务注册与配置 |

## 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 转写代理（engine ↔ server 对接） | ✅ |
| P2 | 用户鉴权（注册 / 登录 / JWT） | ✅ |
| P3 | 记录持久化与历史查询 | ✅ |
| P4 | URL 转写入口（B 站 / 小宇宙 / 直链） | ✅ |
| P5 | 通知服务（Kafka 事件驱动多渠道投递） | ✅ |
| P6 | 可观测与智能诊断 | 🚧 |
| P8 | 调度闸门（PriorityGate：优先级/预留/aging，实测校准） | ✅ |
| P10 | 压测与容量验证（ASR 并发参数校准） | ✅ |
| P12 | 视频中文配音域（TTS→播报→翻译→视频对齐混流→Java 薄层+前端，六站交付） | ✅ |

后续规划：P7 摘要域、P9 配额与多租户、P11 云端生产化、产物对象存储（预签名 URL）、双语字幕、完成事件推送。方案设计见 [docs/design](docs/design/README.md)。

## 快速开始

```bash
make infra-up        # MySQL/Redis/Kafka/Nacos
make run-engine      # :18000
make run-gateway & make run-ai & make run-user   # :8080/:8081/:8082
make run-frontend    # :5173
```

> 使用 AI 编码助手（pi / Claude Code 等）？仓库内置 `.agents/skills/auris-dev` 开发环境编排 skill——对 agent 说「启动 auris」即可一键起全套（含健康判定与故障处置）。

## License

[MIT](LICENSE) © 2026 marsdev0
