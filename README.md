# 企微消息工作台（Java）

基于 **Spring Boot 3 / Java 21**，一套服务包含两部分：

| 模块 | 用途 | UI | API 前缀 | WebSocket |
|------|------|----|----------|-----------|
| **统一收件箱** | **接真实消息**（腾讯官方接口） | http://127.0.0.1:8000/inbox/ | `/api/inbox` | `/ws/inbox` |
| 企业微信模拟器 | 本地联调，不联网 | http://127.0.0.1:8000/wecom/ | `/api` | `/ws` |
| 个人微信模拟器 | 本地联调，不联网 | http://127.0.0.1:8000/wechat/ | `/api/wechat` | `/ws/wechat` |

入口页：http://127.0.0.1:8000

---

## 统一收件箱（真实收发）

**实时收到消息 → 在页面里录入回复 → 自动发给对方，全程不用离开本系统。**

| 通道 | 对应谁 | 入站 | 出站 |
|------|--------|------|------|
| 个人微信 | 微信好友（一对一私聊） | **OpenClaw**（腾讯官方 iLink Bot API，扫码授权） | **OpenClaw** |
| 企业微信 | 企微联系人 / 外部客户 | **会话内容存档**（官方 SDK，seq 增量拉取） | **OpenClaw** |
| 本地演示 | 无（本机自造数据） | 本地 | 仅记录本地 |

