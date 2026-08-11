package com.wecom.simulator.service;

import com.wecom.simulator.model.ChatType;
import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.MessageType;
import com.wecom.simulator.model.SenderRole;
import com.wecom.simulator.store.MessageStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DemoBotService {

    private final MessageStore store;
    private final MessageService messageService;

    public DemoBotService(MessageStore store, @Lazy MessageService messageService) {
        this.store = store;
        this.messageService = messageService;
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
        } else if (message.getMsgtype() == MessageType.IMAGE) {
            content = "收到图片消息\nmedia_id: " + message.getMediaId() + "\n下载: " + message.getImageUrl();
        } else if (message.getMsgtype() == MessageType.FILE) {
            content = "收到文件消息\n文件: " + message.getFileName()
                    + "\nmedia_id: " + message.getMediaId()
                    + "\n下载: " + message.getFileUrl();
        } else {
            return null;
        }

        if (message.getChatType() == ChatType.GROUP) {
            content = "@" + message.getFromUser() + " " + content;
            List<String> mentions = new ArrayList<>();
            mentions.add(message.getFromUser());
            return messageService.replyText(
                    content,
                    "group",
                    message.getGroupId(),
                    null,
                    message.getFromUser(),
                    message.getAgentId(),
                    message.getMsgid(),
                    mentions
            );
        }

        return messageService.replyText(
                content,
                "private",
                null,
                message.getFromUser(),
                null,
                message.getAgentId(),
                message.getMsgid(),
                List.of()
        );
    }
}
