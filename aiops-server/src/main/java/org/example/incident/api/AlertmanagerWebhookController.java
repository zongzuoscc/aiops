package org.example.incident.api;

import org.example.incident.application.IncidentIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/integrations/alertmanager")
public class AlertmanagerWebhookController {

    private final IncidentIngestionService ingestion;

    public AlertmanagerWebhookController(IncidentIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<String> receive(@RequestBody AlertmanagerPayload payload) {
        List<String> incidentIds = new ArrayList<>();
        for (Alert alert : payload.alerts()) {
            String serviceId = alert.labels().getOrDefault(
                    "service", alert.labels().getOrDefault("container", "unregistered"));
            String title = alert.annotations().getOrDefault(
                    "summary", alert.labels().getOrDefault("alertname", "Prometheus alert"));
            String description = alert.annotations().getOrDefault("description", title);
            String id = alert.fingerprint() == null || alert.fingerprint().isBlank()
                    ? serviceId + ':' + title : alert.fingerprint();
            incidentIds.add(ingestion.ingest(new IncidentIngestRequest(
                    "alertmanager",
                    id,
                    serviceId,
                    alert.labels().getOrDefault("severity", "warning"),
                    title,
                    description,
                    alert.labels())).getId());
        }
        return incidentIds;
    }

    public record AlertmanagerPayload(String status, List<Alert> alerts) {
    }

    public record Alert(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            String startsAt,
            String fingerprint) {
    }
}
