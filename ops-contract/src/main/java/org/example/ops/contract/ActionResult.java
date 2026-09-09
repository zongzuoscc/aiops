package org.example.ops.contract;

import java.time.Instant;
import java.util.Map;

public record ActionResult(
        String requestId,
        ActionType action,
        String target,
        boolean success,
        String message,
        Map<String, Object> data,
        Map<String, String> rollbackSnapshot,
        Instant completedAt) {

    public static ActionResult success(
            ActionRequest request,
            String message,
            Map<String, Object> data,
            Map<String, String> rollbackSnapshot) {
        return new ActionResult(request.requestId(), request.action(), request.target(), true,
                message, data, rollbackSnapshot, Instant.now());
    }

    public static ActionResult failure(ActionRequest request, String message) {
        return new ActionResult(request.requestId(), request.action(), request.target(), false,
                message, Map.of(), Map.of(), Instant.now());
    }

    public static ActionResult outcomeUnknown(ActionRequest request) {
        return new ActionResult(request.requestId(), request.action(), request.target(), false,
                "A previous execution did not record its outcome; manual verification is required",
                Map.of("outcome", "UNKNOWN"), Map.of(), Instant.now());
    }
}
