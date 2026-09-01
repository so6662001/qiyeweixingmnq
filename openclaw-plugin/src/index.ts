import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";

/**
 * 把 OpenClaw 收到的个人微信一对一私聊转发到统一收件箱。
 *
 * 设计要点：
 * - 转发是旁路的，失败只记日志，不影响 OpenClaw 自身的消息流。
 * - 默认接管会话（before_dispatch 返回 handled），因为回复由人工在收件箱里录入，
 *   不能让大模型抢先自动回客户。
 */

type ForwarderConfig = {
  endpoint?: string;
  token?: string;
  channels?: string[];
  claimInbound?: boolean;
  mirrorOutbound?: boolean;
  timeoutMs?: number;
};

const DEFAULT_CHANNELS = ["openclaw-weixin", "weixin", "wechat"];
const DEFAULT_TIMEOUT_MS = 5000;

/** 不同 OpenClaw 版本的字段名略有差异，这里按优先级取第一个非空值。 */
function firstString(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim() !== "") {
      return value.trim();
    }
  }
  return "";
}

function firstNumber(...values: unknown[]): number | undefined {
  for (const value of values) {
    if (typeof value === "number" && Number.isFinite(value) && value > 0) {
      return value;
    }
  }
  return undefined;
}

function firstBoolean(...values: unknown[]): boolean | undefined {
  for (const value of values) {
    if (typeof value === "boolean") {
      return value;
    }
  }
  return undefined;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : {};
}

export default definePluginEntry({
  id: "wecom-inbox-forwarder",
  name: "统一收件箱转发",
  description: "把个人微信私聊转发到统一收件箱，并接管会话交由人工回复。",

  register(api) {
    const config = (api.pluginConfig ?? {}) as ForwarderConfig;
    const endpoint = firstString(config.endpoint);
    const token = firstString(config.token);
    const channels = (config.channels && config.channels.length > 0 ? config.channels : DEFAULT_CHANNELS)
      .map((item) => item.toLowerCase());
    const claimInbound = config.claimInbound !== false;
    const mirrorOutbound = config.mirrorOutbound !== false;
    const timeoutMs = config.timeoutMs && config.timeoutMs > 0 ? config.timeoutMs : DEFAULT_TIMEOUT_MS;

    if (!endpoint || !token) {
      api.logger.warn(
        "wecom-inbox-forwarder 未配置 endpoint/token，插件已加载但不会转发任何消息",
      );
      return;
    }

    const matchesChannel = (channelId: string): boolean => {
      if (!channelId) {
        return false;
      }
      return channels.includes(channelId.toLowerCase());
    };

    const isGroup = (event: Record<string, unknown>, ctx: Record<string, unknown>): boolean => {
      const flag = firstBoolean(event.isGroup, event.group, ctx.isGroup, ctx.isGroupMessage);
      if (flag !== undefined) {
        return flag;
      }
      // 兜底：部分渠道用 threadId 区分群会话
      const conversationType = firstString(event.conversationType, ctx.conversationType);
      return conversationType === "group";
    };

    const resolvePeerId = (event: Record<string, unknown>, ctx: Record<string, unknown>): string => {
      const direct = firstString(
        event.senderId,
        ctx.senderId,
        event.peerId,
        ctx.peerId,
        event.threadId,
        ctx.threadId,
      );
      if (direct) {
        return direct;
      }
      // sessionKey 形如 "<channel>:<peer>"，取最后一段
      const sessionKey = firstString(event.sessionKey, ctx.sessionKey);
      if (sessionKey.includes(":")) {
        return sessionKey.slice(sessionKey.lastIndexOf(":") + 1);
      }
      return "";
    };

    const forward = async (
      rawEvent: unknown,
      rawCtx: unknown,
      outbound: boolean,
    ): Promise<void> => {
      const event = asRecord(rawEvent);
      const ctx = asRecord(rawCtx);

      const channelId = firstString(event.channelId, ctx.channelId, event.channel, ctx.channel);
      if (!matchesChannel(channelId)) {
        return;
      }
      if (isGroup(event, ctx)) {
        return;
      }

      const peerId = resolvePeerId(event, ctx);
      if (!peerId) {
        api.logger.debug("跳过一条无法确定对端的消息");
        return;
      }

      const payload = {
        channel: channelId,
        account_id: firstString(event.accountId, ctx.accountId),
        message_id: firstString(event.messageId, ctx.messageId),
        peer_id: peerId,
        peer_name: firstString(event.senderName, ctx.senderName, event.senderDisplayName),
        msgtype: firstString(event.messageType, event.msgtype) || "text",
        content: firstString(event.content, event.text, event.body),
        media_id: firstString(event.mediaId, event.mediaUrl),
        timestamp: firstNumber(event.timestamp, event.createdAt, ctx.timestamp) ?? Date.now(),
        group: false,
        outbound,
      };

      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetch(endpoint, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify(payload),
          signal: controller.signal,
        });
        if (!response.ok) {
          api.logger.warn(`转发到收件箱失败：HTTP ${response.status}`);
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        api.logger.warn(`转发到收件箱异常：${message}`);
      } finally {
        clearTimeout(timer);
      }
    };

    api.on("message_received", async (event, ctx) => {
      await forward(event, ctx, false);
    });

    if (mirrorOutbound) {
      api.on("message_sent", async (event, ctx) => {
        await forward(event, ctx, true);
      });
    }

    if (claimInbound) {
      // 回复由人工在收件箱里录入，这里接管掉，避免大模型自动回客户
      api.on("before_dispatch", (event, ctx) => {
        const channelId = firstString(
          asRecord(event).channelId,
          asRecord(ctx).channelId,
          asRecord(event).channel,
        );
        if (!matchesChannel(channelId)) {
          return undefined;
        }
        return { handled: true };
      });
    }

    api.logger.info(
      `wecom-inbox-forwarder 已启用：渠道 ${channels.join(",")}，接管会话 ${claimInbound}`,
    );
  },
});
