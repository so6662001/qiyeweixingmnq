package com.wecom.bridge.contact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.support.FakeContactTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MomentsServiceTest {

    private BridgeProperties properties;
    private FakeContactTransport transport;
    private MomentsService service;

    @BeforeEach
    void setUp() {
        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.getArchive().setCorpId("ww-corp-1");
        properties.getContact().setEnabled(true);
        properties.getContact().setSecret("contact-secret");

        transport = new FakeContactTransport();
        WecomContactClient client = new WecomContactClient(properties, transport, new ObjectMapper());
        service = new MomentsService(properties, client);
    }

    @Test
    void 创建发表任务带上文本图片与可见范围() {
        transport.respond("/add_moment_task", "{\"errcode\":0,\"errmsg\":\"ok\",\"jobid\":\"JOB-1\"}");

        var created = service.createTask(new MomentModels.MomentDraft(
                "本周钢材行情", List.of("MEDIA-1", "MEDIA-2"), null, null, null,
                List.of("huanghaoting"), null, List.of("etTAG1")));

        assertThat(created.jobId()).isEqualTo("JOB-1");
        assertThat(created.dryRun()).isFalse();

        String body = transport.lastCallTo("/add_moment_task").body();
        assertThat(body)
                .contains("本周钢材行情")
                .contains("\"media_id\":\"MEDIA-1\"")
                .contains("\"msgtype\":\"image\"")
                .contains("\"user_list\":[\"huanghaoting\"]")
                .contains("\"tag_list\":[\"etTAG1\"]");
    }

    @Test
    void 链接附件按官方字段拼装() {
        transport.respond("/add_moment_task", "{\"errcode\":0,\"errmsg\":\"ok\",\"jobid\":\"JOB-2\"}");

        service.createTask(new MomentModels.MomentDraft(
                null, null, "行情周报", "https://example.com/report", "MEDIA-COVER", null, null, null));

        String body = transport.lastCallTo("/add_moment_task").body();
        assertThat(body)
                .contains("\"msgtype\":\"link\"")
                .contains("\"title\":\"行情周报\"")
                .contains("\"url\":\"https://example.com/report\"")
                .contains("\"media_id\":\"MEDIA-COVER\"");
    }

    @Test
    void 内容为空时拒绝() {
        assertThatThrownBy(() -> service.createTask(
                new MomentModels.MomentDraft(" ", List.of(), null, null, null, null, null, null)))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("内容为空");
        assertThat(transport.callsTo("/add_moment_task")).isEmpty();
    }

    @Test
    void 图片超过九张时拒绝() {
        List<String> images = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> "MEDIA-" + i).toList();

        assertThatThrownBy(() -> service.createTask(
                new MomentModels.MomentDraft("x", images, null, null, null, null, null, null)))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("最多 9 张");
    }

    @Test
    void 演练模式下不真正创建任务() {
        properties.setDryRun(true);

        var created = service.createTask(new MomentModels.MomentDraft(
                "演练文案", null, null, null, null, null, null, null));

        assertThat(created.dryRun()).isTrue();
        assertThat(transport.callsTo("/add_moment_task")).isEmpty();
    }

    @Test
    void 查询任务结果解析状态与朋友圈id() {
        transport.respond("/get_moment_task_result", """
                {"errcode":0,"errmsg":"ok","status":3,"type":"add_moment_task",
                 "result":{"errcode":0,"errmsg":"ok","moment_id":"momxxx",
                 "invalid_sender_list":{"user_list":["nobody"],"department_list":[]}}}
                """);

        var status = service.taskStatus("JOB-1");

        assertThat(status.status()).isEqualTo(3);
        assertThat(status.statusText()).isEqualTo("创建完成");
        assertThat(status.momentId()).isEqualTo("momxxx");
        assertThat(status.invalidSenders()).containsExactly("nobody");
    }

    @Test
    void 发表列表分页取完() {
        transport.on("/get_moment_list", call -> call.body().contains("\"cursor\"")
                ? """
                {"errcode":0,"errmsg":"ok","next_cursor":"",
                 "moment_list":[{"moment_id":"mom2","creator":"lisi","create_time":1605000100,
                 "create_type":1,"visible_type":0,"text":{"content":"第二条"}}]}
                """
                : """
                {"errcode":0,"errmsg":"ok","next_cursor":"CURSOR-2",
                 "moment_list":[{"moment_id":"mom1","creator":"zhangsan","create_time":1605000000,
                 "create_type":0,"visible_type":0,"text":{"content":"第一条"},
                 "image":[{"media_id":"m1"},{"media_id":"m2"}]}]}
                """);

        var moments = service.listMoments(1605000000L, 1605086400L, null, 2);

        assertThat(moments).hasSize(2);
        assertThat(moments.get(0).momentId()).isEqualTo("mom1");
        assertThat(moments.get(0).imageCount()).isEqualTo(2);
        assertThat(moments.get(0).createTypeText()).isEqualTo("企业发表");
        assertThat(moments.get(1).momentId()).isEqualTo("mom2");
        assertThat(moments.get(1).createTypeText()).isEqualTo("个人发表");
    }

    @Test
    void 查询区间超过三十天时拒绝() {
        long start = 1605000000L;
        long end = start + 31L * 24 * 3600;

        assertThatThrownBy(() -> service.listMoments(start, end, null, 2))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("30 天");
    }

    @Test
    void 汇总点赞与评论并区分客户和成员() {
        transport.respond("/get_moment_task", """
                {"errcode":0,"errmsg":"ok","next_cursor":"","task_list":[
                  {"userid":"zhangsan","publish_status":1},
                  {"userid":"lisi","publish_status":1},
                  {"userid":"wangwu","publish_status":0}
                ]}
                """);
        transport.on("/get_moment_comments", call -> call.body().contains("zhangsan")
                ? """
                {"errcode":0,"errmsg":"ok",
                 "comment_list":[{"external_userid":"wmCustomer1","create_time":1605000060,"content":"多少钱一吨"}],
                 "like_list":[{"external_userid":"wmCustomer1","create_time":1605000050},
                              {"userid":"colleague","create_time":1605000055}]}
                """
                : """
                {"errcode":0,"errmsg":"ok",
                 "comment_list":[{"userid":"colleague","create_time":1605000070,"content":"同事评论"}],
                 "like_list":[{"external_userid":"wmCustomer2","create_time":1605000080}]}
                """);

        var stats = service.collectStats("momxxx", "zhangsan");

        assertThat(stats.senderCount()).isEqualTo(2);
        assertThat(stats.likeCount()).isEqualTo(3);
        assertThat(stats.commentCount()).isEqualTo(2);
        assertThat(stats.customerLikeCount()).isEqualTo(2);
        assertThat(stats.customerCommentCount()).isEqualTo(1);
        // 评论按时间倒序
        assertThat(stats.comments()).hasSize(2);
        assertThat(stats.comments().get(0).content()).isEqualTo("同事评论");
        assertThat(stats.comments().get(1).fromCustomer()).isTrue();
        // 未发表的成员不应被查询
        assertThat(transport.callsTo("/get_moment_comments")).hasSize(2);
    }

    @Test
    void 个人发表的朋友圈用创建人兜底查询互动数据() {
        transport.respond("/get_moment_task", "{\"errcode\":84061,\"errmsg\":\"invalid moment\"}");
        transport.respond("/get_moment_comments", """
                {"errcode":0,"errmsg":"ok","comment_list":[],
                 "like_list":[{"external_userid":"wmCustomer1","create_time":1}]}
                """);

        var stats = service.collectStats("momxxx", "zhangsan");

        assertThat(stats.likeCount()).isEqualTo(1);
        assertThat(transport.lastCallTo("/get_moment_comments").body()).contains("zhangsan");
    }

    @Test
    void 单个成员互动数据失败不影响其他成员() {
        transport.respond("/get_moment_task", """
                {"errcode":0,"errmsg":"ok","next_cursor":"","task_list":[
                  {"userid":"zhangsan","publish_status":1},{"userid":"lisi","publish_status":1}]}
                """);
        transport.on("/get_moment_comments", call -> call.body().contains("zhangsan")
                ? "{\"errcode\":60011,\"errmsg\":\"no privilege\"}"
                : "{\"errcode\":0,\"errmsg\":\"ok\",\"comment_list\":[],\"like_list\":[{\"userid\":\"a\",\"create_time\":1}]}");

        var stats = service.collectStats("momxxx", null);

        assertThat(stats.likeCount()).isEqualTo(1);
    }

    @Test
    void 未配置时给出可读错误() {
        properties.getContact().setSecret("");

        assertThatThrownBy(() -> service.createTask(
                new MomentModels.MomentDraft("x", null, null, null, null, null, null, null)))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("客户联系未配置");
    }

    @Test
    void 错误码翻译成可执行提示() {
        transport.respond("/add_moment_task", "{\"errcode\":48002,\"errmsg\":\"api forbidden\"}");

        assertThatThrownBy(() -> service.createTask(
                new MomentModels.MomentDraft("x", null, null, null, null, null, null, null)))
                .isInstanceOf(ContactApiException.class)
                .hasMessageContaining("客户联系")
                .hasMessageContaining("48002");
    }
}
