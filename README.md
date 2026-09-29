# auris

**English** | [简体中文](README.zh-CN.md) | [日本語](README.ja.md)

**auris** (Latin for "ear") is an open-source speech-to-text workbench that turns audio into searchable, shareable text. From multi-source ingestion — file uploads, direct links, Bilibili videos, Xiaoyuzhou podcasts — through pluggable ASR engines, all the way to in-app notification delivery, it provides an end-to-end transcription pipeline.

## Key Features

- 🎙️ **Pluggable ASR engines** — Built-in Whisper, Qwen3-ASR, and streaming audio providers, with intelligent VAD segmentation for both short and long audio
- 🔗 **Multi-source ingestion** — Synchronous transcription for file uploads; asynchronous tasks for URLs, with automatic fetching of Bilibili videos, Xiaoyuzhou podcasts, and direct audio links
- ⚡ **Streaming transcription** — Real-time recognition over WebSocket, results as you speak
- 🧱 **Microservice backend** — Spring Boot 3: gateway (routing), user (JWT auth), ai (transcription orchestration), push (notification delivery)
- 📨 **Event-driven notifications** — Two-level Kafka topic architecture, delivered via in-app / email / Feishu channels, with two-level idempotency, DB-driven retries, and a DLQ
- 🖥️ **Modern web frontend** — Vue 3 + TypeScript + Tailwind CSS for transcription, history, notifications, and profile in one place

## Architecture at a Glance

| Module | Stack | Responsibility |
|---|---|---|
| `engine/` | Python · FastAPI | AI engine: ASR (sync / async / streaming), VAD segmentation, long-audio tasks, multi-source fetching |
| `server/` | Java · Spring Boot 3 | Business microservices: gateway routing, user auth, ai orchestration, push notifications, shared common library |
| `frontend/` | Vue 3 · TypeScript · Tailwind CSS | Web app: transcription workbench, history, in-app notifications, profile |
| Infrastructure | MySQL · Redis · Kafka · Nacos | Persistence, caching, event streaming, service registry & config |

## Milestones

| Phase | Scope | Status |
|---|---|---|
| P1 | Transcription proxy (engine ↔ server) | ✅ |
| P2 | User authentication (register / login / JWT) | ✅ |
| P3 | Record persistence and history queries | ✅ |
| P4 | URL transcription entry (Bilibili / Xiaoyuzhou / direct links) | ✅ |
| P5 | Notification service (Kafka event-driven multi-channel delivery) | ✅ |
| P6 | Observability and intelligent diagnostics | 🚧 |

On the roadmap: TTS synthesis, conversational agents, and more ASR providers and notification channels. Design docs live in [docs/design](docs/design/README.md).

## License

[MIT](LICENSE) © 2026 marsdev0
