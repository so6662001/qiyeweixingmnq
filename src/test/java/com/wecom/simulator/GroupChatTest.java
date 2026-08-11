package com.wecom.simulator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.wecom.simulator.store.MessageStore;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GroupChatTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MessageStore store;

    @BeforeEach
    void setUp() {
        store.clear();
        store.setDemoBotEnabled(false);
    }

    @Test
    void groupTextImageFileAndReplySpecificUser() throws Exception {
        mockMvc.perform(get("/api/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].group_id").value("group001"));

        MvcResult text = mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content":"大家好，看看方案",
                                  "chat_type":"group",
                                  "group_id":"group001",
                                  "from_user":"user002",
                                  "from_user_name":"群成员乙"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat_type").value("group"))
                .andExpect(jsonPath("$.group_id").value("group001"))
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andReturn();
        String textId = com.jayway.jsonpath.JsonPath.read(text.getResponse().getContentAsString(), "$.msgid");

        mockMvc.perform(multipart("/api/messages/image")
                        .file(new MockMultipartFile("file", "shot.png", "image/png", "pngdata".getBytes()))
                        .param("chat_type", "group")
                        .param("group_id", "group001")
                        .param("from_user", "user001")
                        .param("from_user_name", "用户甲")
                        .param("reply_to_msgid", textId)
                        .param("reply_to_user", "user002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("image"))
                .andExpect(jsonPath("$.chat_type").value("group"))
                .andExpect(jsonPath("$.reply_to_user").value("user002"))
                .andExpect(jsonPath("$.mention_user_ids", hasItem("user002")));

        mockMvc.perform(multipart("/api/messages/file")
                        .file(new MockMultipartFile("file", "plan.pdf", "application/pdf", "pdfdata".getBytes()))
                        .param("chat_type", "group")
                        .param("group_id", "group001")
                        .param("from_user", "seller001")
                        .param("from_user_name", "销售顾问"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("file"))
                .andExpect(jsonPath("$.file_name").value("plan.pdf"));

        mockMvc.perform(post("/api/reply/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content":"@user002 方案已收到",
                                  "chat_type":"group",
                                  "group_id":"group001",
                                  "reply_to_user":"user002",
                                  "reply_to_msgid":"%s",
                                  "mention_user_ids":["user002"]
                                }
                                """.formatted(textId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("bot"))
                .andExpect(jsonPath("$.chat_type").value("group"))
                .andExpect(jsonPath("$.reply_to_user").value("user002"))
                .andExpect(jsonPath("$.mention_user_ids", hasItem("user002")));

        mockMvc.perform(get("/api/messages").param("chat_type", "group").param("group_id", "group001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));

        mockMvc.perform(get("/api/messages/" + textId + "/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ChatType").value("group"))
                .andExpect(jsonPath("$.GroupId").value("group001"));
    }

    @Test
    void privateAndGroupAreIsolated() throws Exception {
        mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"私聊一条\",\"from_user\":\"user001\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/messages/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"群聊一条","chat_type":"group","group_id":"group001","from_user":"user002"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/messages").param("chat_type", "private"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("私聊一条"));
        mockMvc.perform(get("/api/messages").param("chat_type", "group").param("group_id", "group001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("群聊一条"));
    }
}
