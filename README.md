# 企业微信模拟器（企微模拟器）

本地开发用的企业微信会话模拟器：可发送/获取**文字**与**语音**消息，并通过 API **文字回复**。适合在没有真实企微环境时联调机器人或业务后端。

## 功能

- 聊天界面发送文字消息
- 录音或上传语音，获取 `media_id`、下载地址与识别文本（Recognition）
- `POST /api/reply/text` 模拟应用主动文字回复
- 可选 Webhook：入站消息以企业微信风格 JSON 回调到你的服务
- 内置演示 Bot（回声/语音确认），可开关
- WebSocket 实时刷新会话

## 快速启动

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r backend/requirements.txt
export PYTHONPATH=.
uvicorn backend.app.main:app --host 0.0.0.0 --port 8000 --reload
```

或：

```bash
bash scripts/run.sh
```

打开 http://127.0.0.1:8000

## 核心 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/messages/text` | 用户发送文字 |
| POST | `/api/messages/voice` | 用户上传语音（multipart） |
| GET | `/api/messages` | 拉取消息列表（可按类型/角色过滤） |
| GET | `/api/messages/{msgid}` | 获取单条消息 |
| GET | `/api/messages/{msgid}/callback` | 查看企微风格回调 JSON |
| POST | `/api/reply/text` | 应用文字回复 |
| GET | `/api/media/{media_id}` | 下载语音文件 |
| PUT | `/api/config/webhook` | 配置入站 Webhook |
| PUT | `/api/config/demo-bot` | 开关演示 Bot |
| WS | `/ws` | 实时会话事件 |

### 发送文字

```bash
curl -s http://127.0.0.1:8000/api/messages/text \
  -H 'Content-Type: application/json' \
  -d '{"content":"你好","from_user":"user001","agent_id":"1000001"}'
```

### 上传语音

```bash
curl -s http://127.0.0.1:8000/api/messages/voice \
  -F 'file=@./sample.wav' \
  -F 'from_user=user001' \
  -F 'recognition=会议改到下午三点'
```

### 文字回复

```bash
curl -s http://127.0.0.1:8000/api/reply/text \
  -H 'Content-Type: application/json' \
  -d '{"content":"好的，已记录","to_user":"user001","agent_id":"1000001"}'
```

### 拉取入站消息（给业务侧轮询）

```bash
# 全部
curl -s http://127.0.0.1:8000/api/messages

# 仅语音
curl -s 'http://127.0.0.1:8000/api/messages?msgtype=voice&role=user'
```

## Webhook 回调示例载荷

文字：

```json
{
  "ToUserName": "ww_simulator",
  "FromUserName": "user001",
  "CreateTime": 1710000000,
  "MsgId": "...",
  "AgentID": "1000001",
  "MsgType": "text",
  "Content": "你好"
}
```

语音：

```json
{
  "MsgType": "voice",
  "MediaId": "...",
  "Format": "webm",
  "Recognition": "会议改到下午三点",
  "VoiceUrl": "/api/media/..."
}
```

## 测试

```bash
source .venv/bin/activate
export PYTHONPATH=.
pytest -q
```

## 目录

```
backend/app/     FastAPI 服务
frontend/        聊天模拟器 UI
tests/           API 测试
data/voices/     上传的语音文件
```

## 说明

本项目是**本地联调模拟器**，不对接真实企业微信开放平台，也不做加解密/签名校验。接口字段尽量贴近企微回调与发消息形态，方便你把现有业务代码切到模拟环境验证。
