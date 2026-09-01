package com.wecom.bridge.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveMessageParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 解析文本消息的通用字段() {
        String plain = """
                {"msgid":"CAQQ1","action":"send","from":"huanghaoting","tolist":["wmErxtDgAAtest"],
                 "roomid":"","msgtime":1547087894783,"msgtype":"text","text":{"content":"现货充足"}}
                """;

        var parsed = ArchiveMessageParser.parse(objectMapper, plain);

        assertThat(parsed.msgid()).isEqualTo("CAQQ1");
        assertThat(parsed.action()).isEqualTo("send");
        assertThat(parsed.from()).isEqualTo("huanghaoting");
        assertThat(parsed.tolist()).containsExactly("wmErxtDgAAtest");
        assertThat(parsed.firstTo()).isEqualTo("wmErxtDgAAtest");
        assertThat(parsed.msgtime()).isEqualTo(1547087894783L);
        assertThat(parsed.msgtype()).isEqualTo("text");
        assertThat(parsed.content()).isEqualTo("现货充足");
        assertThat(parsed.mediaId()).isNull();
        assertThat(parsed.isRoomChat()).isFalse();
    }

    @Test
    void 图片消息带出sdkfileid() {
        var parsed = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m2","action":"send","from":"wmErxtDgAAtest","tolist":["huanghaoting"],"roomid":"",
                 "msgtime":1,"msgtype":"image","image":{"md5sum":"x","filesize":100,"sdkfileid":"FILE-1"}}
                """);

        assertThat(parsed.msgtype()).isEqualTo("image");
        assertThat(parsed.content()).isEqualTo("[图片]");
        assertThat(parsed.mediaId()).isEqualTo("FILE-1");
    }

    @Test
    void 文件消息带出文件名() {
        var parsed = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m3","from":"a","tolist":["b"],"roomid":"","msgtime":1,"msgtype":"file",
                 "file":{"filename":"报价单.xlsx","fileext":"xlsx","filesize":1,"sdkfileid":"FILE-2"}}
                """);

        assertThat(parsed.content()).contains("报价单.xlsx");
        assertThat(parsed.mediaId()).isEqualTo("FILE-2");
    }

    @Test
    void 语音与视频带出时长() {
        var voice = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m4","from":"a","tolist":["b"],"roomid":"","msgtime":1,"msgtype":"voice",
                 "voice":{"play_length":10,"sdkfileid":"V-1"}}
                """);
        assertThat(voice.content()).contains("10 秒");
        assertThat(voice.mediaId()).isEqualTo("V-1");

        var video = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m5","from":"a","tolist":["b"],"roomid":"","msgtime":1,"msgtype":"video",
                 "video":{"play_length":108,"sdkfileid":"V-2"}}
                """);
        assertThat(video.content()).contains("108 秒");
    }

    @Test
    void 链接与位置消息可读() {
        var link = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m6","from":"a","tolist":["b"],"roomid":"","msgtime":1,"msgtype":"link",
                 "link":{"title":"邀请你加入群聊","link_url":"https://work.weixin.qq.com/x"}}
                """);
        assertThat(link.content()).contains("邀请你加入群聊").contains("https://work.weixin.qq.com/x");

        var location = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m7","from":"a","tolist":["b"],"roomid":"","msgtime":1,"msgtype":"location",
                 "location":{"title":"xxx管理中心","address":"北京市xxx路"}}
                """);
        assertThat(location.content()).contains("xxx管理中心").contains("北京市xxx路");
    }

    @Test
    void 撤回消息带出原msgid() {
        var parsed = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m8","action":"recall","from":"a","tolist":["b"],"roomid":"","msgtime":1,
                 "msgtype":"revoke","revoke":{"pre_msgid":"old-1"}}
                """);

        assertThat(parsed.action()).isEqualTo("recall");
        assertThat(parsed.content()).contains("old-1");
    }

    @Test
    void 同意与不同意存档可读() {
        var agree = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m9","from":"a","tolist":["b"],"roomid":"","msgtime":1,"msgtype":"agree",
                 "agree":{"userid":"a","agree_time":1}}
                """);
        assertThat(agree.content()).contains("同意");

        var disagree = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m10","from":"a","tolist":["b"],"roomid":"","msgtime":1,"msgtype":"disagree",
                 "disagree":{"userid":"a","disagree_time":1}}
                """);
        assertThat(disagree.content()).contains("不同意");
    }

    @Test
    void 群聊消息可识别() {
        var parsed = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m11","from":"a","tolist":["b","c"],"roomid":"wrjc7bDwYAOAhf9","msgtime":1,
                 "msgtype":"text","text":{"content":"群里说"}}
                """);

        assertThat(parsed.isRoomChat()).isTrue();
        assertThat(parsed.roomId()).isEqualTo("wrjc7bDwYAOAhf9");
    }

    @Test
    void 外部消息按msgid后缀识别() {
        var parsed = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"12345_external","from":"a","tolist":["b"],"roomid":"","msgtime":1,
                 "msgtype":"text","text":{"content":"外部"}}
                """);

        assertThat(parsed.externalMessage()).isTrue();
    }

    @Test
    void 未知类型保留类型名() {
        var parsed = ArchiveMessageParser.parse(objectMapper, """
                {"msgid":"m12","from":"a","tolist":["b"],"roomid":"","msgtime":1,"msgtype":"brandnew"}
                """);

        assertThat(parsed.msgtype()).isEqualTo("brandnew");
        assertThat(parsed.content()).isEqualTo("[brandnew]");
    }

    @Test
    void 明文不是JSON时报错() {
        assertThatThrownBy(() -> ArchiveMessageParser.parse(objectMapper, "not-json"))
                .isInstanceOf(ArchiveSdkException.class)
                .hasMessageContaining("解析存档消息明文失败");
    }
}
