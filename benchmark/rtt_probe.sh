#!/usr/bin/env bash
# 签到接口的 Redis 命令数与 HTTP 并发耗时探针。
# 用法：rtt_probe.sh <label> [并发] [请求数]
set -euo pipefail

LABEL="${1:?用法: rtt_probe.sh <label> [并发] [请求数]}"
CONC="${2:-100}"
TOTAL="${3:-2000}"
[[ "$LABEL" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "label 只能包含字母、数字、下划线和短横线" >&2; exit 1; }
[[ "$CONC" =~ ^[1-9][0-9]*$ && "$TOTAL" =~ ^[1-9][0-9]*$ ]] || { echo "并发和请求数必须是正整数" >&2; exit 1; }
URL="http://${BACKEND_HOST:-127.0.0.1}:${BACKEND_HOST_PORT:-8080}/user/sign/status"
REDIS_CONTAINER="${REDIS_CONTAINER:-ticket-redis}"
TOKEN="rttprobe$(date +%s%N)"
OUT="$(dirname "$0")/rtt_probe_${LABEL}.txt"

redis() {
    docker exec "$REDIS_CONTAINER" sh -c \
        'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw "$@"' sh "$@"
}

# 探针会话：RefreshTokenInterceptor 靠这个 hash 认出用户
redis HSET "tc:login:token:${TOKEN}" id 88899 nickName probe >/dev/null
redis EXPIRE "tc:login:token:${TOKEN}" 60 >/dev/null
trap 'redis DEL "tc:login:token:${TOKEN}" >/dev/null' EXIT

{
    echo "==== ${LABEL} ===="
    echo "时间: $(date '+%Y-%m-%d %H:%M:%S %a')"

    echo
    echo "-- 单请求 Redis 命令数 --"
    redis CONFIG RESETSTAT >/dev/null
    curl -fsS --max-time 10 -H "authorization: ${TOKEN}" "$URL" \
        | python3 -c 'import json, sys; sys.exit(json.load(sys.stdin).get("code") != 200)'
    # INFO 只取一次：调它自己也会被计入 commandstats，取两次会把第一次的 info 算进总数
    stats="$(redis INFO commandstats | grep -a 'cmdstat' \
        | grep -av 'config|resetstat\|cmdstat_auth\|cmdstat_info' || true)"
    echo "$stats" | sort
    echo "$stats" | grep -ao ':calls=[0-9]*' | cut -d= -f2 \
        | awk '{s+=$1} END {printf "往返合计: %d 次/请求\n", s}'

    echo
    echo "-- 并发 ${CONC}，共 ${TOTAL} 请求 --"
    # 每个 URL 单独指定输出位置，避免响应体混入状态码。
    cfg="$(mktemp)"
    for _ in $(seq 1 "$TOTAL"); do
        printf 'url = "%s"\noutput = "/dev/null"\n' "$URL"
    done > "$cfg"

    # || true：-Z 下任一路传输非零退出会让 set -e 直接中断脚本，测量结果就丢了
    start=$(date +%s%N)
    codes="$(curl -sS --max-time 10 -Z --parallel-max "$CONC" -H "authorization: ${TOKEN}" -K "$cfg" -w '%{http_code}\n' || true)"
    end=$(date +%s%N)
    rm -f "$cfg"

    dur=$(awk -v s="$start" -v e="$end" 'BEGIN{printf "%.3f", (e-s)/1e9}')
    ok=$(echo "$codes" | grep -ac '^200$' || true)
    awk -v n="$TOTAL" -v d="$dur" -v ok="$ok" -v c="$CONC" 'BEGIN{
        printf "耗时      : %.3f s\n", d;
        printf "HTTP 200  : %d/%d\n", ok, n;
        printf "吞吐量    : %.1f QPS\n", n/d;
        # 闭环固定并发下 平均耗时 = 并发数 / 吞吐量
        printf "平均耗时  : %.1f ms\n", c/(n/d)*1000;
    }'
    [[ "$ok" == "$TOTAL" ]]
} | tee "$OUT"
