from __future__ import annotations

import asyncio
import time
import uuid
from pathlib import Path
from typing import Any, Optional

from .models import (
    MessageOut,
    MessageType,
    SenderRole,
    SessionStateOut,
)

DATA_DIR = Path(__file__).resolve().parents[2] / "data"
VOICE_DIR = DATA_DIR / "voices"
VOICE_DIR.mkdir(parents=True, exist_ok=True)


def _now() -> int:
    return int(time.time())


def _msgid() -> str:
    return uuid.uuid4().hex


class MessageStore:
    """内存会话存储 + WebSocket 广播。"""

    def __init__(self) -> None:
        self.session_id = uuid.uuid4().hex[:12]
        self.messages: list[MessageOut] = []
        self.webhook_url: Optional[str] = None
        self.webhook_enabled: bool = False
        self.demo_bot_enabled: bool = True
        self._subscribers: set[asyncio.Queue[dict[str, Any]]] = set()
        self._lock = asyncio.Lock()

    def snapshot(self) -> SessionStateOut:
        return SessionStateOut(
            session_id=self.session_id,
            messages=list(self.messages),
            webhook_url=self.webhook_url,
            webhook_enabled=self.webhook_enabled,
            demo_bot_enabled=self.demo_bot_enabled,
        )

    async def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=100)
        self._subscribers.add(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue[dict[str, Any]]) -> None:
        self._subscribers.discard(queue)

    async def broadcast(self, event: dict[str, Any]) -> None:
        dead: list[asyncio.Queue[dict[str, Any]]] = []
        for queue in self._subscribers:
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                dead.append(queue)
        for queue in dead:
            self._subscribers.discard(queue)

    async def add_text_from_user(
        self,
        *,
        content: str,
        from_user: str,
        agent_id: str,
    ) -> MessageOut:
        msg = MessageOut(
            msgid=_msgid(),
            msgtype=MessageType.TEXT,
            role=SenderRole.USER,
            from_user=from_user,
            agent_id=agent_id,
            content=content,
            create_time=_now(),
            raw={
                "MsgType": "text",
                "Content": content,
                "FromUserName": from_user,
                "AgentID": agent_id,
            },
        )
        async with self._lock:
            self.messages.append(msg)
        await self.broadcast({"type": "message", "message": msg.model_dump()})
        return msg

    async def add_voice_from_user(
        self,
        *,
        media_id: str,
        voice_url: str,
        from_user: str,
        agent_id: str,
        duration_ms: Optional[int] = None,
        recognition: Optional[str] = None,
        filename: Optional[str] = None,
    ) -> MessageOut:
        msg = MessageOut(
            msgid=_msgid(),
            msgtype=MessageType.VOICE,
            role=SenderRole.USER,
            from_user=from_user,
            agent_id=agent_id,
            media_id=media_id,
            voice_url=voice_url,
            voice_duration_ms=duration_ms,
            recognition=recognition,
            content=recognition,
            create_time=_now(),
            raw={
                "MsgType": "voice",
                "MediaId": media_id,
                "Format": Path(filename or "audio.webm").suffix.lstrip(".") or "webm",
                "Recognition": recognition or "",
                "FromUserName": from_user,
                "AgentID": agent_id,
            },
        )
        async with self._lock:
            self.messages.append(msg)
        await self.broadcast({"type": "message", "message": msg.model_dump()})
        return msg

    async def add_bot_reply(
        self,
        *,
        content: str,
        to_user: str,
        agent_id: str,
        reply_to_msgid: Optional[str] = None,
    ) -> MessageOut:
        msg = MessageOut(
            msgid=_msgid(),
            msgtype=MessageType.TEXT,
            role=SenderRole.BOT,
            from_user="bot",
            to_user=to_user,
            agent_id=agent_id,
            content=content,
            create_time=_now(),
            raw={
                "msgtype": "text",
                "touser": to_user,
                "agentid": agent_id,
                "text": {"content": content},
                "reply_to_msgid": reply_to_msgid,
            },
        )
        async with self._lock:
            self.messages.append(msg)
        await self.broadcast({"type": "message", "message": msg.model_dump()})
        return msg

    async def clear(self) -> None:
        async with self._lock:
            self.messages.clear()
            self.session_id = uuid.uuid4().hex[:12]
        await self.broadcast({"type": "cleared", "session_id": self.session_id})

    def get_message(self, msgid: str) -> Optional[MessageOut]:
        for msg in self.messages:
            if msg.msgid == msgid:
                return msg
        return None

    def list_messages(
        self,
        *,
        msgtype: Optional[MessageType] = None,
        role: Optional[SenderRole] = None,
        after_msgid: Optional[str] = None,
    ) -> list[MessageOut]:
        items = list(self.messages)
        if after_msgid:
            idx = next((i for i, m in enumerate(items) if m.msgid == after_msgid), None)
            items = items[idx + 1 :] if idx is not None else items
        if msgtype:
            items = [m for m in items if m.msgtype == msgtype]
        if role:
            items = [m for m in items if m.role == role]
        return items


store = MessageStore()
