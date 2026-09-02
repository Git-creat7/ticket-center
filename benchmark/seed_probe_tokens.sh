#!/usr/bin/env bash
# 为压测直接在 Redis 里种登录态，不走短信+登录流程。
#
# 只为了让 RefreshTokenInterceptor 能取到用户：它读 tc:login:token:<token>
# 这个 hash 并填进 UserDTO，签到接口本身不碰数据库，所以用不存在于 tb_user
# 的合成 id 也能跑，而且不会污染真实用户的签到位图。
#
# 密码通过容器内的 $REDIS_PASSWORD 传给 redis-cli，不落到宿主机命令行，
# 与 docker-compose.yml 里 healthcheck 的写法一致。
set -euo pipefail

COUNT="${1:-100}"
CSV="$(dirname "$0")/jmeter_tokens.csv"
BASE_ID=900000
TTL=7200

# 一次 docker exec 里批量 HSET，逐条 exec 的话 100 个用户要几十秒
{
  echo "token,phone"
  script=""
  for i in $(seq 1 "$COUNT"); do
    token="$(printf 'bench%027d' "$i")"
    uid=$((BASE_ID + i))
    script="${script}HSET tc:login:token:${token} id ${uid} nickName bench${i}
EXPIRE tc:login:token:${token} ${TTL}
"
    echo "${token},138$(printf '%08d' "$i")"
  done
  printf '%s' "$script" > /tmp/seed_tokens.redis
} > "$CSV"

docker cp /tmp/seed_tokens.redis ticket-redis:/tmp/seed_tokens.redis >/dev/null
docker exec ticket-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli < /tmp/seed_tokens.redis | sort | uniq -c'

echo "已写入 ${COUNT} 个 token 到 ${CSV}（合成用户 id $((BASE_ID+1))~$((BASE_ID+COUNT))，TTL ${TTL}s）"
