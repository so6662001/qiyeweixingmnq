# 真实环境测试手册

按阶段推进，每个阶段都能独立验证、独立回滚。先在**演练模式**下把参数跑通，再放开真发。

打开 **运营台 → 上线自检**（`/ops/`）可以一次性看到每项配置的状态和修复动作，不用逐个猜。

---

## 零、先看能力边界

这一步最省时间：有些动作在个人微信侧**没有官方接口**，不要在那上面浪费调试时间。

| 动作 | 个人微信（OpenClaw） | 企业微信（官方 API） |
| --- | --- | --- |
| 一对一私聊收 / 发 | ✅ | ✅（存档收 + OpenClaw 发） |
| 发图片 / 文件 | ✅ `--media` | ✅ 群发附件 |
| **群里发图文** | ❌ 插件只声明支持一对一私聊 | ✅ 客户群群发 `add_msg_template` |
| **发朋友圈** | ❌ iLink Bot API 无此能力 | ✅ 客户朋友圈 `add_moment_task` |
| **朋友圈点赞 / 评论数** | ❌ | ✅ `get_moment_comments` |

两点必须先知道，否则测试结果会让你以为是 Bug：

1. **企微的朋友圈和群发都不是「调接口就发出去」**。官方模型是「企业创建任务 → 成员/群主在企业微信客户端点确认 → 才真正发出」。接口返回成功只代表**任务创建成功**。
2. **官方有频控**。客户群群发：同一客户群每月可接收条数有限（超了报 `41048`）。朋友圈发表任务也有频率限制。真实环境别拿同一个群反复刷。

---

## 一、阶段 0：演练模式跑通参数

目的：确认所有凭据、路径、映射都对，但**不发出任何东西**。

```bash
export WECOM_BRIDGE_ENABLED=true
export WECOM_BRIDGE_DRY_RUN=true          # 关键：出站只记录不真发
```

1. 启动服务，打开 `/ops/`，点「重新检查」。
2. 把所有 **红色（fail）** 项按提示修掉；黄色（warn）项按需要处理。
3. 顶部会显示「演练模式：出站只记录不真发」。
4. 到 `/inbox/` 发一条回复、到 `/ops/` 建一个朋友圈任务，确认：返回成功、日志里有 `[dry-run]`、对方**没有**收到。

确认无误后再：

```bash
export WECOM_BRIDGE_DRY_RUN=false
```

---

## 二、阶段 1：个人微信一对一私聊

### 准备

```bash
export WECOM_OPENCLAW_ENABLED=true
export WECOM_OPENCLAW_CLI=/usr/local/bin/openclaw      # 用绝对路径
export WECOM_OPENCLAW_INBOUND_TOKEN=$(openssl rand -hex 24)
```

```bash
openclaw plugins install "@tencent-weixin/openclaw-weixin"
openclaw config set plugins.entries.openclaw-weixin.enabled true
openclaw channels login --channel openclaw-weixin      # 扫码
cd openclaw-plugin && npm install && npm run build
openclaw plugins install --link . --force
openclaw plugins enable wecom-inbox-forwarder
openclaw gateway restart
```

插件配置（`~/.openclaw/openclaw.json`，**必须开 `allowConversationAccess`**）见
[`openclaw-plugin/README.md`](../openclaw-plugin/README.md)。

### 测试步骤

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用另一个微信号给已登录的账号发一条私聊 | `/inbox/` 左侧**自动**出现会话，无需刷新；红色未读角标 |
| 2 | 点开会话，输入文字，Enter | 对方微信收到；气泡显示「已发送」 |
| 3 | 回复时带图片（`media` 字段填本地路径或 URL） | 对方收到图片 |
| 4 | 在手机上用该微信号回一条 | **待确认项**：`mirrorOutbound` 走的是 OpenClaw 的 `message_sent` 钩子，只在经 OpenClaw 发送时触发；手机端直接回复不经过 OpenClaw。这条消息会不会进收件箱、进来后方向对不对，请实测记录（见下文「人不在电脑旁」） |
| 5 | 拉一个群，在群里发消息 | 收件箱**不会**出现（只接一对一，符合预期） |
| 6 | 停掉收件箱服务，再让对方发消息，然后重启 | 期间消息会丢：插件转发失败只记日志。**这是已知边界**，重要会话请勿在服务停机时依赖 |

