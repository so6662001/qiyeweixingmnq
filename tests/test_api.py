from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from backend.app.main import app
from backend.app.store import store


@pytest.fixture(autouse=True)
def _reset_store():
    store.messages.clear()
    store.webhook_url = None
    store.webhook_enabled = False
    store.demo_bot_enabled = False
    yield
    store.messages.clear()


@pytest.fixture()
def client():
    with TestClient(app) as c:
        yield c


def test_health(client):
    resp = client.get("/api/health")
    assert resp.status_code == 200
    assert resp.json()["ok"] is True


def test_send_text_and_list(client):
    resp = client.post(
        "/api/messages/text",
        json={"content": "你好企业微信", "from_user": "u1", "agent_id": "1000001"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["msgtype"] == "text"
    assert data["content"] == "你好企业微信"
    assert data["role"] == "user"

    listed = client.get("/api/messages").json()
    assert len(listed) == 1
    assert listed[0]["msgid"] == data["msgid"]

    callback = client.get(f"/api/messages/{data['msgid']}/callback").json()
    assert callback["MsgType"] == "text"
    assert callback["Content"] == "你好企业微信"


def test_voice_upload_and_media(client, tmp_path):
    audio = b"RIFF....WAVE"  # minimal fake bytes
    resp = client.post(
        "/api/messages/voice",
        data={
            "from_user": "u1",
            "agent_id": "1000001",
            "recognition": "这是语音转写",
            "duration_ms": "1234",
        },
        files={"file": ("hello.wav", audio, "audio/wav")},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["msgtype"] == "voice"
    assert data["recognition"] == "这是语音转写"
    assert data["media_id"]
    assert data["voice_url"].startswith("/api/media/")

    media = client.get(data["voice_url"])
    assert media.status_code == 200
    assert media.content == audio

    callback = client.get(f"/api/messages/{data['msgid']}/callback").json()
    assert callback["MsgType"] == "voice"
    assert callback["MediaId"] == data["media_id"]
    assert callback["Recognition"] == "这是语音转写"


def test_reply_text(client):
    client.post("/api/messages/text", json={"content": "ping", "from_user": "u9"})
    resp = client.post(
        "/api/reply/text",
        json={"content": "pong 来自应用", "to_user": "u9", "agent_id": "1000001"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["role"] == "bot"
    assert data["content"] == "pong 来自应用"
    assert data["to_user"] == "u9"

    messages = client.get("/api/messages").json()
    assert len(messages) == 2
    assert messages[-1]["role"] == "bot"


def test_demo_bot_auto_reply(client):
    store.demo_bot_enabled = True
    client.post("/api/messages/text", json={"content": "自动回复测试"})
    # demo bot runs in background task; give TestClient a moment via session poll
    import time

    for _ in range(20):
        msgs = client.get("/api/messages").json()
        if any(m["role"] == "bot" for m in msgs):
            break
        time.sleep(0.05)
    msgs = client.get("/api/messages").json()
    bots = [m for m in msgs if m["role"] == "bot"]
    assert bots
    assert "自动回复测试" in bots[0]["content"]
