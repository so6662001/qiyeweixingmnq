package com.wecom.bridge.openclaw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.support.FakeProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenClawGatewayTest {

    private BridgeProperties properties;
    private FakeProcessRunner runner;
    private OpenClawGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.getOpenclaw().setEnabled(true);
        properties.getOpenclaw().setCliPath("/usr/local/bin/openclaw");
        properties.getOpenclaw().setWechatChannel("openclaw-weixin");

        runner = new FakeProcessRunner();
        gateway = new OpenClawGateway(properties, runner, new ObjectMapper());
    }

    @Test
    void 用message_send直接发送指定文本() {
        runner.respondOk("{\"ok\":true,\"messageId\":\"wx-out-1\"}");

        OpenClawGateway.SendResult result = gateway.sendText("o9cq808_abc@im.wechat", "现货充足，今天可发运");

        assertThat(result.ok()).isTrue();
        assertThat(result.messageId()).isEqualTo("wx-out-1");

        List<String> command = runner.lastInvocation();
        assertThat(command).startsWith("/usr/local/bin/openclaw", "message", "send");
        assertThat(runner.valueOf("--channel")).isEqualTo("openclaw-weixin");
        assertThat(runner.valueOf("--target")).isEqualTo("o9cq808_abc@im.wechat");
        assertThat(runner.valueOf("--message")).isEqualTo("现货充足，今天可发运");
        assertThat(command).contains("--json");
        // 未配置账号时不应带 --account
        assertThat(command).doesNotContain("--account");
    }

    @Test
    void 配置了账号时带上account参数() {
        properties.getOpenclaw().setAccount("wx-sales-1");
        runner.respondOk("{\"ok\":true}");

        gateway.sendText("peer-1", "你好");

        assertThat(runner.valueOf("--account")).isEqualTo("wx-sales-1");
    }

    @Test
    void 出站未启用时不执行任何命令() {
        properties.getOpenclaw().setEnabled(false);

        OpenClawGateway.SendResult result = gateway.sendText("peer-1", "你好");

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("OpenClaw 出站未启用");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void 缺少发送目标时直接拒绝() {
        OpenClawGateway.SendResult result = gateway.sendText("  ", "你好");

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("缺少发送目标");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void 命令失败时把错误信息带回() {
        runner.respondFailure(1, "{\"ok\":false,\"error\":\"channel not logged in\"}", "");

        OpenClawGateway.SendResult result = gateway.sendText("peer-1", "你好");

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("channel not logged in");
    }

    @Test
    void 命令没有json输出时退回stderr() {
        runner.respondFailure(127, "", "openclaw: command not found");

        OpenClawGateway.SendResult result = gateway.sendText("peer-1", "你好");

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("command not found");
    }

    @Test
    void 超时给出可读原因() {
        runner.respondTimeout();

        OpenClawGateway.SendResult result = gateway.sendText("peer-1", "你好");

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("超时");
    }

    @Test
    void 可执行文件不存在时提示检查cli路径() {
        runner.failWith(new IOException("Cannot run program \"openclaw\""));

        OpenClawGateway.SendResult result = gateway.sendText("peer-1", "你好");

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("cli-path");
    }
}
