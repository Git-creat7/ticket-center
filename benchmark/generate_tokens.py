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

print(f">>> 开始生成 {USER_COUNT} 个压测用 Token...")

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
    cmd = ["docker", "exec", "ticket-redis", "redis-cli", "-a", REDIS_PASSWORD, "get", f"tc:login:code:{phone}"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    code = res.stdout.strip()
    if not code:
        code = "123456"
        
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

# 写入 CSV (token,phone)
with open(CSV_FILE, "w", encoding="utf-8") as f:
    f.write("token,phone\n")
    for token, phone in tokens:
        f.write(f"{token},{phone}\n")

print(f"完成：已写入 {len(tokens)} 个有效 Token 到 {CSV_FILE}")
