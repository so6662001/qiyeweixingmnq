# 企业微信模拟器（Java）

基于 **Spring Boot 3 / Java 21** 的本地企业微信会话模拟器：可发送/获取**文字**与**语音**消息，并通过 API **文字回复**；支持**朋友圈**发帖（图片+方案）、读取点赞/评论，并将动态同步到 **CRM 线索接口**。

## 功能

- 聊天界面发送文字消息
- 录音或上传语音，获取 `media_id`、下载地址与识别文本（Recognition）
- `POST /api/reply/text` 模拟应用主动文字回复
- 可选 Webhook：入站消息以企业微信风格 JSON 回调到你的服务
- 内置演示 Bot（回声/语音确认），可开关
- WebSocket 实时刷新会话
- **朋友圈**：上传图片 + 文案/方案自动发帖；模拟点赞/评论；读取动态
- **CRM 线索同步**：将点赞/评论 POST 到指定线索 API（支持自动/手动同步）

## 环境

- JDK 21+
- Maven 3.8+

## 快速启动

```bash
mvn spring-boot:run
```

或：

```bash
bash scripts/run.sh
```

默认仅监听 `127.0.0.1:8000`。打开 http://127.0.0.1:8000

高保真 HTML 效果图（静态预览）：

- 文件：`docs/mockups/wecom-simulator-hifi.html`（可直接用浏览器打开）
- 服务内访问：http://127.0.0.1:8000/mockups/wecom-simulator-hifi.html

打包运行：

```bash
mvn -q -DskipTests package
java -jar target/wecom-simulator-1.0.0.jar
```

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
| POST | `/api/moments` | 发朋友圈（multipart：image/content/plan） |
| GET | `/api/moments` | 朋友圈列表 |
| POST | `/api/moments/{id}/likes` | 模拟点赞 |
| POST | `/api/moments/{id}/comments` | 模拟评论 |
| GET | `/api/moments/interactions` | 读取点赞/评论动态 |
| PUT | `/api/moments/crm/config` | 配置 CRM 线索 API |
| POST | `/api/moments/crm/sync` | 将未同步动态写入 CRM |
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

### 拉取入站消息（Java 业务侧轮询）

```bash
curl -s http://127.0.0.1:8000/api/messages
curl -s 'http://127.0.0.1:8000/api/messages?msgtype=voice&role=user'
```

Java 示例轮询 Bot：

```bash
javac examples/EchoBot.java && java -cp examples EchoBot
```

## 朋友圈 → CRM 线索

1. 启动本地 CRM 接收器：

```bash
javac examples/CrmLeadReceiver.java && java -cp examples CrmLeadReceiver
```

2. 在模拟器左侧配置 CRM URL：`http://127.0.0.1:9100/crm/leads`，开启「启用 CRM 同步」。

3. 切换到「朋友圈」页：上传图片、填写文案/方案并发布；再模拟点赞/评论。

4. 动态会自动（或点「立即同步」）POST 到 CRM，载荷示例：

```json
{
  "source": "wecom_moment",
  "lead_type": "moment_comment",
  "interaction_type": "comment",
  "moment_id": "...",
  "user_id": "lead002",
  "user_name": "潜在客户乙",
  "content": "怎么报名？",
  "moment_content": "春季活动上线啦",
  "moment_plan": "获客方案A",
  "image_url": "/api/moments/media/...",
  "create_time": 1710000000
}
```

也可 curl：

```bash
curl -s http://127.0.0.1:8000/api/moments \
  -F 'image=@./promo.png' \
  -F 'content=春季活动上线啦' \
  -F 'plan=获客方案A'

curl -s http://127.0.0.1:8000/api/moments/<momentId>/comments \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"lead002","user_name":"潜在客户乙","content":"怎么报名？"}'

curl -s http://127.0.0.1:8000/api/moments/crm/sync -X POST
```

## 项目结构

```
src/main/java/com/wecom/simulator/   Spring Boot 服务
src/main/resources/static/           聊天模拟器 UI
src/test/java/                       API 测试
examples/EchoBot.java                Java 联调示例
data/voices/                         上传的语音文件
```

## 测试

```bash
mvn test
```

## 安全说明（本地联调）

- 默认绑定 `127.0.0.1`，避免局域网误暴露
- Webhook 默认仅允许回环地址（`localhost` / `127.0.0.1` / `::1`），禁止链路本地与云元数据地址；如需 Docker/私网回调，设置 `wecom.simulator.webhook.allow-private-network=true`
- 语音上传扩展名白名单，禁止路径穿越；`media_id` 仅接受十六进制
- 清空会话会同步删除 `data/voices` 下语音文件；单文件上限 5MB，文件数上限可配
- 本项目仍是**本地联调模拟器**，不做企微加解密/签名校验，请勿直接暴露到公网