- 个人微信走 [OpenClaw](https://github.com/openclaw/openclaw) 的腾讯官方渠道插件 `@tencent-weixin/openclaw-weixin`，底层是官方 iLink Bot API，**不是协议逆向**。
- 企业微信入站用官方**会话内容存档**（需管理端开通、成员与客户已授权），只读拉取。
- 两个通道**出站完全一致**：都调 `openclaw message send`，直接投递人工录入的原文，不触发大模型回合。
- 不采用 `itchat` / `wechaty puppet-xp` / `WeChatFerry` 等逆向方案，也不使用微信客服模型。
- 凭据只从环境变量注入；消息落在 `data/bridge/`（已 gitignore），不下载媒体文件。

不配任何东西也能先看效果：打开 `/inbox/`，用右侧「注入演示消息」验证实时接收与页面内回复。

架构、部署、合规边界、接口清单与排查见 **[docs/openclaw-inbox-setup.md](docs/openclaw-inbox-setup.md)**；
OpenClaw 转发插件见 **[openclaw-plugin/README.md](openclaw-plugin/README.md)**；
进真实环境测试按 **[docs/real-world-testing.md](docs/real-world-testing.md)** 分阶段走。

## 运营台（`/ops/`）

| 功能 | 说明 |
|------|------|
| 上线自检 | 逐项检查配置与连通性，每条给出**修复动作**；可实际探测 `openclaw --version` 与换取 access_token |
| 发客户朋友圈 | 上传图片取 media_id → 创建发表任务 → 轮询任务结果。成员需在企微客户端确认后才真正发表 |
| 朋友圈互动数据 | 自动汇总**点赞数 / 评论数**（区分客户与成员），定时刷新并缓存 |
| 客户群群发图文 | 选群 → 文本 + 图片 + 图文链接 → 创建群发任务。群主确认后才进群 |

**能力边界（先看这个再测）**：

| 动作 | 个人微信 | 企业微信 |
|------|---------|---------|
| 一对一私聊收发 | ✅ | ✅ |
| 发图片 / 文件 | ✅ | ✅ |
| 群里发图文 | ❌ 插件只声明支持一对一私聊 | ✅ |
| 发朋友圈 / 点赞评论数 | ❌ 官方 API 无此能力 | ✅ |

演练模式 `WECOM_BRIDGE_DRY_RUN=true`：所有出站只记录不真发，真实环境首测建议先开。

## 手机访问与部署位置

收件箱和运营台都做了手机适配，浏览器打开即可用。但要注意两点：

1. **网关必须跑在常开的机器上**。OpenClaw 官方手机 App 是 companion node，
   [明确不托管 Gateway](https://docs.openclaw.ai/platforms/android)，顶不了这个角色。
   办公电脑会关机的话，把服务搬到云主机 / NAS / 常开旧机器上，手机就只当操作端。
2. **必须设置访问口令**。收件箱能看全部客户会话并以你的身份发消息：

```bash
export WECOM_ACCESS_CODE=$(openssl rand -base64 18)
export WECOM_AUTH_COOKIE_SECURE=true   # 走 HTTPS 时开
```

安全默认：**不配口令时只接受本机回环访问**，外部请求直接拒绝，不会出现裸奔在公网的情况。
外网访问建议走 Tailscale 或 HTTPS 反代，详见 [docs/real-world-testing.md](docs/real-world-testing.md#电脑关机了怎么办--手机能不能顶上)。

```bash
export WECOM_BRIDGE_ENABLED=true
# 个人微信收发 + 企微出站
export WECOM_OPENCLAW_ENABLED=true
export WECOM_OPENCLAW_INBOUND_TOKEN=$(openssl rand -hex 24)
# 企微入站（会话存档）
export WECOM_ARCHIVE_ENABLED=true WECOM_CORP_ID=ww... \
       WECOM_ARCHIVE_SECRET=... WECOM_ARCHIVE_PRIVATE_KEY_V1="$(cat pkcs8_private.pem)" \
       WECOM_ARCHIVE_SDK_LIB=/opt/wework/libWeWorkFinanceSdk_Java.so
mvn spring-boot:run   # 打开 /inbox/
```

OpenClaw 插件把私聊回推到 `POST /api/openclaw/inbound`（带共享密钥）。

---

## 模拟器部分

> 个人微信模拟器为**本地假实现**，不对接真实个微协议；回调 JSON 仅用于联调。

## 功能（两套模拟器均具备）

- 聊天界面发送文字 / 语音 / 图片 / 文件
- **私聊 + 群聊**：群内可回复指定人、@提及；消息可按 `chat_type` / `group_id` 过滤
- 录音或上传语音，获取 `media_id`、下载地址与识别文本
- `POST …/reply/text` 模拟主动文字回复（支持群内指定回复对象）
- 可选 Webhook：企微通道为企业微信风格 JSON；个微通道为个人微信风格 JSON（无 AgentID）
- 内置演示 Bot / 微信助手，可开关
- WebSocket 按通道隔离实时刷新
- **朋友圈**：上传图片 + 文案/方案发帖；点赞/评论；可选 CRM 线索同步
- 会话、媒体目录互相隔离（企微：`data/*`；个微：`data/wechat/*`）

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

默认仅监听 `127.0.0.1:8000`。打开 http://127.0.0.1:8000 选择版本：

- 企业微信：http://127.0.0.1:8000/wecom/
- 个人微信：http://127.0.0.1:8000/wechat/

高保真 HTML 效果图（静态预览）：

- 索引：`docs/mockups/index.html` → http://127.0.0.1:8000/mockups/
- 企微会话 / 群聊 / 朋友圈：`wecom-*-hifi.html`
- 个微会话 / 群聊 / 朋友圈：`wechat-*-hifi.html`

打包运行：

```bash
mvn -q -DskipTests package
java -jar target/wecom-simulator-1.0.0.jar
```

## 核心 API

企微使用下表路径；个人微信在前缀前加 `/wechat`，例如 `POST /api/wechat/messages/text`、`GET /api/wechat/moments`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/messages/text` | 发送文字（私聊/群聊，可 reply_to_user） |
| POST | `/api/messages/voice` | 上传语音（multipart，支持群聊） |
| POST | `/api/messages/image` | 上传图片（multipart，支持群聊） |
| POST | `/api/messages/file` | 上传文件（multipart，支持群聊） |
| GET | `/api/messages` | 拉取消息（`chat_type`/`group_id`/类型/角色） |
| GET | `/api/groups` | 群列表 |
| POST | `/api/groups` | 创建群 |
| GET | `/api/messages/{msgid}` | 获取单条消息 |
| GET | `/api/messages/{msgid}/callback` | 查看企微风格回调 JSON |
| POST | `/api/reply/text` | 应用文字回复（群内可指定人） |
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

### 群聊文字 + 回复指定人

```bash
curl -s http://127.0.0.1:8000/api/messages/text \
  -H 'Content-Type: application/json' \
  -d '{
    "content":"@user002 请看这份资料",
    "chat_type":"group",
    "group_id":"group001",
    "from_user":"seller001",
    "reply_to_user":"user002",
    "mention_user_ids":["user002"]
  }'

curl -s http://127.0.0.1:8000/api/reply/text \
  -H 'Content-Type: application/json' \
  -d '{
    "content":"@user002 已收到",
    "chat_type":"group",
    "group_id":"group001",
    "reply_to_user":"user002",
    "mention_user_ids":["user002"]
  }'

# 按群拉取
curl -s 'http://127.0.0.1:8000/api/messages?chat_type=group&group_id=group001'
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
src/main/java/com/wecom/simulator/   Spring Boot 服务（双通道 ChannelRuntime）
src/main/resources/static/           入口 + /wecom + /wechat UI
src/test/java/                       API 测试（含个微隔离用例）
examples/EchoBot.java                Java 联调示例（默认企微 /api）
data/voices|files|moments            企微媒体
data/wechat/*                         个微媒体
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
- 本项目仍是**本地联调模拟器**，不做企微/个微官方加解密或协议对接，请勿直接暴露到公网
- 个人微信版本仅提供本地假数据与假回调，**不能**连接真实微信客户端或服务号
