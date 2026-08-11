from __future__ import annotations

import logging
from typing import Any, Optional

import httpx

from .models import MessageOut
from .store import store

logger = logging.getLogger(__name__)


def to_wecom_callback_payload(message: MessageOut) -> dict[str, Any]:
    """转换为接近企业微信回调的 JSON 结构，便于本地联调。"""
    base = {
        "ToUserName": "ww_simulator",
        "FromUserName": message.from_user,
        "CreateTime": message.create_time,
        "MsgId": message.msgid,
        "AgentID": message.agent_id,
        "MsgType": message.msgtype.value,
    }
    if message.msgtype.value == "text":
        base["Content"] = message.content or ""
    elif message.msgtype.value == "voice":
        base["MediaId"] = message.media_id or ""
        base["Format"] = message.raw.get("Format", "webm")
        base["Recognition"] = message.recognition or ""
        base["VoiceUrl"] = message.voice_url or ""
        if message.voice_duration_ms is not None:
            base["VoiceDurationMs"] = message.voice_duration_ms
    return base


async def dispatch_inbound_webhook(message: MessageOut) -> Optional[dict[str, Any]]:
    if not store.webhook_enabled or not store.webhook_url:
        return None

    payload = to_wecom_callback_payload(message)
    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.post(store.webhook_url, json=payload)
            body_text = resp.text[:2000]
            result = {
                "status_code": resp.status_code,
                "body": body_text,
                "ok": 200 <= resp.status_code < 300,
            }
            logger.info(
                "webhook dispatched msgid=%s status=%s",
                message.msgid,
                resp.status_code,
            )
            return result
    except Exception as exc:  # noqa: BLE001 - surface to UI/logs
        logger.exception("webhook failed msgid=%s", message.msgid)
        return {"ok": False, "error": str(exc)}
