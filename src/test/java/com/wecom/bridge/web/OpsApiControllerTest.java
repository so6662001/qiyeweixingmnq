package com.wecom.bridge.web;

import com.wecom.bridge.support.FakeContactTransport;
import com.wecom.simulator.WecomSimulatorApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 钉死运营台接口的线上 JSON 格式。
 *
 * <p>全局 Jackson 用 snake_case，请求体字段名写成 camelCase 会被**静默忽略**
 * （图片、可见范围会凭空消失且不报错）。这里用真实的 HTTP 往返把格式固定下来。</p>
 */
@SpringBootTest(classes = {
        WecomSimulatorApplication.class,
        OpsApiControllerTest.Config.class
}, properties = {
        "wecom.bridge.enabled=true",
        "wecom.bridge.data-dir=target/test-ops",
        "wecom.bridge.demo-inbox=false",
        "wecom.bridge.contact.enabled=true",
        "wecom.bridge.contact.corp-id=ww-test-corp",
        "wecom.bridge.contact.secret=test-contact-secret",
        "wecom.bridge.contact.moment-stats-auto-refresh=false"
})
@AutoConfigureMockMvc
class OpsApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeContactTransport transport;

    @TestConfiguration
    static class Config {
        /** 覆盖真实 HTTP 实现，测试中不产生任何外部请求。 */
        @Bean
        @Primary
        FakeContactTransport fakeContactTransport() {
            return new FakeContactTransport();
        }
    }

    @BeforeEach
    void setUp() {
        transport.reset();
    }

    @Test
    void 创建朋友圈任务时图片与可见范围必须真的传到官方接口() throws Exception {
        transport.respond("/add_moment_task", "{\"errcode\":0,\"errmsg\":\"ok\",\"jobid\":\"JOB-9\"}");

        mockMvc.perform(post("/api/ops/moments/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "text": "本周现货行情",
                                  "image_media_ids": ["MEDIA-A", "MEDIA-B"],
                                  "sender_user_ids": ["huanghaoting"],
                                  "customer_tag_ids": ["etTAG1"],
                                  "link_title": "行情周报",
                                  "link_url": "https://example.com/r"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job_id").value("JOB-9"))
                .andExpect(jsonPath("$.dry_run").value(false));

        String outgoing = transport.lastCallTo("/add_moment_task").body();
        assertThat(outgoing)
                .contains("本周现货行情")
                .contains("MEDIA-A")
                .contains("MEDIA-B")
                .contains("huanghaoting")
                .contains("etTAG1")
                .contains("行情周报");
    }

    @Test
    void 任务结果按snake_case返回() throws Exception {
        transport.respond("/get_moment_task_result", """
                {"errcode":0,"errmsg":"ok","status":3,
                 "result":{"errcode":0,"errmsg":"ok","moment_id":"mom-1",
                 "invalid_sender_list":{"user_list":["ghost"]}}}
                """);

        mockMvc.perform(get("/api/ops/moments/tasks/JOB-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(3))
                .andExpect(jsonPath("$.status_text").value("创建完成"))
                .andExpect(jsonPath("$.moment_id").value("mom-1"))
                .andExpect(jsonPath("$.invalid_senders[0]").value("ghost"));
    }

    @Test
    void 群发时群列表与附件必须真的传到官方接口() throws Exception {
        transport.respond("/add_msg_template",
                "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"MSG-9\",\"fail_list\":[\"wrBad\"]}");

        mockMvc.perform(post("/api/ops/groups/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chat_ids": ["wrChatA", "wrChatB"],
                                  "text": "本周现货已更新",
                                  "images": [{"media_id": "MEDIA-A"}],
                                  "link_title": "行情周报",
                                  "link_url": "https://example.com/r",
                                  "link_description": "点击查看",
                                  "sender": "huanghaoting"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg_id").value("MSG-9"))
                .andExpect(jsonPath("$.fail_list[0]").value("wrBad"))
                .andExpect(jsonPath("$.dry_run").value(false));

        String outgoing = transport.lastCallTo("/add_msg_template").body();
        assertThat(outgoing)
                .contains("\"chat_type\":\"group\"")
                .contains("wrChatA")
                .contains("wrChatB")
                .contains("MEDIA-A")
                .contains("行情周报")
                .contains("huanghaoting");
    }

    @Test
    void 客户群列表按snake_case返回() throws Exception {
        transport.respond("/groupchat/list", """
                {"errcode":0,"errmsg":"ok","next_cursor":"","group_chat_list":[{"chat_id":"wrChatA","status":0}]}
                """);
        transport.respond("/groupchat/get",
                "{\"errcode\":0,\"errmsg\":\"ok\",\"group_chat\":{\"name\":\"钢材群\",\"member_list\":[{},{}]}}");

        mockMvc.perform(get("/api/ops/groups?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chat_id").value("wrChatA"))
                .andExpect(jsonPath("$[0].name").value("钢材群"))
                .andExpect(jsonPath("$[0].member_count").value(2));
    }

    @Test
    void 互动数据按snake_case返回() throws Exception {
        transport.respond("/get_moment_list", """
                {"errcode":0,"errmsg":"ok","next_cursor":"","moment_list":[
                  {"moment_id":"mom-1","creator":"huanghaoting","create_time":1756000000,
                   "create_type":0,"text":{"content":"行情"}}]}
                """);
        transport.respond("/get_moment_task", """
                {"errcode":0,"errmsg":"ok","next_cursor":"","task_list":[{"userid":"huanghaoting","publish_status":1}]}
                """);
        transport.respond("/get_moment_comments", """
                {"errcode":0,"errmsg":"ok",
                 "comment_list":[{"external_userid":"wmCust","create_time":1756000600,"content":"多少钱"}],
                 "like_list":[{"external_userid":"wmCust","create_time":1756000500},
                              {"userid":"colleague","create_time":1756000550}]}
                """);

        mockMvc.perform(post("/api/ops/moments/stats/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshed").value(1));

        mockMvc.perform(get("/api/ops/moments/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.like_count").value(2))
                .andExpect(jsonPath("$.overview.comment_count").value(1))
                .andExpect(jsonPath("$.overview.customer_like_count").value(1))
                .andExpect(jsonPath("$.items[0].moment_id").value("mom-1"))
                .andExpect(jsonPath("$.items[0].like_count").value(2))
                .andExpect(jsonPath("$.items[0].comment_count").value(1))
                .andExpect(jsonPath("$.items[0].comments[0].external_user_id").value("wmCust"))
                .andExpect(jsonPath("$.items[0].comments[0].create_time").value(1756000600L));
    }

    @Test
    void 上传素材返回media_id() throws Exception {
        transport.uploadResponds("{\"errcode\":0,\"errmsg\":\"ok\",\"media_id\":\"MEDIA-UP\"}");

        mockMvc.perform(multipart("/api/ops/moments/media")
                        .file(new MockMultipartFile("file", "行情.png", "image/png", "fake".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media_id").value("MEDIA-UP"));

        assertThat(transport.uploads()).singleElement()
                .satisfies(upload -> assertThat(upload.url()).contains("type=image"));
    }

    @Test
    void 能力边界接口明确个人微信不支持群聊与朋友圈() throws Exception {
        mockMvc.perform(get("/api/ops/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wechat.direct_message").value(true))
                .andExpect(jsonPath("$.wechat.group_message").value(false))
                .andExpect(jsonPath("$.wechat.moments").value(false))
                .andExpect(jsonPath("$.wecom.moments").value(true))
                .andExpect(jsonPath("$.wecom.group_message").value(true));
    }

    @Test
    void 自检接口返回检查项与修复动作() throws Exception {
        mockMvc.perform(get("/api/ops/preflight?probe=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks").isArray())
                .andExpect(jsonPath("$.checks[0].level").exists())
                .andExpect(jsonPath("$.checks[0].name").exists());
    }

    @Test
    void 官方错误码以502带回并附errcode() throws Exception {
        transport.respond("/add_msg_template", "{\"errcode\":41048,\"errmsg\":\"limit\"}");

        mockMvc.perform(post("/api/ops/groups/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chat_ids\":[\"wrChatA\"],\"text\":\"x\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errcode").value(41048))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("每月可接收条数有限")));
    }
}
