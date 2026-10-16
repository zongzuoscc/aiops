package org.example.agent.tool;

import org.example.service.AiOpsService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;
import java.util.UUID;

/** Per-request capabilities: the model cannot supply the authentication token or write permission. */
public final class OpsConversationTools {
    private final AiOpsService operations;
    private final String suppliedKey;
    private final boolean allowRemediation;
    private final String requestId = UUID.randomUUID().toString();

    public OpsConversationTools(AiOpsService operations, String suppliedKey, boolean allowRemediation) {
        this.operations = operations;
        this.suppliedKey = suppliedKey;
        this.allowRemediation = allowRemediation;
    }

    @Tool(description = "Start an investigation for an explicitly requested registered service. "
            + "This queues a task, not an immediate repair. Never call for ordinary conceptual questions. "
            + "If the service ID is unknown, ask the user instead of inventing it.")
    public Map<String, Object> startOpsInvestigation(
            @ToolParam(description = "Registered service ID explicitly identified by the user") String serviceId,
            @ToolParam(description = "The user's investigation request") String task) {
        return operations.start(serviceId, task, requestId + ":" + serviceId, allowRemediation, suppliedKey);
    }

    @Tool(description = "Read the real status, executed steps and final report of a known operations incident. "
            + "Do not invent progress or claim completion before the stored status confirms it.")
    public Map<String, Object> getOpsInvestigation(
            @ToolParam(description = "The incidentId returned when the task was started") String incidentId) {
        return operations.status(incidentId, suppliedKey);
    }
}
