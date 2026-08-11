package com.wecom.simulator;

import com.wecom.simulator.store.MessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MessageStore store;

    @BeforeEach
    void setUp() {
        store.clear();
        store.setDemoBotEnabled(false);
        store.setWebhookEnabled(false);
        store.setWebhookUrl(null);
    }

    @Test
    void health() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.runtime").value("java"));
    }

    @Test
    void sendTextAndList() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"你好企业微信","from_user":"u1","agent_id":"1000001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.content").value("你好企业微信"))
                .andExpect(jsonPath("$.role").value("user"))
                .andExpect(jsonPath("$.from_user").value("u1"))
                .andReturn();

        String msgid = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.msgid");

        mockMvc.perform(get("/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].msgid").value(msgid));

        mockMvc.perform(get("/api/messages/" + msgid + "/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MsgType").value("text"))
                .andExpect(jsonPath("$.Content").value("你好企业微信"));
    }

    @Test
    void voiceUploadAndMedia() throws Exception {
        byte[] audio = "RIFF....WAVE".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "hello.wav",
                "audio/wav",
                audio
        );

        MvcResult created = mockMvc.perform(multipart("/api/messages/voice")
                        .file(file)
                        .param("from_user", "u1")
                        .param("agent_id", "1000001")
                        .param("recognition", "这是语音转写")
                        .param("duration_ms", "1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("voice"))
                .andExpect(jsonPath("$.recognition").value("这是语音转写"))
                .andExpect(jsonPath("$.media_id").isNotEmpty())
                .andExpect(jsonPath("$.voice_url").value(org.hamcrest.Matchers.startsWith("/api/media/")))
                .andReturn();

        String voiceUrl = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.voice_url");
        String mediaId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.media_id");
        String msgid = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.msgid");

        byte[] downloaded = mockMvc.perform(get(voiceUrl))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(downloaded).isEqualTo(audio);

        mockMvc.perform(get("/api/messages/" + msgid + "/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MsgType").value("voice"))
                .andExpect(jsonPath("$.MediaId").value(mediaId))
                .andExpect(jsonPath("$.Recognition").value("这是语音转写"));
    }

    @Test
    void replyText() throws Exception {
        mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"ping","from_user":"u9"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/reply/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"pong 来自应用","to_user":"u9","agent_id":"1000001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("bot"))
                .andExpect(jsonPath("$.content").value("pong 来自应用"))
                .andExpect(jsonPath("$.to_user").value("u9"));

        mockMvc.perform(get("/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].role").value("bot"));
    }

    @Test
    void demoBotAutoReply() throws Exception {
        store.setDemoBotEnabled(true);
        mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"自动回复测试"}
                                """))
                .andExpect(status().isOk());

        boolean found = false;
        for (int i = 0; i < 40; i++) {
            if (store.list(null, com.wecom.simulator.model.SenderRole.BOT, null).stream()
                    .anyMatch(m -> m.getContent() != null && m.getContent().contains("自动回复测试"))) {
                found = true;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(found).isTrue();
    }

    @Test
    void configAndClear() throws Exception {
        mockMvc.perform(put("/api/config/demo-bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo_bot_enabled").value(false));

        mockMvc.perform(put("/api/config/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://127.0.0.1:9000/callback\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhook_enabled").value(true))
                .andExpect(jsonPath("$.webhook_url").value("http://127.0.0.1:9000/callback"));

        mockMvc.perform(delete("/api/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
