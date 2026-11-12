#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ixfile —— InputFix 文件桥客户端

当 proot 的网络和 Android 隔离时（TCP 能握手但 HTTP 无响应），走这条路。
不依赖任何网络、不依赖 curl，只靠 /sdcard 上的两个目录。

  /sdcard/Download/InputFix/inbox/<id>.json    写请求
  /sdcard/Download/InputFix/outbox/<id>.json   读结果
  /sdcard/Download/InputFix/status.json        实时状态（InputFix 每 0.5s 刷新）

用法：
  python3 ixfile.py status
  python3 ixfile.py fields
  python3 ixfile.py focus
  python3 ixfile.py activate      ← 切到 InputFix，等 imeReady
  python3 ixfile.py deactivate    ← 切回原来的输入法
  python3 ixfile.py tap 0
  python3 ixfile.py fill 0 "以人民为中心"
  python3 ixfile.py fill 0 "文本" --no-kick
  python3 ixfile.py clear 0
  python3 ixfile.py raw '{"op":"status"}'

典型流程（日常输入法是搜狗也能跑）：
  activate → tap → fill → deactivate

退出码：0 成功，1 失败
"""
import json
import os
import sys
import time
import uuid

# 目录自动定位：脚本在哪，就用哪里的 inbox/outbox。
# 这样分发包整体移动到别处也不会错位；环境变量 IXFILE_BASE 可强制覆盖。
_HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("IXFILE_BASE") or _HERE
INBOX = os.path.join(BASE, "inbox")
OUTBOX = os.path.join(BASE, "outbox")


def call(req, timeout=20.0):
    try:
        os.makedirs(INBOX, exist_ok=True)
        os.makedirs(OUTBOX, exist_ok=True)
    except Exception as e:
        return {"ok": False, "error": "建不了目录 %s: %s" % (BASE, e)}

    rid = "req_%d_%s" % (int(time.time() * 1000), uuid.uuid4().hex[:6])
    pin = os.path.join(INBOX, rid + ".json")
    pout = os.path.join(OUTBOX, rid + ".json")

    try:
        with open(pin, "w", encoding="utf-8") as f:
            json.dump(req, f, ensure_ascii=False)
    except Exception as e:
        return {"ok": False, "error": "写不了 %s: %s" % (pin, e)}

    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.path.exists(pout):
            try:
                with open(pout, encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                pass          # 还在写，下一轮再读
        time.sleep(0.2)

    # 超时了，把自己的请求撤掉 —— 否则 App 过一会儿被拉起时
    # 会把这条陈年请求翻出来执行，产生莫名其妙的副作用
    try:
        if os.path.exists(pin):
            os.remove(pin)
    except Exception:
        pass

    return {
        "ok": False,
        "error": "等了 %ss 没有结果，已撤回请求。"
                 "多半是 InputFix 进程不在——先用 open 打开 com.dsh.inputfix，"
                 "等 3 秒，确认 status 能通再重试。" % int(timeout),
    }


def status_direct():
    """直接读 status.json，比走 inbox 快，适合探活。"""
    p = os.path.join(BASE, "status.json")
    try:
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        return {"ok": False, "error": "读不到 %s: %s" % (p, e)}


def out(obj):
    print(json.dumps(obj, ensure_ascii=False, indent=2))
    return 0 if obj.get("ok") else 1


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2

    cmd = args[0]

    if cmd == "status":
        # 先看 status.json，没有就走 inbox
        s = status_direct()
        if s.get("ok"):
            return out(s)
        return out(call({"op": "status"}))

    if cmd == "fields":
        return out(call({"op": "fields"}))

    if cmd == "focus":
        return out(call({"op": "focus"}))

    if cmd == "tap":
        if len(args) < 2:
            print("用法: ixfile.py tap <index>")
            return 2
        return out(call({"op": "tap", "index": int(args[1])}))

    if cmd == "prepare":
        # 告诉 InputFix「我要填了」：已经是输入法就直接 ready，
        # 不是就弹一条通知，引导用户切过来
        reason = "要往输入框里写字"
        for i, a in enumerate(args):
            if a == "--reason" and i + 1 < len(args):
                reason = args[i + 1]
        return out(call({"op": "prepare", "reason": reason}, timeout=15.0))

    if cmd == "activate":
        # 尝试自动切到 InputFix（Android 16 上通常做不到，会失败）
        return out(call({"op": "activate", "waitMs": 8000}, timeout=15.0))

    if cmd == "deactivate":
        # 切回原来的输入法（登录时记得的那一个）
        return out(call({"op": "deactivate"}, timeout=10.0))

    if cmd == "screenshot":
        name = "screen"
        for i, a in enumerate(args):
            if a == "--name" and i + 1 < len(args):
                name = args[i + 1]
        r = call({"op": "screenshot", "name": name}, timeout=12.0)
        return out(r)

    if cmd == "tapxy":
        if len(args) < 3:
            print("用法: ixfile.py tapxy <x> <y> [--hold 毫秒]")
            return 2
        hold = 60
        for i, a in enumerate(args):
            if a == "--hold" and i + 1 < len(args):
                hold = int(args[i + 1])
        return out(call({"op": "tapxy", "x": int(args[1]), "y": int(args[2]),
                         "holdMs": hold}, timeout=10.0))

    if cmd == "taptext":
        if len(args) < 2:
            print("用法: ixfile.py taptext <文字>")
            return 2
        return out(call({"op": "taptext", "text": args[1]}, timeout=10.0))

    if cmd == "clear":
        idx = int(args[1]) if len(args) > 1 else 0
        return out(call({"op": "clear", "index": idx}))

    if cmd == "fill":
        if len(args) < 3:
            print('用法: ixfile.py fill <index> "文本" [--no-kick] [--no-clear]')
            return 2
        idx = int(args[1])
        text = args[2]
        return out(call({
            "op": "fill",
            "index": idx,
            "text": text,
            "kick": "--no-kick" not in args,
            "clear": "--no-clear" not in args,
        }, timeout=60.0))

    if cmd == "raw":
        if len(args) < 2:
            print('用法: ixfile.py raw \'{"op":"status"}\'')
            return 2
        return out(call(json.loads(args[1])))

    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
