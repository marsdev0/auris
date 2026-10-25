---
name: auris-dev
description: auris 开发环境编排——一键启动/停止/查状态(前端+Java 三服务+engine+docker infra+omlx 探测),含健康判定与故障处置。当用户说「启动 auris」「起服务」「一键启动」「停服务」「停掉/关闭 auris」「服务状态」「只起 xx」「只停 xx」时使用。
---

# auris 开发环境编排

AI 代跑(用户已授权,不需要用户执行命令)。所有命令在**仓库根目录**运行;
后台进程统一 `nohup ... > tmp/run-xxx.log 2>&1 &`,日志集中 `tmp/` 供排障。

## 服务拓扑与健康判定

| 层 | 服务(端口) | 启动 | 健康判定 |
|---|---|---|---|
| infra | mysql/redis/kafka/nacos(3306/6379/9092/8848) | `make infra-up` | `docker ps` 见 auris-* 容器;nacos console 8848 有响应 |
| 推理 | omlx(:28000,LLM+qwen3-asr 共用) | **只探测,不代起** | `curl 127.0.0.1:28000/v1/models` 有响应 |
| engine | Python(:18000) | `nohup make run-engine > tmp/run-engine.log 2>&1 &` | `/health` = 200 |
| java | user/ai/gateway(:8082/8081/8080) | 各 `nohup make run-user|run-ai|run-gateway > tmp/run-xxx.log 2>&1 &` | 端口 POST 空 body:**400/401=活**(正常拒绝),000=死;无 actuator |
| 前端 | vite(:5173) | `nohup make run-frontend > tmp/run-frontend.log 2>&1 &` | GET / = 200 |

**健康探测的语义表**(勿误判):200=活;400/401=活且鉴权/参数校验正常;
404=活但无此路由;000=死。探测用 `curl -s -o /dev/null -w "%{http_code}" --max-time 2`。

## 启动流程(全套,按依赖顺序)

1. **infra**:`docker ps` 查 auris-mysql/redis/kafka/nacos;缺则 `make infra-up`,
   等待 ~15s 后复验(nacos console 最慢)。
2. **omlx 探测**(不阻塞):`curl -s --max-time 3 http://127.0.0.1:28000/v1/models`;
   无响应则在最终汇报中提示用户:
   > 「omlx 推理服务(:28000)未运行——LLM 翻译与 qwen3-asr 转写不可用
   > (whisper 本地转写不受影响)。请手动启动 omlx,不影响其余服务使用。」
3. **engine**:nohup 起(见上表),轮询 /health(2s×30)到 200。
4. **Java 三件**:三个 nohup 并行起;轮询三端口(4s×30)。
   **Nacos 竞态处置**:日志含 `NacosException: Client not connected, current status:STARTING`
   → 多为 nacos 未就绪的瞬态,等 10s 整体重试一次;仍失败 → 回查第 1 步容器。
5. **前端**:nohup 起;轮询 5173 到 200。
6. **汇报**:表格输出各层状态(端口/健康/日志路径),异常项给根因和处置建议;
   提示测试账号(见 .env 的 D6_TEST_*)与素材链接(tests/assets/README.md)。

## 参数化(部分启动)

用户说「只起 engine 和前端」等时,按需求裁剪步骤(依赖仍须满足:engine 不依赖
Java,Java 依赖 infra,前端不依赖后端可独立起)。无参数默认全套。

## 停止

按依赖反向顺序停(前端 → Java → engine),默认**不停 infra**(MySQL/Kafka 等重建便宜但
数据/配置重载慢,开发中通常保留;用户明说「连 infra 一起停」才 `make infra-down`)。

```
pkill -f vite                          # ① 前端(或 make stop-frontend)
pkill -f "spring-boot"                # ② Java 三件(gateway/ai/user 同模式)
pkill -9 -f "engine.main"             # ③ engine(kill -9 即可:任务有 stage 级
                                       #    checkpoint,重启自扫断点续跑,不丢进度)
```

分层停止(与启动参数化对称):「只停 java」= 仅执行②;「停 engine」= 仅③。

**停止后验证**:逐端口复测(拓扑表端口全部 000 + `pgrep -f "engine.main|spring-boot|vite"`
为空),残留进程列出并补杀。汇报:各层停拍状态 + infra 保留状态(容器列表)。

注意:`pkill -f spring-boot` 会匹配 mvn 包装进程与子进程,杀后确认无残留
(`pgrep -f 'spring-boot|mvn'`);有则补杀。

## 状态查询

只做探测不动进程:按拓扑表逐层 curl/docker ps,输出状态表 + in-flight 任务
(MySQL:`docker exec auris-mysql mysql -uroot -proot auris -e "SELECT id,kind,status,
stage_index FROM dubbing_task ORDER BY id DESC LIMIT 3"`)。

## 故障处置库(实测踩坑,2026-25 P12 验收沉淀)

| 症状 | 根因 | 处置 |
|---|---|---|
| Java 起失败,日志 NacosException STARTING | nacos 容器未就绪或不存在 | 查 docker ps;容器全消失(Docker 环境变动)→ `make infra-up`(named volume 数据幸存);就绪后重试 |
| gateway 对新路径 404 | 路由在 Nacos 配置,改本地 resources 不生效 | 拉取 `auris-gateway-routes.yaml`(8848 /nacos/v1/cs/configs)核对该路径;缺则发布;**引用的 bean 名必须存在**(错 key-resolver 会让整个 routes 刷新失败,gateway 静默保旧路由,看日志 `Refresh routes error`) |
| 前端请求 404 但后端接口正常 | vite proxy 白名单缺该路径 | 查 vite.config.ts proxy,补对应 `/v1/xxx` → 8080(WS 例外走 asr 条目已配) |
| engine 任务下载 404 | 素材链路断(raw.githubusercontent 间歇不可达) | 换 jsDelivr(gh 镜像)或 tests/assets 备用素材 |
| 任务页面停在第一步但 engine 早已完成 | DB 只被 getTask 推进,页面心跳停 | 硬刷新页面;根治在 D6.5 事件推送 |
| dubbing 页面字段全空/渲染崩 | 后端分页字段是 records 不是 items | 对齐 transcribe 同款口径 |

## 边界

- **不改任何代码/配置**——只编排与诊断;修复建议给出,改动需用户确认。
- DB/文件操作只读探测(SELECT/ls),写操作须用户明确要求。
