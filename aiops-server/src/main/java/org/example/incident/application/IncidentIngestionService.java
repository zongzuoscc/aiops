package org.example.incident.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.incident.api.IncidentIngestRequest;
import org.example.incident.domain.IncidentStatus;
import org.example.incident.messaging.IncidentMessagingConfiguration;
import org.example.incident.persistence.AlertEventRecord;
import org.example.incident.persistence.AlertEventRepository;
import org.example.incident.persistence.IncidentCorrelationRecord;
import org.example.incident.persistence.IncidentCorrelationRepository;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.IncidentRecordRepository;
import org.example.incident.persistence.IncidentTimelineRecord;
import org.example.incident.persistence.IncidentTimelineRepository;
import org.example.incident.persistence.OutboxEvent;
import org.example.incident.persistence.OutboxEventRepository;
import org.example.ops.contract.IncidentWorkMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.text.Normalizer;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class IncidentIngestionService {

    private static final EnumSet<IncidentStatus> ACTIVE_STATUSES = EnumSet.of(
            IncidentStatus.RECEIVED,
            IncidentStatus.COLLECTING_EVIDENCE,
            IncidentStatus.DIAGNOSING,
            IncidentStatus.PLANNED,
            IncidentStatus.POLICY_CHECKING,
            IncidentStatus.EXECUTING,
            IncidentStatus.VERIFYING,
            IncidentStatus.RETRY_SCHEDULED,
            IncidentStatus.ROLLING_BACK);

    private final IncidentRecordRepository incidents;
    private final AlertEventRepository alertEvents;
    private final IncidentCorrelationRepository correlations;
    private final IncidentTimelineRepository timeline;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final long correlationWindowSeconds;

    public IncidentIngestionService(
            IncidentRecordRepository incidents,
            AlertEventRepository alertEvents,
            IncidentCorrelationRepository correlations,
            IncidentTimelineRepository timeline,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper,
            @Value("${aiops.incident-correlation.window-seconds:600}") long correlationWindowSeconds) {
        this.incidents = incidents;
        this.alertEvents = alertEvents;
        this.correlations = correlations;
        this.timeline = timeline;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.correlationWindowSeconds = Math.max(60, correlationWindowSeconds);
    }

    @Transactional
    public IncidentRecord ingest(IncidentIngestRequest request) {
        AlertEventRecord duplicate = alertEvents
                .findBySourceAndEventKey(request.source(), request.eventId())
                .orElse(null);
        if (duplicate != null) {
            return incidents.findById(duplicate.getIncidentId()).orElseThrow();
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(correlationWindowSeconds);
        String rawEvent = json(request);
        String fingerprint = fingerprint(request);
        String alertType = alertType(request);
        String candidateIncidentId = UUID.randomUUID().toString();

        int insertedCorrelation = correlations.insertIfAbsent(fingerprint, candidateIncidentId, expiresAt, now);
        IncidentCorrelationRecord correlation = correlations.findLocked(fingerprint).orElseThrow();
        IncidentRecord incident = insertedCorrelation == 1
                ? null
                : incidents.findLockedById(correlation.getIncidentId()).orElse(null);
        boolean createIncident = incident == null
                || !ACTIVE_STATUSES.contains(incident.getStatus())
                || correlation.getExpiresAt().isBefore(now);

        if (createIncident) {
            correlation.assign(candidateIncidentId, expiresAt, now);
            incidents.insertReceived(
                    candidateIncidentId,
                    request.source(),
                    request.eventId(),
                    fingerprint,
                    request.serviceId(),
                    request.severity().toUpperCase(Locale.ROOT),
                    request.title(),
                    rawEvent,
                    now);
            incident = incidents.findById(candidateIncidentId).orElseThrow();
        } else {
            correlation.extend(expiresAt, now);
        }

        int insertedEvent = alertEvents.insertReceived(
                UUID.randomUUID().toString(),
                request.source(),
                request.eventId(),
                fingerprint,
                incident.getId(),
                request.serviceId(),
                alertType,
                request.severity().toUpperCase(Locale.ROOT),
                request.title(),
                rawEvent,
                now);
        if (insertedEvent == 0) {
            AlertEventRecord existing = alertEvents
                    .findLockedBySourceAndEventKey(request.source(), request.eventId())
                    .orElseThrow();
            return incidents.findById(existing.getIncidentId()).orElseThrow();
        }

        if (createIncident) {
            IncidentWorkMessage message = new IncidentWorkMessage(
                    UUID.randomUUID().toString(), incident.getId(), "INCIDENT_RECEIVED", now);
            outbox.save(OutboxEvent.create(
                    "INCIDENT", incident.getId(), message.eventType(),
                    IncidentMessagingConfiguration.ROUTING_KEY, json(message)));
            timeline.save(IncidentTimelineRecord.of(
                    incident.getId(), "RECEIVED", "SUCCESS", "Alert accepted and queued", rawEvent));
        } else {
            timeline.save(IncidentTimelineRecord.of(
                    incident.getId(), "CORRELATED", "SUCCESS",
                    "Related alert attached to active incident",
                    json(Map.of(
                            "source", request.source(),
                            "eventId", request.eventId(),
                            "alertType", alertType,
                            "fingerprint", fingerprint))));
        }
        return incident;
    }

    private String fingerprint(IncidentIngestRequest request) {
        Map<String, String> labels = request.labels() == null ? Map.of() : request.labels();
        String environment = firstLabel(labels, "environment", "env", "namespace");
        String resource = firstLabel(labels, "instance", "pod", "container");
        String stableValue = normalizeIdentity(environment == null ? "default" : environment)
                + '|' + normalizeIdentity(request.serviceId())
                + '|' + normalizeIdentity(resource == null ? request.serviceId() : resource)
                + '|' + alertType(request);
        return sha256(stableValue);
    }

    private String alertType(IncidentIngestRequest request) {
        Map<String, String> labels = request.labels() == null ? Map.of() : request.labels();
        String value = firstLabel(labels, "alertname", "alert_type", "type");
        return value == null ? normalizePattern(request.title()) : normalizeIdentity(value);
    }

    private String firstLabel(Map<String, String> labels, String... names) {
        for (String name : names) {
            String value = labels.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeIdentity(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String normalizePattern(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b", "{id}")
                .replaceAll("\\b\\d+\\b", "{n}")
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
