package org.example.incident.api;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record IncidentIngestRequest(
        @NotBlank String source,
        @NotBlank String eventId,
        @NotBlank String serviceId,
        @NotBlank String severity,
        @NotBlank String title,
        String description,
        Map<String, String> labels) {
}
