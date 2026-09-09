#!/bin/sh
set -eu
server="${NACOS_SERVER_ADDR:-http://nacos:8848}"
group="${NACOS_GROUP:-TICKET_CENTER}"
namespace="${NACOS_NAMESPACE:-}"

# 循环等待Nacos服务就绪
until curl -fsS "$server/nacos/v1/console/health/readiness" >/dev/null; do
    sleep 2
done

for file in /configs/*.yaml; do
    [ -f "$file" ] || continue
    data_id="$(basename "$file")"
    response_file="$(mktemp)"
    status="$(curl -sS -o "$response_file" -w '%{http_code}' --get \
        "$server/nacos/v1/cs/configs" \
        --data-urlencode "dataId=$data_id" \
        --data-urlencode "group=$group" \
        --data-urlencode "tenant=$namespace")"
    case "$status" in
        200)
            echo "Nacos配置已存在：$data_id"
            ;;
        404)
            curl -fsS -X POST "$server/nacos/v1/cs/configs" \
                --data-urlencode "dataId=$data_id" \
                --data-urlencode "group=$group" \
                --data-urlencode "content@$file" \
                --data-urlencode "type=yaml" \
                --data-urlencode "tenant=$namespace" >/dev/null
            echo "成功导入Nacos配置：$data_id"
            ;;
        *)
            cat "$response_file" >&2
            rm -f "$response_file"
            echo "读取Nacos配置失败：$data_id（HTTP状态码：$status）" >&2
            exit 1
            ;;
    esac
    rm -f "$response_file"
done
echo "所有Nacos配置处理完成，服务配置就绪"
