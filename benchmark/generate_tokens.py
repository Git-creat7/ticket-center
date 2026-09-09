import csv
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path


BENCHMARK_DIR = Path(__file__).resolve().parent
BASE_URL = f"http://{os.environ.get('BACKEND_HOST', '127.0.0.1')}:{os.environ.get('BACKEND_HOST_PORT', '8080')}"
REDIS_CONTAINER = os.environ.get("REDIS_CONTAINER", "ticket-redis")
CSV_FILE = Path(os.environ.get("TOKEN_FILE", str(BENCHMARK_DIR / "jmeter_tokens.csv")))


def api(path, data=None):
    request = urllib.request.Request(
        BASE_URL + path,
        data=json.dumps(data).encode("utf-8") if data is not None else None,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        result = json.load(response)
    if result.get("code") != 200:
        raise RuntimeError(result.get("msg", "接口调用失败"))
    return result.get("data")


def login(phone):
    api(f"/user/code?phone={phone}")
    result = subprocess.run(
        ["docker", "exec", REDIS_CONTAINER, "sh", "-c",
         'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw GET "$1"',
         "sh", f"tc:login:code:{phone}"],
        check=True, capture_output=True, text=True, timeout=10,
    )
    code = result.stdout.strip()
    if len(code) != 6 or not code.isdigit():
        raise RuntimeError(f"未读取到 {phone} 的验证码")
    token = api("/user/login", {"phone": phone, "code": code})
    if not isinstance(token, str) or not token:
        raise RuntimeError(f"{phone} 登录未返回 Token")
    return token, phone


def main():
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 100
    if count < 1 or count > 100:
        sys.exit("压测用户数需在 1 到 100 之间")

    tokens = [login(f"138{i:08d}") for i in range(1, count + 1)]
    CSV_FILE.parent.mkdir(parents=True, exist_ok=True)
    # 全部登录成功后再写入，避免使用不完整的用户集压测。
    with CSV_FILE.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(("token", "phone"))
        writer.writerows(tokens)
    CSV_FILE.chmod(0o600)
    print(f"已生成 {len(tokens)} 个压测 Token")


if __name__ == "__main__":
    main()
