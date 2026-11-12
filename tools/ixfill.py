#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ixfill —— InputFix 客户端（纯标准库，不依赖 curl）

沙箱环境（proot 等）里没有 curl 时用这个。

服务地址的查找顺序：
  1. 环境变量 IXFILL_BASE
  2. 配置文件 /sdcard/Download/InputFix/ixfill.conf 里每一行
  3. http://127.0.0.1:8791
  4. http://<默认网关>:8791

第 3 条在某些 proot 环境里是"假连通"（TCP 连上但 HTTP 不回），
脚本会在超时后自动往下试，所以写 4 种都无妨。

用法：
  python3 ixfill.py                          看状态
  python3 ixfill.py status
  python3 ixfill.py fields                   列出屏幕上的输入框
  python3 ixfill.py focus                    当前焦点框快照
  python3 ixfill.py tap 0                    点第 0 个输入框
  python3 ixfill.py fill 0 "以人民为中心"      填第 0 个框
  python3 ixfill.py fill 0 "文本" --no-kick
  python3 ixfill.py answer 0="答案A" 1="答案B"
  python3 ixfill.py answer --json /path/answers.json
  python3 ixfill.py probe                    逐个候选地址探测

退出码：0 成功，1 失败
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

PORT = 8791
CONF = "/sdcard/Download/InputFix/ixfill.conf"
PING_TIMEOUT = 1.5
REQ_TIMEOUT = 25

_resolved = None


def default_gateway():
    """从 /proc/net/route 读默认网关（proot 里通常也能读到宿主的路由表）。"""
    try:
        with open("/proc/net/route") as f:
            next(f)
            for line in f:
                parts = line.split()
                if len(parts) < 3:
                    continue
                if parts[1] == "00000000" and parts[2] != "00000000":
                    raw = parts[2]
                    ip = ".".join(str(int(raw[i:i + 2], 16)) for i in (6, 4, 2, 0))
                    return ip
    except Exception:
        pass
    return None


def candidates():
    out = []

    env = os.environ.get("IXFILL_BASE")
    if env:
        out.append(env.strip().rstrip("/"))

    try:
        if os.path.exists(CONF):
            with open(CONF, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#"):
                        out.append(line.rstrip("/"))
    except Exception:
        pass

    out.append("http://127.0.0.1:%d" % PORT)

    gw = default_gateway()
    if gw:
        out.append("http://%s:%d" % (gw, PORT))

    seen, uniq = set(), []
    for c in out:
        if c not in seen:
            seen.add(c)
            uniq.append(c)
    return uniq


def _request(base, path, payload, timeout):
    url = base + path
    if payload is None:
        req = urllib.request.Request(url)
    else:
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def resolve():
    """找出第一个真正回 JSON 的地址。假的会超时，直接跳过。"""
    global _resolved
    if _resolved:
        return _resolved
    tried = []
    for base in candidates():
        try:
            r = _request(base, "/ping", None, PING_TIMEOUT)
            if r.get("ok") and r.get("app") == "inputfix":
                _resolved = base
                return base
            tried.append("%s -> 不是 InputFix" % base)
        except Exception as e:
            tried.append("%s -> %s" % (base, type(e).__name__))
    sys.stderr.write(
        "找不到 InputFix 服务。试过：\n  " + "\n  ".join(tried) +
        "\n\n检查：\n"
        "  1. App 是否在跑（状态栏应有「InputFix 已就绪」通知）\n"
        "  2. %s 里写一行 http://<手机局域网IP>:%d\n"
        "     手机 IP 看 settings 的 WLAN 详情，或运行: ip -4 addr show wlan0\n"
        % (CONF, PORT)
    )
    return None


def call(path, payload=None, timeout=REQ_TIMEOUT):
    base = resolve()
    if not base:
        return {"ok": False, "error": "no server"}
    try:
        return _request(base, path, payload, timeout)
    except urllib.error.HTTPError as e:
        return {"ok": False, "error": "HTTP %s" % e.code}
    except Exception as e:
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}


def out(obj):
    print(json.dumps(obj, ensure_ascii=False, indent=2))
    return 0 if obj.get("ok") else 1


def probe():
    for base in candidates():
        try:
            r = _request(base, "/ping", None, PING_TIMEOUT)
            print("  [OK ] %s  %s" % (base, json.dumps(r, ensure_ascii=False)))
        except Exception as e:
            print("  [ -- ] %s  %s: %s" % (base, type(e).__name__, e))
    return 0


def main():
    args = sys.argv[1:]

    if not args or args[0] in ("status", "-s"):
        return out(call("/status"))

    cmd = args[0]

    if cmd == "probe":
        return probe()

    if cmd == "fields":
        return out(call("/fields"))

    if cmd == "focus":
        return out(call("/focus"))

    if cmd == "tap":
        if len(args) < 2:
            print("用法: ixfill.py tap <index>")
            return 2
        return out(call("/tap", {"index": int(args[1])}))

    if cmd == "fill":
        if len(args) < 3:
            print('用法: ixfill.py fill <index> "文本" [--no-kick] [--no-clear]')
            return 2
        idx = int(args[1])
        text = args[2]
        kick = "--no-kick" not in args
        clear = "--no-clear" not in args
        return out(call("/fill", {
            "index": idx, "text": text, "kick": kick, "clear": clear,
        }, timeout=60))

    if cmd == "answer":
        rest = args[1:]
        if rest and rest[0] == "--json":
            if len(rest) < 2:
                print("用法: ixfill.py answer --json /path/answers.json")
                return 2
            with open(rest[1], "r", encoding="utf-8") as f:
                data = json.load(f)
            answers = data if isinstance(data, list) else data.get("answers", [])
        else:
            answers = []
            for item in rest:
                if "=" not in item:
                    continue
                k, v = item.split("=", 1)
                answers.append({"index": int(k), "text": v})
            if not answers:
                print('用法: ixfill.py answer 0="答案A" 1="答案B"')
                return 2
        return out(call("/answer", {"answers": answers, "kick": True}, timeout=180))

    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())

