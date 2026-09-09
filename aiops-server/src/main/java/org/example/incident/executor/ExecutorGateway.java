package org.example.incident.executor;

import org.example.ops.contract.ActionRequest;
import org.example.ops.contract.ActionResult;
import org.example.ops.contract.ActionType;
import org.example.ops.contract.HmacRequestSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class ExecutorGateway {

    private final RestClient client;
    private final String keyId;
    private final String sharedSecret;

    public ExecutorGateway(
            RestClient.Builder builder,
            @Value("${aiops.executor.url:http://localhost:18091}") String executorUrl,
            @Value("${aiops.executor.key-id:local-control-plane}") String keyId,
            @Value("${aiops.executor.shared-secret:}") String sharedSecret) {
        this.client = builder.baseUrl(executorUrl).build();
        this.keyId = keyId;
        this.sharedSecret = sharedSecret;
    }

    public ActionResult invoke(
            String requestId,
            String incidentId,
            ActionType action,
            String serviceId,
            Map<String, String> parameters) {
        ActionRequest unsigned = new ActionRequest(
                requestId,
                incidentId,
                Instant.now(),
                UUID.randomUUID().toString(),
                keyId,
                action,
                serviceId,
                parameters,
                null);
        ActionRequest signed = unsigned.withSignature(HmacRequestSigner.sign(unsigned, sharedSecret));
        return client.post()
                .uri("/internal/v1/actions")
                .body(signed)
                .retrieve()
                .body(ActionResult.class);
    }
}
