package com.wecom.simulator.service;

import com.wecom.simulator.model.Message;
import com.wecom.simulator.store.MessageStore;

import java.util.Map;

public class InboundPostProcessor {

    private final WebhookDispatcher webhookDispatcher;
    private final DemoBotService demoBotService;
    private final MessageStore store;
    private final AsyncJobs asyncJobs;

    public InboundPostProcessor(
            WebhookDispatcher webhookDispatcher,
            DemoBotService demoBotService,
            MessageStore store,
            AsyncJobs asyncJobs
    ) {
        this.webhookDispatcher = webhookDispatcher;
        this.demoBotService = demoBotService;
        this.store = store;
        this.asyncJobs = asyncJobs;
    }

    public void process(Message message) {
        asyncJobs.submit(() -> processSync(message));
    }

    private void processSync(Message message) {
        Map<String, Object> webhookResult = webhookDispatcher.dispatchInbound(message);
        if (webhookResult != null) {
            store.broadcastEvent(Map.of(
                    "type", "webhook_result",
                    "msgid", message.getMsgid(),
                    "result", webhookResult
            ));
        }
        demoBotService.maybeAutoReply(message);
    }
}
