#!/usr/bin/env bash
set -euo pipefail

benchmark_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
jmeter_exec="${JMETER_EXEC:-jmeter}"
mysql_container="${MYSQL_CONTAINER:-ticket-mysql}"
event_id="${BENCHMARK_EVENT_ID:-1}"
export BACKEND_HOST="${BACKEND_HOST:-127.0.0.1}"
export BACKEND_HOST_PORT="${BACKEND_HOST_PORT:-8080}"
export REDIS_CONTAINER="${REDIS_CONTAINER:-ticket-redis}"

case "${1:-full}" in
    full)
        users=100
        stock=50
        read_threads=200
        reserve_threads=100
        sign_loops=50
        event_loops=20
        reserve_loops=10
        ;;
    smoke)
        users=10
        stock=5
        read_threads=10
        reserve_threads=10
        sign_loops=2
        event_loops=2
        reserve_loops=2
        ;;
    *) echo "用法: bash benchmark/run_all_benchmarks.sh [full|smoke]" >&2; exit 1 ;;
esac

[[ "$event_id" =~ ^[1-9][0-9]*$ ]] || { echo "BENCHMARK_EVENT_ID 必须是正整数" >&2; exit 1; }
for dependency in docker python3 "$jmeter_exec"; do
    command -v "$dependency" >/dev/null || { echo "缺少依赖: $dependency" >&2; exit 1; }
done

mysql_query() {
    docker exec "$mysql_container" sh -c \
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -N -B ticket_order -e "$1"' sh "$1"
}

redis_query() {
    docker exec "$REDIS_CONTAINER" sh -c \
        'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw "$@"' sh "$@"
}

[[ "$(mysql_query 'SELECT 1')" == 1 ]]
[[ "$(redis_query PING)" == PONG ]]

# 每轮单独保存结果，不覆盖历史报告。
run_id="$(date +%Y%m%d-%H%M%S)-$$"
results_dir="$benchmark_dir/results/$run_id"
reports_dir="$benchmark_dir/reports/$run_id"
mkdir -p "$results_dir" "$reports_dir"
export TOKEN_FILE="$results_dir/tokens.csv"
echo "压测地址: http://$BACKEND_HOST:$BACKEND_HOST_PORT"
echo "本轮结果: $results_dir"
python3 "$benchmark_dir/generate_tokens.py" "$users"

run_case() {
    local name="$1" plan="$2" threads="$3" loops="$4"
    "$jmeter_exec" -n -t "$benchmark_dir/$plan" \
        "-Jbackend_host=$BACKEND_HOST" "-Jbackend_port=$BACKEND_HOST_PORT" \
        "-Jtoken_file=$TOKEN_FILE" "-Jevent_id=$event_id" "-Jticket_id=${ticket_id:-0}" \
        "-Jthreads=$threads" "-Jloops=$loops" -Jsample_variables=reservation_id \
        -j "$results_dir/$name.log" -l "$results_dir/$name.jtl" \
        -e -o "$reports_dir/$name"

    # JMeter 出现断言失败时也可能退出 0，需要检查样本结果。
    python3 - "$results_dir/$name.jtl" "$((threads * loops))" <<'PY'
import csv
import sys

with open(sys.argv[1], newline="", encoding="utf-8") as source:
    rows = list(csv.DictReader(source))
errors = sum(row["success"] != "true" for row in rows)
print(f"样本: {len(rows)}，异常: {errors}")
if len(rows) != int(sys.argv[2]) or errors:
    sys.exit("压测失败，样本数量或业务断言不符合预期")
PY
}

run_case sign_status 1_sign_status_qps.jmx "$read_threads" "$sign_loops"
run_case event_detail 2_event_detail_qps.jmx "$read_threads" "$event_loops"

# 使用新票档，不清空演示订单，也不直接重置 Redis 库存。
ticket_id="$(mysql_query "START TRANSACTION;
INSERT INTO tb_ticket (event_id, event_name, title, price, type, status)
VALUES ($event_id, 'benchmark', 'benchmark-$run_id', 10000, 1, 1);
SET @ticket_id = LAST_INSERT_ID();
INSERT INTO tb_ticket_stock (ticket_id, stock, begin_time, end_time)
VALUES (@ticket_id, $stock, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY));
COMMIT;
SELECT @ticket_id;")"
echo "压测票档: $ticket_id，初始库存: $stock"
run_case seckill_reserve 3_seckill_reserve_qps.jmx "$reserve_threads" "$reserve_loops"

