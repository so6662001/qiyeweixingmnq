package com.wecom.simulator.web;

import com.wecom.simulator.dto.ApiOk;
import com.wecom.simulator.dto.CreateGroupRequest;
import com.wecom.simulator.dto.DemoBotConfigRequest;
import com.wecom.simulator.dto.InboundChatOptions;
import com.wecom.simulator.dto.ReplyTextRequest;
import com.wecom.simulator.dto.SessionState;
import com.wecom.simulator.dto.TextMessageRequest;
import com.wecom.simulator.dto.WebhookConfigRequest;
import com.wecom.simulator.model.ChatGroup;
import com.wecom.simulator.model.ChatType;
import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.MessageType;
import com.wecom.simulator.model.SenderRole;
import com.wecom.simulator.security.SafeIds;
import com.wecom.simulator.security.WebhookUrlValidator;
import com.wecom.simulator.service.MessageService;
import com.wecom.simulator.service.MomentService;
import com.wecom.simulator.service.WebhookDispatcher;
import com.wecom.simulator.store.MessageStore;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final MessageStore store;
    private final MessageService messageService;
    private final MomentService momentService;
    private final WebhookDispatcher webhookDispatcher;
    private final WebhookUrlValidator webhookUrlValidator;

    public ApiController(
            MessageStore store,
            MessageService messageService,
            MomentService momentService,
            WebhookDispatcher webhookDispatcher,
            WebhookUrlValidator webhookUrlValidator
    ) {
        this.store = store;
        this.messageService = messageService;
        this.momentService = momentService;
        this.webhookDispatcher = webhookDispatcher;
        this.webhookUrlValidator = webhookUrlValidator;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "wecom-simulator", "runtime", "java");
    }

    @GetMapping("/session")
    public SessionState session() {
        return store.snapshot();
    }

    @DeleteMapping("/session")
    public ApiOk clearSession() {
        store.clear();
        try {
            messageService.clearVoiceFiles();
            momentService.clearAll();
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "清理会话媒体失败");
        }
        return ApiOk.of();
    }

    @GetMapping("/groups")
    public List<ChatGroup> groups() {
        return store.listGroups();
    }

    @PostMapping("/groups")
    public ChatGroup createGroup(@Valid @RequestBody CreateGroupRequest body) {
        try {
            return messageService.createGroup(
                    body.getGroupId(),
                    body.getName(),
                    body.getOwnerId(),
                    body.getMemberIds()
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping("/messages/text")
    public Message sendText(@Valid @RequestBody TextMessageRequest body) {
        try {
            return messageService.sendText(body.getContent(), toOptions(body));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping(value = "/messages/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Message sendVoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "from_user", defaultValue = "user001") String fromUser,
            @RequestParam(value = "from_user_name", required = false) String fromUserName,
            @RequestParam(value = "agent_id", defaultValue = "1000001") String agentId,
            @RequestParam(value = "chat_type", defaultValue = "private") String chatType,
            @RequestParam(value = "group_id", required = false) String groupId,
            @RequestParam(value = "reply_to_msgid", required = false) String replyToMsgid,
            @RequestParam(value = "reply_to_user", required = false) String replyToUser,
            @RequestParam(value = "mention_user_ids", required = false) String mentionUserIds,
            @RequestParam(value = "recognition", required = false) String recognition,
            @RequestParam(value = "duration_ms", required = false) Integer durationMs
    ) {
        try {
            return messageService.sendVoice(
                    file,
                    toOptions(chatType, groupId, fromUser, fromUserName, agentId, replyToMsgid, replyToUser, mentionUserIds),
                    recognition,
                    durationMs
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "保存语音失败");
        }
    }

    @PostMapping(value = "/messages/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Message sendImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "from_user", defaultValue = "user001") String fromUser,
            @RequestParam(value = "from_user_name", required = false) String fromUserName,
            @RequestParam(value = "agent_id", defaultValue = "1000001") String agentId,
            @RequestParam(value = "chat_type", defaultValue = "private") String chatType,
            @RequestParam(value = "group_id", required = false) String groupId,
            @RequestParam(value = "reply_to_msgid", required = false) String replyToMsgid,
            @RequestParam(value = "reply_to_user", required = false) String replyToUser,
            @RequestParam(value = "mention_user_ids", required = false) String mentionUserIds
    ) {
        try {
            return messageService.sendImage(
                    file,
                    toOptions(chatType, groupId, fromUser, fromUserName, agentId, replyToMsgid, replyToUser, mentionUserIds)
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "保存图片失败");
        }
    }

    @PostMapping(value = "/messages/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Message sendFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "from_user", defaultValue = "user001") String fromUser,
            @RequestParam(value = "from_user_name", required = false) String fromUserName,
            @RequestParam(value = "agent_id", defaultValue = "1000001") String agentId,
            @RequestParam(value = "chat_type", defaultValue = "private") String chatType,
            @RequestParam(value = "group_id", required = false) String groupId,
            @RequestParam(value = "reply_to_msgid", required = false) String replyToMsgid,
            @RequestParam(value = "reply_to_user", required = false) String replyToUser,
            @RequestParam(value = "mention_user_ids", required = false) String mentionUserIds
    ) {
        try {
            return messageService.sendFile(
                    file,
                    toOptions(chatType, groupId, fromUser, fromUserName, agentId, replyToMsgid, replyToUser, mentionUserIds)
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "保存文件失败");
        }
    }

    @GetMapping("/messages")
    public List<Message> listMessages(
            @RequestParam(value = "msgtype", required = false) MessageType msgtype,
            @RequestParam(value = "role", required = false) SenderRole role,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "chat_type", required = false) ChatType chatType,
            @RequestParam(value = "group_id", required = false) String groupId
    ) {
        return store.list(msgtype, role, after, chatType, groupId);
    }

    @GetMapping("/messages/{msgid}")
    public Message getMessage(@PathVariable String msgid) {
        return store.findById(msgid)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "消息不存在"));
    }

    @GetMapping("/messages/{msgid}/callback")
    public Map<String, Object> callbackPayload(@PathVariable String msgid) {
        Message msg = store.findById(msgid)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "消息不存在"));
        if (msg.getRole() != SenderRole.USER) {
            throw new ResponseStatusException(BAD_REQUEST, "仅用户入站消息可导出回调载荷");
        }
        return webhookDispatcher.toWecomCallbackPayload(msg);
    }

    @PostMapping("/reply/text")
    public Message replyText(@Valid @RequestBody ReplyTextRequest body) {
        try {
            return messageService.replyText(
                    body.getContent(),
                    body.getChatType(),
                    body.getGroupId(),
                    body.getToUser(),
                    body.getReplyToUser(),
                    body.getAgentId(),
                    body.getReplyToMsgid(),
                    body.getMentionUserIds()
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
    }

    @PutMapping("/config/webhook")
    public SessionState setWebhook(@Valid @RequestBody WebhookConfigRequest body) {
        try {
            String safeUrl = webhookUrlValidator.validateAndNormalize(body.getUrl());
            store.setWebhookUrl(safeUrl);
            store.setWebhookEnabled(body.isEnabled());
            return store.snapshot();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
    }

    @PutMapping("/config/demo-bot")
    public SessionState setDemoBot(@RequestBody DemoBotConfigRequest body) {
        store.setDemoBotEnabled(body.isEnabled());
        return store.snapshot();
    }

    @GetMapping("/media/{mediaId}")
    public ResponseEntity<Resource> media(@PathVariable String mediaId) throws IOException {
        if (!SafeIds.isMediaId(mediaId)) {
            throw new ResponseStatusException(BAD_REQUEST, "media_id 非法");
        }
        Path path = messageService.resolveMedia(mediaId);
        if (path == null || !Files.exists(path)) {
            throw new ResponseStatusException(NOT_FOUND, "媒体不存在");
        }
        String contentType = Files.probeContentType(path);
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + path.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(path));
    }

    private static InboundChatOptions toOptions(TextMessageRequest body) {
        InboundChatOptions options = new InboundChatOptions();
        options.setChatType(body.getChatType());
        options.setGroupId(body.getGroupId());
        options.setFromUser(body.getFromUser());
        options.setFromUserName(body.getFromUserName());
        options.setAgentId(body.getAgentId());
        options.setReplyToMsgid(body.getReplyToMsgid());
        options.setReplyToUser(body.getReplyToUser());
        options.setMentionUserIds(body.getMentionUserIds());
        return options;
    }

    private static InboundChatOptions toOptions(
            String chatType,
            String groupId,
            String fromUser,
            String fromUserName,
            String agentId,
            String replyToMsgid,
            String replyToUser,
            String mentionUserIds
    ) {
        InboundChatOptions options = new InboundChatOptions();
        options.setChatType(chatType);
        options.setGroupId(groupId);
        options.setFromUser(fromUser);
        options.setFromUserName(fromUserName);
        options.setAgentId(agentId);
        options.setReplyToMsgid(replyToMsgid);
        options.setReplyToUser(replyToUser);
        if (mentionUserIds != null && !mentionUserIds.isBlank()) {
            options.setMentionUserIds(
                    Arrays.stream(mentionUserIds.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList()
            );
        }
        return options;
    }
}
