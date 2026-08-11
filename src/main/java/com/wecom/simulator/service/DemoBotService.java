package com.wecom.simulator.service;

import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.MessageType;
import com.wecom.simulator.model.SenderRole;
import com.wecom.simulator.store.MessageStore;
import org.springframework.stereotype.Service;

@Service
public class DemoBotService {

    private final MessageStore store;

    public DemoBotService(MessageStore store) {
        this.store = store;
    }

    public Message maybeAutoReply(Message message) {
        if (!store.isDemoBotEnabled()) {
            return null;
        }
        if (message.getRole() != SenderRole.USER) {
            return null;
        }

        String content;
        if (message.getMsgtype() == MessageType.TEXT) {
            content = "【收到文字】" + message.getContent();
        } else if (message.getMsgtype() == MessageType.VOICE) {
            String recog = message.getRecognition() == null || message.getRecognition().isBlank()
                    ? "（未识别，可下载原语音）"
                    : message.getRecognition();
            content = "收到语音消息\n"
                    + "media_id: " + message.getMediaId() + "\n"
                    + "时长: " + (message.getVoiceDurationMs() == null ? "?" : message.getVoiceDurationMs()) + " ms\n"
                    + "识别: " + recog + "\n"
                    + "下载: " + message.getVoiceUrl();
        } else {
            return null;
        }

        return store.addBotReply(
                content,
                message.getFromUser(),
                message.getAgentId(),
                message.getMsgid()
        );
    }
}
