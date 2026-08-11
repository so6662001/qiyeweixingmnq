package com.wecom.simulator;

import com.wecom.simulator.runtime.ChannelRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WechatApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("wechatRuntime")
    private ChannelRuntime wechatRuntime;

    @BeforeEach
    void setUp() throws Exception {
        wechatRuntime.getMessageStore().clear();
        wechatRuntime.getMessageStore().setDemoBotEnabled(false);
        wechatRuntime.getMessageStore().setWebhookEnabled(false);
        wechatRuntime.getMessageStore().setWebhookUrl(null);
        wechatRuntime.getMomentService().clearAll();
        Path dir = Path.of("data/wechat/voices");
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
    void healthShowsWechatProduct() throws Exception {
        mockMvc.perform(get("/api/wechat/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.product").value("wechat"))
                .andExpect(jsonPath("$.service").value("wechat-simulator"));
    }

    @Test
    void wechatAndWecomSessionsAreIsolated() throws Exception {
        mockMvc.perform(post("/api/wechat/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"你好个人微信","from_user":"friend001","agent_id":"1000001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("你好个人微信"));

        mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"你好企业微信","from_user":"user001","agent_id":"1000001"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/wechat/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("你好个人微信"))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.content=='你好企业微信')]").exists());
    }

    @Test
    void wechatCallbackPayloadHasProductField() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/wechat/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"联调","from_user":"friend001","agent_id":"1000001"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String msgid = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.msgid");

        mockMvc.perform(get("/api/wechat/messages/" + msgid + "/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Product").value("wechat"))
                .andExpect(jsonPath("$.ToUserName").value("wx_simulator"))
                .andExpect(jsonPath("$.Content").value("联调"))
                .andExpect(jsonPath("$.AgentID").doesNotExist());
    }

    @Test
    void wechatDefaultGroupAndMediaUrlPrefix() throws Exception {
        mockMvc.perform(get("/api/wechat/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].group_id").value("group001"))
                .andExpect(jsonPath("$[0].name").value("同学聚会群"));

        MockMultipartFile image = new MockMultipartFile(
                "file",
                "hi.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}
        );
        mockMvc.perform(multipart("/api/wechat/messages/image")
                        .file(image)
                        .param("from_user", "friend001")
                        .param("from_user_name", "好友小陈"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image_url").value(org.hamcrest.Matchers.startsWith("/api/wechat/media/")));
    }

    @Test
    void clearWechatSessionDoesNotClearWecom() throws Exception {
        mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"企微保留","from_user":"user001","agent_id":"1000001"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/wechat/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"个微清空","from_user":"friend001","agent_id":"1000001"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/wechat/session")).andExpect(status().isOk());

        mockMvc.perform(get("/api/wechat/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        MvcResult wecom = mockMvc.perform(get("/api/messages")).andExpect(status().isOk()).andReturn();
        assertThat(wecom.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("企微保留");
    }
}
