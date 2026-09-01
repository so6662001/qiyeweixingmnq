# 统一收件箱 · 合规说明与配置指南

目标：**在本系统里实时收到消息 → 直接录入回复 → 自动发给对方，全程不用离开本系统。**

本功能只调用腾讯官方开放接口（`qyapi.weixin.qq.com`），不使用任何非官方协议或逆向手段。

---

## 一、先说结论：哪些能做、怎么做才合规

| 需求 | 合规通道 | 收消息 | 发消息 | 说明 |
| --- | --- | --- | --- | --- |
| **微信（个人号）用户** 找你咨询 | **微信客服**（企业微信开放能力） | ✅ `kf/sync_msg` | ✅ `kf/send_msg` | 官方唯一支持与微信用户双向实时收发的方式 |
| **企业微信成员** 之间 / 找应用 | 企业微信自建应用 | ✅ 接收消息回调 | ✅ `message/send` | 企业内部沟通 |
| 企微「客户联系」外部客户单聊 | 会话存档 | ⚠️ 只读，需单独申请 + 客户同意 | ❌ 官方无主动单聊发送接口 | 本项目未实现，见下文 |
| 直接接入**个人微信客户端**协议 | — | ❌ | ❌ | **违规，本项目明确不做** |

### 为什么「个人微信」要走微信客服

微信个人号没有对企业开放的第三方收发接口。市面上的 `itchat` / `wechaty puppet-xp` / `WeChatFerry` 等方案属于协议逆向或客户端注入，违反《微信个人账号使用规范》与《微信外部链接内容管理规范》，风险包括账号封禁、企业连带责任、以及个人信息处理合法性缺失。**本项目不包含也不支持此类实现。**

官方给出的替代路径就是**微信客服**：企业在企业微信后台开通「微信客服」，微信用户通过客服入口（小程序 / 公众号 / 网页 / 二维码 / 视频号等）发起会话，企业侧即可用官方接口收发消息。对客户来说仍然是在微信里聊天，对企业来说全程有官方接口和审计。

### 关于「客户联系」外部联系人

企业微信的外部联系人（销售用企微加客户微信）**没有**「主动给某个客户发单聊消息」的开放接口；官方提供的是需要成员在客户端确认的群发（`add_msg_template`）与朋友圈。聊天内容读取只能通过**会话存档**，且必须：企业单独申请开通、生成 RSA 密钥、成员与客户被明确告知并同意。本项目未实现会话存档，避免在未取得授权的情况下留存客户聊天内容。

---

## 二、需要在企业微信后台准备什么

### 1. 企业 ID

「我的企业 → 企业信息」底部的 **企业 ID（CorpID）**。

### 2. 微信客服（对应微信用户）

1. 「应用管理 → 微信客服」开通，创建客服账号，配置接待人员。
2. 「开发配置」获取 **Secret**。
3. 配置「接收回调消息」：
   - URL：`https://你的域名/callback/wecom/kf`
   - Token：自定义（随机字符串）
   - EncodingAESKey：点「随机获取」，43 位
4. 保存时企业微信会向该 URL 发起一次 GET 校验，本服务会自动完成验签与解密应答。
5. 配置「可信 IP」（若后台要求）。

### 3. 企业微信自建应用（对应企微成员）

1. 「应用管理 → 自建 → 创建应用」，记下 **AgentId** 与 **Secret**。
2. 「接收消息 → 设置API接收」：
   - URL：`https://你的域名/callback/wecom/app`
   - Token / EncodingAESKey：同上方式生成
3. 如需在会话列表显示成员姓名，给应用授予通讯录读取权限（不授予也能正常收发，只是显示 UserID）。

> 回调 URL 必须是**公网可访问的 HTTPS**（企业微信不接受自签证书）。本地开发可用内网穿透，生产建议放在网关/反向代理后。

---

## 三、启动配置

所有凭据通过环境变量注入，不写进代码库：

```bash
export WECOM_BRIDGE_ENABLED=true
export WECOM_CORP_ID=ww1234567890abcdef

# 微信客服（微信用户）
export WECOM_KF_ENABLED=true
export WECOM_KF_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export WECOM_KF_CALLBACK_TOKEN=your_kf_token
export WECOM_KF_CALLBACK_AES_KEY=43位EncodingAESKey
export WECOM_KF_SERVICER_USERID=huanghaoting   # 接待人员 UserID
export WECOM_KF_AUTO_TAKE_OVER=true            # 回复前自动转为「人工接待」

# 企业微信自建应用（企微成员）
export WECOM_APP_ENABLED=true
export WECOM_APP_AGENT_ID=1000002
export WECOM_APP_SECRET=yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy
export WECOM_APP_CALLBACK_TOKEN=your_app_token
export WECOM_APP_CALLBACK_AES_KEY=43位EncodingAESKey

mvn spring-boot:run
```

打开 <http://127.0.0.1:8000/inbox/>。右侧「接入状态」会显示每项是否配置完成，顶部会显示回调地址，直接复制到企业微信后台即可。

