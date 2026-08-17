.PHONY: infra-up run-engine run-ai run-user run-push run-gateway test-java build-java clean-java infra-down

# ========== 启动（按依赖顺序：基础设施 -> engine -> 业务服务 -> 网关） ==========

# 基础设施（nacos 等，见 server/compose.yaml）
infra-up:
	cd server && docker compose up -d

# Python engine（ASR/TTS 推理服务，端口 18000）
run-engine:
	uv run python -m engine.main

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
