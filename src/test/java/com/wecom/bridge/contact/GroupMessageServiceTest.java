package com.wecom.bridge.contact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.support.FakeContactTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupMessageServiceTest {

    private BridgeProperties properties;
    private FakeContactTransport transport;
    private GroupMessageService service;

    @BeforeEach
    void setUp() {
        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.getArchive().setCorpId("ww-corp-1");
        properties.getContact().setEnabled(true);
        properties.getContact().setSecret("contact-secret");

        transport = new FakeContactTransport();
        WecomContactClient client = new WecomContactClient(properties, transport, new ObjectMapper());
        service = new GroupMessageService(properties, client);
    }

    @Test
    void 群发图文按官方结构拼装() {
        transport.respond("/add_msg_template", "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"MSG-1\",\"fail_list\":[]}");

        var result = service.send(
                List.of("wrChat1", "wrChat2"),
                "本周现货行情已更新",
                List.of(new GroupMessageService.ImageAttachment("MEDIA-1", null)),
                new GroupMessageService.LinkAttachment("行情周报", "https://example.com/r", "点击查看", null),
                "huanghaoting");

        assertThat(result.msgId()).isEqualTo("MSG-1");
        assertThat(result.dryRun()).isFalse();
        assertThat(result.note()).contains("群主需在企业微信里确认");

        String body = transport.lastCallTo("/add_msg_template").body();
        assertThat(body)
                .contains("\"chat_type\":\"group\"")
                .contains("\"chat_id_list\":[\"wrChat1\",\"wrChat2\"]")
                .contains("本周现货行情已更新")
                .contains("\"msgtype\":\"image\"")
                .contains("\"media_id\":\"MEDIA-1\"")
                .contains("\"msgtype\":\"link\"")
                .contains("\"title\":\"行情周报\"")
                .contains("\"sender\":\"huanghaoting\"");
    }

    @Test
    void 图片可以用外链代替media_id() {
        transport.respond("/add_msg_template", "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"MSG-2\"}");

        service.send(List.of("wrChat1"), null,
                List.of(new GroupMessageService.ImageAttachment(null, "https://p.qpic.cn/x/0")), null, null);

        assertThat(transport.lastCallTo("/add_msg_template").body())
                .contains("\"pic_url\":\"https://p.qpic.cn/x/0\"");
    }

    @Test
    void 图片既无media_id也无链接时拒绝() {
        assertThatThrownBy(() -> service.send(List.of("wrChat1"), "x",
                List.of(new GroupMessageService.ImageAttachment(null, null)), null, null))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("media_id 或 pic_url");
    }

    @Test
    void 没有选群时拒绝() {
        assertThatThrownBy(() -> service.send(List.of(), "x", null, null, null))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("至少选择一个客户群");
    }

    @Test
    void 文本与附件同时为空时拒绝() {
        assertThatThrownBy(() -> service.send(List.of("wrChat1"), "  ", List.of(), null, null))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("不能同时为空");
    }

    @Test
    void 附件超过九个时拒绝() {
        List<GroupMessageService.ImageAttachment> images = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> new GroupMessageService.ImageAttachment("MEDIA-" + i, null))
                .toList();

        assertThatThrownBy(() -> service.send(List.of("wrChat1"), "x", images, null, null))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("最多 9 个");
    }

    @Test
    void 演练模式下不真正创建群发任务() {
        properties.setDryRun(true);

        var result = service.send(List.of("wrChat1"), "演练", null, null, null);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.note()).contains("演练模式");
        assertThat(transport.callsTo("/add_msg_template")).isEmpty();
    }

    @Test
    void 失败列表原样带回() {
        transport.respond("/add_msg_template",
                "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"MSG-3\",\"fail_list\":[\"wrChatBad\"]}");

        var result = service.send(List.of("wrChat1", "wrChatBad"), "x", null, null, null);

        assertThat(result.failList()).containsExactly("wrChatBad");
    }

    @Test
    void 群列表带出名称与成员数() {
        transport.respond("/groupchat/list", """
                {"errcode":0,"errmsg":"ok","next_cursor":"","group_chat_list":[
                  {"chat_id":"wrChat1","status":0},{"chat_id":"wrChat2","status":0}]}
                """);
        transport.on("/groupchat/get", call -> call.body().contains("wrChat1")
                ? "{\"errcode\":0,\"errmsg\":\"ok\",\"group_chat\":{\"name\":\"钢材客户群A\",\"member_list\":[{},{},{}]}}"
                : "{\"errcode\":0,\"errmsg\":\"ok\",\"group_chat\":{\"name\":\"\",\"member_list\":[{}]}}");

        var chats = service.listGroupChats(null, 100);

        assertThat(chats).hasSize(2);
        assertThat(chats.get(0).name()).isEqualTo("钢材客户群A");
        assertThat(chats.get(0).memberCount()).isEqualTo(3);
        assertThat(chats.get(1).name()).isEqualTo("（未命名群聊）");
    }

    @Test
    void 上传图片素材返回media_id() {
        transport.uploadResponds("{\"errcode\":0,\"errmsg\":\"ok\",\"media_id\":\"MEDIA-UP\"}");

        String mediaId = service.uploadImage("行情.png", "image/png", "fake".getBytes(StandardCharsets.UTF_8));

        assertThat(mediaId).isEqualTo("MEDIA-UP");
        assertThat(transport.uploads()).singleElement().satisfies(upload -> {
            assertThat(upload.url()).contains("/cgi-bin/media/upload").contains("type=image");
            assertThat(upload.fieldName()).isEqualTo("media");
            assertThat(upload.fileName()).isEqualTo("行情.png");
        });
    }

    @Test
    void 频控错误码翻译成可读提示() {
        transport.respond("/add_msg_template", "{\"errcode\":41048,\"errmsg\":\"limit\"}");

        assertThatThrownBy(() -> service.send(List.of("wrChat1"), "x", null, null, null))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("每月可接收条数有限");
    }
}
