# 统一收件箱 · 架构、部署与合规

目标：**在本系统里实时收到消息 → 直接录入回复 → 自动发给对方，全程不用离开本系统。**

## 一、架构

```
【个人微信】客户用微信一对一私聊发消息
   │  腾讯官方 iLink Bot API（@tencent-weixin/openclaw-weixin，扫码授权）
   ▼
OpenClaw Gateway
   │  wecom-inbox-forwarder 插件：message_received → 转发
   │                            before_dispatch  → 接管，不让大模型自动回
   ▼  POST /api/openclaw/inbound（共享密钥）
统一收件箱  ◄──── 人工在页面里录入回复
   │  openclaw message send --channel openclaw-weixin --target <peer> --message <原文>
   ▼
OpenClaw → iLink → 客户微信

【企业微信】成员与客户/同事在企微里聊天
   │  官方会话内容存档 SDK：Init → GetChatData(seq) → RSA 解 encrypt_random_key → DecryptData
   ▼
统一收件箱（seq 游标持久化、msgid 去重）  ◄──── 人工在页面里录入回复
   │  同样走 openclaw message send（与个人微信完全一致）
   ▼
OpenClaw → 客户
```

要点：

- **入站两条路**：个人微信由 OpenClaw 实时推送；企业微信由会话存档轮询拉取（存档没有回调）。
- **出站一条路**：两个通道都调 `openclaw message send`。这条命令是直接投递，不会触发大模型回合，发出去的就是人工录入的原文。
- 页面只有一套会话列表与消息流，两个通道归一到同一份数据结构。

## 二、为什么这么选

| 需求 | 采用 | 说明 |
| --- | --- | --- |
| 个人微信一对一私聊收发 | OpenClaw + `@tencent-weixin/openclaw-weixin` | 该渠道插件由**腾讯微信团队维护**，底层是官方 **iLink Bot API**（`ilinkai.weixin.qq.com`），扫码授权，**不是协议逆向** |
| 企业微信消息入站 | 官方**会话内容存档** | 企业微信侧能完整拿到成员与客户的聊天内容，是官方为合规留存设计的能力 |
| 两个通道出站 | OpenClaw `message send` | 行为一致，人工录入原文直接投递 |

不采用的方案：

- `itchat` / `wechaty puppet-xp` / `WeChatFerry` 等协议逆向或客户端注入 —— 违反微信个人账号使用规范，有封号与合规风险。
- 微信客服（`kf/sync_msg` + `kf/send_msg`）—— 那是「客户主动找官方客服入口」的模型，不是一对一私聊，且有 48 小时窗口与人工接待状态等约束，不符合本项目要求。

## 三、合规边界（务必阅读）

1. **会话存档必须先获得授权**：企业需在管理端开通「会话内容存档」，被存档的成员需在企微客户端看到提示，外部客户需**同意**存档（存档流里会出现 `agree` / `disagree` 消息类型）。未取得同意的会话不要接入。
2. **个人微信渠道需本人扫码授权**，凭据保存在运行 OpenClaw 的机器上（`~/.openclaw`）。请使用企业为业务配备的账号，并向使用者说明消息会进入企业客服系统。
3. **告知义务**：按《个人信息保护法》，应在服务协议或欢迎语中告知客户「消息由企业系统处理与留存」。
4. **最小留存**：本服务默认只保留最近 5000 条消息（`wecom.bridge.max-messages`），不下载媒体文件，只记录 `sdkfileid` / 媒体引用。
5. **凭据不入库**：所有 secret / token / 私钥从环境变量注入，日志不打印。

## 四、部署 OpenClaw（个人微信收发）

### 1. 安装 OpenClaw 与腾讯官方微信渠道插件

```bash
# 安装 OpenClaw（参考 https://docs.openclaw.ai/install）
openclaw --version

# 腾讯官方微信渠道插件
openclaw plugins install "@tencent-weixin/openclaw-weixin"
openclaw config set plugins.entries.openclaw-weixin.enabled true

# 扫码登录（在运行 Gateway 的同一台机器上执行）
openclaw channels login --channel openclaw-weixin

openclaw gateway restart
openclaw channels status --probe
```

多账号：重复执行 `channels login` 会新增账号条目；建议按账号隔离会话：

```bash
openclaw config set session.dmScope per-account-channel-peer
```

