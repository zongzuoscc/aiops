package org.example.controller;

import jakarta.validation.Valid;
import org.example.incident.api.IncidentIngestRequest;
import org.example.incident.application.IncidentIngestionService;
import org.example.service.AiOpsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Optional legacy URL bridge; processing uses the same v2 incident queue. */
@RestController
@RequestMapping("/api/incident")
@ConditionalOnProperty(name = "aiops.legacy-incident.enabled", havingValue = "true")
public class IncidentController {
    private final IncidentIngestionService ingestion;
    private final AiOpsService operations;

    public IncidentController(IncidentIngestionService ingestion, AiOpsService operations) {
        this.ingestion = ingestion;
        this.operations = operations;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody IncidentIngestRequest request,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        operations.authorize(apiKey);
        var incident = ingestion.ingest(request);
        return ResponseEntity.accepted().body(Map.of("incidentId", incident.getId(),
                "status", incident.getStatus().name(), "detailUrl", "/api/v2/incidents/" + incident.getId()));
    }

    @GetMapping("/events/{id}")
    public Map<String, Object> status(@PathVariable String id,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return operations.status(id, apiKey);
    }
}
