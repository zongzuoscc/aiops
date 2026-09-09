package org.example.controller;

import org.example.entity.IncidentEventEntity;
import org.example.security.AuthContext;
import org.example.service.IncidentAutomationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/incident")
@ConditionalOnProperty(name = "aiops.legacy-incident.enabled", havingValue = "true")
public class IncidentController {

    private static final Logger logger = LoggerFactory.getLogger(IncidentController.class);

    private final IncidentAutomationService incidentAutomationService;

    public IncidentController(IncidentAutomationService incidentAutomationService) {
        this.incidentAutomationService = incidentAutomationService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> acceptWebhook(@RequestBody Map<String, Object> payload) {
        try {
            String ownerId = AuthContext.getCurrentOwnerId();
            List<IncidentEventEntity> events = incidentAutomationService.receiveWebhook(payload, ownerId);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "success",
                    "count", events.size(),
                    "data", events.stream().map(this::toSummary).toList()
            ));
        } catch (Exception e) {
            logger.error("接入告警事件失败", e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "事件接入失败: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> listEvents(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        String ownerId = AuthContext.getCurrentOwnerId();
        List<IncidentEventEntity> events = incidentAutomationService.listEvents(ownerId, status, limit);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", events.stream().map(this::toDetail).toList()
        ));
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<Map<String, Object>> getEvent(@PathVariable String eventId) {
        String ownerId = AuthContext.getCurrentOwnerId();
        return incidentAutomationService.getEvent(ownerId, eventId)
                .map(event -> ResponseEntity.ok(Map.of(
                        "code", 200,
                        "message", "success",
                        "data", toDetail(event)
                )))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "事件不存在"
                )));
    }

    @PostMapping("/events/{eventId}/run")
    public ResponseEntity<Map<String, Object>> runNow(@PathVariable String eventId) {
        String ownerId = AuthContext.getCurrentOwnerId();
        incidentAutomationService.triggerRunAsyncByOwner(ownerId, eventId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "ok",
                "eventId", eventId
        ));
    }

    private Map<String, Object> toSummary(IncidentEventEntity event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventId", event.getEventId());
        item.put("externalEventId", event.getExternalEventId());
        item.put("status", event.getStatus());
        item.put("severity", event.getSeverity());
        item.put("serviceName", event.getServiceName());
        item.put("title", event.getEventTitle());
        item.put("summary", event.getSummary());
        item.put("createdAt", event.getCreatedAt());
        item.put("updatedAt", event.getUpdatedAt());
        item.put("attempts", event.getAttempts());
        return item;
    }

    private Map<String, Object> toDetail(IncidentEventEntity event) {
        Map<String, Object> item = toSummary(event);
        item.put("source", event.getSource());
        item.put("ownerId", event.getOwnerId());
        item.put("payload", event.getPayloadJson());
        item.put("finalReport", event.getFinalReport());
        item.put("lastError", event.getLastError());
        item.put("lastAttemptAt", event.getLastAttemptAt());
        item.put("nextAttemptAt", event.getNextAttemptAt());
        item.put("resolvedAt", event.getResolvedAt());
        return item;
    }
}
