# wecom-inbox-forwarder（OpenClaw 插件）

把 OpenClaw 收到的**个人微信一对一私聊**转发到统一收件箱，并**接管会话**，
让人工在收件箱里录入回复，而不是由大模型自动回客户。

## 前置

1. 安装 OpenClaw 并登录腾讯官方微信渠道插件：

```bash
openclaw plugins install "@tencent-weixin/openclaw-weixin"
openclaw config set plugins.entries.openclaw-weixin.enabled true
openclaw channels login --channel openclaw-weixin   # 扫码授权
openclaw gateway restart
```

2. 统一收件箱服务已启动，并配置了入站密钥：

```bash
export WECOM_BRIDGE_ENABLED=true
export WECOM_OPENCLAW_ENABLED=true
export WECOM_OPENCLAW_INBOUND_TOKEN=<与下面 token 一致的随机串>
```

## 构建与安装

```bash
cd openclaw-plugin
npm install
npm run build

openclaw plugins install --link . --force
openclaw plugins enable wecom-inbox-forwarder
```

## 配置

在 `~/.openclaw/openclaw.json` 里配置插件，并**开启会话钩子访问权限**
（消息类钩子需要显式授权）：

```json
{
  "plugins": {
    "entries": {
      "wecom-inbox-forwarder": {
        "enabled": true,
        "hooks": { "allowConversationAccess": true },
        "config": {
          "endpoint": "http://127.0.0.1:8000/api/openclaw/inbound",
          "token": "与 WECOM_OPENCLAW_INBOUND_TOKEN 相同的随机串",
          "channels": ["openclaw-weixin"],
          "claimInbound": true,
          "mirrorOutbound": true,
          "timeoutMs": 5000
        }
      }
    }
  }
}
```

重启网关后生效：

```bash
openclaw gateway restart
openclaw plugins list
```

## 行为

| 钩子 | 作用 |
| --- | --- |
| `message_received` | 客户发来的私聊 → POST 到收件箱 |
| `message_sent` | 本方发出的消息（含手机端）→ 同步到收件箱，保持记录完整 |
| `before_dispatch` | 返回 `{ handled: true }` 接管会话，阻止大模型自动回复 |

- 只处理一对一私聊；群聊消息会被跳过。
- 转发是旁路的：收件箱不可达时只记日志，不影响 OpenClaw 自身运行。
- 出站发送不走本插件，由收件箱服务调用 `openclaw message send`。

## 排查

```bash
openclaw plugins list
openclaw channels status --probe
openclaw logs tail
```

- 日志出现「未配置 endpoint/token」：插件配置没生效，检查 `plugins.entries` 路径。
- 收件箱返回 401：`token` 与服务端 `inbound-token` 不一致。
- 收件箱返回 503：服务端未开启 `wecom.bridge.openclaw.enabled` 或没配 `inbound-token`。
- 客户收到了大模型的自动回复：`claimInbound` 没生效，确认 `allowConversationAccess` 已开启。
