package com.wecom.bridge.web;

import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.store.InboxStore;
import com.wecom.simulator.WecomSimulatorApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = WecomSimulatorApplication.class, properties = {
        "wecom.bridge.enabled=true",
        "wecom.bridge.data-dir=target/test-openclaw-inbound",
        "wecom.bridge.demo-inbox=false",
        "wecom.bridge.openclaw.enabled=true",
        "wecom.bridge.openclaw.inbound-token=test-inbound-token"
})
@AutoConfigureMockMvc
class OpenClawInboundControllerTest {

    private static final String BODY = """
            {
              "channel": "openclaw-weixin",
              "message_id": "wx-http-1",
              "peer_id": "o9cq808_abc@im.wechat",
              "peer_name": "李工",
              "msgtype": "text",
              "content": "方管 12 吨今天能发吗？",
              "timestamp": 1756000000000
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InboxStore store;

    @BeforeEach
    void setUp() {
        store.clear();
    }

    @Test
    void 带正确密钥的入站消息会入库() throws Exception {
        mockMvc.perform(post("/api/openclaw/inbound")
                        .header("Authorization", "Bearer test-inbound-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.conversation_id").value("wechat:o9cq808_abc@im.wechat"));

        InboxConversation conversation = store
                .findConversation(InboxConversation.buildId(InboxChannel.WECHAT, "o9cq808_abc@im.wechat"))
                .orElseThrow();
        assertThat(conversation.getPeerName()).isEqualTo("李工");
        assertThat(conversation.getOpenclawTarget()).isEqualTo("o9cq808_abc@im.wechat");
        assertThat(store.listMessages(conversation.getId(), 10))
                .singleElement()
                .satisfies(message -> assertThat(message.getContent()).isEqualTo("方管 12 吨今天能发吗？"));
    }

    @Test
    void 支持自定义头传密钥() throws Exception {
        mockMvc.perform(post("/api/openclaw/inbound")
                        .header("X-OpenClaw-Token", "test-inbound-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("wx-http-1", "wx-http-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void 密钥错误返回401() throws Exception {
        mockMvc.perform(post("/api/openclaw/inbound")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false));

        assertThat(store.listConversations()).isEmpty();
    }

    @Test
    void 没有密钥返回401() throws Exception {
        mockMvc.perform(post("/api/openclaw/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 缺少peer_id返回400() throws Exception {
        mockMvc.perform(post("/api/openclaw/inbound")
                        .header("Authorization", "Bearer test-inbound-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message_id\":\"x\",\"content\":\"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("peer_id")));
    }

    @Test
    void 重复消息返回skipped() throws Exception {
        mockMvc.perform(post("/api/openclaw/inbound")
                        .header("Authorization", "Bearer test-inbound-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/openclaw/inbound")
                        .header("Authorization", "Bearer test-inbound-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").value(true));
    }
}
