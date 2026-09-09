package org.example.ops.contract;

import java.time.Instant;
import java.util.Map;

public record ActionRequest(
        String requestId,
        String incidentId,
        Instant timestamp,
        String nonce,
        String keyId,
        ActionType action,
        String target,
        Map<String, String> parameters,
        String signature) {

    public ActionRequest withSignature(String signature) {
        return new ActionRequest(
                requestId, incidentId, timestamp, nonce, keyId,
                action, target, parameters, signature);
    }
}
