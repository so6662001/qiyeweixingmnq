from __future__ import annotations

from enum import Enum
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field


class MessageType(str, Enum):
    TEXT = "text"
    VOICE = "voice"
    EVENT = "event"


class SenderRole(str, Enum):
    USER = "user"
    BOT = "bot"
    SYSTEM = "system"


class TextMessageIn(BaseModel):
    content: str = Field(..., min_length=1, max_length=4000)
    from_user: str = Field(default="user001", max_length=64)
    agent_id: str = Field(default="1000001", max_length=32)


class ReplyTextIn(BaseModel):
    content: str = Field(..., min_length=1, max_length=4000)
    to_user: Optional[str] = Field(default=None, max_length=64)
    agent_id: str = Field(default="1000001", max_length=32)
    reply_to_msgid: Optional[str] = None


class WebhookConfigIn(BaseModel):
    url: str = Field(..., min_length=1, max_length=2048)
    enabled: bool = True


class MessageOut(BaseModel):
    msgid: str
    msgtype: MessageType
    role: SenderRole
    from_user: str
    to_user: Optional[str] = None
    agent_id: str
    content: Optional[str] = None
    media_id: Optional[str] = None
    voice_url: Optional[str] = None
    voice_duration_ms: Optional[int] = None
    recognition: Optional[str] = None
    create_time: int
    raw: dict[str, Any] = Field(default_factory=dict)


class SessionStateOut(BaseModel):
    session_id: str
    messages: list[MessageOut]
    webhook_url: Optional[str] = None
    webhook_enabled: bool = False
    demo_bot_enabled: bool = True


class DemoBotConfigIn(BaseModel):
    enabled: bool


class ApiOk(BaseModel):
    ok: Literal[True] = True
    message: Optional[MessageOut] = None
