package com.wecom.bridge.service;

import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.openclaw.OpenClawGateway;
import org.springframework.stereotype.Service;

/**
 * 个人微信与企业微信的出站发送：都通过 OpenClaw 发出，行为完全一致。
 *
 * <p>个人微信的发送目标就是入站带回的 peer id；企业微信的存档身份
 * （userid / external_userid）不是 OpenClaw 目标，需要映射后才能发送。</p>
 */
@Service
public class OpenClawSender implements ChannelSender {

    private final OpenClawGateway gateway;
    private final BridgeProperties properties;

    public OpenClawSender(OpenClawGateway gateway, BridgeProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @Override
    public boolean supports(InboxChannel channel) {
        return channel.sendsViaOpenClaw();
    }

    @Override
    public boolean ready() {
        return gateway.outboundReady();
    }

    @Override
    public SendOutcome send(InboxConversation conversation, String text) {
        return send(conversation, text, null);
    }

    /**
     * 带媒体的发送（图片 / 文件）。媒体可以是本地路径或 URL，由 OpenClaw 负责上传。
     */
    public SendOutcome send(InboxConversation conversation, String text, String mediaUrl) {
        String target = resolveTarget(conversation);
        if (target == null || target.isBlank()) {
            return SendOutcome.failed(conversation.getChannel() == InboxChannel.WECOM_ARCHIVE
                    ? "该企微会话还没有对应的 OpenClaw 发送目标。会话存档里的 "
                    + conversation.getPeerId() + " 不能直接当作发送目标，"
                    + "请在 wecom.bridge.archive.target-mapping 里配置映射，或在页面上补填。"
                    : "该会话缺少 OpenClaw 发送目标");
        }

        OpenClawGateway.SendResult result = gateway.send(target, text, mediaUrl);
        return result.ok()
                ? SendOutcome.ok(result.messageId())
                : SendOutcome.failed(result.errorMessage());
    }

    /**
     * 解析发送目标：会话上已有的优先，其次查配置映射，个人微信最后回落到 peerId。
     */
    public String resolveTarget(InboxConversation conversation) {
        if (conversation.getOpenclawTarget() != null && !conversation.getOpenclawTarget().isBlank()) {
            return conversation.getOpenclawTarget();
        }
        String mapped = properties.getArchive().getTargetMapping().get(conversation.getPeerId());
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        return conversation.getChannel() == InboxChannel.WECHAT ? conversation.getPeerId() : null;
    }
}
