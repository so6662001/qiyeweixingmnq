package com.wecom.bridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.archive.ArchiveRandomKeyDecryptor;
import com.wecom.bridge.archive.SessionArchivePoller;
import com.wecom.bridge.archive.SessionArchiveService;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.SendState;
import com.wecom.bridge.openclaw.OpenClawGateway;
import com.wecom.bridge.store.InboxStore;
import com.wecom.bridge.support.FakeProcessRunner;
import com.wecom.bridge.support.FakeWeWorkFinanceSdk;
import com.wecom.bridge.web.InboxRealtimeHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboxServiceTest {

    private BridgeProperties properties;
    private FakeProcessRunner runner;
    private InboxStore store;
    private InboxService inboxService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ObjectMapper objectMapper = new ObjectMapper();

        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.setDataDir(tempDir.toString());
        properties.getOpenclaw().setEnabled(true);
        properties.getOpenclaw().setInboundToken("token");

        runner = new FakeProcessRunner();
        store = new InboxStore(properties, objectMapper);
        InboxRecorder recorder = new InboxRecorder(store, new InboxRealtimeHub(objectMapper));
        OpenClawGateway gateway = new OpenClawGateway(properties, runner, objectMapper);
        OpenClawSender openClawSender = new OpenClawSender(gateway, properties);
        DemoChannelSender demoSender = new DemoChannelSender();

        SessionArchiveService archiveService = new SessionArchiveService(properties,
                new FakeWeWorkFinanceSdk(), new ArchiveRandomKeyDecryptor(properties),
                recorder, store, objectMapper);
        SessionArchivePoller poller = new SessionArchivePoller(properties, archiveService);

        inboxService = new InboxService(store, recorder, properties, archiveService, poller,
                gateway, openClawSender, List.of(openClawSender, demoSender));
    }

    @Test
    void 个人微信回复通过openclaw发出() {
        runner.respondOk("{\"ok\":true,\"messageId\":\"wx-out-1\"}");
        InboxConversation conversation = wechatConversation();

        InboxMessage message = inboxService.reply(conversation.getId(), "  现货充足，今天可发运  ");

        assertThat(message.getSendState()).isEqualTo(SendState.SENT);
        assertThat(message.getContent()).isEqualTo("现货充足，今天可发运");
        assertThat(runner.valueOf("--target")).isEqualTo("o9cq808_abc@im.wechat");
        assertThat(runner.valueOf("--message")).isEqualTo("现货充足，今天可发运");
    }

    @Test
    void 企微存档会话配了映射后也走同一条openclaw发送路径() {
        properties.getArchive().setTargetMapping(Map.of("wmCustomer1", "o9cq999_xyz@im.wechat"));
        runner.respondOk("{\"ok\":true,\"messageId\":\"wx-out-2\"}");
        InboxConversation conversation = store.upsertConversation(InboxChannel.WECOM_ARCHIVE, "wmCustomer1");

        InboxMessage message = inboxService.reply(conversation.getId(), "报价单已发您邮箱");

        assertThat(message.getSendState()).isEqualTo(SendState.SENT);
        assertThat(runner.valueOf("--target")).isEqualTo("o9cq999_xyz@im.wechat");
        // 解析出的目标要写回会话，页面能看到实际发给了谁
        assertThat(conversation.getOpenclawTarget()).isEqualTo("o9cq999_xyz@im.wechat");
    }

    @Test
    void 企微存档会话没有映射时拒绝发送并给出指引() {
        InboxConversation conversation = store.upsertConversation(InboxChannel.WECOM_ARCHIVE, "wmCustomer2");

        InboxMessage message = inboxService.reply(conversation.getId(), "你好");

        assertThat(message.getSendState()).isEqualTo(SendState.FAILED);
        assertThat(message.getErrorMessage()).contains("target-mapping");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void 页面补填发送目标后即可发送() {
        runner.respondOk("{\"ok\":true}");
        InboxConversation conversation = store.upsertConversation(InboxChannel.WECOM_ARCHIVE, "wmCustomer3");

        inboxService.setOpenclawTarget(conversation.getId(), " o9cq111_aaa@im.wechat ");
        InboxMessage message = inboxService.reply(conversation.getId(), "你好");

        assertThat(message.getSendState()).isEqualTo(SendState.SENT);
        assertThat(runner.valueOf("--target")).isEqualTo("o9cq111_aaa@im.wechat");
    }

    @Test
    void openclaw未就绪时不发命令并提示() {
        properties.getOpenclaw().setEnabled(false);
        InboxConversation conversation = wechatConversation();

        InboxMessage message = inboxService.reply(conversation.getId(), "你好");

        assertThat(message.getSendState()).isEqualTo(SendState.FAILED);
        assertThat(message.getErrorMessage()).contains("OpenClaw 出站未就绪");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void 发送失败时把原因写回消息() {
        runner.respondFailure(1, "{\"ok\":false,\"error\":\"peer not found\"}", "");
        InboxConversation conversation = wechatConversation();

        InboxMessage message = inboxService.reply(conversation.getId(), "你好");

        assertThat(message.getSendState()).isEqualTo(SendState.FAILED);
        assertThat(message.getErrorMessage()).contains("peer not found");
    }

    @Test
    void 演示通道只记录本地不发外部命令() {
        inboxService.injectDemoInbound("demo-1", "演示客户", "你好");
        String conversationId = InboxConversation.buildId(InboxChannel.DEMO, "demo-1");

        InboxMessage message = inboxService.reply(conversationId, "这是演示回复");

        assertThat(message.getSendState()).isEqualTo(SendState.LOCAL);
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void 空内容与超长内容被拒绝() {
        InboxConversation conversation = wechatConversation();

        assertThatThrownBy(() -> inboxService.reply(conversation.getId(), "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");

        assertThatThrownBy(() -> inboxService.reply(conversation.getId(), "字".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");
    }

    @Test
    void 会话不存在时返回明确错误() {
        assertThatThrownBy(() -> inboxService.reply("wechat:not-exist", "你好"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 标记已读会清空未读数() {
        inboxService.injectDemoInbound("demo-2", "演示客户", "你好");
        String conversationId = InboxConversation.buildId(InboxChannel.DEMO, "demo-2");
        assertThat(store.findConversation(conversationId).orElseThrow().getUnread()).isEqualTo(1);

        inboxService.markRead(conversationId);

        assertThat(store.totalUnread()).isZero();
    }

    @Test
    void 存档未配置时拉取报错() {
        assertThatThrownBy(() -> inboxService.pullArchive())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void 状态接口反映两条通道() {
        var status = inboxService.status();

        assertThat(status).containsEntry("enabled", true);
        assertThat(status.get("openclaw")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("outbound_ready", true)
                .containsEntry("inbound_ready", true)
                .containsEntry("inbound_path", "/api/openclaw/inbound")
                .containsEntry("wechat_channel", "openclaw-weixin");
        assertThat(status.get("archive")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("configured", false)
                .containsEntry("sdk_ready", false);
    }

    private InboxConversation wechatConversation() {
        InboxConversation conversation = store.upsertConversation(InboxChannel.WECHAT, "o9cq808_abc@im.wechat");
        conversation.setPeerName("李工");
        conversation.setOpenclawTarget("o9cq808_abc@im.wechat");
        return conversation;
    }
}
