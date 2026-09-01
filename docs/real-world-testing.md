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
| 4 | 在手机上用该微信号回一条 | 收件箱里出现一条**出站**镜像消息（`mirrorOutbound`） |
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
