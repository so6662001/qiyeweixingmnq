package com.wecom.bridge.service;

import com.wecom.bridge.archive.SessionArchivePoller;
import com.wecom.bridge.archive.SessionArchiveService;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.contact.ContactApiException;
import com.wecom.bridge.contact.WecomContactClient;
import com.wecom.bridge.openclaw.OpenClawGateway;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上线自检：把「能不能真的收发」拆成一条条可判定的检查项。
 *
 * <p>真实环境联调最费时间的是猜哪一步没配好，这里一次性给出每项的状态与修复动作。</p>
 */
@Service
public class PreflightService {

    private final BridgeProperties properties;
    private final OpenClawGateway openClawGateway;
    private final SessionArchiveService archiveService;
    private final SessionArchivePoller archivePoller;
    private final WecomContactClient contactClient;

    public PreflightService(BridgeProperties properties,
                            OpenClawGateway openClawGateway,
                            SessionArchiveService archiveService,
                            SessionArchivePoller archivePoller,
                            WecomContactClient contactClient) {
        this.properties = properties;
        this.openClawGateway = openClawGateway;
        this.archiveService = archiveService;
        this.archivePoller = archivePoller;
        this.contactClient = contactClient;
    }

    /**
     * 执行全部检查。
     *
     * @param probeNetwork 是否执行会产生外部调用的检查（探测 CLI、换 access_token）
     */
    public Map<String, Object> run(boolean probeNetwork) {
        List<Check> checks = new ArrayList<>();

        checks.add(properties.isEnabled()
                ? Check.pass("总开关", "已启用")
                : Check.fail("总开关", "未启用", "设置 WECOM_BRIDGE_ENABLED=true 后重启"));

        if (properties.isDryRun()) {
            checks.add(Check.warn("演练模式", "已开启：所有出站只记录不真发",
                    "真实环境验证完参数后，关闭 wecom.bridge.dry-run 才会真正发出"));
        }

        checks.addAll(openClawChecks(probeNetwork));
        checks.addAll(archiveChecks());
        checks.addAll(contactChecks(probeNetwork));

        long failed = checks.stream().filter(check -> "fail".equals(check.level())).count();
        long warned = checks.stream().filter(check -> "warn".equals(check.level())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", failed == 0);
        result.put("failed", failed);
        result.put("warned", warned);
        result.put("dry_run", properties.isDryRun());
        result.put("checks", checks);
        return result;
    }

    private List<Check> openClawChecks(boolean probeNetwork) {
        List<Check> checks = new ArrayList<>();
        BridgeProperties.OpenClaw config = properties.getOpenclaw();

        if (!config.isEnabled()) {
            checks.add(Check.fail("OpenClaw 通道", "未启用",
                    "设置 WECOM_OPENCLAW_ENABLED=true；个人微信收发与企微出站都依赖它"));
            return checks;
        }

        if (probeNetwork) {
            OpenClawGateway.ProbeResult probe = openClawGateway.probeCli();
            checks.add(probe.ok()
                    ? Check.pass("openclaw 可执行", probe.detail())
                    : Check.fail("openclaw 可执行", probe.detail(),
                    "把 WECOM_OPENCLAW_CLI 配成 openclaw 的绝对路径，并确认运行用户有执行权限"));

            String channels = openClawGateway.probeChannels();
            if (channels.isBlank()) {
                checks.add(Check.warn("微信渠道状态", "未取到渠道状态",
                        "执行 openclaw channels status --probe 确认 " + config.getWechatChannel() + " 已登录"));
            } else if (channels.contains(config.getWechatChannel())) {
                checks.add(Check.pass("微信渠道状态", "已发现渠道 " + config.getWechatChannel()));
            } else {
                checks.add(Check.fail("微信渠道状态", "渠道 " + config.getWechatChannel() + " 不在已配置列表里",
                        "执行 openclaw channels login --channel " + config.getWechatChannel() + " 扫码登录"));
            }
        } else {
            checks.add(Check.pass("OpenClaw 通道", "已启用（未执行外部探测）"));
        }

        checks.add(config.isInboundReady()
                ? Check.pass("OpenClaw 入站密钥", "已配置")
                : Check.fail("OpenClaw 入站密钥", "未配置",
                "设置 WECOM_OPENCLAW_INBOUND_TOKEN，并在插件 config.token 里填相同值，否则收不到私聊"));

        return checks;
    }

    private List<Check> archiveChecks() {
        List<Check> checks = new ArrayList<>();
        BridgeProperties.Archive archive = properties.getArchive();

        if (!archive.isEnabled()) {
            checks.add(Check.warn("企微会话存档", "未启用",
                    "只做个人微信可以忽略；要接企业微信消息则设置 WECOM_ARCHIVE_ENABLED=true"));
            return checks;
        }

        checks.add(archive.getCorpId().isBlank()
                ? Check.fail("存档企业 ID", "未配置", "设置 WECOM_CORP_ID")
                : Check.pass("存档企业 ID", "已配置"));
        checks.add(archive.getSecret().isBlank()
                ? Check.fail("存档 Secret", "未配置", "管理端「聊天内容存档」页面获取，设置 WECOM_ARCHIVE_SECRET")
                : Check.pass("存档 Secret", "已配置"));
        checks.add(archive.getPrivateKeys().isEmpty()
                ? Check.fail("存档私钥", "未配置",
                "设置 WECOM_ARCHIVE_PRIVATE_KEY_V1，需 PKCS#8（BEGIN PRIVATE KEY）")
                : Check.pass("存档私钥", "已配置 " + archive.getPrivateKeys().size() + " 个版本"));
        checks.add(archiveService.sdkReady()
                ? Check.pass("存档 SDK", "已加载")
                : Check.warn("存档 SDK", "未加载",
                "把官方 WeWorkFinanceSdk_Java.jar 加入 classpath，并把 .so 路径配到 WECOM_ARCHIVE_SDK_LIB"));
        checks.add(archive.getMemberUserids().isEmpty()
                ? Check.warn("成员名单", "未配置，按 external_userid 形态推断消息方向",
                "设置 WECOM_ARCHIVE_MEMBER_USERIDS 可避免方向判断出错")
                : Check.pass("成员名单", "已配置 " + archive.getMemberUserids().size() + " 人"));

        String lastError = archivePoller.lastError();
        if (lastError != null && !lastError.isBlank()) {
            checks.add(Check.fail("存档拉取", "最近一次失败：" + lastError, "按错误信息排查后会自动恢复"));
        } else if (archive.isConfigured()) {
            checks.add(Check.pass("存档拉取", "当前 seq=" + archiveService.currentSeq()));
        }
        return checks;
    }

    private List<Check> contactChecks(boolean probeNetwork) {
        List<Check> checks = new ArrayList<>();
        BridgeProperties.Contact contact = properties.getContact();

        if (!contact.isEnabled()) {
            checks.add(Check.warn("客户联系（朋友圈/群发）", "未启用",
                    "要发朋友圈或客户群群发则设置 WECOM_CONTACT_ENABLED=true"));
            return checks;
        }

        checks.add(properties.contactCorpId().isBlank()
                ? Check.fail("客户联系企业 ID", "未配置", "设置 WECOM_CORP_ID 或 WECOM_CONTACT_CORP_ID")
                : Check.pass("客户联系企业 ID", "已配置"));
        checks.add(contact.getSecret().isBlank()
                ? Check.fail("客户联系 Secret", "未配置",
                "用「客户联系」Secret，或把自建应用加入可调用应用列表后用其 Secret")
                : Check.pass("客户联系 Secret", "已配置"));

        if (probeNetwork && contact.isConfigured() && !properties.contactCorpId().isBlank()) {
            try {
                contactClient.accessToken(false);
                checks.add(Check.pass("客户联系鉴权", "access_token 获取成功"));
            } catch (ContactApiException e) {
                checks.add(Check.fail("客户联系鉴权", e.getMessage(),
                        "检查 corpid/secret 是否匹配，以及服务器出网 IP 是否在可信 IP 列表里"));
            }
        }
        return checks;
    }

    /**
     * 一条检查结果。level 取 pass / warn / fail。
     */
    public record Check(String level, String name, String detail, String action) {

        static Check pass(String name, String detail) {
            return new Check("pass", name, detail, null);
        }

        static Check warn(String name, String detail, String action) {
            return new Check("warn", name, detail, action);
        }

        static Check fail(String name, String detail, String action) {
            return new Check("fail", name, detail, action);
        }
    }
}
