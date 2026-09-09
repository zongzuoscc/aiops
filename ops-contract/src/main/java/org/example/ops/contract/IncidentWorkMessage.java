package org.example.ops.contract;

import java.time.Instant;

public record IncidentWorkMessage(
        String messageId,
        String incidentId,
        String eventType,
        Instant occurredAt) {
}
