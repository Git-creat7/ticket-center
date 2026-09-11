#!/usr/bin/env bash
# 多服务器联调验收：Nacos 注册表、容器级跨主机可达性、Gateway 负载均衡分发。
# 在能免密 SSH 到中间件服务器的机器上运行（本机即节点 B）：
#   bash deploy/verify-multinode.sh
# 可用环境变量覆盖：SERVER_SSH、SERVER_IP、LOCAL_IP、GW_B_PORT、N
set -u
export MSYS_NO_PATHCONV=1

SERVER_SSH="${SERVER_SSH:-creat@10.115.110.241}"
SERVER_IP="${SERVER_IP:-10.115.110.241}"
LOCAL_IP="${LOCAL_IP:-10.115.82.254}"
GW_B_PORT="${GW_B_PORT:-18081}"
N="${N:-20}"

remote() { ssh -o BatchMode=yes -o ConnectTimeout=15 "$SERVER_SSH" "$@"; }

# Nacos 按 NACOS_BIND_IP 只绑私网地址，服务器上的 127.0.0.1:8848 不通，直接从本机查。
nacos_instances() {
  curl -s -m 8 "http://$SERVER_IP:8848/nacos/v1/ns/instance/list?serviceName=$1&groupName=TICKET_CENTER" \
    | grep -oE '"ip":"[^"]+","port":[0-9]+,"weight":[0-9.]+,"healthy":(true|false)' \
    | sed -E 's/"ip":"([^"]+)","port":([0-9]+),"weight":[0-9.]+,"healthy":(true|false)/\1:\2(\3)/'
}

echo "== 0. 容器状态"
echo "-- 节点 B（本机）"
docker ps --format '{{.Names}}  {{.Status}}  {{.Ports}}' | grep ticket-app
echo "-- 节点 A + 中间件（服务器）"
remote "docker ps --format '{{.Names}}  {{.Status}}  {{.Ports}}' | grep -E 'ticket-app|ticket-(mysql|redis|rabbitmq|nacos)'"

echo
echo "== 1. Nacos 注册实例（期望每个服务各有 A、B 两个 healthy 实例）"
for s in ticket-center-api order-service ticket-gateway; do
  printf '%-18s %s\n' "$s" "$(nacos_instances "$s" | tr '\n' ' ')"
done

GW_A_PORT=$(nacos_instances ticket-gateway | grep "^$SERVER_IP:" | head -1 | sed -E 's/^[^:]+:([0-9]+).*/\1/')
API_A_PORT=$(nacos_instances ticket-center-api | grep "^$SERVER_IP:" | head -1 | sed -E 's/^[^:]+:([0-9]+).*/\1/')
API_B_PORT=$(nacos_instances ticket-center-api | grep "^$LOCAL_IP:" | head -1 | sed -E 's/^[^:]+:([0-9]+).*/\1/')
if [ -z "$GW_A_PORT" ] || [ -z "$API_A_PORT" ] || [ -z "$API_B_PORT" ]; then
  echo "Nacos 里缺少节点 A 或 B 的实例，先解决注册问题再验收。" >&2
  exit 1
fi

API_B=$(docker ps --format '{{.Names}}' | grep -m1 ticket-center-api)
ORDER_B=$(docker ps --format '{{.Names}}' | grep -m1 order-service)
GW_B=$(docker ps --format '{{.Names}}' | grep -m1 gateway)
API_A=$(remote "docker ps --format '{{.Names}}' | grep -m1 ticket-center-api")
ORDER_A=$(remote "docker ps --format '{{.Names}}' | grep -m1 order-service")
GW_A=$(remote "docker ps --format '{{.Names}}' | grep -m1 gateway")

echo
echo "== 2. 容器级跨主机可达性（Gateway 容器直连对端 API，期望 200）"
printf 'A gateway -> B api %s:%s : ' "$LOCAL_IP" "$API_B_PORT"
remote "docker exec $GW_A curl -s -m 5 -o /dev/null -w '%{http_code}' http://$LOCAL_IP:$API_B_PORT/event/hot"; echo
printf 'B gateway -> A api %s:%s : ' "$SERVER_IP" "$API_A_PORT"
docker exec "$GW_B" curl -s -m 5 -o /dev/null -w '%{http_code}' "http://$SERVER_IP:$API_A_PORT/event/hot"; echo

# 管理端口不对外映射，计数从容器内读 actuator。
count_local()  { docker exec "$1" curl -s "http://localhost:$2/actuator/metrics/http.server.requests?tag=$3" | grep -oE '"COUNT","value":[0-9.]+' | grep -oE '[0-9]+' | head -1; }
count_remote() { remote "docker exec $1 curl -s 'http://localhost:$2/actuator/metrics/http.server.requests?tag=$3'" | grep -oE '"COUNT","value":[0-9.]+' | grep -oE '[0-9]+' | head -1; }

fire() {
  local codes=""
  for _ in $(seq 1 "$N"); do codes+="$(curl -s -m 8 -o /dev/null -w '%{http_code}' "$1$2") "; done
  echo "$codes"
}

# $1 标签 $2 gateway 地址 $3 路径 $4 本机容器 $5 服务器容器 $6 管理端口 $7 metric tag
distribute() {
  local la ra lb rb
  la=$(count_local "$4" "$6" "$7"); ra=$(count_remote "$5" "$6" "$7")
  echo "-- $N 次 $3 打到 $1"
  echo "   状态码: $(fire "$2" "$3")"
  lb=$(count_local "$4" "$6" "$7"); rb=$(count_remote "$5" "$6" "$7")
  echo "   节点 A($SERVER_IP) 处理 $(( ${rb:-0} - ${ra:-0} )) 次，节点 B($LOCAL_IP) 处理 $(( ${lb:-0} - ${la:-0} )) 次"
}

echo
echo "== 3. Gateway 负载均衡分发（期望 A、B 各约一半）"
distribute "Gateway A" "http://$SERVER_IP:$GW_A_PORT" "/event/hot"     "$API_B"   "$API_A"   9082 "uri:/event/hot"
distribute "Gateway B" "http://127.0.0.1:$GW_B_PORT"  "/event/hot"     "$API_B"   "$API_A"   9082 "uri:/event/hot"
distribute "Gateway A" "http://$SERVER_IP:$GW_A_PORT" "/ticket/list/1" "$ORDER_B" "$ORDER_A" 9083 "status:401"
distribute "Gateway B" "http://127.0.0.1:$GW_B_PORT"  "/ticket/list/1" "$ORDER_B" "$ORDER_A" 9083 "status:401"
