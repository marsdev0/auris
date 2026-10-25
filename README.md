# auris

**English** | [简体中文](README.zh-CN.md)

**auris** (Latin for "ear") is an open-source speech-to-text workbench that turns audio into searchable, shareable text — and goes one step further: **turning foreign-language videos into Chinese-dubbed ones**. From multi-source ingestion — file uploads, direct links, Bilibili videos, Xiaoyuzhou podcasts — through pluggable ASR engines, LLM batch translation, TTS synthesis, and audio-visual duration alignment, all the way to in-app notification delivery, it provides end-to-end pipelines.

## Key Features

- 🎙️ **Pluggable ASR engines** — Built-in Whisper, Qwen3-ASR, and streaming audio providers, with intelligent VAD segmentation, sentence-level timestamps, for both short and long audio
- 🌐 **Video Chinese dubbing (P12)** — Paste a Bilibili video link: automatic download → transcription → LLM batch translation → TTS synthesis → **audio-visual duration alignment** (three-tier escalation: length-constrained translation / re-splitting / chunk-level time-stretching) → muxing, producing a Chinese-dubbed mp4 + srt subtitles; **stage-level checkpoints with crash-safe resume (verified with kill -9)**
- 🔗 **Multi-source ingestion** — Synchronous transcription for file uploads; asynchronous tasks for URLs, with automatic fetching of Bilibili videos, Xiaoyuzhou podcasts, and direct audio/video links
- ⚡ **Streaming transcription** — Real-time recognition over WebSocket, results as you speak
- 🧠 **Engine-side LLM client** — Batch translation (grouping + numbered alignment + graceful degradation chain), call-level retries with deterministic-error discrimination, json_mode compatibility fallback
- 🧱 **Microservice backend** — Spring Boot 3: gateway (routing), user (JWT auth), ai (transcription & dubbing orchestration, idempotency, asset streaming proxy), push (notification delivery)
- 📨 **Event-driven notifications** — Two-level Kafka topic architecture, delivered via in-app / email / Feishu channels, with two-level idempotency, DB-driven retries, and a DLQ
- 🖥️ **Modern web frontend** — Vue 3 + TypeScript + Tailwind CSS for transcription, dubbing task board (dual-form progress / playback / subtitle download), history, notifications, and profile

## Architecture at a Glance

| Module | Stack | Responsibility |
|---|---|---|
| `engine/` | Python · FastAPI | AI engine: ASR (sync / async / streaming), VAD segmentation, long-audio segmented pipeline, multi-source fetching, LLM client, TTS (edge-tts), dubbing pipeline (6/8 stages, checkpoint resume, duration alignment, ffmpeg muxing) |
| `server/` | Java · Spring Boot 3 | Business microservices: gateway routing (Nacos dynamic config), user auth, ai transcription/dubbing orchestration (task table, idempotency, two-level polling, asset proxy), push notifications, shared common library |
| `frontend/` | Vue 3 · TypeScript · Tailwind CSS | Web app: transcription workbench, dubbing task board (audio/video dual-form), real-time recognition, history, in-app notifications, model config (BYOK) |
| Infrastructure | MySQL · Redis · Kafka · Nacos | Persistence (authoritative task state), caching/rate-limiting, event streaming, service registry & config |

## Milestones

| Phase | Scope | Status |
|---|---|---|
| P1 | Transcription proxy (engine ↔ server) | ✅ |
| P2 | User authentication (register / login / JWT) | ✅ |
| P3 | Record persistence and history | ✅ |
| P4 | URL transcription entry (Bilibili / Xiaoyuzhou / direct links) | ✅ |
| P5 | Notifications (Kafka event-driven, multi-channel delivery) | ✅ |
| P6 | Observability & intelligent diagnostics | 🚧 |
| P8 | Scheduling gate (PriorityGate: priority/reserve/aging, empirically calibrated) | ✅ |
| P10 | Load testing & capacity validation (ASR concurrency calibration) | ✅ |
| P12 | Video Chinese dubbing domain (TTS → narration → translation → video alignment & muxing → Java thin layer + frontend, six-station delivery) | ✅ |

Roadmap: P7 summarization domain, P9 quotas & multi-tenancy, P11 cloud productionization, object storage for artifacts (pre-signed URLs), bilingual subtitles, completion-event push. Design docs in [docs/design](docs/design/README.md).

## Quick Start

```bash
make infra-up        # MySQL/Redis/Kafka/Nacos
make run-engine      # :18000
make run-gateway & make run-ai & make run-user   # :8080/:8081/:8082
make run-frontend    # :5173
```

> Using an AI coding agent (pi / Claude Code / etc.)? The repo ships an `.agents/skills/auris-dev` orchestration skill — just tell your agent "start auris" to bring up the full stack (with health checks and failure handling).

## License

[MIT](LICENSE) © 2026 marsdev0
