package com.wecom.simulator.service;

import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.SenderRole;
import com.wecom.simulator.security.SafeIds;
import com.wecom.simulator.store.MessageStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class MessageService {

    private final MessageStore store;
    private final InboundPostProcessor inboundPostProcessor;
    private final Path voiceDir;
    private final long maxVoiceFiles;

    public MessageService(
            MessageStore store,
            InboundPostProcessor inboundPostProcessor,
            @Value("${wecom.simulator.voice-dir:data/voices}") String voiceDir,
            @Value("${wecom.simulator.max-voice-files:200}") long maxVoiceFiles
    ) throws IOException {
        this.store = store;
        this.inboundPostProcessor = inboundPostProcessor;
        this.voiceDir = Path.of(voiceDir).toAbsolutePath().normalize();
        this.maxVoiceFiles = maxVoiceFiles;
        Files.createDirectories(this.voiceDir);
    }

    public Message sendText(String content, String fromUser, String agentId) {
        requireSafeIdentity(fromUser, agentId);
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
        requireSafeIdentity(fromUser, agentId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("语音文件为空");
        }
        if (countVoiceFiles() >= maxVoiceFiles) {
            throw new IllegalArgumentException("语音文件数量已达上限，请先清空会话");
        }
        if (durationMs != null && (durationMs < 0 || durationMs > 24 * 60 * 60 * 1000)) {
            throw new IllegalArgumentException("duration_ms 非法");
        }

        String original = file.getOriginalFilename() == null ? "audio.webm" : file.getOriginalFilename();
        String ext = SafeIds.normalizeAudioExtension(original);
        String mediaId = UUID.randomUUID().toString().replace("-", "");
        Path dest = voiceDir.resolve(mediaId + "." + ext).normalize();
        if (!dest.startsWith(voiceDir)) {
            throw new IllegalArgumentException("非法语音存储路径");
        }

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dest);
        }

        String safeName = Path.of(original).getFileName().toString();
        String recog = SafeIds.sanitizeRecognition(recognition, safeName);

        Message message = store.addVoiceFromUser(
                mediaId,
                "/api/media/" + mediaId,
                fromUser,
                agentId,
                durationMs,
                recog,
                ext
        );
        inboundPostProcessor.process(message);
        return message;
    }

    public Message replyText(String content, String toUser, String agentId, String replyToMsgid) {
        if (agentId != null && !agentId.isBlank() && !SafeIds.isSafeToken(agentId)) {
            throw new IllegalArgumentException("agent_id 非法");
        }
        if (toUser != null && !toUser.isBlank() && !SafeIds.isSafeToken(toUser)) {
            throw new IllegalArgumentException("to_user 非法");
        }
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
        if (!SafeIds.isMediaId(mediaId)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(voiceDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(mediaId + ".") && p.normalize().startsWith(voiceDir);
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    public void clearVoiceFiles() throws IOException {
        if (!Files.isDirectory(voiceDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(voiceDir)) {
            stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best effort
                        }
                    });
        }
    }

    private long countVoiceFiles() throws IOException {
        if (!Files.isDirectory(voiceDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(voiceDir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private static void requireSafeIdentity(String fromUser, String agentId) {
        if (!SafeIds.isSafeToken(fromUser)) {
            throw new IllegalArgumentException("from_user 非法");
        }
        if (!SafeIds.isSafeToken(agentId)) {
            throw new IllegalArgumentException("agent_id 非法");
        }
    }
}
