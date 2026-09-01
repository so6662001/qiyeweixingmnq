package com.wecom.bridge.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.service.InboxRecorder;
import com.wecom.bridge.store.InboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 企业微信入站：从会话内容存档增量拉取消息。
 *
 * <p>seq 游标持久化在本地，重启不会重复拉取或漏消息；msgid 去重防止重投。</p>
 */
@Service
public class SessionArchiveService {

    private static final Logger log = LoggerFactory.getLogger(SessionArchiveService.class);

    /** 游标键。 */
    static final String SEQ_CURSOR_KEY = "wecom_archive_seq";

    /** external_userid 的常见形态：wm/wo 开头的长串。仅在未显式配置成员名单时作为兜底判断。 */
    private static final Pattern EXTERNAL_ID = Pattern.compile("^w[mo][A-Za-z0-9_-]{10,}$");

    private static final int MAX_ROUNDS = 20;

    private final BridgeProperties properties;
    private final WeWorkFinanceSdk sdk;
    private final ArchiveRandomKeyDecryptor keyDecryptor;
    private final InboxRecorder recorder;
    private final InboxStore store;
    private final ObjectMapper objectMapper;

    public SessionArchiveService(BridgeProperties properties,
                                 WeWorkFinanceSdk sdk,
                                 ArchiveRandomKeyDecryptor keyDecryptor,
                                 InboxRecorder recorder,
                                 InboxStore store,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.sdk = sdk;
        this.keyDecryptor = keyDecryptor;
        this.recorder = recorder;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public boolean configured() {
        return properties.isEnabled() && properties.getArchive().isConfigured();
    }

    public boolean sdkReady() {
        return sdk.available();
    }

    public long currentSeq() {
        String cursor = store.cursor(SEQ_CURSOR_KEY);
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 确保 SDK 已初始化。未部署官方 SDK 时抛出可读异常。
     */
    public void ensureInitialized() {
        if (!configured()) {
            throw new ArchiveSdkException("会话存档未配置：需要 corp-id、存档 Secret 与私钥");
        }
        if (!sdk.available()) {
            BridgeProperties.Archive archive = properties.getArchive();
            sdk.init(archive.getCorpId(), archive.getSecret());
        }
    }

    /**
     * 拉取一轮增量消息。
     *
     * @return 本次新入库的条数
     */
    public int pull() {
        ensureInitialized();

        BridgeProperties.Archive archive = properties.getArchive();
        long seq = currentSeq();
        int imported = 0;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            String raw = sdk.getChatData(seq, archive.getBatchLimit(), archive.getProxy(),
                    archive.getProxyPasswd(), archive.getRequestTimeoutSeconds());

            JsonNode response;
            try {
                response = objectMapper.readTree(raw);
            } catch (IOException e) {
                throw new ArchiveSdkException("解析 GetChatData 响应失败", e);
            }

            int errcode = response.path("errcode").asInt(0);
            if (errcode != 0) {
                throw new ArchiveSdkException("GetChatData 返回错误: " + response.path("errmsg").asText(""), errcode);
            }

            JsonNode chatdata = response.path("chatdata");
            if (!chatdata.isArray() || chatdata.isEmpty()) {
                break;
            }

            long maxSeq = seq;
            for (JsonNode item : chatdata) {
                long itemSeq = item.path("seq").asLong(0L);
                maxSeq = Math.max(maxSeq, itemSeq);
                try {
                    if (importItem(item, itemSeq)) {
                        imported++;
                    }
                } catch (ArchiveSdkException e) {
                    // 单条失败不能中断整批，否则游标卡死
                    log.warn("跳过一条存档消息 seq={}: {}", itemSeq, e.getMessage());
                }
            }

            if (maxSeq > seq) {
                seq = maxSeq;
                store.saveCursor(SEQ_CURSOR_KEY, Long.toString(seq));
            }

            if (chatdata.size() < archive.getBatchLimit()) {
                break;
            }
        }

        if (imported > 0) {
            log.info("会话存档新增 {} 条消息，当前 seq={}", imported, seq);
        }
        return imported;
    }

    private boolean importItem(JsonNode item, long itemSeq) {
        String publicKeyVer = item.path("publickey_ver").asText("");
        String encryptRandomKey = item.path("encrypt_random_key").asText("");
        String encryptChatMsg = item.path("encrypt_chat_msg").asText("");
        if (encryptChatMsg.isBlank()) {
            return false;
        }

        String randomKey = keyDecryptor.decrypt(publicKeyVer, encryptRandomKey);
        String plain = sdk.decryptData(randomKey, encryptChatMsg);
        ArchiveMessageParser.ParsedArchiveMessage parsed = ArchiveMessageParser.parse(objectMapper, plain);

        BridgeProperties.Archive archive = properties.getArchive();
        if (parsed.isRoomChat() && !archive.isIncludeRoomChats()) {
            return false;
        }
        if (archive.isExternalOnly() && !isExternalConversation(parsed)) {
            return false;
        }

        boolean fromMember = isOwnMember(parsed.from());
        String peerId = fromMember ? parsed.firstTo() : parsed.from();
        String ownerUserid = fromMember ? parsed.from() : parsed.firstTo();
        if (peerId == null || peerId.isBlank()) {
            log.debug("跳过无法确定对端的存档消息 seq={}", itemSeq);
            return false;
        }

        MessageDirection direction = fromMember ? MessageDirection.OUTBOUND : MessageDirection.INBOUND;
        String mappedTarget = archive.getTargetMapping().get(peerId);

        return recorder.record(InboxRecorder.InboundRecord
                .builder(InboxChannel.WECOM_ARCHIVE, peerId)
                .messageId(parsed.msgid())
                .ownerUserid(ownerUserid)
                .roomId(parsed.roomId())
                .openclawTarget(mappedTarget)
                .direction(direction)
                .msgtype("recall".equals(parsed.action()) ? "revoke" : parsed.msgtype())
                .content(parsed.content())
                .mediaId(parsed.mediaId())
                .createTime(parsed.msgtime())
                .archiveSeq(itemSeq)
                .build()).isPresent();
    }

    /**
     * 判断 from 是否本企业成员。配置了成员名单就以名单为准，否则按 external_userid 形态兜底。
     */
    boolean isOwnMember(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        var members = properties.getArchive().getMemberUserids();
        if (!members.isEmpty()) {
            return members.contains(userId);
        }
        return !EXTERNAL_ID.matcher(userId).matches();
    }

    private boolean isExternalConversation(ArchiveMessageParser.ParsedArchiveMessage parsed) {
        if (parsed.externalMessage()) {
            return true;
        }
        return EXTERNAL_ID.matcher(parsed.from()).matches()
                || EXTERNAL_ID.matcher(parsed.firstTo()).matches();
    }
}
