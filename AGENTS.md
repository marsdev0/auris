# AGENTS.md

语音转文字工作台。三个模块：`engine/`（Python FastAPI，ASR 推理）、`server/`（Java Spring Boot 3 微服务：gateway/user/ai/push + common）、`frontend/`（Vue 3 + TS + Tailwind）。基础设施（MySQL/Redis/Kafka/Nacos）由 `server/compose.yaml` 提供。

## 常用命令

| 操作 | 命令 |
|---|---|
| 基础设施 | `make infra-up` / `make infra-down` |
| Python engine（:18000） | `make run-engine` |
| Java 各服务 | `make run-gateway`(:8080) / `make run-ai`(:8081) / `make run-user`(:8082) / `make run-push`(:8083) |
| 前端（:5173） | `make run-frontend` / `make stop-frontend` |
| Java 测试 / 构建 | `make test-java` / `make build-java`（等价 `cd server && ./mvnw test` / `./mvnw -q verify`） |
| Python 依赖 | `uv add <pkg>` / `uv sync`（加删依赖后记得提交 uv.lock） |

端口速查：engine 18000 · gateway 8080 · ai 8081 · user 8082 · push 8083 · 前端 5173 · nacos 8848 · kafka 9092 · kafka-ui 9000。

## 硬性规则（踩过的坑）

- **修改任何非前端代码（`engine/`、`server/` 等）前必须先向用户说明意图并获批准，未批准只能读不能改**；前端（`frontend/`）可直接修改。
- **Python 一律走 uv，禁止 `pip install`**——会绕过 `uv.lock` 导致环境漂移。
- **engine 必须用 `make run-engine`（即 `python -m engine.main`），禁止裸 `uvicorn engine.main:app`**——后者绕过 `config.py`，落到默认 8000 端口。且必须在仓库根目录运行。
- **engine/sources/router.py 只是本机排障口，业务流量严禁直达 engine**，一律经 ai-service（P4 §2.6）。
- **Kafka 禁止自动建 topic**（broker 已关 auto-create），新 topic 用 `server/kafka-topics-push.sh` 手动创建。
- compose 的 Kafka INTERNAL listener（kafka:29092）是容器网用的；宿主机服务一律连 `localhost:9092`（HOST listener）。
- **JVM 服务默认时区为 UTC**（PushApplication 等显式设置），时间处理勿按本地时区假设。
- 公共 JSON 序列化用 `common` 的 `JsonUtils`（已注册 JavaTimeModule，支持 LocalDateTime）。

## 代码与提交规范

- 注释、文档用中文；标识符、commit message 用英文。
- commit 遵循 Conventional Commits，scope 按模块：`ai` / `push` / `user` / `gateway` / `web`（前端）/ `engine` / `asr` / `common`。
- 一次提交只做一件事；跨服务的改动按服务拆成多个 commit。
- Java：Spring Boot 3 + MyBatis-Plus，DO/Mapper/Service/Controller 分层，参考各服务现有结构。
- engine：ASR provider 插件式（`engine/asr/providers/`），新 provider 实现后注册到 service；来源抓取器放 `engine/sources/`。
- 数据库表结构以 `server/sql/*.sql` 为准，新增表记得补 SQL 文件。