### 常见失败

| 现象 | 处理 |
| --- | --- |
| 收不到私聊 | `openclaw logs tail` 看插件是否报错；确认 `allowConversationAccess: true` |
| 入站 401 | 插件 `config.token` 与 `WECOM_OPENCLAW_INBOUND_TOKEN` 不一致 |
| 入站 503 | 服务端没开 `openclaw.enabled` 或没配 `inbound-token` |
| 客户收到 AI 自动回复 | `claimInbound` 没生效，检查插件是否启用、钩子权限是否开 |
| 发送报 command not found | `WECOM_OPENCLAW_CLI` 用绝对路径 |
| 发送报 channel not logged in | 重新 `openclaw channels login` |

---

### 电脑关机了怎么办 / 手机能不能顶上

先说结论：**手机顶不了网关，但可以完全替代「操作端」**。

OpenClaw 官方的 iOS / Android App 是 **companion node（外设节点）**，文档写得很明确：
「Android does not host the Gateway」。它给网关补充摄像头、定位、语音、Canvas 等能力，
通过 WebSocket 以 `role: node` 配对，**不能自己收发微信**。iOS 更是完全不可能托管网关。

所以正确的做法不是「让手机顶上」，而是**把网关从会关机的电脑上搬走**：

| 方案 | 适合谁 | 说明 |
| --- | --- | --- |
| **云主机 / VPS** | 大多数人，推荐 | 2 核 2G 足够。天生常开，公网可达，配 HTTPS 就能从手机用 |
| **公司内网服务器 / NAS** | 已有机器 | 常开、稳定；外网访问要额外做（见下） |
| **一台旧电脑常插电** | 成本最低 | 关掉休眠、设开机自启；断电就停 |
| **安卓机 + Termux** | 想「口袋里的常开网关」 | 社区方案（`openclaw-termux` 用 proot 装 Ubuntu）。能跑，但不是官方支持路径，自担风险 |

搬过去之后：**你的办公电脑关不关机都无所谓**，手机浏览器打开收件箱就能收发。

#### 无头服务器怎么扫码登录

扫码必须在跑网关的那台机器上执行，但服务器没有图形界面——没关系，二维码会直接打在终端里：

```bash
ssh 你的服务器
openclaw channels login --channel openclaw-weixin
# 终端里出现二维码，用手机微信扫
```

手机上装个 SSH 客户端（Termius 等），**在外面也能重新扫码**，这就解决了「登录失效必须回公司」的问题。

#### 手机怎么安全访问

收件箱能看到全部客户会话、还能以你的身份发消息，**绝不能裸奔在公网上**。三选一：

| 方式 | 做法 | 评价 |
| --- | --- | --- |
| **Tailscale（推荐）** | 服务器和手机装同一个 tailnet，手机访问 `http://<tailnet-ip>:8000` | 不暴露公网，零配置证书，最省事 |
| HTTPS + 反向代理 | Nginx/Caddy 配证书，转发到 8000 | 需要域名和证书；务必同时配口令 |
| 内网 + VPN | 公司已有 VPN 就直接用 | 取决于现有网络 |

无论哪种，**都必须设置访问口令**：

```bash
export WECOM_ACCESS_CODE=$(openssl rand -base64 18)   # 记下来，手机上要输
export WECOM_AUTH_COOKIE_SECURE=true                  # 走 HTTPS 时开
```

安全默认：**不配口令时服务只接受本机回环访问**，外部请求直接拒绝并告诉你原因。
所以不会出现「忘了配口令，客户聊天记录裸奔在公网」这种事。

