package com.wecom.simulator.web;

import com.wecom.simulator.dto.CrmConfigRequest;
import com.wecom.simulator.dto.CrmConfigState;
import com.wecom.simulator.dto.CrmSyncResult;
import com.wecom.simulator.dto.MomentCommentRequest;
import com.wecom.simulator.dto.MomentLikeRequest;
import com.wecom.simulator.model.MomentInteraction;
import com.wecom.simulator.model.MomentPost;
import com.wecom.simulator.security.SafeIds;
import com.wecom.simulator.security.WebhookUrlValidator;
import com.wecom.simulator.service.CrmLeadSyncService;
import com.wecom.simulator.service.MomentService;
import com.wecom.simulator.store.MomentStore;
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
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/moments")
public class MomentsController {

    private final MomentService momentService;
    private final MomentStore momentStore;
    private final CrmLeadSyncService crmLeadSyncService;
    private final WebhookUrlValidator urlValidator;

    public MomentsController(
            MomentService momentService,
            MomentStore momentStore,
            CrmLeadSyncService crmLeadSyncService,
            WebhookUrlValidator urlValidator
    ) {
        this.momentService = momentService;
        this.momentStore = momentStore;
        this.crmLeadSyncService = crmLeadSyncService;
        this.urlValidator = urlValidator;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MomentPost publish(
            @RequestParam("image") MultipartFile image,
            @RequestParam("content") String content,
            @RequestParam(value = "plan", required = false) String plan,
            @RequestParam(value = "author_id", defaultValue = "seller001") String authorId,
            @RequestParam(value = "author_name", defaultValue = "销售顾问") String authorName
    ) {
        try {
            return momentService.publish(image, content, plan, authorId, authorName);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "保存朋友圈图片失败");
        }
    }

    @GetMapping
    public List<MomentPost> list() {
        return momentService.listPosts();
    }

    /**
     * 读取朋友圈动态（点赞/评论），可过滤未同步到 CRM 的记录。
     */
    @GetMapping("/interactions")
    public List<MomentInteraction> interactions(
            @RequestParam(value = "unsynced_only", defaultValue = "false") boolean unsyncedOnly,
            @RequestParam(value = "after", required = false) String after
    ) {
        return momentService.listInteractions(unsyncedOnly, after);
    }

    @GetMapping("/{momentId:[a-fA-F0-9]{8,64}}")
    public MomentPost get(@PathVariable String momentId) {
        return momentStore.findPost(momentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "朋友圈不存在"));
    }

    @PostMapping("/{momentId:[a-fA-F0-9]{8,64}}/likes")
    public MomentInteraction like(
            @PathVariable String momentId,
            @Valid @RequestBody MomentLikeRequest body
    ) {
        try {
            return momentService.like(momentId, body.getUserId(), body.getUserName());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping("/{momentId:[a-fA-F0-9]{8,64}}/comments")
    public MomentInteraction comment(
            @PathVariable String momentId,
            @Valid @RequestBody MomentCommentRequest body
    ) {
        try {
            return momentService.comment(
                    momentId,
                    body.getUserId(),
                    body.getUserName(),
                    body.getContent()
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/media/{mediaId}")
    public ResponseEntity<Resource> media(@PathVariable String mediaId) throws IOException {
        if (!SafeIds.isMediaId(mediaId)) {
            throw new ResponseStatusException(BAD_REQUEST, "media_id 非法");
        }
        Path path = momentService.resolveImage(mediaId);
        if (path == null || !Files.exists(path)) {
            throw new ResponseStatusException(NOT_FOUND, "图片不存在");
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

    @GetMapping("/crm/config")
    public CrmConfigState crmConfig() {
        return momentStore.crmSnapshot();
    }

    @PutMapping("/crm/config")
    public CrmConfigState setCrmConfig(@Valid @RequestBody CrmConfigRequest body) {
        try {
            String safeUrl = urlValidator.validateAndNormalize(body.getUrl());
            momentStore.setCrmUrl(safeUrl);
            momentStore.setCrmEnabled(body.isEnabled());
            momentStore.setCrmAutoSync(body.isAutoSync());
            momentStore.setCrmAuthHeader(
                    body.getAuthHeader() == null || body.getAuthHeader().isBlank()
                            ? null
                            : body.getAuthHeader().trim()
            );
            return momentStore.crmSnapshot();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * 将未同步的点赞/评论动态推送到 CRM 线索接口。
     */
    @PostMapping("/crm/sync")
    public CrmSyncResult syncCrm() {
        try {
            return crmLeadSyncService.syncUnsynced();
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/crm/preview/{interactionId}")
    public Map<String, Object> previewLead(@PathVariable String interactionId) {
        return momentService.listInteractions(false, null).stream()
                .filter(i -> i.getInteractionId().equals(interactionId))
                .findFirst()
                .map(crmLeadSyncService::toCrmLeadPayload)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "动态不存在"));
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        try {
            momentService.clearAll();
            return Map.of("ok", true);
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "清理朋友圈失败");
        }
    }
}
