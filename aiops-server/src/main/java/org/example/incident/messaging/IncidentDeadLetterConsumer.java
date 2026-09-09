package org.example.incident.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.incident.application.WorkflowPersistence;
import org.example.incident.domain.IncidentStatus;
import org.example.incident.notification.MailIncidentNotifier;
import org.example.incident.observability.IncidentMetrics;
import org.example.incident.persistence.IncidentRecord;
import org.example.ops.contract.IncidentWorkMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class IncidentDeadLetterConsumer {

    private final WorkflowPersistence persistence;
    private final MailIncidentNotifier notifier;
    private final IncidentMetrics metrics;
    private final ObjectMapper objectMapper;

    public IncidentDeadLetterConsumer(
            WorkflowPersistence persistence,
            MailIncidentNotifier notifier,
            IncidentMetrics metrics,
            ObjectMapper objectMapper) {
        this.persistence = persistence;
        this.notifier = notifier;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = IncidentMessagingConfiguration.DEAD_LETTER_QUEUE)
    public void consume(String payload) throws JsonProcessingException {
        IncidentWorkMessage message = objectMapper.readValue(payload, IncidentWorkMessage.class);
        IncidentRecord incident = persistence.incident(message.incidentId());
        if (terminal(incident.getStatus())) {
            return;
        }
        String reason = "Incident worker retries were exhausted; message moved to dead-letter queue";
        IncidentRecord manual = persistence.manualRequired(incident.getId(), reason);
        metrics.manual();
        try {
            notifier.manualRequired(manual, reason);
        } catch (RuntimeException ex) {
            persistence.timeline(
                    incident.getId(), "NOTIFICATION", "FAILED",
                    "Incident email notification failed",
                    java.util.Map.of("error", String.valueOf(ex.getMessage())));
        }
    }

    private boolean terminal(IncidentStatus status) {
        return status == IncidentStatus.RESOLVED
                || status == IncidentStatus.MANUAL_REQUIRED
                || status == IncidentStatus.FAILED
                || status == IncidentStatus.SUPPRESSED;
    }
}
