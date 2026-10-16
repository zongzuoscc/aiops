package org.example.incident.api;

import jakarta.validation.Valid;
import org.example.incident.application.IncidentIngestionService;
import org.example.incident.persistence.ActionExecutionRecord;
import org.example.incident.persistence.ActionExecutionRepository;
import org.example.incident.persistence.AlertEventRecord;
import org.example.incident.persistence.AlertEventRepository;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.IncidentRecordRepository;
import org.example.incident.persistence.IncidentTimelineRecord;
import org.example.incident.persistence.IncidentTimelineRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v2/incidents")
public class IncidentCommandController {

    private final IncidentIngestionService ingestion;
    private final IncidentRecordRepository incidents;
    private final AlertEventRepository alertEvents;
    private final IncidentTimelineRepository timeline;
    private final ActionExecutionRepository actions;

    public IncidentCommandController(
            IncidentIngestionService ingestion,
            IncidentRecordRepository incidents,
            AlertEventRepository alertEvents,
            IncidentTimelineRepository timeline,
            ActionExecutionRepository actions) {
        this.ingestion = ingestion;
        this.incidents = incidents;
        this.alertEvents = alertEvents;
        this.timeline = timeline;
        this.actions = actions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IncidentView ingest(@Valid @RequestBody IncidentIngestRequest request) {
        return IncidentView.from(ingestion.ingest(request));
    }

    @GetMapping
    public List<IncidentView> list(@RequestParam(defaultValue = "50") int limit) {
        return incidents.findAll(PageRequest.of(0, Math.min(limit, 200),
                        Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream().map(IncidentView::from).toList();
    }

    @GetMapping("/{id}")
    public IncidentDetail detail(@PathVariable String id) {
        IncidentRecord incident = incidents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new IncidentDetail(
                IncidentView.from(incident),
                alertEvents.findByIncidentIdOrderByReceivedAt(id),
                timeline.findByIncidentIdOrderByCreatedAt(id),
                actions.findByIncidentIdOrderByCreatedAt(id));
    }

    public record IncidentView(
            String id,
            String source,
            String serviceId,
            String severity,
            String title,
            String status,
            String diagnosis,
            String policyReason,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            java.time.Instant resolvedAt) {

        static IncidentView from(IncidentRecord incident) {
            return new IncidentView(
                    incident.getId(), incident.getSource(), incident.getServiceId(),
                    incident.getSeverity(), incident.getTitle(), incident.getStatus().name(),
                    incident.getDiagnosis(), incident.getPolicyReason(), incident.getCreatedAt(),
                    incident.getUpdatedAt(), incident.getResolvedAt());
        }
    }

    public record IncidentDetail(
            IncidentView incident,
            List<AlertEventRecord> alerts,
            List<IncidentTimelineRecord> timeline,
            List<ActionExecutionRecord> actions) {
    }
}
