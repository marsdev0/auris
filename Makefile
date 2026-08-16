.PHONY: run-engine run-ai run-gateway test-ai test-java build-java clean-java infra-up infra-down

# Python engine（ASR/TTS 推理服务，端口 18000）
run-engine:
	uv run python -m engine.main

# Java ai 服务（对接 engine，端口 8081）
run-ai:
	cd server && ./mvnw -pl ai spring-boot:run

# Java gateway 网关（端口 8080，路由见 nacos: auris-gateway-routes.yaml）
run-gateway:
	cd server && ./mvnw -pl gateway spring-boot:run

test-java: test-ai
test-ai:
	cd server && ./mvnw test

build-java:
	cd server && ./mvnw -q verify

# 基础设施（nacos 等，见 server/compose.yaml）
infra-up:
	cd server && docker compose up -d

infra-down:
	cd server && docker compose down
