package com.wecom.bridge.config;

import com.wecom.bridge.archive.NativeWeWorkFinanceSdk;
import com.wecom.bridge.archive.WeWorkFinanceSdk;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 单独放置会话存档 SDK 的 bean 定义。
 *
 * <p>刻意不与 {@link BridgeConfiguration} 合并：那个类依赖 WebSocket 处理器，
 * 而存档服务又被收件箱服务依赖，放在一起会形成构造期循环。</p>
 */
@Configuration
public class ArchiveSdkConfiguration {

    /**
     * 官方 jar 与 .so 由企业自行部署；缺失时该 bean 只是「不可用」，不影响服务启动。
     */
    @Bean
    WeWorkFinanceSdk weWorkFinanceSdk(BridgeProperties properties) {
        return new NativeWeWorkFinanceSdk(properties.getArchive().getSdkLibraryPath());
    }
}
