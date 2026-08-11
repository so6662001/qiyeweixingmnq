package com.wecom.simulator.config;

import com.wecom.simulator.model.ProductChannel;
import com.wecom.simulator.runtime.ChannelRuntime;
import com.wecom.simulator.service.AsyncJobs;
import com.wecom.simulator.service.CrmLeadSyncService;
import com.wecom.simulator.service.DemoBotService;
import com.wecom.simulator.service.InboundPostProcessor;
import com.wecom.simulator.service.MessageService;
import com.wecom.simulator.service.MomentService;
import com.wecom.simulator.service.WebhookDispatcher;
import com.wecom.simulator.store.MessageStore;
import com.wecom.simulator.store.MomentStore;
import com.wecom.simulator.web.RealtimeHub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
public class ChannelRuntimeConfig {

    @Bean
    @Primary
    public ChannelRuntime wecomRuntime(
            RealtimeHub realtimeHub,
            AsyncJobs asyncJobs,
            @Value("${wecom.simulator.demo-bot-enabled:true}") boolean demoBotEnabled,
            @Value("${wecom.simulator.max-voice-files:200}") long maxMediaFiles,
            @Value("${wecom.simulator.max-moment-images:200}") long maxMomentImages
    ) throws IOException {
        return build(ProductChannel.WECOM, realtimeHub, asyncJobs, demoBotEnabled, maxMediaFiles, maxMomentImages);
    }

    @Bean("wechatRuntime")
    public ChannelRuntime wechatRuntime(
            RealtimeHub realtimeHub,
            AsyncJobs asyncJobs,
            @Value("${wecom.simulator.demo-bot-enabled:true}") boolean demoBotEnabled,
            @Value("${wecom.simulator.max-voice-files:200}") long maxMediaFiles,
            @Value("${wecom.simulator.max-moment-images:200}") long maxMomentImages
    ) throws IOException {
        return build(ProductChannel.WECHAT, realtimeHub, asyncJobs, demoBotEnabled, maxMediaFiles, maxMomentImages);
    }

    /** 兼容既有测试与组件对 MessageStore 的注入（默认企微通道）。 */
    @Bean
    @Primary
    public MessageStore messageStore(ChannelRuntime wecomRuntime) {
        return wecomRuntime.getMessageStore();
    }

    @Bean
    @Primary
    public MomentStore momentStore(ChannelRuntime wecomRuntime) {
        return wecomRuntime.getMomentStore();
    }

    @Bean
    @Primary
    public MessageService messageService(ChannelRuntime wecomRuntime) {
        return wecomRuntime.getMessageService();
    }

    @Bean
    @Primary
    public MomentService momentService(ChannelRuntime wecomRuntime) {
        return wecomRuntime.getMomentService();
    }

    @Bean
    @Primary
    public WebhookDispatcher webhookDispatcher(ChannelRuntime wecomRuntime) {
        return wecomRuntime.getWebhookDispatcher();
    }

    @Bean
    @Primary
    public CrmLeadSyncService crmLeadSyncService(ChannelRuntime wecomRuntime) {
        return wecomRuntime.getCrmLeadSyncService();
    }

    private static ChannelRuntime build(
            ProductChannel channel,
            RealtimeHub realtimeHub,
            AsyncJobs asyncJobs,
            boolean demoBotEnabled,
            long maxMediaFiles,
            long maxMomentImages
    ) throws IOException {
        MessageStore messageStore = new MessageStore(realtimeHub, channel, demoBotEnabled);
        MomentStore momentStore = new MomentStore(realtimeHub, channel);
        AtomicReference<MessageService> messageServiceRef = new AtomicReference<>();
        DemoBotService demoBotService = new DemoBotService(messageStore, messageServiceRef::get);
        WebhookDispatcher webhookDispatcher = new WebhookDispatcher(messageStore, channel);
        InboundPostProcessor inboundPostProcessor = new InboundPostProcessor(
                webhookDispatcher,
                demoBotService,
                messageStore,
                asyncJobs
        );
        MessageService messageService = new MessageService(
                messageStore,
                inboundPostProcessor,
                channel,
                maxMediaFiles
        );
        messageServiceRef.set(messageService);
        CrmLeadSyncService crmLeadSyncService = new CrmLeadSyncService(
                momentStore,
                realtimeHub,
                channel,
                asyncJobs
        );
        MomentService momentService = new MomentService(
                momentStore,
                crmLeadSyncService,
                channel,
                maxMomentImages
        );
        return new ChannelRuntime(
                channel,
                messageStore,
                momentStore,
                messageService,
                momentService,
                webhookDispatcher,
                crmLeadSyncService
        );
    }
}
