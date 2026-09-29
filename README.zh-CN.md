# auris

**简体中文** | [English](README.md) | [日本語](README.ja.md)

**auris** 是一个开源的语音转文字工作台：把音频变成可检索、可分享的文本。从文件上传、直链、B 站视频、小宇宙播客等多来源输入，经可插拔的 ASR 引擎转写，到站内通知触达，提供端到端的完整链路。

## 核心特性

- 🎙️ **可插拔 ASR 引擎** — 内置 Whisper、Qwen3-ASR、流式音频三个 provider，VAD 智能分段，长短音频通吃
- 🔗 **多来源接入** — 文件上传同步直返；URL 异步任务，支持 B 站视频、小宇宙播客、直链音频自动抓取
- ⚡ **流式识别** — WebSocket 实时转写，边说边出
- 🧱 **微服务后端** — Spring Boot 3：gateway（路由）/ user（JWT 鉴权）/ ai（转写编排）/ push（通知投递）
- 📨 **事件驱动通知** — Kafka 两级 topic 架构，站内信 / 邮件 / 飞书多渠道投递，两级幂等 + DB 驱动重试 + DLQ 死信队列
- 🖥️ **现代 Web 前端** — Vue 3 + TypeScript + Tailwind CSS，登录、转写、历史、通知一站直达

## 架构一览

| 模块 | 技术栈 | 职责 |
|---|---|---|
| `engine/` | Python · FastAPI | AI 引擎：ASR 转写（同步 / 异步 / 流式）、VAD 分段、长音频任务、多来源抓取 |
| `server/` | Java · Spring Boot 3 | 业务微服务：gateway 路由、user 鉴权、ai 转写编排、push 通知投递、common 公共库 |
| `frontend/` | Vue 3 · TypeScript · Tailwind CSS | Web 端：转写工作台、历史记录、站内通知、个人中心 |
| 基础设施 | MySQL · Redis · Kafka · Nacos | 持久化、缓存、事件流、服务注册与配置 |

## 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 转写代理（engine ↔ server 对接） | ✅ |
| P2 | 用户鉴权（注册 / 登录 / JWT） | ✅ |
| P3 | 记录持久化与历史查询 | ✅ |
| P4 | URL 转写入口（B 站 / 小宇宙 / 直链） | ✅ |
| P5 | 通知服务（Kafka 事件驱动多渠道投递） | ✅ |
| P6 | 可观测与智能诊断 | 🚧 |

后续规划：TTS 语音合成、Agent 对话、更多 ASR provider 与通知渠道。方案设计见 [docs/design](docs/design/README.md)。

## License

[MIT](LICENSE) © 2026 marsdev0