deadline=$((SECONDS + 120))
pending=1
while (( SECONDS < deadline )); do
    pending="$(mysql_query "SELECT
        (SELECT COUNT(*) FROM tb_ticket_reservation WHERE ticket_id = $ticket_id AND (status = 0 OR release_pending = 1)) +
        (SELECT COUNT(*) FROM tb_reservation_task t JOIN tb_ticket_reservation r ON r.id = t.reservation_id
         WHERE r.ticket_id = $ticket_id AND t.status = 0);")"
    [[ "$pending" == 0 ]] && break
    sleep 1
done
[[ "$pending" == 0 ]] || { echo "预约或补偿任务在 120 秒内未处理完成" >&2; exit 1; }

response_ids="$(python3 - "$results_dir/seckill_reserve.jtl" <<'PY'
import csv
import sys

with open(sys.argv[1], newline="", encoding="utf-8") as source:
    ids = [row["reservation_id"] for row in csv.DictReader(source) if row["reservation_id"]]
if not ids or len(ids) != len(set(ids)) or not all(value.isdigit() for value in ids):
    sys.exit("预约返回值为空、重复或格式不正确")
print("\n".join(sorted(ids, key=int)))
PY
)"
db_ids="$(mysql_query "SELECT id FROM tb_ticket_reservation WHERE ticket_id = $ticket_id ORDER BY id;")"
[[ "$response_ids" == "$db_ids" ]] || { echo "已受理预约号与数据库记录不一致" >&2; exit 1; }

read -r db_stock orders unique_users succeeded invalid <<< "$(mysql_query "SELECT
    (SELECT stock FROM tb_ticket_stock WHERE ticket_id = $ticket_id),
    (SELECT COUNT(*) FROM tb_ticket_order WHERE ticket_id = $ticket_id AND status IN (0, 1)),
    (SELECT COUNT(DISTINCT user_id) FROM tb_ticket_order WHERE ticket_id = $ticket_id AND status IN (0, 1)),
    (SELECT COUNT(*) FROM tb_ticket_reservation WHERE ticket_id = $ticket_id AND status = 1),
    (SELECT COUNT(*) FROM tb_ticket_reservation r LEFT JOIN tb_ticket_order o ON o.id = r.order_id
     WHERE r.ticket_id = $ticket_id AND (
         r.status NOT IN (1, 2) OR
         (r.status = 1 AND (o.id IS NULL OR o.user_id <> r.user_id OR o.ticket_id <> r.ticket_id OR o.status NOT IN (0, 1))) OR
         (r.status = 2 AND (o.id IS NOT NULL OR r.failure_reason IS NULL OR r.failure_reason NOT IN ('库存不足', '每个用户限购一张')))));")"
redis_stock="$(redis_query GET "tc:ticket:{$ticket_id}:stock")"
redis_users="$(redis_query SCARD "tc:ticket:{$ticket_id}:order")"
redis_reservations="$(redis_query HLEN "tc:ticket:{$ticket_id}:reservation")"

echo "MySQL / Redis 剩余库存: $db_stock / $redis_stock"
echo "订单 / 用户 / 成功预约: $orders / $unique_users / $succeeded"
echo "Redis 资格 / 预约记录: $redis_users / $redis_reservations"
if [[ "$db_stock" != 0 || "$redis_stock" != 0 || "$orders" != "$stock" ||
      "$unique_users" != "$stock" || "$succeeded" != "$stock" || "$invalid" != 0 ||
      "$redis_users" != "$stock" || "$redis_reservations" != "$stock" ]]; then
    echo "库存或预约结果不一致，压测失败" >&2
    exit 1
fi

echo "核对通过。压测票档和订单已保留，待支付订单仍会按业务规则超时取消。"
echo "HTML 报告: $reports_dir"
