package org.example.incident.agent;

import java.util.Map;

public record AgentObservation(int round, String tool, boolean success,
                               String summary, Map<String, Object> details) {
}
