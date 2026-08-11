package com.wecom.simulator.service;

import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.SenderRole;
import com.wecom.simulator.store.MessageStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageStore store;
    private final InboundPostProcessor inboundPostProcessor;
    private final Path voiceDir;

    public MessageService(
            MessageStore store,
            InboundPostProcessor inboundPostProcessor,
            @Value("${wecom.simulator.voice-dir:data/voices}") String voiceDir
    ) throws IOException {
        this.store = store;
        this.inboundPostProcessor = inboundPostProcessor;
        this.voiceDir = Path.of(voiceDir).toAbsolutePath().normalize();
        Files.createDirectories(this.voiceDir);
    }

    public Message sendText(String content, String fromUser, String agentId) {
        Message message = store.addTextFromUser(content.trim(), fromUser, agentId);
        inboundPostProcessor.process(message);
        return message;
    }

    public Message sendVoice(
            MultipartFile file,
            String fromUser,
            String agentId,
            String recognition,
            Integer durationMs
    ) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("语音文件为空");
        }
        String original = file.getOriginalFilename() == null ? "audio.webm" : file.getOriginalFilename();
        String suffix = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) {
            suffix = original.substring(dot);
        }
        if (suffix.isBlank()) {
            suffix = ".webm";
        }

        String mediaId = UUID.randomUUID().toString().replace("-", "");
        Path dest = voiceDir.resolve(mediaId + suffix);
        Files.write(dest, file.getBytes());

        String recog = recognition == null || recognition.isBlank()
                ? "[语音文件] " + original
                : recognition.trim();
        String format = suffix.startsWith(".") ? suffix.substring(1).toLowerCase(Locale.ROOT) : suffix;

        Message message = store.addVoiceFromUser(
                mediaId,
                "/api/media/" + mediaId,
                fromUser,
                agentId,
                durationMs,
                recog,
                format
        );
        inboundPostProcessor.process(message);
        return message;
    }

    public Message replyText(String content, String toUser, String agentId, String replyToMsgid) {
        String target = toUser;
        if (target == null || target.isBlank()) {
            target = store.list(null, SenderRole.USER, null).stream()
                    .reduce((a, b) -> b)
                    .map(Message::getFromUser)
                    .orElse("user001");
        }
        return store.addBotReply(content.trim(), target, agentId, replyToMsgid);
    }

    public Path resolveMedia(String mediaId) throws IOException {
        try (var stream = Files.list(voiceDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(mediaId + "."))
                    .findFirst()
                    .orElse(null);
        }
    }
}
