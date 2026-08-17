#!/usr/bin/env bash
# 停止服务（与 start.sh 相反的顺序），并清理残留进程
# 用法: ./stop.sh [service ...]
#   ./stop.sh            # 停止全部（gateway -> ... -> engine -> infra）
#   ./stop.sh engine ai  # 只停止指定部分: infra engine ai user push gateway
set -euo pipefail
cd "$(dirname "$0")"

usage() {
    sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

stop_one() {
    local name="$1"
    echo "[stop] ${name}"
    case "$name" in
        infra)
            cd server && docker compose down; cd ..
            ;;
        engine)   pkill -f "python -m engine.main" 2>/dev/null || true ;;
        ai)       pkill -f "spring-boot:run.*-pl ai-service" 2>/dev/null || true ;;
        user)     pkill -f "spring-boot:run.*-pl user-service" 2>/dev/null || true ;;
        push)     pkill -f "spring-boot:run.*-pl push-service" 2>/dev/null || true ;;
        gateway)  pkill -f "spring-boot:run.*-pl gateway-service" 2>/dev/null || true ;;
        *)        echo "[error] 未知服务: ${name}" >&2; usage ;;
    esac
}

if [[ $# -eq 0 ]]; then
    targets=(gateway push user ai engine infra)
else
    targets=("$@")
fi

for t in "${targets[@]}"; do
    stop_one "$t"
done
