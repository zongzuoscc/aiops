package org.example.incident.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.incident.api.IncidentIngestRequest;
import org.example.incident.messaging.IncidentMessagingConfiguration;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.IncidentRecordRepository;
import org.example.incident.persistence.IncidentTimelineRecord;
import org.example.incident.persistence.IncidentTimelineRepository;
import org.example.incident.persistence.OutboxEvent;
import org.example.incident.persistence.OutboxEventRepository;
import org.example.ops.contract.IncidentWorkMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class IncidentIngestionService {

    private final IncidentRecordRepository incidents;
    private final IncidentTimelineRepository timeline;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public IncidentIngestionService(
            IncidentRecordRepository incidents,
            IncidentTimelineRepository timeline,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IncidentRecord ingest(IncidentIngestRequest request) {
        IncidentRecord existing = incidents.findBySourceAndEventKey(request.source(), request.eventId())
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        String incidentId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String rawEvent = json(request);
        int inserted = incidents.insertReceived(
                incidentId,
                request.source(),
                request.eventId(),
                fingerprint(request),
                request.serviceId(),
                request.severity().toUpperCase(),
                request.title(),
                rawEvent,
                now);
        IncidentRecord incident = incidents.findBySourceAndEventKey(request.source(), request.eventId())
                .orElseThrow();
        if (inserted == 1) {
            IncidentWorkMessage message = new IncidentWorkMessage(
                    UUID.randomUUID().toString(), incidentId, "INCIDENT_RECEIVED", now);
            outbox.save(OutboxEvent.create(
                    "INCIDENT", incidentId, message.eventType(),
                    IncidentMessagingConfiguration.ROUTING_KEY, json(message)));
            timeline.save(IncidentTimelineRecord.of(
                    incidentId, "RECEIVED", "SUCCESS", "Alert accepted and queued", rawEvent));
        }
        return incident;
    }

    private String fingerprint(IncidentIngestRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String value = request.serviceId() + '|' + request.title().toLowerCase();
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Incident payload cannot be serialized", ex);
        }
    }
}