只想先看效果？不配任何凭据也能打开页面：保留 `demo-inbox: true`，用右侧「注入演示消息」验证「实时收到 → 页面内回复」链路（演示消息不触达任何真实用户）。

---

## 四、消息是怎么流转的

### 收（微信用户）

```
微信用户发消息
  → 企业微信推送 kf_msg_or_event 回调到 /callback/wecom/kf
  → 本服务验签 + AES 解密，拿到一次性 Token
  → 调 kf/sync_msg（带持久化游标）增量拉取消息
  → 统一入库、按 msgid 去重
  → WebSocket 推送到页面，左侧会话置顶并提醒
```

游标（`next_cursor`）持久化在 `data/bridge/inbox.json`，重启不会重复拉取或漏消息。回调偶发丢失时，可点页面上的「立即同步」手动兜底。

### 发（微信用户）

```
页面输入框录入内容 → POST /api/inbox/conversations/{id}/reply
  → 校验 48 小时可回复窗口
  → 需要时先调 service_state/trans 转「人工接待」
  → 调 kf/send_msg 发出
  → 发送结果写回同一条消息（已发送 / 失败原因），WebSocket 推回页面
```

### 官方规则在产品上的体现

| 规则 | 本系统的处理 |
| --- | --- |
| 客户最后一条消息 48 小时内才可主动发送 | 提前本地拦截并提示剩余时间，不把官方错误码直接抛给使用者 |
| 人工回复需会话处于「由人工接待」 | 配置接待人员后自动转接；也可手动点「接入人工」 |
| 回调需 5 秒内响应 | 回调只做验签解密，拉取放后台线程 |
| access_token 需复用，勿频繁获取 | 按 secret 缓存，过期前 2 分钟自动刷新，遇 42001 强制刷新重试一次 |
| 同一条消息可能重复推送 | 按官方 msgid 去重 |

---

## 五、数据与隐私

- 消息与会话保存在本机 `data/bridge/inbox.json`（该目录已在 `.gitignore` 中），默认最多保留 5000 条，超出后淘汰最旧的。
- 不下载、不落地任何媒体文件，图片/语音/文件只记录官方 `media_id` 引用。
- 日志不打印 secret、access_token 与消息正文。
- 如需完全不落盘，把 `wecom.bridge.max-messages` 设小并定期清理，或改写 `InboxStore`（存储实现是独立的一层）。
- 上线前请确认：已在客服欢迎语或服务协议中告知客户「消息由企业客服系统处理」，符合《个人信息保护法》的告知要求。

---

## 六、接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/inbox/status` | 配置与连通状态 |
| GET | `/api/inbox/conversations` | 会话列表 |
| GET | `/api/inbox/conversations/{id}/messages?limit=200` | 会话消息 |
| POST | `/api/inbox/conversations/{id}/reply` | 录入回复并发出，body `{"text":"..."}` |
| POST | `/api/inbox/conversations/{id}/read` | 标记已读 |
| POST | `/api/inbox/conversations/{id}/take-over` | 转人工接待 |
| POST | `/api/inbox/sync` | 手动增量拉取 |
| GET | `/api/inbox/kf/accounts` | 客服账号列表（排查配置） |
| POST | `/api/inbox/demo/inbound` | 注入演示消息（本地） |
| WS | `/ws/inbox` | 实时推送 `snapshot` / `message` / `conversation` |
| GET/POST | `/callback/wecom/kf` | 微信客服回调 |
| GET/POST | `/callback/wecom/app` | 企微应用回调 |

---

## 七、常见错误

| 现象 | 原因与处理 |
| --- | --- |
| 后台保存回调 URL 失败 | URL 未公网可达 / 非 HTTPS / Token 与 AESKey 与环境变量不一致 |
| 回调 400 | `msg_signature` 校验失败，通常是 Token 配错或复制带了空格 |
| 回调 503 | 服务端该通道未配置完整（缺 secret / token / 43 位 AESKey） |
| 发送提示「超出可回复窗口」 | 官方 48 小时限制，需等客户再次发起会话 |
| 错误码 60011 | 应用无权操作该客服账号，检查可调用范围与接待人员配置 |
| 错误码 301056 | 会话未在人工接待状态，先点「接入人工」 |
| 会话只显示一串 ID | 未授予客户基础信息 / 通讯录读取权限，不影响收发 |

---

## 八、本项目里的相关代码

| 位置 | 作用 |
| --- | --- |
| `com.wecom.bridge.crypto.CallbackCrypto` | 官方回调加解密（SHA1 验签 + AES-256-CBC，禁用外部实体防 XXE） |
| `com.wecom.bridge.client.WecomApiClient` | access_token 缓存、errcode 处理、失效重试 |
| `com.wecom.bridge.service.WechatKfService` | 微信客服 sync_msg / send_msg / 接待状态 |
| `com.wecom.bridge.service.WecomAppService` | 企微成员回调接收与 message/send |
| `com.wecom.bridge.service.InboxService` | 统一收件箱读写与通道路由 |
| `com.wecom.bridge.store.InboxStore` | 会话/消息/游标持久化与去重 |
| `src/main/resources/static/inbox/` | 统一收件箱页面 |
