package org.example.incident.agent;

import java.util.Set;

public record AgentPlan(Decision decision, String tool, String query,
                        String runbookId, double confidence, String summary) {
    public enum Decision { EXECUTE, FINISH, MANUAL }

    public static final Set<String> TOOLS = Set.of(
            "getContainerState", "getContainerLogs", "getContainerStats", "checkHttpHealth",
            "searchRunbooks", "searchKnowledge", "queryMetrics", "restartContainer",
            "recreateWithStableImage", "verifyRecovery", "rollback");

    public AgentPlan {
        if (decision == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("A decision and finite confidence in [0,1] are required");
        }
        if (decision == Decision.EXECUTE && !TOOLS.contains(tool == null ? "" : tool)) {
            throw new IllegalArgumentException("The planned tool must belong to the operations catalog");
        }
        query = query == null ? "" : query;
        summary = summary == null ? "" : summary;
    }
}
