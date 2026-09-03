package com.wecom.bridge.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.service.InboxRecorder;
import com.wecom.bridge.store.InboxStore;
import com.wecom.bridge.support.FakeWeWorkFinanceSdk;
import com.wecom.bridge.web.InboxRealtimeHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionArchiveServiceTest {

    private static final String RANDOM_KEY = "0123456789abcdef0123456789abcdef";
    private static final String MEMBER = "huanghaoting";
    private static final String CUSTOMER = "wmErxtDgAA9AW32YyyuYRimKr7D1KWlw";

    private static KeyPair keyPair;

    private BridgeProperties properties;
    private FakeWeWorkFinanceSdk sdk;
    private InboxStore store;
    private SessionArchiveService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        if (keyPair == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();
        }

        ObjectMapper objectMapper = new ObjectMapper();
        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.setDataDir(tempDir.toString());
        properties.getArchive().setEnabled(true);
        properties.getArchive().setCorpId("ww-corp-1");
        properties.getArchive().setSecret("archive-secret");
        properties.getArchive().setPrivateKeys(Map.of("1", privateKeyPem()));
        properties.getArchive().setMemberUserids(new java.util.LinkedHashSet<>(List.of(MEMBER)));

        sdk = new FakeWeWorkFinanceSdk().expectRandomKey(RANDOM_KEY);
        store = new InboxStore(properties, objectMapper);
        InboxRecorder recorder = new InboxRecorder(store, new InboxRealtimeHub(objectMapper));
        service = new SessionArchiveService(properties, sdk,
                new ArchiveRandomKeyDecryptor(properties), recorder, store, objectMapper);
    }

    @Test
    void 客户发来的消息入库为入站并生成会话() {
        queue("CIPHER-1", 101L, plaintext("m-1", "send", CUSTOMER, MEMBER, "text", "\"text\":{\"content\":\"方管有现货吗？\"}"));

        assertThat(service.pull()).isEqualTo(1);

        assertThat(sdk.initCorpId()).isEqualTo("ww-corp-1");
        assertThat(sdk.initSecret()).isEqualTo("archive-secret");

        String conversationId = InboxConversation.buildId(InboxChannel.WECOM_ARCHIVE, CUSTOMER);
        InboxConversation conversation = store.findConversation(conversationId).orElseThrow();
        assertThat(conversation.getOwnerUserid()).isEqualTo(MEMBER);
        assertThat(conversation.getUnread()).isEqualTo(1);
        // 存档身份不是发送目标，未配置映射时应为空，页面据此提示补填
        assertThat(conversation.getOpenclawTarget()).isNull();

        List<InboxMessage> messages = store.listMessages(conversationId, 10);
        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.getDirection()).isEqualTo(MessageDirection.INBOUND);
            assertThat(message.getContent()).isEqualTo("方管有现货吗？");
            assertThat(message.getArchiveSeq()).isEqualTo(101L);
        });
    }

    @Test
    void 成员发出的消息入库为出站() {
        queue("CIPHER-2", 102L, plaintext("m-2", "send", MEMBER, CUSTOMER, "text", "\"text\":{\"content\":\"现货充足\"}"));

        service.pull();

        String conversationId = InboxConversation.buildId(InboxChannel.WECOM_ARCHIVE, CUSTOMER);
        assertThat(store.listMessages(conversationId, 10))
                .singleElement()
                .satisfies(message -> assertThat(message.getDirection()).isEqualTo(MessageDirection.OUTBOUND));
        // 出站不计未读
        assertThat(store.totalUnread()).isZero();
    }

    @Test
    void seq游标推进并持久化() {
        queue("CIPHER-3", 205L, plaintext("m-3", "send", CUSTOMER, MEMBER, "text", "\"text\":{\"content\":\"你好\"}"));

        service.pull();

        assertThat(store.cursor(SessionArchiveService.SEQ_CURSOR_KEY)).isEqualTo("205");
        assertThat(service.currentSeq()).isEqualTo(205L);
        assertThat(sdk.calls().get(0).seq()).isZero();

        // 下一轮从已保存的 seq 继续
        service.pull();
        assertThat(sdk.lastCall().seq()).isEqualTo(205L);
    }

    @Test
    void 重复msgid不会重复入库() {
        String plain = plaintext("m-dup", "send", CUSTOMER, MEMBER, "text", "\"text\":{\"content\":\"重复\"}");
        queue("CIPHER-4", 301L, plain);
        assertThat(service.pull()).isEqualTo(1);

        // 同一条消息再次被投递
        sdk.queueChatData(chatData("CIPHER-4", 301L));
        assertThat(service.pull()).isZero();

        assertThat(store.listMessages(
                InboxConversation.buildId(InboxChannel.WECOM_ARCHIVE, CUSTOMER), 10)).hasSize(1);
    }

    @Test
    void 默认跳过群聊() {
        String plain = """
                {"msgid":"m-room","action":"send","from":"%s","tolist":["%s"],"roomid":"wrjc7bDwY",
                 "msgtime":1756000000000,"msgtype":"text","text":{"content":"群里说"}}
                """.formatted(CUSTOMER, MEMBER);
        queue("CIPHER-5", 401L, plain);

        assertThat(service.pull()).isZero();
        assertThat(store.listConversations()).isEmpty();
        // 即使被过滤，游标也要推进，否则会卡死
        assertThat(service.currentSeq()).isEqualTo(401L);
    }

    @Test
    void 开启群聊后可入库() {
        properties.getArchive().setIncludeRoomChats(true);
        String plain = """
                {"msgid":"m-room2","action":"send","from":"%s","tolist":["%s"],"roomid":"wrjc7bDwY",
                 "msgtime":1756000000000,"msgtype":"text","text":{"content":"群里说"}}
                """.formatted(CUSTOMER, MEMBER);
        queue("CIPHER-6", 402L, plain);

        assertThat(service.pull()).isEqualTo(1);
        assertThat(store.findConversation(
                InboxConversation.buildId(InboxChannel.WECOM_ARCHIVE, CUSTOMER)).orElseThrow()
                .getRoomId()).isEqualTo("wrjc7bDwY");
    }

    @Test
    void 配置了目标映射时会话直接可发送() {
        properties.getArchive().setTargetMapping(Map.of(CUSTOMER, "o9cq808_abc@im.wechat"));
        queue("CIPHER-7", 501L, plaintext("m-7", "send", CUSTOMER, MEMBER, "text", "\"text\":{\"content\":\"你好\"}"));

        service.pull();

        InboxConversation conversation = store.findConversation(
                InboxConversation.buildId(InboxChannel.WECOM_ARCHIVE, CUSTOMER)).orElseThrow();
        assertThat(conversation.getOpenclawTarget()).isEqualTo("o9cq808_abc@im.wechat");
        assertThat(conversation.sendable()).isTrue();
    }

    @Test
    void 单条解密失败不影响同批其他消息() {
        String good = plaintext("m-good", "send", CUSTOMER, MEMBER, "text", "\"text\":{\"content\":\"正常\"}");
        sdk.mapPlaintext("CIPHER-GOOD", good);
        // CIPHER-BAD 未登记明文，decryptData 会抛异常
        sdk.queueChatData("""
                {"errcode":0,"errmsg":"ok","chatdata":[
                  {"seq":601,"msgid":"m-bad","publickey_ver":"1","encrypt_random_key":"%s","encrypt_chat_msg":"CIPHER-BAD"},
                  {"seq":602,"msgid":"m-good","publickey_ver":"1","encrypt_random_key":"%s","encrypt_chat_msg":"CIPHER-GOOD"}
                ]}
                """.formatted(encryptedRandomKey(), encryptedRandomKey()));

        assertThat(service.pull()).isEqualTo(1);
        assertThat(service.currentSeq()).isEqualTo(602L);
    }

    @Test
    void 存档返回错误码时抛出可读异常() {
        sdk.queueChatData("{\"errcode\":301002,\"errmsg\":\"no privilege\"}");

        assertThatThrownBy(() -> service.pull())
                .isInstanceOf(ArchiveSdkException.class)
                .hasMessageContaining("301002");
    }

    @Test
    void 未配置时拒绝拉取() {
        properties.getArchive().setSecret("");

        assertThatThrownBy(() -> service.pull())
                .isInstanceOf(ArchiveSdkException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void 未配置成员名单时按external_userid形态判断方向() {
        properties.getArchive().setMemberUserids(new java.util.LinkedHashSet<>());

        assertThat(service.isOwnMember(MEMBER)).isTrue();
        assertThat(service.isOwnMember(CUSTOMER)).isFalse();
    }

    @Test
    void 只收外部消息时过滤内部同事会话() {
        properties.getArchive().setExternalOnly(true);
        queue("CIPHER-8", 701L, plaintext("m-8", "send", MEMBER, "colleague", "text", "\"text\":{\"content\":\"内部沟通\"}"));

        assertThat(service.pull()).isZero();
        assertThat(store.listConversations()).isEmpty();
    }

    /* —— 辅助 —— */

    private void queue(String cipher, long seq, String plainJson) {
        sdk.mapPlaintext(cipher, plainJson);
        sdk.queueChatData(chatData(cipher, seq));
    }

    private String chatData(String cipher, long seq) {
        return """
                {"errcode":0,"errmsg":"ok","chatdata":[
                  {"seq":%d,"msgid":"x","publickey_ver":"1","encrypt_random_key":"%s","encrypt_chat_msg":"%s"}
                ]}
                """.formatted(seq, encryptedRandomKey(), cipher);
    }

    private static String plaintext(String msgid, String action, String from, String to,
                                   String msgtype, String payload) {
        return """
                {"msgid":"%s","action":"%s","from":"%s","tolist":["%s"],"roomid":"",
                 "msgtime":1756000000000,"msgtype":"%s",%s}
                """.formatted(msgid, action, from, to, msgtype, payload);
    }

    private static String encryptedRandomKey() {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPublic());
            return Base64.getEncoder()
                    .encodeToString(cipher.doFinal(RANDOM_KEY.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String privateKeyPem() {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