登录一次后 7 天内免输（`WECOM_AUTH_SESSION_TTL` 可调），改口令会让所有旧登录态立即失效。

#### 手机上的界面

收件箱和运营台都做了手机适配：单栏布局、会话列表与聊天用返回键切换、
输入框字号避免 iOS 自动缩放、底部留出安全区。顶部右侧有「退出」。

---

### 人不在电脑旁（常见场景）

典型用法：办公室/机房一台机器常开，跑 OpenClaw 网关；人在外面用手机微信。**这样是可以正常收发的**，原理是：

- 扫码登录后，插件把**账号令牌**存在网关那台机器的 `~/.openclaw` 下；
- 网关用这个令牌**自己**长轮询腾讯服务器（`ilinkai.weixin.qq.com`）收消息、调接口发消息；
- 你的手机微信是同一账号的另一个客户端，两者互不依赖。手机关屏、换网络、走出办公室都不影响网关。

但下面这些会让它停摆，出门前要确认：

| 风险 | 说明 | 怎么办 |
| --- | --- | --- |
| **机器休眠 / 睡眠** | 最常见的失败原因。休眠后长轮询中断，消息收不到 | 电源设置为「从不睡眠」；笔记本合盖不休眠；建议用常开的台式机或服务器 |
| 断网 / 换 IP | 长轮询中断 | 用有线网；探测到掉线会在页面显示 |
| 网关进程退出 | 崩溃、系统更新重启 | 用 systemd / 开机自启守护，配 `Restart=always` |
| **令牌失效需重新扫码** | 扫码登录必须**在跑网关的那台机器上**执行 | 人在外面就做不了。出差前先确认渠道在线；必要时准备远程桌面 |
| 收件箱服务停机 | 插件转发失败只记日志，**这期间的消息会丢** | 收件箱服务和网关一起做守护自启 |

**在外面怎么判断还活着**：打开 `/inbox/`（手机浏览器也行），顶部有一枚「渠道」状态标签：

- `渠道 在线 · N 秒前` —— 网关有响应且微信渠道在列
- `渠道 网关无响应` —— 那台机器可能休眠、断网或进程挂了
- `渠道 掉线` —— 网关活着但微信渠道不在了，通常是登录失效，需要回到那台机器重新扫码
- `渠道 探测已停` —— 探测本身停了，服务多半有问题

默认每 60 秒探测一次（`WECOM_OPENCLAW_HEALTH_INTERVAL`），页面每 60 秒刷新一次状态。

**协同问题**：你在手机上直接回了客户，同事又在收件箱里回一遍，客户会收到两条。建议约定「在外期间由谁回」，或统一都在收件箱里回。

---

## 三、阶段 2：企业微信会话存档（入站）

### 准备

管理端「管理工具 → 聊天内容存档」开通，拿 Secret；生成 2048 位 RSA 密钥对，公钥填后台；配可信 IP；给成员开存档并确认客户已同意。

```bash
export WECOM_ARCHIVE_ENABLED=true
export WECOM_CORP_ID=ww1234567890abcdef
export WECOM_ARCHIVE_SECRET=<存档 Secret>
export WECOM_ARCHIVE_PRIVATE_KEY_V1="$(cat pkcs8_private.pem)"
export WECOM_ARCHIVE_SDK_LIB=/opt/wework/libWeWorkFinanceSdk_Java.so
export WECOM_ARCHIVE_MEMBER_USERIDS=huanghaoting,zhangsan   # 强烈建议配
export WECOM_ARCHIVE_EXTERNAL_ONLY=true                     # 只接与外部客户的会话
```

官方 SDK jar 要在 classpath 里：

```bash
java -cp "app.jar:/opt/wework/WeWorkFinanceSdk_Java.jar" \
     org.springframework.boot.loader.launch.PropertiesLauncher
```

