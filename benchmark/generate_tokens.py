import urllib.request
import json
import subprocess
import os
import sys
from pathlib import Path

BASE_URL = "http://localhost:8080"
BENCHMARK_DIR = Path(__file__).resolve().parent
CSV_FILE = str(BENCHMARK_DIR / "jmeter_tokens.csv")
USER_COUNT = 100


def load_env():
    """从仓库根目录的 .env 读取凭据，避免把密码写进脚本。"""
    env_file = BENCHMARK_DIR.parent / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


load_env()
REDIS_PASSWORD = os.environ.get("TICKET_REDIS_PASSWORD")
if not REDIS_PASSWORD:
    sys.exit("缺少 TICKET_REDIS_PASSWORD，请先在仓库根目录准备 .env（参考 .env.example）")

def find_redis_master():
    """主节点会随故障转移漂移，容器名不能写死：从降级后的从节点读不到刚写入的验证码。

    以 sentinel 的答案为准而不是自己扫 role:master —— 后端也走 sentinel 发现，
    脑裂时集群里可能有多个自称 master 的节点，扫描会挑中后端没在用的那个。
    """
    addr = subprocess.run(
        ["docker", "exec", "ticket-redis-sentinel-1", "redis-cli", "-p", "26379",
         "-a", REDIS_PASSWORD, "--no-auth-warning",
         "sentinel", "get-master-addr-by-name", "mymaster"],
        capture_output=True, text=True).stdout.split()
    if not addr:
        return None
    master_ip = addr[0]

    for name in ("ticket-redis", "ticket-redis-replica-1", "ticket-redis-replica-2"):
        ip = subprocess.run(
            ["docker", "inspect", "-f",
             "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name],
            capture_output=True, text=True).stdout.strip()
        if ip == master_ip:
            return name
    return None


REDIS_MASTER = find_redis_master()
if not REDIS_MASTER:
    sys.exit("找不到 Redis 主节点，集群可能处于降级状态，请先修复再生成 Token")

print(f">>> 开始生成 {USER_COUNT} 个压测用 Token（Redis 主节点：{REDIS_MASTER}）...")

tokens = []

for i in range(1, USER_COUNT + 1):
    phone = f"138{i:08d}"
    
    # 1. 发送验证码
    req = urllib.request.Request(f"{BASE_URL}/user/code?phone={phone}", method="POST")
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            pass
    except Exception as e:
        print(f"发送验证码失败 {phone}: {e}")
        continue
    
    # 2. 从 Redis 提取验证码
    cmd = ["docker", "exec", REDIS_MASTER, "redis-cli", "-a", REDIS_PASSWORD,
           "--no-auth-warning", "get", f"tc:login:code:{phone}"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    code = res.stdout.strip()
    # 读不到就跳过，不要回落到硬编码验证码：那样登录必然失败，
    # 而失败是静默的（返回 200 + data:null），最后会写出一个只有表头的 CSV
    if not code:
        print(f"Redis 中没有 {phone} 的验证码，跳过")
        continue


    # 3. 登录
    login_data = json.dumps({"phone": phone, "code": code}).encode("utf-8")
    login_req = urllib.request.Request(
        f"{BASE_URL}/user/login",
        data=login_data,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(login_req, timeout=3) as resp:
            resp_data = json.loads(resp.read().decode("utf-8"))
            token = resp_data.get("data")
            if token:
                tokens.append((token, phone))
                if i % 25 == 0:
                    print(f"已生成 {i} / {USER_COUNT} 个 Token...")
    except Exception as e:
        print(f"登录失败 {phone}: {e}")

# 一个都没拿到就不要覆盖 CSV：写出只有表头的文件会让后续压测全程 401，
# 而 JMeter 不会因此报错，只会给出一堆无意义的样本
if not tokens:
    sys.exit("没有生成任何有效 Token，保留原 CSV 不覆盖。请检查后端与 Redis 状态")

# 写入 CSV (token,phone)
with open(CSV_FILE, "w", encoding="utf-8") as f:
    f.write("token,phone\n")
    for token, phone in tokens:
        f.write(f"{token},{phone}\n")

print(f"完成：已写入 {len(tokens)} 个有效 Token 到 {CSV_FILE}")
