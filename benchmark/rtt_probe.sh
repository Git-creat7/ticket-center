#!/usr/bin/env bash
# 签到接口往返次数与并发耗时探针。
#
# 用途：在同一台机器、同一时刻对比改动前后的表现，避免和历史基线跨环境比较。
# 两件事分开测：
#   1) 单请求的 Redis 命令数（CONFIG RESETSTAT + 一次请求 + INFO commandstats）
#   2) 固定并发下的吞吐与耗时（curl -Z 单进程内并发，避免进程创建开销污染）
#
# 用法：rtt_probe.sh <label> [并发] [请求数]
set -euo pipefail

LABEL="${1:?用法: rtt_probe.sh <label> [并发] [请求数]}"
CONC="${2:-100}"
TOTAL="${3:-2000}"
ENV_FILE="$(dirname "$0")/../.env"
BACKEND_PORT="${BACKEND_HOST_PORT:-}"
if [ -z "$BACKEND_PORT" ] && [ -f "$ENV_FILE" ]; then
    BACKEND_PORT="$(awk -F= '
        $1 ~ /^[[:space:]]*BACKEND_HOST_PORT[[:space:]]*$/ {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", $2)
            print $2
            exit
        }' "$ENV_FILE")"
fi
BACKEND_PORT="${BACKEND_PORT:-8080}"
URL="http://localhost:${BACKEND_PORT}/user/sign/status"
TOKEN="rttprobe0000000000000000000000ab"
OUT="$(dirname "$0")/rtt_probe_${LABEL}.txt"

redis() { docker exec ticket-redis sh -c "REDISCLI_AUTH=\"\$REDIS_PASSWORD\" redis-cli $1"; }

# 探针会话：RefreshTokenInterceptor 靠这个 hash 认出用户
redis "HSET tc:login:token:${TOKEN} id 88899 nickName probe" >/dev/null

{
    echo "==== ${LABEL} ===="
    echo "时间: $(date '+%Y-%m-%d %H:%M:%S %a')"

    echo
    echo "-- 单请求 Redis 命令数 --"
    redis "CONFIG RESETSTAT" >/dev/null
    curl -s -o /dev/null -H "authorization: ${TOKEN}" "$URL"
    # INFO 只取一次：调它自己也会被计入 commandstats，取两次会把第一次的 info 算进总数
    stats="$(redis 'INFO commandstats' | grep -a 'cmdstat' \
        | grep -av 'config|resetstat\|cmdstat_auth\|cmdstat_info' || true)"
    echo "$stats" | sort
    echo "$stats" | grep -ao ':calls=[0-9]*' | cut -d= -f2 \
        | awk '{s+=$1} END {printf "往返合计: %d 次/请求\n", s}'

    echo
    echo "-- 并发 ${CONC}，共 ${TOTAL} 请求 --"
    # url 与 output 成对写进配置文件：-o 只作用于第一个 URL，
    # 不逐条指定的话响应体会混进 -w 的输出里。header 放命令行，
    # 因为写在配置文件里会全局累加成上千个重复头，直接被服务端判成 400。
    cfg="$(mktemp)"
    for _ in $(seq 1 "$TOTAL"); do
        printf 'url = "%s"\noutput = "/dev/null"\n' "$URL"
    done > "$cfg"

    # || true：-Z 下任一路传输非零退出会让 set -e 直接中断脚本，测量结果就丢了
    start=$(date +%s%N)
    codes="$(curl -s -Z --parallel-max "$CONC" -H "authorization: ${TOKEN}" -K "$cfg" -w '%{http_code}\n' || true)"
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
} | tee "$OUT"