### 测试步骤

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 自检页看「存档 SDK」 | 显示「已加载」；显示为「未加载」就是 jar/.so 没配对 |
| 2 | 用企微给一个外部客户发条消息 | 几秒后（默认 5s 轮询）收件箱出现该会话，方向为**出站** |
| 3 | 让客户回一条 | 出现**入站**消息并计未读 |
| 4 | 看自检页「存档拉取」的 seq | 数字在增长 |
| 5 | 重启服务 | seq 从上次位置继续，不重复不丢 |
| 6 | 在群里聊 | 默认不入库（`include-room-chats=false`）；需要就打开 |

### 常见失败

| 现象 | 处理 |
| --- | --- |
| 消息方向反了 | 配 `WECOM_ARCHIVE_MEMBER_USERIDS`，别靠 external_userid 形态猜 |
| 解密失败 | `publickey_ver` 与私钥版本对不上，或私钥不是 PKCS#8 |
| 报 301002 / 无权限 | 存档未开通、Secret 错、或出网 IP 不在可信 IP 里 |
| 企微会话不能回复 | 存档身份不是 OpenClaw 目标，见下一节 |

### 企微会话怎么回复

存档里的 `userid` / `external_userid` **不是** OpenClaw 的发送目标。第一次会话出现时页面会提示补映射，两种方式：

- 配置：`wecom.bridge.archive.target-mapping`（存档 id → OpenClaw 对端标识）
- 页面：打开会话 → 右上「设置发送目标」

---

## 四、阶段 3：发朋友圈 + 点赞评论统计

### 准备

```bash
export WECOM_CONTACT_ENABLED=true
export WECOM_CONTACT_SECRET=<客户联系 Secret>
# 企业 ID 复用 WECOM_CORP_ID；单独配用 WECOM_CONTACT_CORP_ID
export WECOM_MOMENT_STATS_AUTO=true          # 自动刷新互动数据
export WECOM_MOMENT_STATS_INTERVAL=10m
export WECOM_MOMENT_LOOKBACK_DAYS=7          # 官方限制查询区间 ≤ 30 天
```

权限：用**「客户联系」Secret**，或把自建应用加入「可调用应用」列表后用它的 Secret。缺权限会报 `48002` / `60011`。

### 测试步骤（运营台 → 发朋友圈）

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 选 1～2 张图片 | 自动上传，页面显示 `文件名 → media_id` |
| 2 | 填文案，执行成员填自己的 UserID，点「创建发表任务」 | 返回 jobid，随后轮询到「创建完成」并给出 `moment_id` |
| 3 | 打开该成员的企业微信 | 收到待发表提醒，**手动确认后**才真正发到客户朋友圈 |
| 4 | 让客户点赞、评论 | 到「朋友圈互动数据」点「立即刷新」，看到点赞数、评论数与评论内容 |
| 5 | 等一个刷新周期 | 数据自动更新，不用手动点 |

统计口径：`点赞合计 / 评论合计` 是所有发表成员累加；`客户点赞 / 客户评论` 只统计外部客户（`external_userid` 有值的）。

### 常见失败

| 现象 | 处理 |
| --- | --- |
| 84063 可见范围为空 | 填执行成员 UserID，或配客户标签 |
| 84064 / 84065 | 发表任务超频，等一会再试 |
| 互动数据一直是 0 | 成员还没在客户端确认发表；或该成员不在应用可见范围 |
| 列表取不到 | 回看天数窗口内没有发表记录；注意区间不能超过 30 天 |

---

## 五、阶段 4：客户群发图文

### 测试步骤（运营台 → 客户群群发）

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 点「加载客户群」 | 列出客户群（名称 + chat_id） |
| 2 | 勾选**一个测试群**，填文本 + 图片 + 图文链接 | — |
| 3 | 点「创建群发任务」 | 返回 msgid，并提示「群主需在企业微信里确认」 |
| 4 | 群主打开企业微信 | 收到群发待确认，点确认后消息才进群 |
| 5 | 故意重复发同一个群多次 | 触发 `41048` 频控，属预期 |

