package org.example.ops.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HmacRequestSignerTest {

    private static final String SECRET = "test-secret-with-at-least-32-characters";

    @Test
    void verifiesCanonicalRequestIndependentOfParameterOrder() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("tail", "200");
        first.put("format", "text");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("format", "text");
        second.put("tail", "200");

        ActionRequest request = request(first, null);
        String signature = HmacRequestSigner.sign(request, SECRET);
        ActionRequest reordered = request(second, signature);

        assertThat(HmacRequestSigner.verify(reordered, SECRET)).isTrue();
    }

    @Test
    void rejectsTamperedAction() {
        ActionRequest request = request(Map.of(), null);
        String signature = HmacRequestSigner.sign(request, SECRET);
        ActionRequest tampered = new ActionRequest(
                request.requestId(), request.incidentId(), request.timestamp(), request.nonce(),
                request.keyId(), ActionType.RESTART_CONTAINER, request.target(), Map.of(), signature);

        assertThat(HmacRequestSigner.verify(tampered, SECRET)).isFalse();
    }

    private ActionRequest request(Map<String, String> parameters, String signature) {
        return new ActionRequest(
                "request-1", "incident-1", Instant.parse("2026-08-22T00:00:00Z"),
                "nonce-1", "key-1", ActionType.GET_CONTAINER_LOGS,
                "payment", parameters, signature);
    }
}
