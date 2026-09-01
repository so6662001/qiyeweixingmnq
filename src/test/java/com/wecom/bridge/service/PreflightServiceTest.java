package com.wecom.bridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.archive.ArchiveRandomKeyDecryptor;
import com.wecom.bridge.archive.SessionArchivePoller;
import com.wecom.bridge.archive.SessionArchiveService;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.contact.WecomContactClient;
import com.wecom.bridge.openclaw.OpenClawGateway;
import com.wecom.bridge.store.InboxStore;
import com.wecom.bridge.support.FakeContactTransport;
import com.wecom.bridge.support.FakeProcessRunner;
import com.wecom.bridge.support.FakeWeWorkFinanceSdk;
import com.wecom.bridge.web.InboxRealtimeHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreflightServiceTest {

    private BridgeProperties properties;
    private FakeProcessRunner runner;
    private FakeContactTransport contactTransport;
    private PreflightService preflight;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ObjectMapper objectMapper = new ObjectMapper();

        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.setDataDir(tempDir.toString());

        runner = new FakeProcessRunner();
        contactTransport = new FakeContactTransport();

        InboxStore store = new InboxStore(properties, objectMapper);
        InboxRecorder recorder = new InboxRecorder(store, new InboxRealtimeHub(objectMapper));
        OpenClawGateway gateway = new OpenClawGateway(properties, runner, objectMapper);
        SessionArchiveService archiveService = new SessionArchiveService(properties,
                new FakeWeWorkFinanceSdk(), new ArchiveRandomKeyDecryptor(properties),
                recorder, store, objectMapper);
        SessionArchivePoller poller = new SessionArchivePoller(properties, archiveService);
        WecomContactClient contactClient = new WecomContactClient(properties, contactTransport, objectMapper);

        preflight = new PreflightService(properties, gateway, archiveService, poller, contactClient);
    }

    @SuppressWarnings("unchecked")
    private List<PreflightService.Check> checks(Map<String, Object> result) {
        return (List<PreflightService.Check>) result.get("checks");
    }

    private PreflightService.Check find(Map<String, Object> result, String name) {
        return checks(result).stream()
                .filter(check -> check.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有这项检查: " + name));
    }

    @Test
    void 总开关未启用时判定为阻塞() {
        properties.setEnabled(false);

        Map<String, Object> result = preflight.run(false);

        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(find(result, "总开关").level()).isEqualTo("fail");
        assertThat(find(result, "总开关").action()).contains("WECOM_BRIDGE_ENABLED");
    }

    @Test
    void openclaw未启用时给出修复动作() {
        Map<String, Object> result = preflight.run(false);

        PreflightService.Check check = find(result, "OpenClaw 通道");
        assertThat(check.level()).isEqualTo("fail");
        assertThat(check.action()).contains("WECOM_OPENCLAW_ENABLED");
    }

    @Test
    void 入站密钥缺失会被指出() {
        properties.getOpenclaw().setEnabled(true);

        Map<String, Object> result = preflight.run(false);

        PreflightService.Check check = find(result, "OpenClaw 入站密钥");
        assertThat(check.level()).isEqualTo("fail");
        assertThat(check.action()).contains("WECOM_OPENCLAW_INBOUND_TOKEN");
    }

    @Test
    void 探测到cli可执行时通过() {
        properties.getOpenclaw().setEnabled(true);
        properties.getOpenclaw().setInboundToken("token");
        runner.respond(command -> command.contains("--version")
                ? new com.wecom.bridge.openclaw.ProcessRunner.Result(0, "openclaw 2.4.8", "", false)
                : new com.wecom.bridge.openclaw.ProcessRunner.Result(0,
                "{\"channels\":[{\"id\":\"openclaw-weixin\"}]}", "", false));

        Map<String, Object> result = preflight.run(true);

        assertThat(find(result, "openclaw 可执行").level()).isEqualTo("pass");
        assertThat(find(result, "微信渠道状态").level()).isEqualTo("pass");
    }

    @Test
    void cli不可执行时判定失败并提示路径() {
        properties.getOpenclaw().setEnabled(true);
        properties.getOpenclaw().setInboundToken("token");
        runner.failWith(new IOException("Cannot run program"));

        Map<String, Object> result = preflight.run(true);

        PreflightService.Check check = find(result, "openclaw 可执行");
        assertThat(check.level()).isEqualTo("fail");
        assertThat(check.action()).contains("WECOM_OPENCLAW_CLI");
    }

    @Test
    void 渠道未登录时判定失败() {
        properties.getOpenclaw().setEnabled(true);
        properties.getOpenclaw().setInboundToken("token");
        runner.respond(command -> command.contains("--version")
                ? new com.wecom.bridge.openclaw.ProcessRunner.Result(0, "openclaw 2.4.8", "", false)
                : new com.wecom.bridge.openclaw.ProcessRunner.Result(0, "{\"channels\":[]}", "", false));

        Map<String, Object> result = preflight.run(true);

        assertThat(find(result, "微信渠道状态").level()).isEqualTo("fail");
        assertThat(find(result, "微信渠道状态").action()).contains("channels login");
    }

    @Test
    void 演练模式会作为提醒列出() {
        properties.setDryRun(true);

        Map<String, Object> result = preflight.run(false);

        assertThat(result.get("dry_run")).isEqualTo(true);
        assertThat(find(result, "演练模式").level()).isEqualTo("warn");
    }

    @Test
    void 存档未启用只提醒不阻塞() {
        properties.getOpenclaw().setEnabled(true);
        properties.getOpenclaw().setInboundToken("token");

        Map<String, Object> result = preflight.run(false);

        assertThat(find(result, "企微会话存档").level()).isEqualTo("warn");
    }

    @Test
    void 存档启用但缺私钥时阻塞() {
        properties.getArchive().setEnabled(true);
        properties.getArchive().setCorpId("ww-1");
        properties.getArchive().setSecret("s");

        Map<String, Object> result = preflight.run(false);

        assertThat(find(result, "存档私钥").level()).isEqualTo("fail");
        assertThat(find(result, "存档 SDK").level()).isEqualTo("warn");
    }

    @Test
    void 客户联系鉴权成功时通过() {
        properties.getContact().setEnabled(true);
        properties.getContact().setSecret("contact-secret");
        properties.getArchive().setCorpId("ww-corp-1");

        Map<String, Object> result = preflight.run(true);

        assertThat(find(result, "客户联系鉴权").level()).isEqualTo("pass");
    }

    @Test
    void 客户联系鉴权失败时给出排查方向() {
        properties.getContact().setEnabled(true);
        properties.getContact().setSecret("bad");
        properties.getArchive().setCorpId("ww-corp-1");
        contactTransport.respond("/cgi-bin/gettoken", "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");

        Map<String, Object> result = preflight.run(true);

        PreflightService.Check check = find(result, "客户联系鉴权");
        assertThat(check.level()).isEqualTo("fail");
        assertThat(check.action()).contains("可信 IP");
    }
}
