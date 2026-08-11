package com.wecom.simulator;

import com.wecom.simulator.store.MessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
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

    @Value("${wecom.simulator.voice-dir}")
    private String voiceDir;

    @BeforeEach
    void setUp() throws Exception {
        store.clear();
        store.setDemoBotEnabled(false);
        store.setWebhookEnabled(false);
        store.setWebhookUrl(null);
        Path dir = Path.of(voiceDir);
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // ignore
                    }
                });
            }
        }
    }

    @Test
    void health() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.runtime").value("java"));
    }

    @Test
    void sendTextAndListAndCallback() throws Exception {
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

        mockMvc.perform(get("/api/messages?msgtype=text&role=user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/messages/" + msgid + "/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MsgType").value("text"))
                .andExpect(jsonPath("$.Content").value("你好企业微信"));
    }

    @Test
    void voiceUploadDownloadAndClearDeletesFile() throws Exception {
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
                .andExpect(jsonPath("$.voice_url").value(startsWith("/api/media/")))
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
        assertThat(Files.exists(Path.of(voiceDir).resolve(mediaId + ".wav"))).isTrue();

        mockMvc.perform(get("/api/messages/" + msgid + "/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MsgType").value("voice"))
                .andExpect(jsonPath("$.MediaId").value(mediaId))
                .andExpect(jsonPath("$.Recognition").value("这是语音转写"));

        mockMvc.perform(delete("/api/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(Files.exists(Path.of(voiceDir).resolve(mediaId + ".wav"))).isFalse();
    }

    @Test
    void rejectsPathTraversalVoiceFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "a.wav/../../tmp/pwned",
                "audio/wav",
                "audio-bytes".getBytes()
        );

        MvcResult created = mockMvc.perform(multipart("/api/messages/voice")
                        .file(file)
                        .param("from_user", "u1")
                        .param("agent_id", "1000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media_id").isNotEmpty())
                .andReturn();

        String mediaId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.media_id");
        assertThat(Files.exists(Path.of(voiceDir).resolve(mediaId + ".webm"))).isTrue();
        assertThat(Files.exists(Path.of("/tmp/pwned"))).isFalse();
        assertThat(Files.exists(Path.of(voiceDir).resolve("../../tmp/pwned").normalize())).isFalse();
    }

    @Test
    void rejectsInvalidMediaIdAndIdentities() throws Exception {
        mockMvc.perform(get("/api/media/not-a-hex-media-id"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"x","from_user":"../evil","agent_id":"1000001"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replyTextAndDemoBot() throws Exception {
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

        store.setDemoBotEnabled(true);
        mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"自动回复测试"}
                                """))
                .andExpect(status().isOk());

        boolean found = false;
        for (int i = 0; i < 40; i++) {
            if (store.list(null, com.wecom.simulator.model.SenderRole.BOT, null, null, null).stream()
                    .anyMatch(m -> m.getContent() != null && m.getContent().contains("自动回复测试"))) {
                found = true;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(found).isTrue();
    }

    @Test
    void webhookConfigAllowsLoopbackRejectsMetadata() throws Exception {
        mockMvc.perform(put("/api/config/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://127.0.0.1:9000/callback\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhook_enabled").value(true))
                .andExpect(jsonPath("$.webhook_url").value("http://127.0.0.1:9000/callback"));

        mockMvc.perform(put("/api/config/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://169.254.169.254/latest/meta-data/\",\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("拒绝")));
    }

    @Test
    void afterFilterWorks() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"one\",\"from_user\":\"u1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String firstId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.msgid");

        mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"two\",\"from_user\":\"u1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/messages").param("after", firstId).param("role", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("two"));
    }
}
