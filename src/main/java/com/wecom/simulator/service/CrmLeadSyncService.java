package com.wecom.simulator.service;

import com.wecom.simulator.dto.CrmSyncResult;
import com.wecom.simulator.model.MomentInteraction;
import com.wecom.simulator.store.MomentStore;
import com.wecom.simulator.web.RealtimeHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CrmLeadSyncService {

    private static final Logger log = LoggerFactory.getLogger(CrmLeadSyncService.class);

    private final MomentStore momentStore;
    private final RealtimeHub realtimeHub;
    private final RestClient restClient;

    public CrmLeadSyncService(MomentStore momentStore, RealtimeHub realtimeHub) {
        this.momentStore = momentStore;
        this.realtimeHub = realtimeHub;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public Map<String, Object> toCrmLeadPayload(MomentInteraction interaction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "wecom_moment");
        payload.put("lead_type", "moment_" + interaction.getType().getValue());
        payload.put("interaction_id", interaction.getInteractionId());
        payload.put("interaction_type", interaction.getType().getValue());
        payload.put("moment_id", interaction.getMomentId());
        payload.put("user_id", interaction.getUserId());
        payload.put("user_name", interaction.getUserName());
        payload.put("content", interaction.getContent());
        payload.put("moment_content", interaction.getMomentContent());
        payload.put("moment_plan", interaction.getMomentPlan());
        payload.put("image_url", interaction.getImageUrl());
        payload.put("create_time", interaction.getCreateTime());
        payload.put("external_userid", interaction.getUserId());
        return payload;
    }

    public CrmSyncResult syncUnsynced() {
        if (!momentStore.isCrmEnabled() || momentStore.getCrmUrl() == null || momentStore.getCrmUrl().isBlank()) {
            throw new IllegalStateException("CRM 未启用或未配置 URL");
        }
        List<MomentInteraction> pending = momentStore.listInteractions(true, null);
        CrmSyncResult result = new CrmSyncResult();
        result.setTotal(pending.size());
        int success = 0;
        int failed = 0;
        for (MomentInteraction item : pending) {
            boolean ok = pushOne(item);
            if (ok) {
                success++;
            } else {
                failed++;
            }
            result.getItems().add(item);
        }
        result.setSuccess(success);
        result.setFailed(failed);
        realtimeHub.broadcast(Map.of("type", "crm_sync_result", "result", result));
        return result;
    }

    @Async
    public void maybeAutoSync(MomentInteraction interaction) {
        if (!momentStore.isCrmEnabled() || !momentStore.isCrmAutoSync()) {
            return;
        }
        if (momentStore.getCrmUrl() == null || momentStore.getCrmUrl().isBlank()) {
            return;
        }
        pushOne(interaction);
        realtimeHub.broadcast(Map.of("type", "crm_sync_item", "interaction", interaction));
    }

    private boolean pushOne(MomentInteraction interaction) {
        Map<String, Object> payload = toCrmLeadPayload(interaction);
        try {
            var spec = restClient.post()
                    .uri(momentStore.getCrmUrl())
                    .contentType(MediaType.APPLICATION_JSON);
            String auth = momentStore.getCrmAuthHeader();
            if (auth != null && !auth.isBlank()) {
                spec = spec.header("Authorization", auth);
            }
            var response = spec.body(payload).retrieve().toEntity(String.class);
            int code = response.getStatusCode().value();
            String body = response.getBody() == null ? "" : response.getBody();
            if (body.length() > 500) {
                body = body.substring(0, 500);
            }
            boolean ok = code >= 200 && code < 300;
            interaction.setSyncedToCrm(ok);
            interaction.setCrmSyncResult("HTTP " + code + (body.isBlank() ? "" : (": " + body)));
            log.info(
                    "CRM sync interaction={} status={} ok={}",
                    interaction.getInteractionId(),
                    code,
                    ok
            );
            return ok;
        } catch (Exception ex) {
            interaction.setSyncedToCrm(false);
            String msg = ex.getMessage() == null ? "error" : ex.getMessage();
            if (msg.length() > 300) {
                msg = msg.substring(0, 300);
            }
            interaction.setCrmSyncResult("ERROR: " + msg);
            log.warn("CRM sync failed interaction={}: {}", interaction.getInteractionId(), msg);
            return false;
        }
    }
}