### 2. 安装本仓库的转发插件

见 [`openclaw-plugin/README.md`](../openclaw-plugin/README.md)。要点：

```bash
cd openclaw-plugin && npm install && npm run build
openclaw plugins install --link . --force
openclaw plugins enable wecom-inbox-forwarder
```

在 `~/.openclaw/openclaw.json` 中配置（**必须开启 `allowConversationAccess`**，消息类钩子需要显式授权）：

```json
{
  "plugins": {
    "entries": {
      "wecom-inbox-forwarder": {
        "enabled": true,
        "hooks": { "allowConversationAccess": true },
        "config": {
          "endpoint": "http://127.0.0.1:8000/api/openclaw/inbound",
          "token": "<与 WECOM_OPENCLAW_INBOUND_TOKEN 相同>",
          "channels": ["openclaw-weixin"],
          "claimInbound": true,
          "mirrorOutbound": true
        }
      }
    }
  }
}
```

`claimInbound: true` 很关键：回复由人工在收件箱里录入，插件要接管会话，避免大模型抢先自动回客户。

## 五、部署会话内容存档（企业微信入站）

### 1. 管理端开通

1. 企业微信管理端 →「管理工具 → 聊天内容存档」开通，拿到 **Secret**。
2. 生成 2048 位 RSA 密钥对，把**公钥**填到管理端（每次更换公钥版本号 `publickey_ver` 会 +1）。
3. 配置**可信 IP**（服务器出网 IP）。
4. 给需要存档的成员开启存档，并确认外部客户已同意。

### 2. 部署官方 SDK

官方 SDK 是本地动态库，有分发限制，**不随本仓库提供**，需自行从企业微信开发者文档下载：

- `WeWorkFinanceSdk_Java.jar` → 加入服务的 classpath
- `libWeWorkFinanceSdk_Java.so` → 放到服务器上，路径配到 `sdk-library-path`

本服务通过反射调用 `com.tencent.wework.Finance`，所以 SDK 缺失时**不会影响启动**，只是企微通道显示「SDK 未加载」。

启动示例：

```bash
java -cp "app.jar:/opt/wework/WeWorkFinanceSdk_Java.jar" \
     -Dloader.main=com.wecom.simulator.WecomSimulatorApplication \
     org.springframework.boot.loader.launch.PropertiesLauncher
```

### 3. 私钥格式

配置里放 **PKCS#8** PEM（`-----BEGIN PRIVATE KEY-----`）。若手上是 PKCS#1（`BEGIN RSA PRIVATE KEY`），先转换：

```bash
openssl pkcs8 -topk8 -nocrypt -in rsa_private.pem -out pkcs8_private.pem
```

按公钥版本号配置，可同时保留多个版本（存档消息会带回 `publickey_ver`）。

## 六、环境变量

```bash
export WECOM_BRIDGE_ENABLED=true

# —— OpenClaw：个人微信收发 + 企微出站 ——
export WECOM_OPENCLAW_ENABLED=true
export WECOM_OPENCLAW_CLI=/usr/local/bin/openclaw
export WECOM_OPENCLAW_WECHAT_CHANNEL=openclaw-weixin
export WECOM_OPENCLAW_INBOUND_TOKEN=$(openssl rand -hex 24)   # 与插件 token 一致
# export WECOM_OPENCLAW_ACCOUNT=wx-sales-1                    # 多账号时指定

# —— 企微会话存档：入站 ——
export WECOM_ARCHIVE_ENABLED=true
export WECOM_CORP_ID=ww1234567890abcdef
export WECOM_ARCHIVE_SECRET=<聊天内容存档 Secret>
export WECOM_ARCHIVE_PRIVATE_KEY_V1="$(cat pkcs8_private.pem)"
export WECOM_ARCHIVE_SDK_LIB=/opt/wework/libWeWorkFinanceSdk_Java.so
export WECOM_ARCHIVE_MEMBER_USERIDS=huanghaoting,zhangsan      # 用于判断消息方向
export WECOM_ARCHIVE_EXTERNAL_ONLY=true                        # 只接与外部客户的会话
```

打开 <http://127.0.0.1:8000/inbox/>，右侧「接入状态」会逐项显示是否就绪。

