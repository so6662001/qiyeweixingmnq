package com.wecom.simulator.service;

import com.wecom.simulator.model.Message;
import com.wecom.simulator.store.MessageStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class InboundPostProcessor {

    private final WebhookDispatcher webhookDispatcher;
    private final DemoBotService demoBotService;
    private final MessageStore store;

    public InboundPostProcessor(
            WebhookDispatcher webhookDispatcher,
            DemoBotService demoBotService,
            MessageStore store
    ) {
        this.webhookDispatcher = webhookDispatcher;
        this.demoBotService = demoBotService;
        this.store = store;
    }

    @Async
    public void process(Message message) {
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
