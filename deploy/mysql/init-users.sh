#!/usr/bin/env bash
set -eu
: "${MYSQL_ROOT_PASSWORD:?必须配置环境变量 MYSQL_ROOT_PASSWORD}"
: "${DB_PASSWORD:?必须配置环境变量 DB_PASSWORD}"
: "${DB_USERNAME:?必须配置环境变量 DB_USERNAME}"
: "${ORDER_DB_USERNAME:?必须配置环境变量 ORDER_DB_USERNAME}"

# 校验数据库用户名合法性，仅允许字母、数字、下划线、点、横杠
valid_user() {
    case "$1" in
        ''|*[!A-Za-z0-9_.-]*) return 1 ;;
    esac
}

valid_user "$DB_USERNAME" || { echo "DB_USERNAME 包含不支持的特殊字符" >&2; exit 1; }
valid_user "$ORDER_DB_USERNAME" || { echo "ORDER_DB_USERNAME 包含不支持的特殊字符" >&2; exit 1; }

# SQL单引号转义函数，防止注入
escape_sql() {
    printf '%s' "$1" | sed "s/'/''/g"
}

core_user="$(escape_sql "$DB_USERNAME")"
order_user="$(escape_sql "$ORDER_DB_USERNAME")"
app_password="$(escape_sql "$DB_PASSWORD")"

# 连接MySQL执行建用户、授权语句
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
    --protocol=TCP \
    --host="${MYSQL_HOST:-mysql}" \
    --port="${MYSQL_PORT:-3306}" \
    --user=root \
    --batch \
    --skip-column-names <<SQL
CREATE USER IF NOT EXISTS '$core_user'@'%' IDENTIFIED BY '$app_password';
ALTER USER '$core_user'@'%' IDENTIFIED BY '$app_password';
GRANT ALL PRIVILEGES ON ticket_center.* TO '$core_user'@'%';
CREATE USER IF NOT EXISTS '$order_user'@'%' IDENTIFIED BY '$app_password';
ALTER USER '$order_user'@'%' IDENTIFIED BY '$app_password';
GRANT ALL PRIVILEGES ON ticket_order.* TO '$order_user'@'%';
FLUSH PRIVILEGES;
SQL

echo "MySQL业务账号初始化完成"