**别用真实客户群做压力测试**：每个客户群每月可接收条数有限，刷爆了这个月就发不了了。

---

## 六、配置速查

| 环境变量 | 作用 |
| --- | --- |
| `WECOM_BRIDGE_ENABLED` | 总开关 |
| `WECOM_BRIDGE_DRY_RUN` | 演练模式，出站只记录不真发 |
| `WECOM_BRIDGE_DEMO_INBOX` | 是否保留本地演示会话 |
| **`WECOM_ACCESS_CODE`** | **访问口令。不配则只允许本机访问；要从手机用必须配** |
| `WECOM_AUTH_COOKIE_SECURE` | 走 HTTPS 时置 true |
| `WECOM_AUTH_SESSION_TTL` | 登录态有效期，默认 7d |
| `WECOM_OPENCLAW_HEALTH_INTERVAL` | 渠道存活探测间隔，默认 60s |
| `WECOM_OPENCLAW_ENABLED` / `_CLI` / `_INBOUND_TOKEN` | 个人微信收发 + 企微出站 |
| `WECOM_OPENCLAW_WECHAT_CHANNEL` / `_ACCOUNT` | 渠道 id 与多账号 |
| `WECOM_ARCHIVE_ENABLED` / `_SECRET` / `_PRIVATE_KEY_V1` / `_SDK_LIB` | 企微入站（会话存档） |
| `WECOM_ARCHIVE_MEMBER_USERIDS` | 消息方向判定 |
| `WECOM_ARCHIVE_EXTERNAL_ONLY` / `_INCLUDE_ROOMS` | 入站过滤 |
| `WECOM_CONTACT_ENABLED` / `_SECRET` / `_CORP_ID` | 朋友圈与群发 |
| `WECOM_MOMENT_STATS_AUTO` / `_INTERVAL` / `WECOM_MOMENT_LOOKBACK_DAYS` | 互动数据自动刷新 |

---

## 七、上线前检查清单

- [ ] 运营台自检**没有红色项**
- [ ] **已设置 `WECOM_ACCESS_CODE`**，且服务不是以明文 HTTP 暴露在公网
- [ ] 网关跑在常开的机器上，不依赖某台会关机的办公电脑
- [ ] 演练模式验证过一轮后才关闭 `dry-run`
- [ ] 存档已取得成员与客户授权，欢迎语/服务协议里已告知客户消息会被企业系统处理
- [ ] 凭据都在环境变量里，没有写进代码或配置文件提交
- [ ] `data/bridge/` 有磁盘配额与备份策略；`max-messages` 设成合适值
- [ ] 群发与朋友圈先用内部测试群/测试客户跑通，再对真实客户放开
- [ ] 明确告知一线：企微朋友圈与群发都需要**在企业微信里手动确认**才会发出

---

## 八、接口速查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ops/preflight?probe=true` | 上线自检 |
| GET | `/api/ops/capabilities` | 能力边界 |
| POST | `/api/ops/moments/media` | 上传图片取 media_id（multipart） |
| POST | `/api/ops/moments/tasks` | 创建朋友圈发表任务 |
| GET | `/api/ops/moments/tasks/{jobId}` | 查任务创建结果 |
| GET | `/api/ops/moments?days=7` | 发表记录 |
| GET | `/api/ops/moments/stats` | 点赞/评论汇总（读缓存） |
| POST | `/api/ops/moments/stats/refresh` | 立即刷新全部 |
| POST | `/api/ops/moments/{momentId}/stats/refresh` | 刷新单条 |
| GET | `/api/ops/groups` | 客户群列表 |
| POST | `/api/ops/groups/messages` | 客户群群发图文 |

收件箱接口见 [`docs/openclaw-inbox-setup.md`](openclaw-inbox-setup.md)。
