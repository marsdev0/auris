#!/usr/bin/env bash
# 一键启动：基础设施 -> engine -> 业务服务 -> 网关（服务以后台进程运行，日志在 logs/）
# 用法: ./start.sh [service ...]
#   ./start.sh            # 全量启动（infra + engine + 全部 Java 服务）
#   ./start.sh engine ai  # 只启动指定部分: infra engine ai user push gateway
set -euo pipefail
cd "$(dirname "$0")"

usage() {
    sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

# 等待本地端口可连接；超时返回 1
wait_for_port() {
    local port=$1 name=$2 timeout=${3:-90} waited=0
    while ! (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; do
        if (( waited >= timeout )); then
            echo "[error] ${name} (port ${port}) 在 ${timeout}s 内未就绪" >&2
            return 1
        fi
        sleep 1
        ((waited++))
    done
    exec 3>&- 2>/dev/null || true
}

# 等待 nacos 完全就绪（HTTP readiness，失败退化为 gRPC 端口探测）
wait_for_nacos() {
    local waited=0
    echo "[wait] nacos 就绪中..."
    while ! curl -sf -o /dev/null "http://127.0.0.1:8848/nacos/v1/console/health/readiness" 2>/dev/null; do
        if (( waited >= 60 )); then
            echo "[error] nacos 60s 内未就绪，请检查: docker compose -f server/compose.yaml logs nacos" >&2
            return 1
        fi
        sleep 2
        ((waited+=2))
    done
}

is_running() {
    pgrep -f "$1" >/dev/null 2>&1
}

start_one() {
    local name="$1"
    local log="logs/${name}.log"

    local match port
    case "$name" in
        infra)    match="__never_match__"; port="" ;;
        engine)   match="python -m engine.main"; port="" ;;
        ai)       match="spring-boot:run.*-pl ai-service"; port=8081 ;;
        user)     match="spring-boot:run.*-pl user-service"; port=8082 ;;
        push)     match="spring-boot:run.*-pl push-service"; port=8083 ;;
        gateway)  match="spring-boot:run.*-pl gateway-service"; port=8080 ;;
        *)        echo "[error] 未知服务: ${name}" >&2; usage ;;
    esac

    if [[ "$name" == "infra" ]]; then
        echo "[start] infra (docker compose)"
        docker compose -f server/compose.yaml up -d
        wait_for_nacos
        return
    fi

    if is_running "$match"; then
        echo "[skip] ${name} 已在运行"
        return
    fi

    mkdir -p logs
    echo "[start] ${name} -> ${log}"
    case "$name" in
        engine)   nohup make run-engine   > "$log" 2>&1 & ;;
        ai)       nohup make run-ai       > "$log" 2>&1 & ;;
        user)     nohup make run-user     > "$log" 2>&1 & ;;
        push)     nohup make run-push     > "$log" 2>&1 & ;;
        gateway)  nohup make run-gateway  > "$log" 2>&1 & ;;
    esac

    # Java 服务：等端口就绪再继续，避免下游服务连接/注册竞态
    if [[ -n "$port" ]]; then
        if wait_for_port "$port" "$name" 120; then
            echo "[ok] ${name} 就绪 (:${port})"
        else
            echo "[error] ${name} 启动失败，查看日志: tail -50 ${log}" >&2
            return 1
        fi
    fi
}

if [[ $# -eq 0 ]]; then
    targets=(infra engine ai user push gateway)
else
    targets=("$@")
fi

for t in "${targets[@]}"; do
    start_one "$t"
done

echo "done. 查看日志: tail -f logs/<service>.log，停止: ./stop.sh"
