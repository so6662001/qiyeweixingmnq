#!/usr/bin/env python3
"""示例：轮询模拟器消息并文字回复。

用法：
  export PYTHONPATH=.
  python examples/echo_bot.py
"""

from __future__ import annotations

import time

import httpx

BASE = "http://127.0.0.1:8000"


def main() -> None:
    last = None
    print("echo_bot 已启动，轮询 /api/messages …")
    with httpx.Client(timeout=10.0) as client:
        # 关闭内置演示 Bot，避免重复回复
        client.put(f"{BASE}/api/config/demo-bot", json={"enabled": False})
        while True:
            params = {"role": "user"}
            if last:
                params["after"] = last
            msgs = client.get(f"{BASE}/api/messages", params=params).json()
            for msg in msgs:
                last = msg["msgid"]
                if msg["msgtype"] == "text":
                    reply = f"echo: {msg['content']}"
                else:
                    reply = f"收到语音 {msg.get('media_id')} / {msg.get('recognition')}"
                client.post(
                    f"{BASE}/api/reply/text",
                    json={
                        "content": reply,
                        "to_user": msg["from_user"],
                        "agent_id": msg["agent_id"],
                        "reply_to_msgid": msg["msgid"],
                    },
                )
                print("replied:", reply)
            time.sleep(1)


if __name__ == "__main__":
    main()
