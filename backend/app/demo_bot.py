from __future__ import annotations

from .models import MessageOut, MessageType
from .store import store


async def maybe_auto_reply(message: MessageOut) -> MessageOut | None:
    """内置演示 Bot：文字回声 + 语音确认。"""
    if not store.demo_bot_enabled:
        return None
    if message.role.value != "user":
        return None

    if message.msgtype == MessageType.TEXT:
        content = f"【收到文字】{message.content}"
    elif message.msgtype == MessageType.VOICE:
        recog = message.recognition or "（未识别，可下载原语音）"
        content = (
            f"收到语音消息\n"
            f"media_id: {message.media_id}\n"
            f"时长: {message.voice_duration_ms or '?'} ms\n"
            f"识别: {recog}\n"
            f"下载: {message.voice_url}"
        )
    else:
        return None

    return await store.add_bot_reply(
        content=content,
        to_user=message.from_user,
        agent_id=message.agent_id,
        reply_to_msgid=message.msgid,
    )
