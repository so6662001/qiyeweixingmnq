package com.wecom.bridge.openclaw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.support.FakeProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人在外面、收发靠另一台机器时，这条探测是判断「还能不能用」的唯一依据，
 * 所以掉线的每种形态都要能测出来。
 */
class OpenClawHealthServiceTest {

    private BridgeProperties properties;
    private FakeProcessRunner runner;
    private OpenClawHealthService health;

    @BeforeEach
    void setUp() {
        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.getOpenclaw().setEnabled(true);
        properties.getOpenclaw().setCliPath("openclaw");
        properties.getOpenclaw().setWechatChannel("openclaw-weixin");

        runner = new FakeProcessRunner();
        health = new OpenClawHealthService(properties, new OpenClawGateway(properties, runner, new ObjectMapper()));
    }

    @Test
    void 渠道在列时判定健康() {
        runner.respondOk("{\"channels\":[{\"id\":\"openclaw-weixin\",\"status\":\"ok\"}]}");

        var snapshot = health.check();

        assertThat(snapshot.probeOk()).isTrue();
        assertThat(snapshot.channelListed()).isTrue();
        assertThat(snapshot.healthy()).isTrue();
        assertThat(snapshot.checkedAt()).isPositive();
    }

    @Test
    void 网关无响应时判定不健康() {
        runner.failWith(new IOException("Cannot run program"));

        var snapshot = health.check();

        assertThat(snapshot.probeOk()).isFalse();
        assertThat(snapshot.healthy()).isFalse();
        assertThat(snapshot.detail()).contains("无响应");
    }

    @Test
    void 网关有响应但渠道掉线时能区分出来() {
        runner.respondOk("{\"channels\":[{\"id\":\"telegram\",\"status\":\"ok\"}]}");

        var snapshot = health.check();

        assertThat(snapshot.probeOk()).isTrue();
        assertThat(snapshot.channelListed()).isFalse();
        assertThat(snapshot.healthy()).isFalse();
        assertThat(snapshot.detail()).contains("不在列表里");
    }

    @Test
    void 命令超时按无响应处理() {
        runner.respondTimeout();

        assertThat(health.check().healthy()).isFalse();
    }

    @Test
    void 从未探测过时视为过期() {
        var snapshot = health.latest();

        assertThat(snapshot.checkedAt()).isZero();
        assertThat(snapshot.stale(60_000L)).isTrue();
        assertThat(snapshot.healthy()).isFalse();
    }

    @Test
    void 刚探测过不算过期() {
        runner.respondOk("{\"channels\":[{\"id\":\"openclaw-weixin\"}]}");

        assertThat(health.check().stale(Duration.ofSeconds(60).toMillis())).isFalse();
    }

    @Test
    void 探测停摆超过两个周期算过期() {
        var stale = new OpenClawHealthService.Snapshot(
                true, true, "ok", System.currentTimeMillis() - Duration.ofMinutes(5).toMillis());

        assertThat(stale.stale(Duration.ofSeconds(60).toMillis())).isTrue();
    }

    @Test
    void 状态描述包含页面需要的字段() {
        runner.respondOk("{\"channels\":[{\"id\":\"openclaw-weixin\"}]}");
        health.check();

        var described = health.describe();

        assertThat(described)
                .containsEntry("enabled", true)
                .containsEntry("probe_ok", true)
                .containsEntry("channel_listed", true)
                .containsKeys("detail", "checked_at", "stale");
    }

    @Test
    void 关闭探测后状态里能看出未开启() {
        properties.getOpenclaw().setHealthCheckEnabled(false);

        assertThat(health.describe()).containsEntry("enabled", false);
    }
}
