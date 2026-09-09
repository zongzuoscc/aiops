package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class IncidentWebhookRequest {

    @JsonAlias({"id", "alertId", "event_id", "incidentId", "fingerprint"})
    private String eventId;

    @JsonAlias({"source", "from", "system"})
    private String source;

    private String service;

    private String severity;

    private String title;

    private String summary;

    private String description;

    @JsonAlias({"status", "state"})
    private String status;

    @JsonAlias({"service_name", "serviceName"})
    private String serviceName;

    @JsonAlias({"startsAt", "startedAt", "startTime"})
    private String startsAt;

    @JsonAlias({"endsAt", "endTime", "endsAt"})
    private String endsAt;

    private String receiver;

    private String environment;

    private Map<String, Object> labels = new LinkedHashMap<>();

    private Map<String, Object> annotations = new LinkedHashMap<>();

    @JsonIgnore
    private Map<String, Object> rawPayload = new LinkedHashMap<>();

    @JsonAnySetter
    public void addExtra(String key, Object value) {
        rawPayload.put(key, value);
    }
}
