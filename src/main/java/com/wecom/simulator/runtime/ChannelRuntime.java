package com.wecom.simulator.runtime;

import com.wecom.simulator.model.ProductChannel;
import com.wecom.simulator.service.CrmLeadSyncService;
import com.wecom.simulator.service.MessageService;
import com.wecom.simulator.service.MomentService;
import com.wecom.simulator.service.WebhookDispatcher;
import com.wecom.simulator.store.MessageStore;
import com.wecom.simulator.store.MomentStore;

/**
 * 单一产品通道的运行时：会话、朋友圈、媒体与回调彼此隔离。
 */
public class ChannelRuntime {

    private final ProductChannel channel;
    private final MessageStore messageStore;
    private final MomentStore momentStore;
    private final MessageService messageService;
    private final MomentService momentService;
    private final WebhookDispatcher webhookDispatcher;
    private final CrmLeadSyncService crmLeadSyncService;

    public ChannelRuntime(
            ProductChannel channel,
            MessageStore messageStore,
            MomentStore momentStore,
            MessageService messageService,
            MomentService momentService,
            WebhookDispatcher webhookDispatcher,
            CrmLeadSyncService crmLeadSyncService
    ) {
        this.channel = channel;
        this.messageStore = messageStore;
        this.momentStore = momentStore;
        this.messageService = messageService;
        this.momentService = momentService;
        this.webhookDispatcher = webhookDispatcher;
        this.crmLeadSyncService = crmLeadSyncService;
    }

    public ProductChannel getChannel() {
        return channel;
    }

    public MessageStore getMessageStore() {
        return messageStore;
    }

    public MomentStore getMomentStore() {
        return momentStore;
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public MomentService getMomentService() {
        return momentService;
    }

    public WebhookDispatcher getWebhookDispatcher() {
        return webhookDispatcher;
    }

    public CrmLeadSyncService getCrmLeadSyncService() {
        return crmLeadSyncService;
    }
}