不配任何东西也能先看效果：保留 `demo-inbox: true`，用右侧「注入演示消息」验证「实时收到 → 页面内回复」链路（不触达真实用户）。

## 七、企微会话的发送目标映射（重要）

会话存档里的身份是 **企微 UserID / external_userid**（如 `wmErxtDgAA...`），
它**不是** OpenClaw 的发送目标。所以企微会话第一次出现时无法直接回复，需要补映射：

- **配置方式**：`wecom.bridge.archive.target-mapping`，键为存档里的对端 id，值为 OpenClaw 的对端标识。
- **页面方式**：打开该会话 → 右上「设置发送目标」→ 填入对应的微信对端标识。

未映射时页面会明确提示，并且不会盲发。个人微信通道不需要映射，入站带回的 peer id 就是发送目标。

## 八、接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/inbox/status` | 两条通道的配置与就绪状态 |
| GET | `/api/inbox/conversations` | 会话列表 |
| GET | `/api/inbox/conversations/{id}/messages?limit=200` | 会话消息 |
| POST | `/api/inbox/conversations/{id}/reply` | 录入回复并通过 OpenClaw 发出，body `{"text":"..."}` |
| POST | `/api/inbox/conversations/{id}/read` | 标记已读 |
| PUT | `/api/inbox/conversations/{id}/openclaw-target` | 补填发送目标，body `{"target":"..."}` |
| POST | `/api/inbox/archive/pull` | 手动触发一次存档增量拉取 |
| POST | `/api/inbox/demo/inbound` | 注入演示消息（本地） |
| POST | `/api/openclaw/inbound` | **插件专用**：回推个人微信私聊，需带共享密钥 |
| WS | `/ws/inbox` | 实时推送 `snapshot` / `message` / `conversation` |

## 九、排查

| 现象 | 原因与处理 |
| --- | --- |
| 页面「OpenClaw 出站」未就绪 | `wecom.bridge.openclaw.enabled` 未开，或 `cli-path` 不可执行 |
| 发送报「command not found」 | `cli-path` 配错，用绝对路径 |
| 发送报 channel not logged in | `openclaw channels login --channel openclaw-weixin` 重新扫码 |
| 个人微信消息收不到 | 插件未启用 / 未开 `allowConversationAccess` / `endpoint` 不通；看 `openclaw logs tail` |
| 入站返回 401 | 插件 `token` 与 `WECOM_OPENCLAW_INBOUND_TOKEN` 不一致 |
| 入站返回 503 | 服务端未开 `openclaw.enabled` 或没配 `inbound-token` |
| 客户收到大模型自动回复 | `claimInbound` 未生效，确认插件已启用且有会话钩子权限 |
| 企微「存档 SDK」未加载 | 官方 jar 不在 classpath，或 `.so` 路径不对 |
| 存档报 `301002` 之类错误码 | 未开通存档 / Secret 错 / 可信 IP 未配 |
| 存档解密失败 | `publickey_ver` 与私钥版本不匹配，或私钥不是 PKCS#8 |
| 企微会话不能回复 | 缺 OpenClaw 发送目标，见第七节 |
| 消息方向反了 | 配 `WECOM_ARCHIVE_MEMBER_USERIDS`，别依赖 external_userid 形态推断 |

## 十、代码位置

| 位置 | 作用 |
| --- | --- |
| `com.wecom.bridge.openclaw.OpenClawGateway` | 调 `openclaw message send` 出站 |
| `com.wecom.bridge.openclaw.OpenClawInboundService` | 插件回推的私聊入站处理、密钥校验 |
| `com.wecom.bridge.web.OpenClawInboundController` | `/api/openclaw/inbound` |
| `com.wecom.bridge.archive.SessionArchiveService` | 存档增量拉取、解密、方向判定 |
| `com.wecom.bridge.archive.NativeWeWorkFinanceSdk` | 反射调用官方 SDK |
| `com.wecom.bridge.archive.ArchiveRandomKeyDecryptor` | RSA/PKCS1 解 `encrypt_random_key` |
| `com.wecom.bridge.archive.ArchiveMessageParser` | 存档明文 → 统一消息 |
| `com.wecom.bridge.service.InboxService` | 收件箱读写与出站路由 |
| `openclaw-plugin/` | OpenClaw 转发插件（TypeScript） |
| `src/main/resources/static/inbox/` | 收件箱页面 |
