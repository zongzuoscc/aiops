package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.incident.agent.AgentCheckpoint;
import org.example.incident.api.IncidentIngestRequest;
import org.example.incident.application.IncidentIngestionService;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.IncidentRecordRepository;
import org.example.incident.persistence.IncidentTimelineRepository;
import org.example.incident.persistence.ManagedServiceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared operator/chat entry point. All work is queued through the real incident pipeline. */
@Service
public class AiOpsService {
    private final IncidentIngestionService ingestion;
    private final IncidentRecordRepository incidents;
    private final IncidentTimelineRepository timeline;
    private final ManagedServiceRepository services;
    private final ObjectMapper mapper;
    private final boolean securityEnabled;
    private final String apiKey;

    public AiOpsService(IncidentIngestionService ingestion, IncidentRecordRepository incidents,
            IncidentTimelineRepository timeline, ManagedServiceRepository services, ObjectMapper mapper,
            @Value("${aiops.control-plane.security.enabled:true}") boolean securityEnabled,
            @Value("${aiops.control-plane.security.api-key:}") String apiKey) {
        this.ingestion = ingestion;
        this.incidents = incidents;
        this.timeline = timeline;
        this.services = services;
        this.mapper = mapper;
        this.securityEnabled = securityEnabled;
        this.apiKey = apiKey;
    }

    public Map<String, Object> start(String serviceId, String task, String requestId,
                                     boolean allowRemediation, String suppliedKey) {
        authorize(suppliedKey);
        if (serviceId == null || serviceId.isBlank() || task == null || task.isBlank()
                || requestId == null || requestId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serviceId, task and requestId are required");
        }
        services.findById(serviceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Managed service not found"));
        String title = task.codePointCount(0, task.length()) > 220
                ? task.substring(0, task.offsetByCodePoints(0, 220)) + "…" : task;
        IncidentRecord incident = ingestion.ingest(new IncidentIngestRequest(
                "operator", requestId, serviceId, "INFO", "人工调查：" + title,
                task, Map.of("alert_type", allowRemediation ? "operator_remediation" : "operator_investigation",
                        "execution_mode", allowRemediation ? "REMEDIATE" : "OBSERVE_ONLY")));
        if (!incident.getServiceId().equals(serviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "requestId already belongs to another service");
        }
        return Map.of("incidentId", incident.getId(), "serviceId", serviceId,
                "status", incident.getStatus().name(), "allowRemediation", allowRemediation,
                "message", "已交给统一运维队列；创建任务不等于已经完成调查或修复",
                "detailUrl", "/api/v2/incidents/" + incident.getId());
    }

    public Map<String, Object> status(String incidentId, String suppliedKey) {
        authorize(suppliedKey);
        IncidentRecord incident = incidents.findById(incidentId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("incidentId", incident.getId());
        result.put("serviceId", incident.getServiceId());
        result.put("status", incident.getStatus().name());
        result.put("reason", incident.getPolicyReason());
        result.put("timeline", timeline.findByIncidentIdOrderByCreatedAt(incidentId).stream()
                .map(item -> Map.of("stage", item.getStage(), "outcome", item.getOutcome(),
                        "summary", item.getSummary(), "createdAt", item.getCreatedAt())).toList());
        if (incident.getDiagnosis() != null) {
            try {
                var checkpoint = mapper.readValue(incident.getDiagnosis(), AgentCheckpoint.class);
                result.put("round", checkpoint.getRound());
                result.put("verified", checkpoint.isVerified());
                result.put("report", checkpoint.getFinalReport() == null ? "" : checkpoint.getFinalReport());
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                result.put("report", "旧版诊断记录，请查看事故详情");
            }
        }
        return result;
    }

    public void authorize(String suppliedKey) {
        if (!securityEnabled) return;
        if (apiKey.isBlank() || suppliedKey == null || !MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8), suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A control-plane API key is required");
        }
    }
}
