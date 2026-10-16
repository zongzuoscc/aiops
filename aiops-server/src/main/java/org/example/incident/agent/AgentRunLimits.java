package org.example.incident.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AgentRunLimits(
        @Value("${aiops.agent.segment-rounds:8}") int segmentRounds,
        @Value("${aiops.agent.max-rounds:32}") int maxRounds,
        @Value("${aiops.agent.max-tool-calls:40}") int maxToolCalls,
        @Value("${aiops.agent.max-duration-seconds:600}") int maxDurationSeconds,
        @Value("${aiops.agent.model-timeout-seconds:60}") int modelTimeoutSeconds) {
    public AgentRunLimits {
        if (segmentRounds < 1 || maxRounds < segmentRounds || maxToolCalls < 1
                || maxDurationSeconds < 1 || modelTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Agent budgets must be positive and max-rounds >= segment-rounds");
        }
    }
}
