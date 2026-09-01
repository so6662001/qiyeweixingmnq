package com.wecom.bridge.openclaw;

import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.service.InboxRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 处理 OpenClaw 插件回推的个人微信一对一私聊消息。
 */
@Service
public class OpenClawInboundService {

    private static final Logger log = LoggerFactory.getLogger(OpenClawInboundService.class);

    /** 秒级时间戳阈值：小于该值认为传的是秒。 */
    private static final long SECONDS_THRESHOLD = 100_000_000_000L;

    private final BridgeProperties properties;
    private final InboxRecorder recorder;

    public OpenClawInboundService(BridgeProperties properties, InboxRecorder recorder) {
        this.properties = properties;
        this.recorder = recorder;
    }

    /**
     * 校验插件带来的共享密钥。未配置密钥时一律拒绝，避免暴露成开放写接口。
     */
    public boolean authorized(String presented) {
        String expected = properties.getOpenclaw().getInboundToken();
        if (expected.isBlank() || presented == null || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    public boolean inboundEnabled() {
        return properties.isEnabled() && properties.getOpenclaw().isInboundReady();
    }

    /**
     * @return 入库后的消息；重复消息或被过滤（群聊）时返回空
     */
    public Optional<InboxMessage> accept(OpenClawInboundRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        String peerId = trim(request.getPeerId());
        if (peerId.isEmpty()) {
            throw new IllegalArgumentException("缺少 peer_id");
        }
        if (Boolean.TRUE.equals(request.getGroup())) {
            log.debug("跳过群聊消息 peer={}", peerId);
            return Optional.empty();
        }

        String msgtype = trim(request.getMsgtype());
        String content = request.getContent() == null ? "" : request.getContent();
        MessageDirection direction = Boolean.TRUE.equals(request.getOutbound())
                ? MessageDirection.OUTBOUND : MessageDirection.INBOUND;

        return recorder.record(InboxRecorder.InboundRecord
                .builder(InboxChannel.WECHAT, peerId)
                .messageId(trim(request.getMessageId()))
                .peerName(trim(request.getPeerName()))
                // 个人微信的回复目标就是对端 id
                .openclawTarget(peerId)
                .direction(direction)
                .msgtype(msgtype.isEmpty() ? "text" : msgtype)
                .content(content)
                .mediaId(emptyToNull(trim(request.getMediaId())))
                .createTime(normalizeTimestamp(request.getTimestamp()))
                .build());
    }

    private static long normalizeTimestamp(Long value) {
        if (value == null || value <= 0) {
            return System.currentTimeMillis();
        }
        return value < SECONDS_THRESHOLD ? value * 1000L : value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
