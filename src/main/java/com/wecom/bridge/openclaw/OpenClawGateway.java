package com.wecom.bridge.openclaw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenClaw 出站网关。
 *
 * <p>用 {@code openclaw message send} 直接把指定文本发给指定对端：这条路径不会触发
 * 大模型回合，发出的就是人工在页面里录入的原文。个人微信与企业微信两个通道都走这里。</p>
 */
@Component
public class OpenClawGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenClawGateway.class);

    private final BridgeProperties properties;
    private final ProcessRunner processRunner;
    private final ObjectMapper objectMapper;

    public OpenClawGateway(BridgeProperties properties, ProcessRunner processRunner, ObjectMapper objectMapper) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
    }

    public boolean outboundReady() {
        return properties.isEnabled() && properties.getOpenclaw().isOutboundReady();
    }

    /**
     * 发送一条文本。
     *
     * @param target 渠道内的对端标识（个人微信为 OpenClaw 的 peer id）
     */
    public SendResult sendText(String target, String text) {
        if (!outboundReady()) {
            return SendResult.failed("OpenClaw 出站未启用：请配置 wecom.bridge.openclaw.enabled 与 cli-path");
        }
        if (target == null || target.isBlank()) {
            return SendResult.failed("缺少发送目标，无法通过 OpenClaw 发送");
        }

        BridgeProperties.OpenClaw config = properties.getOpenclaw();
        List<String> command = new ArrayList<>(List.of(
                config.getCliPath(), "message", "send",
                "--channel", config.getWechatChannel(),
                "--target", target,
                "--message", text,
                "--json"
        ));
        if (!config.getAccount().isBlank()) {
            command.add("--account");
            command.add(config.getAccount());
        }

        Duration timeout = config.getCommandTimeout();
        ProcessRunner.Result result;
        try {
            result = processRunner.run(command, timeout);
        } catch (IOException e) {
            return SendResult.failed("调用 openclaw 失败（检查 cli-path 是否可执行）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.failed("调用 openclaw 被中断");
        }

        if (result.timedOut()) {
            return SendResult.failed("openclaw 发送超时（超过 " + timeout.toSeconds() + " 秒）");
        }
        if (!result.ok()) {
            return SendResult.failed(describeFailure(result));
        }
        return SendResult.ok(extractMessageId(result.stdout()));
    }

    /**
     * 探测 OpenClaw 渠道状态，用于页面上显示是否真的连上了。
     */
    public String probeChannels() {
        if (!outboundReady()) {
            return "";
        }
        BridgeProperties.OpenClaw config = properties.getOpenclaw();
        try {
            ProcessRunner.Result result = processRunner.run(
                    List.of(config.getCliPath(), "channels", "status", "--json"),
                    config.getCommandTimeout());
            return result.ok() ? result.stdout().trim() : "";
        } catch (IOException e) {
            log.debug("探测 OpenClaw 渠道状态失败: {}", e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private String describeFailure(ProcessRunner.Result result) {
        String detail = firstNonBlank(parseError(result.stdout()), result.stderr(), result.stdout());
        String trimmed = detail == null ? "" : detail.strip();
        if (trimmed.length() > 400) {
            trimmed = trimmed.substring(0, 400) + "…";
        }
        return trimmed.isBlank()
                ? "openclaw 返回失败（退出码 " + result.exitCode() + "）"
                : "openclaw 发送失败：" + trimmed;
    }

    private String parseError(String stdout) {
        JsonNode node = readJson(stdout);
        if (node == null) {
            return null;
        }
        for (String field : List.of("error", "message", "errmsg")) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String extractMessageId(String stdout) {
        JsonNode node = readJson(stdout);
        if (node == null) {
            return "";
        }
        for (String field : List.of("messageId", "message_id", "id")) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return node.path("result").path("messageId").asText("");
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw.trim());
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record SendResult(boolean ok, String messageId, String errorMessage) {

        public static SendResult ok(String messageId) {
            return new SendResult(true, messageId, null);
        }

        public static SendResult failed(String errorMessage) {
            return new SendResult(false, null, errorMessage);
        }
    }
}
