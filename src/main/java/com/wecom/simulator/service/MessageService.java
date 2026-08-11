package com.wecom.simulator.service;

import com.wecom.simulator.dto.InboundChatOptions;
import com.wecom.simulator.model.ChatGroup;
import com.wecom.simulator.model.ChatType;
import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.MessageType;
import com.wecom.simulator.model.ProductChannel;
import com.wecom.simulator.model.SenderRole;
import com.wecom.simulator.security.SafeIds;
import com.wecom.simulator.store.MessageStore;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public class MessageService {

    private final MessageStore store;
    private final InboundPostProcessor inboundPostProcessor;
    private final ProductChannel channel;
    private final Path voiceDir;
    private final Path fileDir;
    private final long maxMediaFiles;

    public MessageService(
            MessageStore store,
            InboundPostProcessor inboundPostProcessor,
            ProductChannel channel,
            long maxMediaFiles
    ) throws IOException {
        this.store = store;
        this.inboundPostProcessor = inboundPostProcessor;
        this.channel = channel;
        this.voiceDir = Path.of(channel.getVoiceDir()).toAbsolutePath().normalize();
        this.fileDir = Path.of(channel.getFileDir()).toAbsolutePath().normalize();
        this.maxMediaFiles = maxMediaFiles;
        Files.createDirectories(this.voiceDir);
        Files.createDirectories(this.fileDir);
    }

    private String mediaUrl(String mediaId) {
        return channel.getApiBasePath() + "/media/" + mediaId;
    }

    public Message sendText(String content, InboundChatOptions options) {
        Message message = buildBaseInbound(MessageType.TEXT, options);
        message.setContent(content.trim());
        Map<String, Object> raw = message.getRaw();
        raw.put("Content", message.getContent());
        enrichReplyRaw(raw, message);
        store.addInbound(message);
        inboundPostProcessor.process(message);
        return message;
    }

    public Message sendVoice(
            MultipartFile file,
            InboundChatOptions options,
            String recognition,
            Integer durationMs
    ) throws IOException {
        if (durationMs != null && (durationMs < 0 || durationMs > 24 * 60 * 60 * 1000)) {
            throw new IllegalArgumentException("duration_ms 非法");
        }
        StoredMedia media = storeUpload(file, voiceDir, SafeIds.normalizeAudioExtension(
                file == null ? null : file.getOriginalFilename()
        ), "语音");
        String recog = SafeIds.sanitizeRecognition(recognition, media.originalName());

        Message message = buildBaseInbound(MessageType.VOICE, options);
        message.setMediaId(media.mediaId());
        message.setVoiceUrl(mediaUrl(media.mediaId()));
        message.setVoiceDurationMs(durationMs);
        message.setRecognition(recog);
        message.setContent(recog);
        Map<String, Object> raw = message.getRaw();
        raw.put("MediaId", media.mediaId());
        raw.put("Format", media.ext());
        raw.put("Recognition", recog);
        raw.put("VoiceUrl", message.getVoiceUrl());
        enrichReplyRaw(raw, message);
        store.addInbound(message);
        inboundPostProcessor.process(message);
        return message;
    }

    public Message sendImage(MultipartFile file, InboundChatOptions options) throws IOException {
        StoredMedia media = storeUpload(file, fileDir, SafeIds.normalizeImageExtension(
                file == null ? null : file.getOriginalFilename()
        ), "图片");
        Message message = buildBaseInbound(MessageType.IMAGE, options);
        message.setMediaId(media.mediaId());
        message.setImageUrl(mediaUrl(media.mediaId()));
        message.setFileName(media.originalName());
        message.setFileSize(media.size());
        message.setContent("[图片] " + media.originalName());
        Map<String, Object> raw = message.getRaw();
        raw.put("MediaId", media.mediaId());
        raw.put("PicUrl", message.getImageUrl());
        raw.put("FileName", media.originalName());
        enrichReplyRaw(raw, message);
        store.addInbound(message);
        inboundPostProcessor.process(message);
        return message;
    }

    public Message sendFile(MultipartFile file, InboundChatOptions options) throws IOException {
        String original = file == null || file.getOriginalFilename() == null
                ? "file.bin"
                : file.getOriginalFilename();
        String ext = SafeIds.normalizeGenericFileExtension(original);
        StoredMedia media = storeUpload(file, fileDir, ext, "文件");
        Message message = buildBaseInbound(MessageType.FILE, options);
        message.setMediaId(media.mediaId());
        message.setFileUrl(mediaUrl(media.mediaId()));
        message.setFileName(media.originalName());
        message.setFileSize(media.size());
        message.setContent("[文件] " + media.originalName());
        Map<String, Object> raw = message.getRaw();
        raw.put("MediaId", media.mediaId());
        raw.put("FileUrl", message.getFileUrl());
        raw.put("FileName", media.originalName());
        raw.put("FileSize", media.size());
        enrichReplyRaw(raw, message);
        store.addInbound(message);
        inboundPostProcessor.process(message);
        return message;
    }

    public Message replyText(
            String content,
            String chatTypeRaw,
            String groupId,
            String toUser,
            String replyToUser,
            String agentId,
            String replyToMsgid,
            List<String> mentionUserIds
    ) {
        if (agentId != null && !agentId.isBlank() && !SafeIds.isSafeToken(agentId)) {
            throw new IllegalArgumentException("agent_id 非法");
        }
        ChatType chatType = ChatType.from(chatTypeRaw);
        Message msg = new Message();
        msg.setMsgid(MessageStore.newId());
        msg.setMsgtype(MessageType.TEXT);
        msg.setRole(SenderRole.BOT);
        msg.setFromUser("bot");
        msg.setFromUserName(channel.getBotDisplayName());
        msg.setAgentId(agentId == null || agentId.isBlank() ? "1000001" : agentId);
        msg.setContent(content.trim());
        msg.setChatType(chatType);
        msg.setCreateTime(System.currentTimeMillis() / 1000);
        msg.setReplyToMsgid(blankToNull(replyToMsgid));
        msg.setMentionUserIds(sanitizeMentions(mentionUserIds));

        if (chatType == ChatType.GROUP) {
            ChatGroup group = requireGroup(groupId);
            msg.setGroupId(group.getGroupId());
            msg.setGroupName(group.getName());
            String targetUser = blankToNull(replyToUser);
            if (targetUser == null && replyToMsgid != null) {
                targetUser = store.findById(replyToMsgid).map(Message::getFromUser).orElse(null);
            }
            if (targetUser != null) {
                if (!SafeIds.isSafeToken(targetUser)) {
                    throw new IllegalArgumentException("reply_to_user 非法");
                }
                msg.setReplyToUser(targetUser);
                msg.setReplyToUserName(targetUser);
                if (!msg.getMentionUserIds().contains(targetUser)) {
                    msg.getMentionUserIds().add(targetUser);
                }
                applyReplySnapshot(msg, replyToMsgid, targetUser);
            }
        } else {
            String target = toUser;
            if (target == null || target.isBlank()) {
                target = store.list(null, SenderRole.USER, null, ChatType.PRIVATE, null).stream()
                        .reduce((a, b) -> b)
                        .map(Message::getFromUser)
                        .orElse(channel.isWechat() ? "friend001" : "user001");
            }
            if (!SafeIds.isSafeToken(target)) {
                throw new IllegalArgumentException("to_user 非法");
            }
            msg.setToUser(target);
            applyReplySnapshot(msg, replyToMsgid, null);
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("msgtype", "text");
        raw.put("agentid", msg.getAgentId());
        raw.put("chattype", chatType.getValue());
        raw.put("text", Map.of("content", msg.getContent()));
        if (msg.getGroupId() != null) {
            raw.put("chatid", msg.getGroupId());
        }
        if (msg.getToUser() != null) {
            raw.put("touser", msg.getToUser());
        }
        if (msg.getReplyToMsgid() != null) {
            raw.put("reply_to_msgid", msg.getReplyToMsgid());
        }
        if (msg.getReplyToUser() != null) {
            raw.put("reply_to_user", msg.getReplyToUser());
        }
        if (!msg.getMentionUserIds().isEmpty()) {
            raw.put("mentioned_list", msg.getMentionUserIds());
        }
        msg.setRaw(raw);
        return store.addBotReply(msg);
    }

    public ChatGroup createGroup(String groupId, String name, String ownerId, List<String> memberIds) {
        if (!SafeIds.isSafeDisplayName(name)) {
            throw new IllegalArgumentException("群名称非法");
        }
        if (!SafeIds.isSafeToken(ownerId)) {
            throw new IllegalArgumentException("owner_id 非法");
        }
        String id = groupId == null || groupId.isBlank() ? "group" + MessageStore.newId().substring(0, 8) : groupId;
        if (!SafeIds.isSafeToken(id)) {
            throw new IllegalArgumentException("group_id 非法");
        }
        if (store.findGroup(id).isPresent()) {
            throw new IllegalArgumentException("群已存在");
        }
        List<String> members = new ArrayList<>();
        if (memberIds != null) {
            for (String member : memberIds) {
                if (member != null && !member.isBlank()) {
                    if (!SafeIds.isSafeToken(member)) {
                        throw new IllegalArgumentException("member_id 非法: " + member);
                    }
                    if (!members.contains(member)) {
                        members.add(member);
                    }
                }
            }
        }
        if (!members.contains(ownerId)) {
            members.add(ownerId);
        }
        if (!members.contains("bot")) {
            members.add("bot");
        }
        ChatGroup group = new ChatGroup();
        group.setGroupId(id);
        group.setName(name.trim());
        group.setOwnerId(ownerId);
        group.setMemberIds(members);
        group.setCreateTime(System.currentTimeMillis() / 1000);
        return store.createGroup(group);
    }

    public Path resolveMedia(String mediaId) throws IOException {
        if (!SafeIds.isMediaId(mediaId)) {
            return null;
        }
        Path inVoice = findInDir(voiceDir, mediaId);
        if (inVoice != null) {
            return inVoice;
        }
        return findInDir(fileDir, mediaId);
    }

    public void clearVoiceFiles() throws IOException {
        clearDir(voiceDir);
        clearDir(fileDir);
    }

    private Message buildBaseInbound(MessageType type, InboundChatOptions options) {
        if (options == null) {
            options = new InboundChatOptions();
        }
        requireSafeIdentity(options.getFromUser(), options.getAgentId());
        ChatType chatType = ChatType.from(options.getChatType());
        Message msg = new Message();
        msg.setMsgid(MessageStore.newId());
        msg.setMsgtype(type);
        msg.setRole(SenderRole.USER);
        msg.setChatType(chatType);
        msg.setFromUser(options.getFromUser());
        msg.setFromUserName(
                options.getFromUserName() == null || options.getFromUserName().isBlank()
                        ? options.getFromUser()
                        : options.getFromUserName().trim()
        );
        if (!SafeIds.isSafeDisplayName(msg.getFromUserName())) {
            throw new IllegalArgumentException("from_user_name 非法");
        }
        msg.setAgentId(options.getAgentId());
        msg.setCreateTime(System.currentTimeMillis() / 1000);
        msg.setMentionUserIds(sanitizeMentions(options.getMentionUserIds()));
        msg.setReplyToMsgid(blankToNull(options.getReplyToMsgid()));

        if (chatType == ChatType.GROUP) {
            ChatGroup group = requireGroup(options.getGroupId());
            if (!group.getMemberIds().contains(options.getFromUser()) && !"bot".equals(options.getFromUser())) {
                // 允许联调时自动加入发送者
                group.getMemberIds().add(options.getFromUser());
            }
            msg.setGroupId(group.getGroupId());
            msg.setGroupName(group.getName());
            String replyUser = blankToNull(options.getReplyToUser());
            if (replyUser == null && msg.getReplyToMsgid() != null) {
                replyUser = store.findById(msg.getReplyToMsgid()).map(Message::getFromUser).orElse(null);
            }
            if (replyUser != null) {
                if (!SafeIds.isSafeToken(replyUser)) {
                    throw new IllegalArgumentException("reply_to_user 非法");
                }
                msg.setReplyToUser(replyUser);
                if (!msg.getMentionUserIds().contains(replyUser)) {
                    msg.getMentionUserIds().add(replyUser);
                }
            }
            applyReplySnapshot(msg, msg.getReplyToMsgid(), replyUser);
        } else {
            applyReplySnapshot(msg, msg.getReplyToMsgid(), blankToNull(options.getReplyToUser()));
            if (msg.getReplyToUser() == null && blankToNull(options.getReplyToUser()) != null) {
                String replyUser = options.getReplyToUser();
                if (!SafeIds.isSafeToken(replyUser)) {
                    throw new IllegalArgumentException("reply_to_user 非法");
                }
                msg.setReplyToUser(replyUser);
            }
        }

        msg.setRaw(MessageStore.baseRaw(msg));
        return msg;
    }

    private void applyReplySnapshot(Message msg, String replyToMsgid, String replyToUser) {
        if (replyToMsgid != null && !replyToMsgid.isBlank()) {
            store.findById(replyToMsgid).ifPresent(origin -> {
                msg.setReplyToMsgid(origin.getMsgid());
                msg.setReplyToContent(summarize(origin));
                if (msg.getReplyToUser() == null) {
                    msg.setReplyToUser(origin.getFromUser());
                }
                if (msg.getReplyToUserName() == null) {
                    msg.setReplyToUserName(
                            origin.getFromUserName() == null ? origin.getFromUser() : origin.getFromUserName()
                    );
                }
            });
        }
        if (replyToUser != null && msg.getReplyToUser() == null) {
            msg.setReplyToUser(replyToUser);
            msg.setReplyToUserName(replyToUser);
        }
    }

    private static void enrichReplyRaw(Map<String, Object> raw, Message message) {
        if (message.getReplyToMsgid() != null) {
            raw.put("ReplyToMsgId", message.getReplyToMsgid());
        }
        if (message.getReplyToUser() != null) {
            raw.put("ReplyToUser", message.getReplyToUser());
        }
        if (message.getReplyToContent() != null) {
            raw.put("ReplyToContent", message.getReplyToContent());
        }
        if (message.getMentionUserIds() != null && !message.getMentionUserIds().isEmpty()) {
            raw.put("MentionUserIds", message.getMentionUserIds());
        }
    }

    private ChatGroup requireGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("群聊必须提供 group_id");
        }
        if (!SafeIds.isSafeToken(groupId)) {
            throw new IllegalArgumentException("group_id 非法");
        }
        return store.findGroup(groupId)
                .orElseThrow(() -> new IllegalArgumentException("群不存在: " + groupId));
    }

    private StoredMedia storeUpload(
            MultipartFile file,
            Path dir,
            String ext,
            String label
    ) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(label + "文件为空");
        }
        if (countMediaFiles() >= maxMediaFiles) {
            throw new IllegalArgumentException("媒体文件数量已达上限，请先清空会话");
        }
        String original = file.getOriginalFilename() == null ? label.toLowerCase(Locale.ROOT) : file.getOriginalFilename();
        String safeName = Path.of(original).getFileName().toString();
        String mediaId = MessageStore.newId();
        Path dest = dir.resolve(mediaId + "." + ext).normalize();
        if (!dest.startsWith(dir)) {
            throw new IllegalArgumentException("非法存储路径");
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dest);
        }
        return new StoredMedia(mediaId, ext, safeName, Files.size(dest));
    }

    private Path findInDir(Path dir, String mediaId) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(mediaId + ".") && p.normalize().startsWith(dir);
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private void clearDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
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

    private long countMediaFiles() throws IOException {
        return countFiles(voiceDir) + countFiles(fileDir);
    }

    private static long countFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private static List<String> sanitizeMentions(List<String> mentionUserIds) {
        List<String> result = new ArrayList<>();
        if (mentionUserIds == null) {
            return result;
        }
        for (String id : mentionUserIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!SafeIds.isSafeToken(id)) {
                throw new IllegalArgumentException("mention_user_ids 含非法值");
            }
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private static String summarize(Message origin) {
        if (origin.getContent() != null && !origin.getContent().isBlank()) {
            String c = origin.getContent();
            return c.length() > 80 ? c.substring(0, 80) + "…" : c;
        }
        return "[" + origin.getMsgtype().getValue() + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireSafeIdentity(String fromUser, String agentId) {
        if (!SafeIds.isSafeToken(fromUser)) {
            throw new IllegalArgumentException("from_user 非法");
        }
        if (!SafeIds.isSafeToken(agentId)) {
            throw new IllegalArgumentException("agent_id 非法");
        }
    }

    private record StoredMedia(String mediaId, String ext, String originalName, long size) {
    }
}
