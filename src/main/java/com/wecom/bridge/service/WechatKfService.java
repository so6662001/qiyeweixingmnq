package com.wecom.bridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.wecom.bridge.client.WecomApiClient;
import com.wecom.bridge.client.WecomApiException;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.store.InboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 微信客服通道：接收微信用户消息并回复。
 *
 * <p>这是官方开放给企业、可与微信个人用户双向实时收发的通道，
 * 全部走 qyapi 官方接口（sync_msg / send_msg / service_state），不涉及任何非官方协议。</p>
 *
 * <p>官方约束：客户最后一条消息 48 小时内才允许主动发消息；人工回复需会话处于「由人工接待」。</p>
 */
@Service
public class WechatKfService implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(WechatKfService.class);

    /** 微信客服消息来源：3=客户发送，4=系统推送，5=接待人员发送。 */
    private static final int ORIGIN_CUSTOMER = 3;
    private static final int ORIGIN_SYSTEM = 4;
    private static final int ORIGIN_SERVICER = 5;

    /** 接待状态：3=由人工接待。 */
    private static final int STATE_HUMAN = 3;

    private static final int MAX_SYNC_ROUNDS = 20;
    private static final int NAME_BATCH = 100;

    private final BridgeProperties properties;
    private final WecomApiClient apiClient;
    private final InboxRecorder recorder;
    private final InboxStore store;

    public WechatKfService(BridgeProperties properties,
                           WecomApiClient apiClient,
                           InboxRecorder recorder,
                           InboxStore store) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.recorder = recorder;
        this.store = store;
    }

    @Override
    public InboxChannel channel() {
        return InboxChannel.WECHAT_KF;
    }

    @Override
    public boolean configured() {
        return properties.isEnabled() && properties.getKf().isConfigured();
    }

    /**
     * 处理 kf_msg_or_event 回调：拿事件里的一次性 token 去增量拉取消息。
     *
     * @return 本次新入库的消息条数
     */
    public int handleCallbackEvent(String token, String openKfid) {
        if (!configured()) {
            log.warn("收到微信客服回调，但通道未配置完整，忽略");
            return 0;
        }
        return syncMessages(token, openKfid);
    }

    /**
     * 增量拉取消息。游标持久化在本地，避免重复拉取或漏消息。
     */
    public int syncMessages(String token, String openKfid) {
        String cursorKey = openKfid == null || openKfid.isBlank() ? "__all__" : openKfid;
        String cursor = store.cursor(cursorKey);
        int imported = 0;

        for (int round = 0; round < MAX_SYNC_ROUNDS; round++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cursor", cursor == null ? "" : cursor);
            body.put("limit", properties.getKf().getSyncLimit());
            body.put("voice_format", 0);
            if (token != null && !token.isBlank()) {
                body.put("token", token);
            }
            if (openKfid != null && !openKfid.isBlank()) {
                body.put("open_kfid", openKfid);
            }

            JsonNode response = apiClient.post("/cgi-bin/kf/sync_msg", properties.getKf().getSecret(), body);
            JsonNode list = response.path("msg_list");
            Set<String> unknownCustomers = new HashSet<>();

            if (list.isArray()) {
                for (JsonNode item : list) {
                    if (importMessage(item)) {
                        imported++;
                        String externalUserId = item.path("external_userid").asText("");
                        if (!externalUserId.isBlank()) {
                            unknownCustomers.add(externalUserId);
                        }
                    }
                }
            }

            String next = response.path("next_cursor").asText("");
            if (!next.isBlank()) {
                cursor = next;
                store.saveCursor(cursorKey, next);
            }

            fillCustomerNames(unknownCustomers);

            boolean hasMore = response.path("has_more").asInt(0) == 1;
            if (!hasMore) {
                break;
            }
        }

        if (imported > 0) {
            log.info("微信客服新增 {} 条消息", imported);
        }
        return imported;
    }

    private boolean importMessage(JsonNode item) {
        String externalUserId = item.path("external_userid").asText("");
        String openKfid = item.path("open_kfid").asText("");
        int origin = item.path("origin").asInt(ORIGIN_CUSTOMER);
        String msgtype = item.path("msgtype").asText("text");
        long sendTime = item.path("send_time").asLong(0L) * 1000L;

        // 系统推送的进入会话等事件里没有 external_userid 时，尝试从 event 中取
        if (externalUserId.isBlank()) {
            externalUserId = item.path("event").path("external_userid").asText("");
        }
        if (externalUserId.isBlank()) {
            log.debug("跳过缺少 external_userid 的客服消息");
            return false;
        }

        MessageDirection direction = switch (origin) {
            case ORIGIN_SERVICER -> MessageDirection.OUTBOUND;
            case ORIGIN_SYSTEM -> MessageDirection.SYSTEM;
            default -> MessageDirection.INBOUND;
        };

        Content content = extractContent(item, msgtype);

        return recorder.recordInbound(InboxRecorder.InboundRecord
                .builder(InboxChannel.WECHAT_KF, externalUserId)
                .messageId(item.path("msgid").asText(""))
                .openKfid(openKfid)
                .servicerUserid(item.path("servicer_userid").asText(""))
                .direction(direction)
                .msgtype(content.msgtype())
                .content(content.text())
                .mediaId(content.mediaId())
                .createTime(sendTime)
                .build()).isPresent();
    }

    private Content extractContent(JsonNode item, String msgtype) {
        return switch (msgtype) {
            case "text" -> new Content("text", item.path("text").path("content").asText(""), null);
            case "image" -> new Content("image", "[图片]", item.path("image").path("media_id").asText(null));
            case "voice" -> new Content("voice", "[语音]", item.path("voice").path("media_id").asText(null));
            case "video" -> new Content("video", "[视频]", item.path("video").path("media_id").asText(null));
            case "file" -> new Content("file", "[文件]", item.path("file").path("media_id").asText(null));
            case "location" -> new Content("location",
                    "[位置] " + item.path("location").path("name").asText("")
                            + " " + item.path("location").path("address").asText(""), null);
            case "link" -> new Content("link",
                    "[链接] " + item.path("link").path("title").asText("")
                            + " " + item.path("link").path("url").asText(""), null);
            case "miniprogram" -> new Content("miniprogram",
                    "[小程序] " + item.path("miniprogram").path("title").asText(""), null);
            case "business_card" -> new Content("business_card",
                    "[名片] " + item.path("business_card").path("userid").asText(""), null);
            case "msgmenu" -> new Content("text", item.path("msgmenu").path("head_content").asText("[菜单消息]"), null);
            case "event" -> new Content("event", describeEvent(item.path("event")), null);
            default -> new Content(msgtype, "[" + msgtype + "]", null);
        };
    }

    private String describeEvent(JsonNode event) {
        String type = event.path("event_type").asText("");
        return switch (type) {
            case "enter_session" -> "客户进入会话";
            case "msg_send_fail" -> "消息发送失败：" + event.path("fail_msgid").asText("");
            case "servicer_status_change" -> "接待人员状态变更";
            case "session_status_change" -> "会话状态变更";
            case "user_recall_msg" -> "客户撤回了一条消息";
            case "servicer_recall_msg" -> "接待人员撤回了一条消息";
            case "reject_customer_msg_switch_change" -> "拒收客户消息开关变更";
            default -> type.isBlank() ? "会话事件" : "会话事件：" + type;
        };
    }

    /**
     * 批量补齐客户昵称，避免列表里只显示一串 external_userid。
     */
    private void fillCustomerNames(Set<String> externalUserIds) {
        List<String> pending = new ArrayList<>();
        for (String id : externalUserIds) {
            InboxConversation conversation = store
                    .findConversation(InboxConversation.buildId(InboxChannel.WECHAT_KF, id))
                    .orElse(null);
            if (conversation != null && (conversation.getPeerName() == null || conversation.getPeerName().isBlank())) {
                pending.add(id);
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        for (int start = 0; start < pending.size(); start += NAME_BATCH) {
            List<String> batch = pending.subList(start, Math.min(start + NAME_BATCH, pending.size()));
            try {
                JsonNode response = apiClient.post("/cgi-bin/kf/customer/batchget",
                        properties.getKf().getSecret(),
                        Map.of("external_userid_list", batch, "need_enter_session_context", 0));
                JsonNode customers = response.path("customer_list");
                if (!customers.isArray()) {
                    continue;
                }
                for (JsonNode customer : customers) {
                    String id = customer.path("external_userid").asText("");
                    String nickname = customer.path("nickname").asText("");
                    if (id.isBlank() || nickname.isBlank()) {
                        continue;
                    }
                    InboxConversation conversation = store
                            .upsertConversation(InboxChannel.WECHAT_KF, id, nickname, null);
                    recorder.publishConversation(conversation);
                }
            } catch (WecomApiException e) {
                // 缺少「客户基础信息」权限时会失败，不影响消息收发
                log.debug("补齐客户昵称失败: {}", e.getMessage());
                return;
            }
        }
    }

    @Override
    public SendOutcome send(InboxConversation conversation, String text) {
        if (!configured()) {
            return SendOutcome.failed("微信客服通道未配置：需要 corp-id、客服 secret、回调 token 与 EncodingAESKey");
        }
        if (conversation.getOpenKfid() == null || conversation.getOpenKfid().isBlank()) {
            return SendOutcome.failed("该会话缺少 open_kfid，无法回复");
        }

        String windowError = checkReplyWindow(conversation);
        if (windowError != null) {
            return SendOutcome.failed(windowError);
        }

        try {
            ensureHumanServicer(conversation);
        } catch (WecomApiException e) {
            log.info("转人工接待失败（继续尝试发送）: {}", e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", conversation.getPeerId());
        body.put("open_kfid", conversation.getOpenKfid());
        body.put("msgtype", "text");
        body.put("text", Map.of("content", text));

        try {
            JsonNode response = apiClient.post("/cgi-bin/kf/send_msg", properties.getKf().getSecret(), body);
            return SendOutcome.ok(response.path("msgid").asText(""));
        } catch (WecomApiException e) {
            return SendOutcome.failed(explain(e));
        }
    }

    /**
     * 官方规则：客户最后一条消息超过 48 小时后不能主动发消息。
     * 这里提前拦截并给出可读原因，而不是把官方错误码抛给使用者。
     */
    private String checkReplyWindow(InboxConversation conversation) {
        long lastInbound = conversation.getLastInboundAt();
        if (lastInbound <= 0) {
            return null;
        }
        long windowMillis = properties.getKf().getReplyWindow().toMillis();
        long elapsed = System.currentTimeMillis() - lastInbound;
        if (elapsed > windowMillis) {
            long hours = elapsed / 3_600_000L;
            return "超出官方可回复窗口（客户最后消息距今约 " + hours + " 小时，上限 "
                    + properties.getKf().getReplyWindow().toHours() + " 小时）。请等客户再次发起会话。";
        }
        return null;
    }

    /**
     * 把会话转为「由人工接待」，否则人工消息可能无法发出。
     */
    public void ensureHumanServicer(InboxConversation conversation) {
        String servicer = properties.getKf().getServicerUserid();
        if (servicer.isBlank() || !properties.getKf().isAutoTakeOver()) {
            return;
        }
        if (conversation.getServiceState() != null && conversation.getServiceState() == STATE_HUMAN
                && servicer.equals(conversation.getServicerUserid())) {
            return;
        }
        transferToHuman(conversation, servicer);
    }

    public void transferToHuman(InboxConversation conversation, String servicerUserid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("open_kfid", conversation.getOpenKfid());
        body.put("external_userid", conversation.getPeerId());
        body.put("service_state", STATE_HUMAN);
        body.put("servicer_userid", servicerUserid);
        apiClient.post("/cgi-bin/kf/service_state/trans", properties.getKf().getSecret(), body);
        conversation.setServiceState(STATE_HUMAN);
        conversation.setServicerUserid(servicerUserid);
        recorder.publishConversation(conversation);
    }

    public void refreshServiceState(InboxConversation conversation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("open_kfid", conversation.getOpenKfid());
        body.put("external_userid", conversation.getPeerId());
        JsonNode response = apiClient.post("/cgi-bin/kf/service_state/get", properties.getKf().getSecret(), body);
        conversation.setServiceState(response.path("service_state").asInt(0));
        String servicer = response.path("servicer_userid").asText("");
        if (!servicer.isBlank()) {
            conversation.setServicerUserid(servicer);
        }
        recorder.publishConversation(conversation);
    }

    /**
     * 客服账号列表，用于确认回调与账号配置是否打通。
     */
    public List<Map<String, String>> listAccounts() {
        JsonNode response = apiClient.post("/cgi-bin/kf/account/list",
                properties.getKf().getSecret(), Map.of("offset", 0, "limit", 100));
        List<Map<String, String>> accounts = new ArrayList<>();
        JsonNode list = response.path("account_list");
        if (list.isArray()) {
            for (JsonNode account : list) {
                accounts.add(Map.of(
                        "open_kfid", account.path("open_kfid").asText(""),
                        "name", account.path("name").asText("")
                ));
            }
        }
        return accounts;
    }

    private String explain(WecomApiException e) {
        return switch (e.getErrcode()) {
            case 95004 -> "客户已超过 48 小时未发消息，官方不允许主动发送";
            case 95011 -> "该客户已被限制发送消息";
            case 60011 -> "当前应用无权操作该客服账号，请检查可调用范围";
            case 301056 -> "会话未处于人工接待状态，请先接入人工";
            default -> e.getErrmsg() == null || e.getErrmsg().isBlank()
                    ? "官方接口返回错误码 " + e.getErrcode()
                    : "官方接口错误 " + e.getErrcode() + "：" + e.getErrmsg();
        };
    }

    private record Content(String msgtype, String text, String mediaId) {
    }
}
