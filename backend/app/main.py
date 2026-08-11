from __future__ import annotations

import asyncio
import logging
import mimetypes
import uuid
from pathlib import Path
from typing import Optional

from fastapi import (
    FastAPI,
    File,
    Form,
    HTTPException,
    Query,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .demo_bot import maybe_auto_reply
from .models import (
    ApiOk,
    DemoBotConfigIn,
    MessageOut,
    MessageType,
    ReplyTextIn,
    SenderRole,
    SessionStateOut,
    TextMessageIn,
    WebhookConfigIn,
)
from .store import VOICE_DIR, store
from .webhook import dispatch_inbound_webhook, to_wecom_callback_payload

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("wecom-simulator")

FRONTEND_DIR = Path(__file__).resolve().parents[2] / "frontend"

app = FastAPI(
    title="企业微信模拟器",
    description="本地模拟企业微信：接收文字/语音，并支持文字回复。",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


async def _after_user_message(message: MessageOut) -> None:
    webhook_result = await dispatch_inbound_webhook(message)
    if webhook_result is not None:
        await store.broadcast(
            {"type": "webhook_result", "msgid": message.msgid, "result": webhook_result}
        )
    await maybe_auto_reply(message)


@app.get("/api/health")
async def health() -> dict:
    return {"ok": True, "service": "wecom-simulator"}


@app.get("/api/session", response_model=SessionStateOut)
async def get_session() -> SessionStateOut:
    return store.snapshot()


@app.delete("/api/session", response_model=ApiOk)
async def clear_session() -> ApiOk:
    await store.clear()
    return ApiOk()


@app.post("/api/messages/text", response_model=MessageOut)
async def send_text(body: TextMessageIn) -> MessageOut:
    message = await store.add_text_from_user(
        content=body.content.strip(),
        from_user=body.from_user,
        agent_id=body.agent_id,
    )
    asyncio.create_task(_after_user_message(message))
    return message


@app.post("/api/messages/voice", response_model=MessageOut)
async def send_voice(
    file: UploadFile = File(...),
    from_user: str = Form(default="user001"),
    agent_id: str = Form(default="1000001"),
    recognition: Optional[str] = Form(default=None),
    duration_ms: Optional[int] = Form(default=None),
) -> MessageOut:
    if not file.filename:
        raise HTTPException(status_code=400, detail="缺少语音文件名")

    media_id = uuid.uuid4().hex
    suffix = Path(file.filename).suffix or ".webm"
    dest = VOICE_DIR / f"{media_id}{suffix}"
    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail="语音文件为空")
    dest.write_bytes(content)

    # 未传识别文本时，用文件名/占位说明，便于联调「获取语音」
    recog = (recognition or "").strip() or f"[语音文件] {file.filename}"

    message = await store.add_voice_from_user(
        media_id=media_id,
        voice_url=f"/api/media/{media_id}",
        from_user=from_user,
        agent_id=agent_id,
        duration_ms=duration_ms,
        recognition=recog,
        filename=file.filename,
    )
    asyncio.create_task(_after_user_message(message))
    return message


@app.get("/api/messages", response_model=list[MessageOut])
async def list_messages(
    msgtype: Optional[MessageType] = Query(default=None),
    role: Optional[SenderRole] = Query(default=None),
    after: Optional[str] = Query(default=None, description="仅返回该 msgid 之后的消息"),
) -> list[MessageOut]:
    return store.list_messages(msgtype=msgtype, role=role, after_msgid=after)


@app.get("/api/messages/{msgid}", response_model=MessageOut)
async def get_message(msgid: str) -> MessageOut:
    msg = store.get_message(msgid)
    if not msg:
        raise HTTPException(status_code=404, detail="消息不存在")
    return msg


@app.get("/api/messages/{msgid}/callback")
async def get_callback_payload(msgid: str) -> dict:
    """查看某条入站消息对应的企业微信风格回调 JSON。"""
    msg = store.get_message(msgid)
    if not msg:
        raise HTTPException(status_code=404, detail="消息不存在")
    if msg.role != SenderRole.USER:
        raise HTTPException(status_code=400, detail="仅用户入站消息可导出回调载荷")
    return to_wecom_callback_payload(msg)


@app.post("/api/reply/text", response_model=MessageOut)
async def reply_text(body: ReplyTextIn) -> MessageOut:
    """模拟企业微信应用发消息（文字回复）。"""
    to_user = body.to_user
    if not to_user:
        # 默认回复最近一位用户
        users = [m.from_user for m in store.messages if m.role == SenderRole.USER]
        to_user = users[-1] if users else "user001"

    return await store.add_bot_reply(
        content=body.content.strip(),
        to_user=to_user,
        agent_id=body.agent_id,
        reply_to_msgid=body.reply_to_msgid,
    )


@app.put("/api/config/webhook", response_model=SessionStateOut)
async def set_webhook(body: WebhookConfigIn) -> SessionStateOut:
    store.webhook_url = body.url.strip()
    store.webhook_enabled = body.enabled
    return store.snapshot()


@app.put("/api/config/demo-bot", response_model=SessionStateOut)
async def set_demo_bot(body: DemoBotConfigIn) -> SessionStateOut:
    store.demo_bot_enabled = body.enabled
    return store.snapshot()


@app.get("/api/media/{media_id}")
async def get_media(media_id: str) -> FileResponse:
    matches = list(VOICE_DIR.glob(f"{media_id}.*"))
    if not matches:
        raise HTTPException(status_code=404, detail="媒体不存在")
    path = matches[0]
    mime, _ = mimetypes.guess_type(str(path))
    return FileResponse(path, media_type=mime or "application/octet-stream", filename=path.name)


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket) -> None:
    await ws.accept()
    queue = await store.subscribe()
    try:
        await ws.send_json({"type": "snapshot", "session": store.snapshot().model_dump()})
        while True:
            recv_task = asyncio.create_task(ws.receive_text())
            queue_task = asyncio.create_task(queue.get())
            done, pending = await asyncio.wait(
                {recv_task, queue_task},
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            if queue_task in done:
                event = queue_task.result()
                await ws.send_json(event)
            if recv_task in done:
                # 客户端心跳/忽略内容
                _ = recv_task.result()
    except WebSocketDisconnect:
        pass
    except Exception:  # noqa: BLE001
        logger.exception("websocket error")
    finally:
        store.unsubscribe(queue)


if FRONTEND_DIR.exists():
    app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="frontend")
