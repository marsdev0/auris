.PHONY: infra-up run-engine run-frontend stop-frontend run-ai run-user run-push run-gateway test-java build-java clean-java infra-down

# ========== 启动（按依赖顺序：基础设施 -> engine -> 业务服务 -> 网关） ==========

# 基础设施（nacos 等，见 server/compose.yaml）
infra-up:
	cd server && docker compose up -d

# Python engine（ASR/TTS 推理服务，端口 18000）
run-engine:
	uv run python -m engine.main

# 前端 dev server（Vue3+Vite，端口 5173；/v1/asr 代理到 engine:18000，需先起 engine）
run-frontend:
	cd frontend && npm run dev

# 停止前端 dev server（vite 无 pid 文件；按进程特征杀，cwd 校验只杀本项目的 vite）
stop-frontend:
	@for pid in $$(pgrep -f "vite/bin/vite.js"); do \
		cwd=$$(lsof -p $$pid 2>/dev/null | awk '$$4=="cwd" {print $$NF}'); \
		if [[ "$$cwd" == "$$(pwd)/frontend" ]]; then kill $$pid && echo "[stop] frontend ($$pid)"; fi; \
	done

# Java ai 服务（对接 engine，端口 8081）
run-ai:
	cd server && ./mvnw -pl ai-service spring-boot:run

# Java user 用户服务（端口 8082）
run-user:
	cd server && ./mvnw -pl user-service spring-boot:run

# Java push 推送服务（端口 8083）
run-push:
	cd server && ./mvnw -pl push-service spring-boot:run

# Java gateway 网关（端口 8080，路由见 nacos: auris-gateway-routes.yaml）
run-gateway:
	cd server && ./mvnw -pl gateway-service spring-boot:run

# ========== 构建 / 测试 ==========

test-java:
	cd server && ./mvnw test

build-java:
	cd server && ./mvnw -q verify

# ========== 停止 ==========

infra-down:
	cd server && docker compose down
